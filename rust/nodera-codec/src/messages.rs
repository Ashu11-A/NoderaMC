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

/// "List the worlds you know" — the tracker directory / browse request (complement of the per-world
/// `TrackerQuery`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackerCatalogQuery {
    /// Max worlds to return; `0` = the tracker's default page.
    pub limit: u32,
}

/// One world's summary in a `TrackerCatalogResponse` directory listing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackerCatalogEntry {
    /// The world's genesis hash (the id a client then queries/joins).
    pub genesis_hash: Vec<u8>,
    /// The host-registered display name.
    pub world_name: String,
    /// Players currently online in-world.
    pub world_player_count: u64,
    /// Distinct pieces held network-wide.
    pub stored_chunks: u64,
    /// Mean network reliability, basis points.
    pub reliability_bps: u32,
    /// The world's health class.
    pub health: WorldHealth,
    /// The decommission-countdown deadline (epoch millis), or `0`.
    pub retention_deadline_epoch_millis: u64,
}

impl TrackerCatalogEntry {
    /// Encode this entry inline (it has no tag of its own).
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_bytes(&self.genesis_hash);
        w.write_string(&self.world_name);
        w.write_u64(self.world_player_count);
        w.write_u64(self.stored_chunks);
        w.write_u32(self.reliability_bps);
        self.health.encode(w);
        w.write_u64(self.retention_deadline_epoch_millis);
    }

    /// Decode the inverse of [`TrackerCatalogEntry::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        let genesis_hash = r.read_bytes_vec()?;
        let world_name = r.read_string()?;
        let world_player_count = r.read_u64()?;
        let stored_chunks = r.read_u64()?;
        let reliability_bps = r.read_u32()?;
        let health = WorldHealth::decode(r)?;
        let retention_deadline_epoch_millis = r.read_u64()?;
        Ok(Self {
            genesis_hash,
            world_name,
            world_player_count,
            stored_chunks,
            reliability_bps,
            health,
            retention_deadline_epoch_millis,
        })
    }
}

/// The tracker's directory listing — every listed world it knows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackerCatalogResponse {
    /// The listed worlds, in the tracker's order.
    pub worlds: Vec<TrackerCatalogEntry>,
}

/// Ask for the full claimed dial-route lists of a world's live peers (join flow).
///
/// A `TrackerResponse` `PeerEntry` carries exactly one route; a host announces several in
/// preference order (P2P listener + `mc/host:port` game endpoint while its game is open), and a
/// joiner needs them all to pick the lane it wants.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackerRoutesQuery {
    /// The world whose peers' routes are requested.
    pub genesis_hash: Vec<u8>,
}

/// One live peer's claimed dial routes, relayed verbatim from its signed announce.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerRoutes {
    /// The peer's node id.
    pub peer: NodeId,
    /// Its claimed routes, in the peer's own preference order.
    pub routes: Vec<String>,
}

impl PeerRoutes {
    /// Encode this entry inline (it has no tag of its own).
    pub fn encode(&self, w: &mut CanonicalWriter) {
        self.peer.encode(w);
        w.write_list(&self.routes, |ww, r| {
            ww.write_string(r);
        });
    }

    /// Decode the inverse of [`PeerRoutes::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        let peer = NodeId::decode(r)?;
        let routes = r.read_list(|rr| rr.read_string())?;
        Ok(Self { peer, routes })
    }
}

