//! This device's own peer identity.
//!
//! An Ed25519 key pair, generated once and kept. It is the same kind of identity the Java worker
//! holds — the tracker cannot tell the difference and is not meant to: a peer is whatever can sign
//! for its own node id, and a phone can do that as well as a desktop can.
//!
//! Persisted because a node that changes identity on every launch is a new stranger every time: it
//! accumulates no reputation, its old announces linger as dead routes, and nothing that remembers
//! peers can remember it.

use std::path::{Path, PathBuf};

use ed25519_dalek::{SigningKey, VerifyingKey};

/// The file holding the private key. Owner-readable only, like the worker's.
fn key_file() -> PathBuf {
    crate::settings::config_dir().join("peer-identity.key")
}

/// This device's peer identity.
///
/// Cloneable so the announce loop can hand a copy to a blocking task without holding a lock across
/// an await. A clone is the same key, not a new one — identity is minted only by
/// [`PeerIdentity::load_or_create`].
#[derive(Clone)]
pub struct PeerIdentity {
    signing: SigningKey,
    node_id: String,
    node_id_value: nodera_codec::types::NodeId,
}

impl PeerIdentity {
    /// Load the stored identity, or create and store one.
    pub fn load_or_create() -> Result<Self, String> {
        let path = key_file();
        if let Ok(bytes) = std::fs::read(&path) {
            if bytes.len() == 32 {
                let mut seed = [0u8; 32];
                seed.copy_from_slice(&bytes);
                return Ok(Self::from_seed(seed));
            }
            // A key file of the wrong size is not a key. Refused rather than replaced: overwriting
            // would silently discard an identity the network may already know.
            return Err(format!(
                "{} is not a 32-byte key — move it aside if you mean to start a new identity",
                path.display()
            ));
        }
        let seed = fresh_seed();
        write_owner_only(&path, &seed)?;
        Ok(Self::from_seed(seed))
    }

    pub(crate) fn from_seed(seed: [u8; 32]) -> Self {
        let signing = SigningKey::from_bytes(&seed);
        let id = node_id_bytes(&signing.verifying_key());
        Self {
            signing,
            node_id: uuid_string(id),
            node_id_value: nodera_codec::types::NodeId::new(
                u64::from_be_bytes(id[..8].try_into().expect("16-byte id")),
                u64::from_be_bytes(id[8..].try_into().expect("16-byte id")),
            ),
        }
    }

    /// The same id as a codec value, which is what an announce carries.
    pub fn node_id_value(&self) -> nodera_codec::types::NodeId {
        self.node_id_value
    }

    /// The raw 32-byte public key, as the announce carries it.
    pub fn public_key(&self) -> Vec<u8> {
        self.signing.verifying_key().to_bytes().to_vec()
    }

    /// This peer's node id, as a UUID string.
    pub fn node_id(&self) -> &str {
        &self.node_id
    }

    /// Sign canonical bytes.
    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        use ed25519_dalek::Signer;
        self.signing.sign(message).to_bytes().to_vec()
    }
}

/// A node id derived from the public key.
///
/// Derived rather than random so the id and the key cannot disagree: a peer that announced one id
/// and signed with a key implying another would be refused by anything that checks, and the check
/// is cheap enough that nothing should have to trust the pairing.
fn node_id_bytes(key: &VerifyingKey) -> [u8; 16] {
    let bytes = key.to_bytes();
    // UUID v4-shaped from the first 16 bytes of the key, so it is a legal UUID everywhere it is
    // rendered without pretending to be random.
    let mut id = [0u8; 16];
    id.copy_from_slice(&bytes[..16]);
    id[6] = (id[6] & 0x0F) | 0x40;
    id[8] = (id[8] & 0x3F) | 0x80;
    id
}

fn uuid_string(id: [u8; 16]) -> String {
    let hex: String = id.iter().map(|b| format!("{b:02x}")).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}

