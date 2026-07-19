//! Nested canonical value types shared by the discovery messages.
//!
//! Each type encodes exactly as its Java counterpart: `u16 tag; u16 version; body`.

use crate::tags::type_tags;
use crate::{CanonicalReader, CanonicalWriter, CodecError, Result, ENCODING_VERSION};

/// A peer's node id — a UUID encoded as two big-endian `u64`s (most-significant first).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct NodeId {
    /// Most-significant 64 bits of the UUID.
    pub msb: u64,
    /// Least-significant 64 bits of the UUID.
    pub lsb: u64,
}

impl NodeId {
    /// Build a node id from its two halves.
    pub fn new(msb: u64, lsb: u64) -> Self {
        Self { msb, lsb }
    }

    /// Encode `tag(1) + version + msb + lsb`.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(type_tags::NODE_ID, ENCODING_VERSION);
        w.write_u64(self.msb);
        w.write_u64(self.lsb);
    }

    /// Decode the inverse of [`NodeId::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        r.read_frame_header(type_tags::NODE_ID, ENCODING_VERSION)?;
        Ok(Self {
            msb: r.read_u64()?,
            lsb: r.read_u64()?,
        })
    }

    /// The canonical `8-4-4-4-12` UUID string — used only for logs and config, never on the wire.
    pub fn to_uuid_string(self) -> String {
        format!(
            "{:08x}-{:04x}-{:04x}-{:04x}-{:012x}",
            (self.msb >> 32) & 0xFFFF_FFFF,
            (self.msb >> 16) & 0xFFFF,
            self.msb & 0xFFFF,
            (self.lsb >> 48) & 0xFFFF,
            self.lsb & 0xFFFF_FFFF_FFFF,
        )
    }
}

/// A peer's advertised role. Ordinals are frozen — they are the encoded form.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[repr(u8)]
pub enum PeerRole {
    /// Well-known entry point new peers dial first.
    Bootstrap = 0,
    /// Forwards traffic for peers that cannot reach each other directly.
    Relay = 1,
    /// Owns a player session's connection.
    SessionGateway = 2,
    /// Executes a region's batches.
    RegionExecutor = 3,
    /// Re-executes and votes on a region's batches.
    RegionValidator = 4,
    /// Holds part of the world's content.
    PartialArchive = 5,
    /// Holds the whole world's content.
    FullArchive = 6,
    /// Seeds world content to the swarm.
    WorldSeeder = 7,
}

impl PeerRole {
    /// Map a frozen ordinal to its role.
    pub fn from_ordinal(ordinal: u8) -> Result<Self> {
        Ok(match ordinal {
            0 => Self::Bootstrap,
            1 => Self::Relay,
            2 => Self::SessionGateway,
            3 => Self::RegionExecutor,
            4 => Self::RegionValidator,
            5 => Self::PartialArchive,
            6 => Self::FullArchive,
            7 => Self::WorldSeeder,
            other => {
                return Err(CodecError::Malformed(format!(
                    "invalid PeerRole ordinal {other}"
                )))
            }
        })
    }

    /// Whether a peer in this role serves world content (the tracker's "seeder" floor).
    pub fn is_seeder(self) -> bool {
        matches!(self, Self::FullArchive | Self::WorldSeeder)
    }
}

/// A peer's declared hardware/behaviour capabilities.
///
/// `reliability` is carried as the raw `Double.doubleToLongBits` pattern: the services never do
/// float arithmetic on it — they forward the exact bits the peer signed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NodeCapabilities {
    /// Logical CPU cores.
    pub logical_cores: u32,
    /// Usable memory in bytes.
    pub memory_bytes: u64,
    /// Self-reported round-trip latency in milliseconds.
    pub latency_ms: u32,
    /// `Double.doubleToLongBits(reliability)` — forwarded verbatim, never interpreted.
    pub reliability_bits: u64,
    /// Maximum regions the peer will execute as primary.
    pub max_primary_regions: u32,
    /// Maximum regions the peer will validate.
    pub max_validator_regions: u32,
    /// Whether the peer accepts worker duty.
    pub accepts_worker: bool,
    /// Advertised roles, in frozen-ordinal order (canonical set encoding).
    pub roles: Vec<PeerRole>,
}

impl NodeCapabilities {
    /// Encode `tag(2) + version + body` exactly as `NodeCapabilities.encode`.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(type_tags::NODE_CAPABILITIES, ENCODING_VERSION);
        w.write_u32(self.logical_cores);
        w.write_u64(self.memory_bytes);
        w.write_u32(self.latency_ms);
        w.write_u64(self.reliability_bits);
        w.write_u32(self.max_primary_regions);
        w.write_u32(self.max_validator_regions);
        w.write_u8(u8::from(self.accepts_worker));
        w.write_list(&self.roles, |ww, role| {
            ww.write_u8(*role as u8);
        });
    }

    /// Decode the inverse of [`NodeCapabilities::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        r.read_frame_header(type_tags::NODE_CAPABILITIES, ENCODING_VERSION)?;
        let logical_cores = r.read_u32()?;
        let memory_bytes = r.read_u64()?;
        let latency_ms = r.read_u32()?;
        let reliability_bits = r.read_u64()?;
        let max_primary_regions = r.read_u32()?;
        let max_validator_regions = r.read_u32()?;
        let accepts_worker = r.read_u8()? != 0;
        let roles = r.read_list(|rr| PeerRole::from_ordinal(rr.read_u8()?))?;
        Ok(Self {
            logical_cores,
            memory_bytes,
            latency_ms,
            reliability_bits,
            max_primary_regions,
            max_validator_regions,
            accepts_worker,
            roles,
        })
    }
}