/// The answer to a [`TrackerRoutesQuery`] — the tracker relays claims, adding no authority.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackerRoutesResponse {
    /// The queried world.
    pub genesis_hash: Vec<u8>,
    /// One entry per live peer of the world.
    pub peers: Vec<PeerRoutes>,
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
    /// Players this peer can see in the world, or `-1` when it cannot see.
    ///
    /// Every seeder is in the second case: it holds the world's bytes and has no game in it.
    /// Carried here because it cannot be derived anywhere else — a tracker only ever observes who
    /// is *announcing* a swarm, and reporting that as a population credited three always-on
    /// seeders of an empty world with three players. Self-reported and unverifiable in the same
    /// weak sense as `world_name`: a peer can lie about its own population, which misleads a UI
    /// and nothing more.
    pub world_player_count: i64,
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

    pub fn write_signed_portion(&self, w: &mut CanonicalWriter) {
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
        // Offset by one so the "unknown" sentinel survives an unsigned field: -1 travels as 0, a
        // real count of n travels as n+1. Must match TrackerAnnounce.writeSignedPortion in Java,
        // byte for byte — this range is what the signature covers.
        w.write_u64((self.world_player_count + 1) as u64);
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
    /// Tag 44.
    TrackerCatalogQuery(TrackerCatalogQuery),
    /// Tag 45.
    TrackerCatalogResponse(TrackerCatalogResponse),
    /// Tag 49.
    TrackerRoutesQuery(TrackerRoutesQuery),
    /// Tag 50.
    TrackerRoutesResponse(TrackerRoutesResponse),
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
            Self::TrackerCatalogQuery(_) => message_tags::TRACKER_CATALOG_QUERY,
            Self::TrackerCatalogResponse(_) => message_tags::TRACKER_CATALOG_RESPONSE,
            Self::TrackerRoutesQuery(_) => message_tags::TRACKER_ROUTES_QUERY,
            Self::TrackerRoutesResponse(_) => message_tags::TRACKER_ROUTES_RESPONSE,
        }
    }

    /// Encode a complete `NDR2` frame carrying this message as an unsolicited event.
    ///
    /// The body is canonical TLV, so a field a peer has never heard of is skipped instead of
    /// desynchronising everything after it.
    pub fn encode(&self) -> Vec<u8> {
        self.encode_frame(crate::frame::flags::EVENT, 0)
    }

    /// Encode a complete `NDR2` frame with explicit routing metadata.
    pub fn encode_frame(&self, flags: u16, correlation_id: u64) -> Vec<u8> {
        crate::frame::NoderaFrame {
            epoch: crate::frame::WIRE_EPOCH,
            kind: self.tag(),
            flags,
            correlation_id,
            body: crate::wire::encode_discovery_body(self),
        }
        .encode()
    }

    /// Decode a complete `NDR2` frame.
    pub fn decode(frame: &[u8]) -> Result<Self> {
        Self::decode_with_overlay(frame).map(|(m, _)| m)
    }

    /// Decode a frame together with the overlay describing how its field set differed from this
    /// build's — what a forwarding peer needs in order to re-emit what it was given rather than
    /// its own idea of it.
    pub fn decode_with_overlay(frame: &[u8]) -> Result<(Self, crate::tlv::TlvOverlay)> {
        let parsed = crate::frame::NoderaFrame::decode(frame)?;
        crate::wire::decode_discovery_body_with_overlay(parsed.kind, &parsed.body)
    }

    /// Encode a frame, re-emitting fields this build did not understand when it was decoded.
    pub fn encode_with_overlay(&self, overlay: &crate::tlv::TlvOverlay) -> Result<Vec<u8>> {
        Ok(crate::frame::NoderaFrame {
            epoch: crate::frame::WIRE_EPOCH,
            kind: self.tag(),
            flags: crate::frame::flags::EVENT,
            correlation_id: 0,
            body: overlay.apply_to(&crate::wire::encode_discovery_body(self))?,
        }
        .encode())
    }

    /// The exact byte range of a received announce frame that its signature covers.
    ///
    /// Verification uses the bytes as they arrived, never a re-encoding of the decoded value: a
    /// re-encoding would check this implementation against itself and quietly accept an announce
    /// whose canonical form differs from what the peer actually signed. The announce crosses the
    /// tolerant plane whole and opaque, so the span lives inside field 1 of the TLV body.
    pub fn split_announce_signature(frame: &[u8]) -> Result<(&[u8], &[u8])> {
        let kind = crate::frame::NoderaFrame::peek_kind(frame)?;
        if kind != message_tags::TRACKER_ANNOUNCE {
            return Err(CodecError::UnknownTag(kind));
        }
        let body = crate::wire::validated_body(frame)?;
        let opaque = crate::tlv::field_slice(body, 1)?.ok_or_else(|| {
            CodecError::Malformed("announce frame carries no opaque payload".to_owned())
        })?;
        let announce = crate::wire::decode_legacy_announce(opaque)?;
        let trailer = announce
            .signature
            .len()
            .checked_add(4)
            .ok_or_else(|| CodecError::Malformed("signature length overflow".to_owned()))?;
        let split = opaque.len().checked_sub(trailer).ok_or_else(|| {
            CodecError::Malformed("announce shorter than its signature".to_owned())
        })?;
        Ok((&opaque[..split], &opaque[split + 4..]))
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
                public_key: Vec::new(),
                client_version: String::new(),
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
        // The frame declares its own body length, so an extra byte is caught at the header
        // rather than after a body has already been interpreted.
        let mut bytes = sample_response().encode();
        bytes.push(0);
        assert!(DiscoveryMessage::decode(&bytes).is_err());
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
            world_player_count: 2,
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
        // It is a prefix of the OPAQUE payload, not of the whole frame: the announce crosses the
        // tolerant plane whole and untouched inside one TLV field.
        assert!(frame.windows(signed.len()).any(|w| w == signed));
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
    fn an_unknown_kind_is_named_rather_than_guessed_at() {
        let frame = crate::frame::NoderaFrame::event(9_999, Vec::new()).encode();
        assert!(matches!(
            DiscoveryMessage::decode(&frame),
            Err(CodecError::UnknownTag(9_999))
        ));
    }
}
