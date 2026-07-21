//! Test-only builders shared by the unit tests in every module.
//!
//! The service never signs — these helpers exist only so the tests can produce genuinely valid
//! records and prove the verification path accepts them (and rejects everything else), exactly as a
//! real Java peer's `NodeIdentity` would.

use nodera_codec::rendezvous::SignedRecord;
use nodera_codec::types::{
    CandidateKind, NetworkId, NodeCapabilities, NodeId, PeerCandidate, PeerRole, RegistrationEvent,
    SignedPeerRecord,
};

/// The fixed issue time the test records claim; freshness tests pass a matching `now`. Kept well
/// above the clock-skew window so a "too far in the past" case does not underflow to `0`.
pub const ISSUED_AT: u64 = 1_000_000;

/// Capabilities of a plain player peer.
pub fn caps() -> NodeCapabilities {
    NodeCapabilities {
        logical_cores: 8,
        memory_bytes: 16 << 30,
        latency_ms: 40,
        reliability_bits: 0x3FF0_0000_0000_0000,
        max_primary_regions: 4,
        max_validator_regions: 8,
        accepts_worker: true,
        roles: vec![PeerRole::PartialArchive],
    }
}

/// A host + reflexive candidate list.
pub fn candidates(n: u64) -> Vec<PeerCandidate> {
    vec![
        PeerCandidate {
            kind: CandidateKind::Host,
            address: format!("10.0.0.{n}:25566"),
            priority: 100,
        },
        PeerCandidate {
            kind: CandidateKind::Relay,
            address: "198.51.100.9:25601".to_owned(),
            priority: 1,
        },
    ]
}

/// A deterministic Ed25519 signer keyed by a seed.
pub struct TestSigner {
    key: ed25519_dalek::SigningKey,
}

impl TestSigner {
    /// A signer reproducible from `seed`.
    pub fn new(seed: u8) -> Self {
        Self {
            key: ed25519_dalek::SigningKey::from_bytes(&[seed; 32]),
        }
    }

    /// The raw 32-byte public key.
    pub fn public_key(&self) -> Vec<u8> {
        self.key.verifying_key().to_bytes().to_vec()
    }

    /// Sign a record's canonical bytes, returning the signed pair.
    pub fn sign(&self, mut record: SignedPeerRecord) -> SignedRecord {
        use ed25519_dalek::Signer;
        record.public_key = self.public_key();
        let signature = self.key.sign(&record.signed_bytes()).to_bytes().to_vec();
        SignedRecord { record, signature }
    }
}

/// A validly-signed record from peer number `n`, signed by the deterministic signer seeded by `n`.
///
/// `self_expiry` of `0` means "no early self-expiry" (a far-future deadline, so the service TTL is
/// the effective one); a non-zero value sets the record's own `expiresAt`.
pub fn signed_record(
    n: u8,
    network_id: NetworkId,
    genesis: &[u8],
    event: RegistrationEvent,
    self_expiry: u64,
) -> SignedRecord {
    let record = SignedPeerRecord {
        network_id,
        genesis_hash: genesis.to_vec(),
        peer: NodeId::new(0, u64::from(n)),
        public_key: vec![0u8; 32],
        event,
        candidates: candidates(u64::from(n)),
        capabilities: caps(),
        issued_at_epoch_millis: ISSUED_AT,
        expires_at_epoch_millis: if self_expiry == 0 {
            u64::MAX
        } else {
            self_expiry
        },
    };
    TestSigner::new(n).sign(record)
}