/// A world's survivability across the swarm. Ordinals are frozen.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum WorldHealth {
    /// Enough seeders to sustain the replication factor (green).
    Healthy = 0,
    /// Under-replicated, or zero seeders inside the retention window (red).
    Degraded = 1,
    /// Retention window elapsed with no seeder (gray).
    Dead = 2,
}

impl WorldHealth {
    /// Map a frozen ordinal to its health class.
    pub fn from_ordinal(ordinal: u8) -> Result<Self> {
        Ok(match ordinal {
            0 => Self::Healthy,
            1 => Self::Degraded,
            2 => Self::Dead,
            other => {
                return Err(CodecError::Malformed(format!(
                    "invalid WorldHealth ordinal {other}"
                )))
            }
        })
    }

    /// Encode `tag(73) + version + ordinal`.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(type_tags::WORLD_HEALTH, ENCODING_VERSION);
        w.write_u8(*self as u8);
    }

    /// Decode the inverse of [`WorldHealth::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        r.read_frame_header(type_tags::WORLD_HEALTH, ENCODING_VERSION)?;
        Self::from_ordinal(r.read_u8()?)
    }
}

/// One membership entry: who a peer is and how to reach it.
///
/// Nested inside `MembershipUpdate`/`TrackerResponse`; it has no tag of its own on either side.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerEntry {
    /// The peer's id.
    pub node_id: NodeId,
    /// Advertised dial route (`host:port`), as the peer claims it.
    pub route: String,
    /// The peer's declared capabilities.
    pub capabilities: NodeCapabilities,
    /// Whether the peer serves as a bootstrap entry point.
    pub bootstrap: bool,
}

impl PeerEntry {
    /// Encode the untagged body (`nodeId + route + capabilities + bootstrap`).
    pub fn encode(&self, w: &mut CanonicalWriter) {
        self.node_id.encode(w);
        w.write_string(&self.route);
        self.capabilities.encode(w);
        w.write_bool(self.bootstrap);
    }

    /// Decode the inverse of [`PeerEntry::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        let node_id = NodeId::decode(r)?;
        let route = r.read_string()?;
        let capabilities = NodeCapabilities::decode(r)?;
        let bootstrap = r.read_bool()?;
        Ok(Self {
            node_id,
            route,
            capabilities,
            bootstrap,
        })
    }
}

/// Which pieces of one manifest a holder has, as a bitmap.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManifestHolding {
    /// The manifest's root hash.
    pub manifest_root: Vec<u8>,
    /// One bit per piece index, MSB-first within each byte.
    pub piece_bitmap: Vec<u8>,
}

impl ManifestHolding {
    /// Encode the untagged body.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_bytes(&self.manifest_root);
        w.write_bytes(&self.piece_bitmap);
    }

    /// Decode the inverse of [`ManifestHolding::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        Ok(Self {
            manifest_root: r.read_bytes_vec()?,
            piece_bitmap: r.read_bytes_vec()?,
        })
    }

    /// Count of set bits — how many pieces of this manifest the holder actually has.
    pub fn held_piece_count(&self) -> u32 {
        self.piece_bitmap.iter().map(|b| b.count_ones()).sum()
    }
}

/// The peers seeding one manifest.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManifestSeeders {
    /// The manifest's root hash.
    pub manifest_root: Vec<u8>,
    /// Node ids known to hold it.
    pub seeders: Vec<NodeId>,
}

impl ManifestSeeders {
    /// Encode the untagged body.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_bytes(&self.manifest_root);
        w.write_list(&self.seeders, |ww, id| id.encode(ww));
    }

    /// Decode the inverse of [`ManifestSeeders::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        let manifest_root = r.read_bytes_vec()?;
        let seeders = r.read_list(NodeId::decode)?;
        Ok(Self {
            manifest_root,
            seeders,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn caps() -> NodeCapabilities {
        NodeCapabilities {
            logical_cores: 8,
            memory_bytes: 17_179_869_184,
            latency_ms: 42,
            reliability_bits: 0x3FF0_0000_0000_0000,
            max_primary_regions: 4,
            max_validator_regions: 8,
            accepts_worker: true,
            roles: vec![PeerRole::FullArchive, PeerRole::WorldSeeder],
        }
    }

    #[test]
    fn nested_types_round_trip() {
        let entry = PeerEntry {
            node_id: NodeId::new(1, 2),
            route: "203.0.113.7:25599".to_owned(),
            capabilities: caps(),
            bootstrap: true,
        };
        let mut w = CanonicalWriter::new();
        entry.encode(&mut w);
        let bytes = w.into_vec();
        let mut r = CanonicalReader::new(&bytes);
        assert_eq!(PeerEntry::decode(&mut r).unwrap(), entry);
        r.expect_end().unwrap();
    }

    #[test]
    fn wrong_nested_tag_is_rejected() {
        let mut w = CanonicalWriter::new();
        WorldHealth::Healthy.encode(&mut w);
        let bytes = w.into_vec();
        let mut r = CanonicalReader::new(&bytes);
        assert!(matches!(
            NodeId::decode(&mut r),
            Err(CodecError::UnexpectedTag { .. })
        ));
    }

    #[test]
    fn held_piece_count_counts_bits() {
        let holding = ManifestHolding {
            manifest_root: vec![0xAA; 32],
            piece_bitmap: vec![0b1010_0000, 0xFF],
        };
        assert_eq!(holding.held_piece_count(), 10);
    }
}
