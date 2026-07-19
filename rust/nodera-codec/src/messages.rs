//! The discovery message frames the Rust services speak.
//!
//! Byte-identical to `MessageCodec`'s tags 27–29 and 33–34. Only the discovery family is ported: the
//! services never touch consensus, simulation, or storage traffic (Task 0 §4 rule 7).

use crate::tags::message_tags;
use crate::types::{
    ManifestHolding, ManifestSeeders, NodeCapabilities, NodeId, PeerEntry, WorldHealth,
};
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

/// A peer's lifecycle event on a swarm (frozen ordinals — the encoded form).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum AnnounceEvent {
    /// First announce of a session: register (or replace) the record.
    Started = 0,
    /// Periodic refresh: extend the record and update holdings/reliability.
    Heartbeat = 1,
    /// Graceful departure: drop the record now instead of waiting for the TTL sweep.
    Stopped = 2,
}

impl AnnounceEvent {
    /// Map a frozen ordinal to its event.
    pub fn from_ordinal(ordinal: u8) -> Result<Self> {
        Ok(match ordinal {
            0 => Self::Started,
            1 => Self::Heartbeat,
            2 => Self::Stopped,
            other => {
                return Err(CodecError::Malformed(format!(
                    "invalid AnnounceEvent ordinal {other}"
                )))
            }
        })
    }
}

/// A peer's signed self-registration with a tracker.
///
/// The service verifies [`TrackerAnnounce::signed_portion`] against `public_key` before touching
/// its registry: only the key holder can register, refresh, or remove that identity's record.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackerAnnounce {
    /// The world (swarm id).
    pub genesis_hash: Vec<u8>,
    /// The announcing peer.
    pub peer: NodeId,
    /// The peer's X.509 Ed25519 public key.
    pub public_key: Vec<u8>,
    /// What the announce asks for.
    pub event: AnnounceEvent,
    /// Advertised dial routes, in preference order — a claim, never proof.
    pub routes: Vec<String>,
    /// Declared capabilities, including the roles the seeder floor reads.
    pub capabilities: NodeCapabilities,
    /// Which pieces of which manifests the peer holds for this world.
    pub holdings: Vec<ManifestHolding>,
    /// The world's display name — honoured only from a `FULL_ARCHIVE` host, empty otherwise.
    pub world_name: String,
    /// The retention countdown the host wants surfaced, or `0` when none is running.
    pub retention_deadline_epoch_millis: u64,
    /// Self-reported reliability in basis points.
    pub reliability_bps: u32,
    /// The peer's wall-clock at announce time — a freshness bound only.
    pub announce_epoch_millis: u64,
    /// Ed25519 over the signed portion.
    pub signature: Vec<u8>,
}

impl TrackerAnnounce {
    /// Encode everything the signature covers: the frame minus the trailing signature field.
    ///
    /// Callers verifying a *received* announce should prefer the received byte range
    /// ([`DiscoveryMessage::split_announce_signature`]) — re-encoding a decoded value would verify
    /// this implementation against itself rather than against what the peer signed.
    pub fn signed_portion(&self) -> Vec<u8> {
        let mut w = CanonicalWriter::with_capacity(512);
        self.write_signed_portion(&mut w);
        w.into_vec()
    }

    fn write_signed_portion(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(message_tags::TRACKER_ANNOUNCE, ENCODING_VERSION);
        w.write_bytes(&self.genesis_hash);
        self.peer.encode(w);
        w.write_bytes(&self.public_key);
        w.write_u8(self.event as u8);
        w.write_list(&self.routes, |ww, route| {
            ww.write_string(route);
        });
        self.capabilities.encode(w);
        w.write_list(&self.holdings, |ww, h| h.encode(ww));
        w.write_string(&self.world_name);
        w.write_u64(self.retention_deadline_epoch_millis);
        w.write_u32(self.reliability_bps);
        w.write_u64(self.announce_epoch_millis);
    }

    /// Whether this peer claims a seeding role for the world.
    pub fn is_seeder(&self) -> bool {
        self.capabilities.roles.iter().any(|r| r.is_seeder())
    }

    /// Whether this peer claims to be the world's host — the `FULL_ARCHIVE` holder that rule 0
    /// makes the world's physical backup, and the only role whose display metadata is honoured.
    pub fn is_host(&self) -> bool {
        self.capabilities
            .roles
            .contains(&crate::types::PeerRole::FullArchive)
    }
}

/// The tracker's reply to an announce: accepted or not, and when to come back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackerAnnounceAck {
    /// Whether the record was registered/refreshed/removed.
    pub accepted: bool,
    /// The interval before the next announce — the tracker paces the traffic.
    pub next_announce_after_seconds: u32,
    /// Empty when accepted; otherwise a short stable rejection code.
    pub reason: String,
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
    /// Tag 33.
    TrackerAnnounce(TrackerAnnounce),
    /// Tag 34.
    TrackerAnnounceAck(TrackerAnnounceAck),
}

impl DiscoveryMessage {
    /// The frozen wire tag of this message.
    pub fn tag(&self) -> u16 {
        match self {
            Self::TrackerQuery(_) => message_tags::TRACKER_QUERY,
            Self::TrackerResponse(_) => message_tags::TRACKER_RESPONSE,
            Self::InventoryAdvertisement(_) => message_tags::INVENTORY_ADVERTISEMENT,
            Self::TrackerAnnounce(_) => message_tags::TRACKER_ANNOUNCE,
            Self::TrackerAnnounceAck(_) => message_tags::TRACKER_ANNOUNCE_ACK,
        }
    }