/// 32 bytes of key material from the OS.
///
/// `getrandom` via the OS is the only acceptable source here: unlike the challenge nonce elsewhere
/// in this app, this seed *is* the secret, and a predictable one is an identity anybody can forge.
fn fresh_seed() -> [u8; 32] {
    use std::io::Read as _;

    let mut seed = [0u8; 32];
    // EXACTLY 32 bytes, with `read_exact` on an open handle.
    //
    // This used to be `std::fs::read("/dev/urandom")`, which reads a file **to EOF** — and
    // `/dev/urandom` has no end. On a desktop with an identity already on disk the call was never
    // reached, so it looked fine for months; on a phone creating its first identity it consumed the
    // kernel's entropy stream at a few hundred MB/s until Android killed the process a few seconds
    // after launch. The app "crashed on startup" and the peer never announced, because it never got
    // past creating its own key.
    let mut source = std::fs::File::open("/dev/urandom")
        // No entropy source is a refusal to proceed, not a reason to invent one: a predictable
        // seed here is an identity anybody could forge.
        .unwrap_or_else(|e| {
            panic!("no OS entropy source available to create a peer identity: {e}")
        });
    source
        .read_exact(&mut seed)
        .unwrap_or_else(|e| panic!("could not read 32 bytes of entropy: {e}"));
    seed
}

fn write_owner_only(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    std::fs::write(path, bytes).map_err(|e| format!("{}: {e}", path.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The seed must come from a **bounded** read.
    ///
    /// The old implementation read `/dev/urandom` to EOF, which does not exist, so it allocated
    /// until the OS killed the process. A test cannot assert "did not hang" directly, so it asserts
    /// the two things that follow from a bounded read: the call returns at all, and two calls
    /// differ — a fixed-size read of a CSPRNG.
    #[test]
    fn a_fresh_seed_is_bounded_and_unpredictable() {
        let first = fresh_seed();
        let second = fresh_seed();

        assert_ne!(first, second, "two seeds from the CSPRNG must differ");
        assert_ne!(first, [0u8; 32], "an all-zero seed means nothing was read");
    }

    #[test]
    fn a_node_id_is_derived_from_the_key_and_is_a_legal_uuid() {
        let identity = PeerIdentity::from_seed([7u8; 32]);
        let id = identity.node_id();
        assert_eq!(id.len(), 36);
        assert_eq!(id.chars().filter(|c| *c == '-').count(), 4);
        // Version and variant nibbles, so anything parsing this as a UUID accepts it.
        assert_eq!(id.as_bytes()[14], b'4');
        assert!(matches!(id.as_bytes()[19], b'8' | b'9' | b'a' | b'b'));
    }

    #[test]
    fn the_same_key_always_yields_the_same_node_id() {
        // The identity is persisted precisely so it does not change; if the derivation were not
        // stable, persisting the key would not persist the peer.
        assert_eq!(
            PeerIdentity::from_seed([3u8; 32]).node_id(),
            PeerIdentity::from_seed([3u8; 32]).node_id()
        );
        assert_ne!(
            PeerIdentity::from_seed([3u8; 32]).node_id(),
            PeerIdentity::from_seed([4u8; 32]).node_id()
        );
    }

    #[test]
    fn a_signature_verifies_under_the_announced_public_key() {
        let identity = PeerIdentity::from_seed([11u8; 32]);
        let message = b"a canonical announce";

        let signature = identity.sign(message);

        // The tracker verifies with this exact pair, through the shared codec. If these two ever
        // disagreed, every announce this device made would be refused.
        assert!(
            nodera_codec::sig::verify(&identity.public_key(), message, &signature).is_ok(),
            "the tracker must be able to verify what this peer signs"
        );
    }

    #[test]
    fn a_wrong_key_does_not_verify() {
        let identity = PeerIdentity::from_seed([1u8; 32]);
        let other = PeerIdentity::from_seed([2u8; 32]);
        let signature = identity.sign(b"x");

        assert!(nodera_codec::sig::verify(&other.public_key(), b"x", &signature).is_err());
    }
}
