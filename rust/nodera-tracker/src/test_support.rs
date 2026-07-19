//! Test-only builders shared by the unit tests in every module.
//!
//! Kept in one place so a change to the announce shape does not have to be re-typed in five test
//! modules — and so the fixtures the tests use look like what a real peer sends.

use nodera_codec::messages::{AnnounceEvent, TrackerAnnounce};
use nodera_codec::types::{ManifestHolding, NodeCapabilities, NodeId, PeerRole};

/// Capabilities of a plain player peer (no seeding role).
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

/// Capabilities of a peer that seeds the world.
pub fn seeder_caps() -> NodeCapabilities {
    NodeCapabilities {
        roles: vec![PeerRole::WorldSeeder],
        ..caps()
    }
}

/// Capabilities of the world's host: a `FULL_ARCHIVE` holder, whose display metadata is honoured.
pub fn host_caps() -> NodeCapabilities {
    NodeCapabilities {
        roles: vec![PeerRole::FullArchive],
        ..caps()
    }
}

/// A holdings entry with an explicit bitmap.
pub fn holding(root_byte: u8, bitmap: &[u8]) -> ManifestHolding {
    ManifestHolding {
        manifest_root: vec![root_byte; 32],
        piece_bitmap: bitmap.to_vec(),
    }
}

/// An unsigned announce from peer number `n`.
pub fn announce(
    n: u64,
    genesis: &[u8],
    event: AnnounceEvent,
    capabilities: NodeCapabilities,
) -> TrackerAnnounce {
    TrackerAnnounce {
        genesis_hash: genesis.to_vec(),
        peer: NodeId::new(0, n),
        public_key: vec![0u8; 32],
        event,
        routes: vec![format!("198.51.100.{n}:25599")],
        capabilities,
        holdings: Vec::new(),
        world_name: String::new(),
        retention_deadline_epoch_millis: 0,
        reliability_bps: 9_000,
        announce_epoch_millis: 10_000,
        signature: vec![0u8; 64],
    }
}

/// A deterministic Ed25519 signer.
///
/// The service itself never signs — this exists only so the tests can produce genuinely valid
/// announces and prove the verification path accepts them (and rejects everything else).
pub struct TestSigner {
    key: ed25519_dalek::SigningKey,
}

impl TestSigner {
    /// A signer seeded by `seed`, so a test can reproduce the same identity every run.
    pub fn new(seed: u8) -> Self {
        Self {
            key: ed25519_dalek::SigningKey::from_bytes(&[seed; 32]),
        }
    }

    /// The raw 32-byte public key.
    pub fn public_key(&self) -> Vec<u8> {
        self.key.verifying_key().to_bytes().to_vec()
    }

    /// Fill in `public_key` and `signature` so the announce verifies.
    pub fn sign_announce(&self, announce: &mut TrackerAnnounce) {
        use ed25519_dalek::Signer;
        announce.public_key = self.public_key();
        let portion = announce.signed_portion();
        announce.signature = self.key.sign(&portion).to_bytes().to_vec();
    }
}
