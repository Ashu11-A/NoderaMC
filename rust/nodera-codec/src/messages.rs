//! The discovery message frames the Rust services speak.
//!
//! Byte-identical to `MessageCodec`'s tags 27–29. Only the discovery family is ported: the
//! services never touch consensus, simulation, or storage traffic (Task 0 §4 rule 7).

use crate::tags::message_tags;
use crate::types::{ManifestHolding, ManifestSeeders, NodeId, PeerEntry, WorldHealth};
use crate::{CanonicalReader, CanonicalWriter, CodecError, Result, ENCODING_VERSION};

/// "Who is on this world right now?" — keyed by the world's genesis hash (the `info_hash` analog).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackerQuery {
    /// The world's genesis hash.
    pub genesis_hash: Vec<u8>,
}

/// The tracker's answer: peers, per-manifest seeders, counters, health, retention countdown.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackerResponse {
    /// The world's genesis hash (echoed).
    pub genesis_hash: Vec<u8>,
    /// Display name of the world.
    pub world_name: String,
    /// A sample of the world's peers.
    pub peers: Vec<PeerEntry>,
    /// Seeders per manifest root.
    pub seeders: Vec<ManifestSeeders>,
    /// Players currently on the world.
    pub world_player_count: u64,
    /// Distinct manifest roots with at least one holder.
    pub stored_chunks: u64,
    /// Mean announced reliability, in basis points.
    pub reliability_bps: u32,
    /// The world's health class.
    pub health: WorldHealth,
    /// Epoch-millis deadline of the retention countdown, or `0` when no countdown is running.
    pub retention_deadline_epoch_millis: u64,
}

/// A holder gossiping which pieces it has for a world.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InventoryAdvertisement {
    /// The world's genesis hash.
    pub genesis_hash: Vec<u8>,
    /// The advertising peer.
    pub holder: NodeId,
    /// What the holder has, per manifest.
    pub holdings: Vec<ManifestHolding>,
}

/// Any discovery frame this crate can decode.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DiscoveryMessage {
    /// Tag 27.
    TrackerQuery(TrackerQuery),
    /// Tag 28.
    TrackerResponse(TrackerResponse),
    /// Tag 29.
    InventoryAdvertisement(InventoryAdvertisement),
}

impl DiscoveryMessage {
    /// The frozen wire tag of this message.
    pub fn tag(&self) -> u16 {
        match self {
            Self::TrackerQuery(_) => message_tags::TRACKER_QUERY,
            Self::TrackerResponse(_) => message_tags::TRACKER_RESPONSE,
            Self::InventoryAdvertisement(_) => message_tags::INVENTORY_ADVERTISEMENT,
        }
    }

    /// Encode a complete frame (`tag + version + body`).
    pub fn encode(&self) -> Vec<u8> {
        let mut w = CanonicalWriter::new();
        self.encode_into(&mut w);
        w.into_vec()
    }

    /// Encode into an existing writer.
    pub fn encode_into(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(self.tag(), ENCODING_VERSION);
        match self {
            Self::TrackerQuery(m) => {
                w.write_bytes(&m.genesis_hash);
            }
            Self::TrackerResponse(m) => {
                w.write_bytes(&m.genesis_hash);
                w.write_string(&m.world_name);
                w.write_list(&m.peers, |ww, p| p.encode(ww));
                w.write_list(&m.seeders, |ww, s| s.encode(ww));
                w.write_u64(m.world_player_count);
                w.write_u64(m.stored_chunks);
                w.write_u32(m.reliability_bps);
                m.health.encode(w);
                w.write_u64(m.retention_deadline_epoch_millis);
            }
            Self::InventoryAdvertisement(m) => {
                w.write_bytes(&m.genesis_hash);
                m.holder.encode(w);
                w.write_list(&m.holdings, |ww, h| h.encode(ww));
            }
        }
    }