    /// Encode a complete frame (`tag + version + body`).
    pub fn encode(&self) -> Vec<u8> {
        let mut w = CanonicalWriter::new();
        self.encode_into(&mut w);
        w.into_vec()
    }

    /// The exact byte range of a received announce frame that its signature covers.
    ///
    /// Verification uses the bytes as they arrived, never a re-encoding of the decoded value: a
    /// re-encoding would check this implementation against itself and quietly accept an announce
    /// whose canonical form differs from what the peer actually signed.
    pub fn split_announce_signature(frame: &[u8]) -> Result<(&[u8], &[u8])> {
        let announce = match Self::decode(frame)? {
            Self::TrackerAnnounce(a) => a,
            other => {
                return Err(CodecError::UnknownTag(other.tag()));
            }
        };
        let sig_len = announce.signature.len();
        let trailer = sig_len
            .checked_add(4)
            .ok_or_else(|| CodecError::Malformed("signature length overflow".to_owned()))?;
        let split = frame.len().checked_sub(trailer).ok_or_else(|| {
            CodecError::Malformed("announce shorter than its signature".to_owned())
        })?;
        Ok((&frame[..split], &frame[split + 4..]))
    }

    /// Encode into an existing writer.
    pub fn encode_into(&self, w: &mut CanonicalWriter) {
        if let Self::TrackerAnnounce(m) = self {
            // The signed portion owns the frame header, so signer and codec cannot disagree about
            // where the signature starts.
            m.write_signed_portion(w);
            w.write_bytes(&m.signature);
            return;
        }
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
            Self::TrackerAnnounce(_) => unreachable!("handled above: signed-portion layout"),
            Self::TrackerAnnounceAck(m) => {
                w.write_bool(m.accepted);
                w.write_u32(m.next_announce_after_seconds);
                w.write_string(&m.reason);
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
            message_tags::TRACKER_ANNOUNCE => {
                let genesis_hash = r.read_bytes_vec()?;
                let peer = NodeId::decode(&mut r)?;
                let public_key = r.read_bytes_vec()?;
                let event = AnnounceEvent::from_ordinal(r.read_u8()?)?;
                let routes = r.read_list(|rr| rr.read_string())?;
                let capabilities = NodeCapabilities::decode(&mut r)?;
                let holdings = r.read_list(ManifestHolding::decode)?;
                let world_name = r.read_string()?;
                let retention_deadline_epoch_millis = r.read_u64()?;
                let reliability_bps = r.read_u32()?;
                let announce_epoch_millis = r.read_u64()?;
                let signature = r.read_bytes_vec()?;
                Self::TrackerAnnounce(TrackerAnnounce {
                    genesis_hash,
                    peer,
                    public_key,
                    event,
                    routes,
                    capabilities,
                    holdings,
                    world_name,
                    retention_deadline_epoch_millis,
                    reliability_bps,
                    announce_epoch_millis,
                    signature,
                })
            }
            message_tags::TRACKER_ANNOUNCE_ACK => Self::TrackerAnnounceAck(TrackerAnnounceAck {
                accepted: r.read_bool()?,
                next_announce_after_seconds: r.read_u32()?,
                reason: r.read_string()?,
            }),
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

    fn sample_announce() -> TrackerAnnounce {
        TrackerAnnounce {
            genesis_hash: vec![0x11; 32],
            peer: NodeId::new(1, 2),
            public_key: vec![0x66; 44],
            event: AnnounceEvent::Started,
            routes: vec!["198.51.100.4:25599".to_owned()],
            capabilities: NodeCapabilities {
                logical_cores: 8,
                memory_bytes: 16 << 30,
                latency_ms: 42,
                reliability_bits: 0x3FF0_0000_0000_0000,
                max_primary_regions: 4,
                max_validator_regions: 8,
                accepts_worker: true,
                roles: vec![PeerRole::FullArchive],
            },
            holdings: vec![ManifestHolding {
                manifest_root: vec![0x22; 32],
                piece_bitmap: vec![0xFF],
            }],
            world_name: "nodera-overworld".to_owned(),
            retention_deadline_epoch_millis: 0,
            reliability_bps: 9_400,
            announce_epoch_millis: 1_700_000_000_000,
            signature: vec![0x77; 64],
        }
    }

    #[test]
    fn announce_round_trips_and_its_signed_range_is_a_prefix_of_the_frame() {
        let msg = DiscoveryMessage::TrackerAnnounce(sample_announce());
        let frame = msg.encode();
        assert_eq!(DiscoveryMessage::decode(&frame).unwrap(), msg);

        let (signed, signature) = DiscoveryMessage::split_announce_signature(&frame).unwrap();
        assert_eq!(signature, &[0x77; 64]);
        // The range taken from the received bytes must equal what a signer would have produced.
        assert_eq!(signed, sample_announce().signed_portion().as_slice());
        assert!(frame.starts_with(signed));
    }

    #[test]
    fn split_announce_signature_refuses_a_non_announce_frame() {
        let frame = sample_response().encode();
        assert!(DiscoveryMessage::split_announce_signature(&frame).is_err());
    }

    #[test]
    fn seeder_roles_are_recognised() {
        let mut announce = sample_announce();
        assert!(announce.is_seeder());
        announce.capabilities.roles = vec![PeerRole::PartialArchive];
        assert!(!announce.is_seeder());
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
