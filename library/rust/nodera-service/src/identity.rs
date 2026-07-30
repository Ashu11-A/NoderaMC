//! A service's own stable identity: a `NodeId` plus the Ed25519 key it signs its address record with.
//!
//! ## Why a service has an identity at all
//!
//! Until the service directory existed, a rendezvous was a `host:port` in somebody's configuration
//! file and needed no name. Two things changed that:
//!
//! * **A drain notice must be unforgeable.** "This rendezvous is going away, use these instead" is an
//!   eviction primitive. Unsigned, it is a cheap way to herd a target's traffic onto a relay the
//!   attacker runs.
//! * **A peer should be able to pin what worked.** Scores accumulate per service across restarts and
//!   address changes, which needs a name that is not an address.
//!
//! The key signs exactly one kind of value — this service's own [`ServiceRecord`] — and never a peer
//! record, a vote, or anything about a world.
//!
//! ## Persistence
//!
//! The key file is the identity. A service that regenerated its key on every start would appear as a
//! brand-new, unmeasured service after every restart, which is precisely the moment its accumulated
//! availability score matters most.

use std::io;
use std::path::{Path, PathBuf};

use ed25519_dalek::{Signer, SigningKey};
use nodera_codec::service::{ServiceKind, ServiceLifecycle, ServiceRecord};
use nodera_codec::sig;
use nodera_codec::types::{NetworkId, NodeId};

/// The on-disk key file's format version, so a later format can be recognised rather than misread.
const KEY_FILE_VERSION: u8 = 1;

/// Bytes in a persisted identity: version + 16-byte NodeId + 32-byte secret key.
const KEY_FILE_BYTES: usize = 1 + 16 + 32;

/// A service's signing identity.
pub struct ServiceIdentity {
    node_id: NodeId,
    signing: SigningKey,
    public_key_x509: Vec<u8>,
}

impl std::fmt::Debug for ServiceIdentity {
    /// Deliberately omits the secret: an identity in a log line must not be a key leak.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ServiceIdentity")
            .field("node_id", &self.node_id)
            .finish_non_exhaustive()
    }
}

impl ServiceIdentity {
    /// Build an identity from an explicit `NodeId` and 32-byte secret — the seam tests drive.
    pub fn from_parts(node_id: NodeId, secret: [u8; 32]) -> Self {
        let signing = SigningKey::from_bytes(&secret);
        let public_key_x509 = sig::x509_public_key(&signing.verifying_key().to_bytes());
        Self {
            node_id,
            signing,
            public_key_x509,
        }
    }

    /// Load the identity at `path`, creating it on first run.
    ///
    /// The seed is caller-supplied rather than drawn here so the whole module stays free of a random
    /// source: `main` already has one, and a test can produce a deterministic identity without a
    /// feature flag.
    pub fn load_or_create(path: &Path, seed: [u8; 32], node_id: NodeId) -> io::Result<Self> {
        match std::fs::read(path) {
            Ok(bytes) => Self::decode_file(&bytes).ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!(
                        "{} is not a service identity file (expected {KEY_FILE_BYTES} bytes, \
                         version {KEY_FILE_VERSION}); move it aside to mint a new identity",
                        path.display()
                    ),
                )
            }),
            Err(e) if e.kind() == io::ErrorKind::NotFound => {
                let identity = Self::from_parts(node_id, seed);
                identity.write_to(path)?;
                Ok(identity)
            }
            Err(e) => Err(e),
        }
    }

    fn decode_file(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != KEY_FILE_BYTES || bytes[0] != KEY_FILE_VERSION {
            return None;
        }
        let msb = u64::from_be_bytes(bytes[1..9].try_into().ok()?);
        let lsb = u64::from_be_bytes(bytes[9..17].try_into().ok()?);
        let secret: [u8; 32] = bytes[17..].try_into().ok()?;
        Some(Self::from_parts(NodeId::new(msb, lsb), secret))
    }

    fn write_to(&self, path: &Path) -> io::Result<()> {
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent)?;
            }
        }
        let mut bytes = Vec::with_capacity(KEY_FILE_BYTES);
        bytes.push(KEY_FILE_VERSION);
        bytes.extend_from_slice(&self.node_id.msb.to_be_bytes());
        bytes.extend_from_slice(&self.node_id.lsb.to_be_bytes());
        bytes.extend_from_slice(&self.signing.to_bytes());
        // Write-then-rename so a crash mid-write cannot leave a truncated key file behind — a
        // truncated identity is a permanently unstartable service.
        let temp: PathBuf = path.with_extension("tmp");
        std::fs::write(&temp, &bytes)?;
        restrict_permissions(&temp)?;
        std::fs::rename(&temp, path)
    }

    /// This service's node id.
    pub fn node_id(&self) -> NodeId {
        self.node_id
    }

    /// This service's public key in Java's X.509 form (the only form Java verifies).
    pub fn public_key(&self) -> &[u8] {
        &self.public_key_x509
    }

    /// Sign arbitrary canonical bytes.
    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        self.signing.sign(message).to_bytes().to_vec()
    }

    /// Build and sign a record describing this service right now.
    ///
    /// Returns the record and its signature so the caller can put the pair in an announce, a
    /// directory row, or a drain notice — all three carry the identical signed bytes.
    #[allow(clippy::too_many_arguments)]
    pub fn sign_record(&self, snapshot: RecordSnapshot) -> (ServiceRecord, Vec<u8>) {
        let record = ServiceRecord {
            service: self.node_id,
            public_key: self.public_key_x509.clone(),
            kind: snapshot.kind,
            lifecycle: snapshot.lifecycle,
            network_id: snapshot.network_id,
            routes: snapshot.routes,
            version: snapshot.version,
            active_sessions: snapshot.active_sessions,
            max_sessions: snapshot.max_sessions,
            active_circuits: snapshot.active_circuits,
            max_circuits: snapshot.max_circuits,
            rejected_last_window: snapshot.rejected_last_window,
            issued_at_epoch_millis: snapshot.now_millis,
            expires_at_epoch_millis: snapshot.now_millis + snapshot.ttl_millis,
            drain_deadline_epoch_millis: snapshot.drain_deadline_epoch_millis,
        };
        let signature = self.sign(&record.signed_bytes());
        (record, signature)
    }
}