    /// Decode a complete frame, rejecting trailing bytes.
    pub fn decode(frame: &[u8]) -> Result<Self> {
        let mut r = CanonicalReader::new(frame);
        let tag = r.read_u16()?;
        let version = r.read_u16()?;
        if version != ENCODING_VERSION {
            return Err(CodecError::UnsupportedVersion { tag, version });
        }
        let msg = match tag {
            message_tags::TRACKER_QUERY => Self::TrackerQuery(TrackerQuery {
                genesis_hash: r.read_bytes_vec()?,
            }),
            message_tags::TRACKER_RESPONSE => {
                let genesis_hash = r.read_bytes_vec()?;
                let world_name = r.read_string()?;
                let peers = r.read_list(PeerEntry::decode)?;
                let seeders = r.read_list(ManifestSeeders::decode)?;
                let world_player_count = r.read_u64()?;
                let stored_chunks = r.read_u64()?;
                let reliability_bps = r.read_u32()?;
                let health = WorldHealth::decode(&mut r)?;
                let retention_deadline_epoch_millis = r.read_u64()?;
                Self::TrackerResponse(TrackerResponse {
                    genesis_hash,
                    world_name,
                    peers,
                    seeders,
                    world_player_count,
                    stored_chunks,
                    reliability_bps,
                    health,
                    retention_deadline_epoch_millis,
                })
            }
            message_tags::INVENTORY_ADVERTISEMENT => {
                let genesis_hash = r.read_bytes_vec()?;
                let holder = NodeId::decode(&mut r)?;
                let holdings = r.read_list(ManifestHolding::decode)?;
                Self::InventoryAdvertisement(InventoryAdvertisement {
                    genesis_hash,
                    holder,
                    holdings,
                })
            }
            other => return Err(CodecError::UnknownTag(other)),
        };
        r.expect_end()?;
        Ok(msg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{NodeCapabilities, PeerRole};

    fn sample_response() -> DiscoveryMessage {
        DiscoveryMessage::TrackerResponse(TrackerResponse {
            genesis_hash: vec![0x11; 32],
            world_name: "overworld".to_owned(),
            peers: vec![PeerEntry {
                node_id: NodeId::new(7, 9),
                route: "198.51.100.4:25599".to_owned(),
                capabilities: NodeCapabilities {
                    logical_cores: 4,
                    memory_bytes: 8 << 30,
                    latency_ms: 30,
                    reliability_bits: 0x3FF0_0000_0000_0000,
                    max_primary_regions: 2,
                    max_validator_regions: 4,
                    accepts_worker: true,
                    roles: vec![PeerRole::WorldSeeder],
                },
                bootstrap: false,
            }],
            seeders: vec![ManifestSeeders {
                manifest_root: vec![0x22; 32],
                seeders: vec![NodeId::new(7, 9)],
            }],
            world_player_count: 3,
            stored_chunks: 128,
            reliability_bps: 9_400,
            health: WorldHealth::Degraded,
            retention_deadline_epoch_millis: 1_700_000_000_000,
        })
    }

    #[test]
    fn messages_round_trip_byte_exactly() {
        let msg = sample_response();
        let bytes = msg.encode();
        let decoded = DiscoveryMessage::decode(&bytes).unwrap();
        assert_eq!(decoded, msg);
        assert_eq!(decoded.encode(), bytes);
    }

    #[test]
    fn trailing_bytes_reject_the_frame() {
        let mut bytes = sample_response().encode();
        bytes.push(0);
        assert!(matches!(
            DiscoveryMessage::decode(&bytes),
            Err(CodecError::TrailingBytes(1))
        ));
    }

    #[test]
    fn unknown_tag_is_rejected() {
        let mut w = CanonicalWriter::new();
        w.write_frame_header(9_999, ENCODING_VERSION);
        assert!(matches!(
            DiscoveryMessage::decode(w.as_slice()),
            Err(CodecError::UnknownTag(9_999))
        ));
    }
}