/// Everything that varies between one signed record and the next.
#[derive(Debug, Clone)]
pub struct RecordSnapshot {
    /// Rendezvous or tracker.
    pub kind: ServiceKind,
    /// The lifecycle state to advertise.
    pub lifecycle: ServiceLifecycle,
    /// The network served.
    pub network_id: NetworkId,
    /// Dial routes in preference order.
    pub routes: Vec<String>,
    /// The running binary's product version.
    pub version: String,
    /// Sessions held now.
    pub active_sessions: u32,
    /// Session ceiling; 0 = unstated.
    pub max_sessions: u32,
    /// Circuits bridged now.
    pub active_circuits: u32,
    /// Circuit ceiling; 0 = unstated.
    pub max_circuits: u32,
    /// Refusals in the last window.
    pub rejected_last_window: u32,
    /// Wall clock at signing.
    pub now_millis: u64,
    /// How long the record stays valid without a refresh.
    pub ttl_millis: u64,
    /// Intended stop time when draining; 0 otherwise.
    pub drain_deadline_epoch_millis: u64,
}

#[cfg(unix)]
fn restrict_permissions(path: &Path) -> io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    // A service signing key is secret material; 0600 is the same posture the worker's identity file
    // has, and leaving it group-readable on a shared host would be a silent downgrade.
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
}

#[cfg(not(unix))]
fn restrict_permissions(_path: &Path) -> io::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snapshot() -> RecordSnapshot {
        RecordSnapshot {
            kind: ServiceKind::Rendezvous,
            lifecycle: ServiceLifecycle::Serving,
            network_id: NetworkId::new(1, 2),
            routes: vec!["rdv.example:25601".to_owned()],
            version: "0.1.0".to_owned(),
            active_sessions: 3,
            max_sessions: 100,
            active_circuits: 1,
            max_circuits: 8,
            rejected_last_window: 0,
            now_millis: 1_700_000_000_000,
            ttl_millis: 300_000,
            drain_deadline_epoch_millis: 0,
        }
    }

    #[test]
    fn a_signed_record_verifies_under_the_announced_public_key() {
        let identity = ServiceIdentity::from_parts(NodeId::new(7, 8), [9u8; 32]);
        let (record, signature) = identity.sign_record(snapshot());
        // The announced key is what a peer verifies with — not some other encoding of it.
        assert_eq!(record.public_key, identity.public_key());
        sig::verify(&record.public_key, &record.signed_bytes(), &signature).unwrap();
    }

    #[test]
    fn the_announced_key_is_the_44_byte_form_java_can_parse() {
        let identity = ServiceIdentity::from_parts(NodeId::new(1, 1), [3u8; 32]);
        assert_eq!(identity.public_key().len(), 44);
    }

    #[test]
    fn a_tampered_record_does_not_verify() {
        let identity = ServiceIdentity::from_parts(NodeId::new(7, 8), [9u8; 32]);
        let (record, signature) = identity.sign_record(snapshot());
        let moved = ServiceRecord {
            routes: vec!["attacker.example:25601".to_owned()],
            ..record
        };
        assert!(sig::verify(&moved.public_key, &moved.signed_bytes(), &signature).is_err());
    }

    #[test]
    fn an_identity_survives_a_restart_because_the_score_should_too() {
        let dir = std::env::temp_dir().join(format!("nodera-identity-{}", std::process::id()));
        let path = dir.join("service-identity.bin");
        let _ = std::fs::remove_file(&path);

        let first = ServiceIdentity::load_or_create(&path, [5u8; 32], NodeId::new(11, 12)).unwrap();
        // A different seed and id on the second call: if the file were ignored, these would win.
        let second =
            ServiceIdentity::load_or_create(&path, [6u8; 32], NodeId::new(99, 99)).unwrap();

        assert_eq!(first.node_id(), second.node_id());
        assert_eq!(first.public_key(), second.public_key());
        assert_eq!(first.sign(b"probe"), second.sign(b"probe"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_corrupt_key_file_is_an_error_not_a_silent_new_identity() {
        let dir = std::env::temp_dir().join(format!("nodera-identity-bad-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("service-identity.bin");
        std::fs::write(&path, b"not a key").unwrap();
        // Minting a fresh identity here would silently discard the operator's accumulated
        // reputation and look like a working start, which is the worse of the two failures.
        assert!(ServiceIdentity::load_or_create(&path, [1u8; 32], NodeId::new(1, 2)).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_expiry_is_the_issue_time_plus_the_ttl() {
        let identity = ServiceIdentity::from_parts(NodeId::new(1, 2), [4u8; 32]);
        let (record, _) = identity.sign_record(snapshot());
        assert_eq!(record.issued_at_epoch_millis, 1_700_000_000_000);
        assert_eq!(record.expires_at_epoch_millis, 1_700_000_300_000);
    }

    #[test]
    fn a_debug_line_does_not_print_the_secret() {
        let identity = ServiceIdentity::from_parts(NodeId::new(1, 2), [0xAB; 32]);
        let rendered = format!("{identity:?}");
        assert!(
            !rendered.contains("ab"),
            "secret leaked into Debug: {rendered}"
        );
        assert!(
            !rendered.to_lowercase().contains("171"),
            "secret leaked: {rendered}"
        );
    }
}
