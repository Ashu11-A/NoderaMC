//! The infrastructure plane's TLV bodies, mirroring Java's `InfrastructureCodec`.
//!
//! Field ids are part of the wire contract and must match the Java shapes exactly; the shared
//! fixture corpus is what proves they do. Nothing here parses a signed structure: a
//! `TrackerAnnounce`, a `SignedPeerRecord`, a `ServiceRecord` and a `ServiceScoreReport` all cross
//! this plane as opaque canonical bytes, because their signature covers those bytes and re-spelling
//! them would invalidate it.

use crate::messages::{
    AnnounceEvent, DiscoveryMessage, InventoryAdvertisement, PeerRoutes, TrackerAnnounce,
    TrackerAnnounceAck, TrackerCatalogEntry, TrackerCatalogQuery, TrackerCatalogResponse,
    TrackerQuery, TrackerResponse, TrackerRoutesQuery, TrackerRoutesResponse,
};
use crate::rendezvous::{
    ObservedAddress, PunchSync, RelayConnect, RelayIncoming, RelayReservation, RelayReserve,
    RendezvousDiscover, RendezvousMessage, RendezvousPeers, RendezvousRegister, SignedRecord,
};
use crate::service::{
    ServiceAnnounce, ServiceAnnounceAck, ServiceDirectoryEntry, ServiceDirectoryQuery,
    ServiceDirectoryResponse, ServiceDrainNotice, ServiceKind, ServiceMessage, ServiceRecord,
    ServiceScore, ServiceScoreReport,
};
use crate::tags::message_tags;
use crate::tlv::{TlvBody, TlvWriter};
use crate::types::{
    CandidateKind, ManifestHolding, ManifestSeeders, NetworkId, NodeCapabilities, NodeId,
    PeerCandidate, PeerEntry, PeerRole, SignedPeerRecord, WorldHealth,
};
use crate::{CanonicalReader, CanonicalWriter, CodecError, Result};

// ---------------------------------------------------------------- enum wire codes
//
// Explicit and permanent, mirroring `dev.nodera.protocol.wire.WireEnums`. They are written down
// rather than derived from a declaration's position, so reordering an enum on either side is no
// longer a silent network change.

fn peer_role_code(role: PeerRole) -> u16 {
    match role {
        PeerRole::Bootstrap => 1,
        PeerRole::Relay => 2,
        PeerRole::SessionGateway => 3,
        PeerRole::RegionExecutor => 4,
        PeerRole::RegionValidator => 5,
        PeerRole::PartialArchive => 6,
        PeerRole::FullArchive => 7,
        PeerRole::WorldSeeder => 8,
    }
}

fn peer_role_from_code(code: u32) -> Option<PeerRole> {
    Some(match code {
        1 => PeerRole::Bootstrap,
        2 => PeerRole::Relay,
        3 => PeerRole::SessionGateway,
        4 => PeerRole::RegionExecutor,
        5 => PeerRole::RegionValidator,
        6 => PeerRole::PartialArchive,
        7 => PeerRole::FullArchive,
        8 => PeerRole::WorldSeeder,
        _ => return None,
    })
}

fn world_health_code(health: WorldHealth) -> u16 {
    match health {
        WorldHealth::Healthy => 1,
        WorldHealth::Degraded => 2,
        WorldHealth::Dead => 3,
    }
}

fn world_health_from_code_at(b: &TlvBody, id: u16, code: u16) -> WorldHealth {
    if !matches!(code, 1..=3) {
        b.mark_verbatim(id);
    }
    world_health_from_code(code)
}

fn world_health_from_code(code: u16) -> WorldHealth {
    // Infrastructure policy: an unrecognised health is degraded, never healthy. Guessing upward
    // would advertise a world as serving on the strength of a word this build cannot read.
    match code {
        1 => WorldHealth::Healthy,
        3 => WorldHealth::Dead,
        _ => WorldHealth::Degraded,
    }
}

fn candidate_kind_code(kind: CandidateKind) -> u16 {
    match kind {
        CandidateKind::Host => 1,
        CandidateKind::Public => 2,
        CandidateKind::ServerReflexive => 3,
        CandidateKind::Mapped => 4,
        CandidateKind::Relay => 5,
    }
}

fn candidate_kind_from_code(code: u16) -> Result<CandidateKind> {
    Ok(match code {
        1 => CandidateKind::Host,
        2 => CandidateKind::Public,
        3 => CandidateKind::ServerReflexive,
        4 => CandidateKind::Mapped,
        5 => CandidateKind::Relay,
        other => {
            return Err(CodecError::Malformed(format!(
                "unknown CandidateKind wire code {other}"
            )))
        }
    })
}

fn service_kind_code(kind: ServiceKind) -> u16 {
    match kind {
        ServiceKind::Rendezvous => 1,
        ServiceKind::Tracker => 2,
    }
}

fn service_kind_from_code(code: u16) -> Result<ServiceKind> {
    Ok(match code {
        1 => ServiceKind::Rendezvous,
        2 => ServiceKind::Tracker,
        other => {
            return Err(CodecError::Malformed(format!(
                "unknown ServiceKind wire code {other}"
            )))
        }
    })
}

// ---------------------------------------------------------------- opaque canonical helpers

/// The canonical bytes of a signed structure, carried opaquely.
fn canonical(encode: impl FnOnce(&mut CanonicalWriter)) -> Vec<u8> {
    let mut w = CanonicalWriter::new();
    encode(&mut w);
    w.into_vec()
}

/// Parse an opaque canonical structure, refusing trailing bytes.
fn from_canonical<T>(
    raw: &[u8],
    decode: impl FnOnce(&mut CanonicalReader<'_>) -> Result<T>,
) -> Result<T> {
    let mut r = CanonicalReader::new(raw);
    let value = decode(&mut r)?;
    r.expect_end()?;
    Ok(value)
}

// ---------------------------------------------------------------- nested structures

fn write_capabilities(w: &mut TlvWriter, c: &NodeCapabilities) {
    w.u32(1, c.logical_cores)
        .u64(2, c.memory_bytes)
        .u32(3, c.latency_ms)
        .u64(4, c.reliability_bits)
        .u32(5, c.max_primary_regions)
        .u32(6, c.max_validator_regions)
        .bool(7, c.accepts_worker);
    let mut codes: Vec<u32> = c
        .roles
        .iter()
        .map(|r| u32::from(peer_role_code(*r)))
        .collect();
    codes.sort_unstable();
    codes.dedup();
    w.u32_array(8, &codes);
}

fn read_capabilities(b: &TlvBody) -> Result<NodeCapabilities> {
    // A role this build does not know is dropped rather than fatal: it describes what the peer
    // offers, and a capability we cannot use is one we simply do not use. But dropping it from the
    // VALUE must not drop it from the BYTES — a peer that re-advertises this structure would
    // otherwise strip a capability the rest of the mesh can use.
    let mut roles = Vec::new();
    for code in b.u32_array(8)? {
        match peer_role_from_code(code) {
            Some(role) => roles.push(role),
            None => b.mark_verbatim(8),
        }
    }
    Ok(NodeCapabilities {
        logical_cores: b.u32(1, 0)?,
        memory_bytes: b.u64(2, 0)?,
        latency_ms: b.u32(3, 0)?,
        reliability_bits: b.u64(4, 0)?,
        max_primary_regions: b.u32(5, 0)?,
        max_validator_regions: b.u32(6, 0)?,
        accepts_worker: b.bool(7, false)?,
        roles,
    })
}

fn write_peer_entry(w: &mut TlvWriter, e: &PeerEntry) {
    w.uuid(1, e.node_id.msb, e.node_id.lsb)
        .str(2, &e.route)
        .nested(3, |n| write_capabilities(n, &e.capabilities))
        .bool(4, e.bootstrap)
        .bytes(5, &e.public_key)
        .str(6, &e.client_version);
}

fn read_peer_entry(b: &TlvBody) -> Result<PeerEntry> {
    let (msb, lsb) = b.uuid(1)?;
    Ok(PeerEntry {
        node_id: NodeId::new(msb, lsb),
        route: b.str(2)?,
        capabilities: {
            let inner = b.nested(3)?;
            let caps = read_capabilities(&inner)?;
            b.seal_nested(3, &inner);
            caps
        },
        bootstrap: b.bool(4, false)?,
        public_key: b.bytes(5)?,
        client_version: b.str(6)?,
    })
}

fn write_holding(w: &mut TlvWriter, h: &ManifestHolding) {
    w.bytes(1, &h.manifest_root).bytes(2, &h.piece_bitmap);
}

fn read_holding(b: &TlvBody) -> Result<ManifestHolding> {
    Ok(ManifestHolding {
        manifest_root: b.bytes(1)?,
        piece_bitmap: b.bytes(2)?,
    })
}

fn write_seeders(w: &mut TlvWriter, s: &ManifestSeeders) {
    w.bytes(1, &s.manifest_root).list(2, &s.seeders, |e, id| {
        e.uuid(1, id.msb, id.lsb);
    });
}

fn read_seeders(b: &TlvBody) -> Result<ManifestSeeders> {
    Ok(ManifestSeeders {
        manifest_root: b.bytes(1)?,
        seeders: b.list(2, |e| {
            let (msb, lsb) = e.uuid(1)?;
            Ok(NodeId::new(msb, lsb))
        })?,
    })
}

fn write_catalog_entry(w: &mut TlvWriter, e: &TrackerCatalogEntry) {
    w.bytes(1, &e.genesis_hash)
        .str(2, &e.world_name)
        .u64(3, e.world_player_count)
        .u64(4, e.stored_chunks)
        .u32(5, e.reliability_bps)
        .u16(6, world_health_code(e.health))
        .u64(7, e.retention_deadline_epoch_millis);
}

fn read_catalog_entry(b: &TlvBody) -> Result<TrackerCatalogEntry> {
    Ok(TrackerCatalogEntry {
        genesis_hash: b.bytes(1)?,
        world_name: b.str(2)?,
        world_player_count: b.u64(3, 0)?,
        stored_chunks: b.u64(4, 0)?,
        reliability_bps: b.u32(5, 0)?,
        health: world_health_from_code_at(b, 6, b.u16(6, 0)?),
        retention_deadline_epoch_millis: b.u64(7, 0)?,
    })
}

fn write_candidate(w: &mut TlvWriter, c: &PeerCandidate) {
    w.u16(1, candidate_kind_code(c.kind))
        .str(2, &c.address)
        .u32(3, c.priority);
}

fn read_candidate(b: &TlvBody) -> Result<PeerCandidate> {
    Ok(PeerCandidate {
        kind: candidate_kind_from_code(b.u16(1, 0)?)?,
        address: b.str(2)?,
        priority: b.u32(3, 0)?,
    })
}

fn write_signed_record(w: &mut TlvWriter, s: &SignedRecord) {
    w.bytes(1, &canonical(|c| s.record.encode(c)))
        .bytes(2, &s.signature);
}

fn read_signed_record(b: &TlvBody) -> Result<SignedRecord> {
    Ok(SignedRecord {
        record: from_canonical(&b.bytes(1)?, SignedPeerRecord::decode)?,
        signature: b.bytes(2)?,
    })
}

fn write_score(w: &mut TlvWriter, s: &ServiceScore) {
    w.u32(1, s.availability_permille)
        .u32(2, s.rtt_p50_millis)
        .u32(3, s.rtt_p95_millis)
        .u32(4, s.capacity_permille)
        .u32(5, s.freshness_permille)
        .u32(6, s.reporter_count)
        .u32(7, s.composite_permille);
}

fn read_score(b: &TlvBody) -> Result<ServiceScore> {
    Ok(ServiceScore {
        availability_permille: b.u32(1, 0)?,
        rtt_p50_millis: b.u32(2, 0)?,
        rtt_p95_millis: b.u32(3, 0)?,
        capacity_permille: b.u32(4, 0)?,
        freshness_permille: b.u32(5, 0)?,
        reporter_count: b.u32(6, 0)?,
        composite_permille: b.u32(7, 0)?,
    })
}

fn write_directory_entry(w: &mut TlvWriter, e: &ServiceDirectoryEntry) {
    w.bytes(1, &canonical(|c| e.record.encode(c)))
        .bytes(2, &e.signature)
        .nested(3, |n| write_score(n, &e.score));
}

fn read_directory_entry(b: &TlvBody) -> Result<ServiceDirectoryEntry> {
    Ok(ServiceDirectoryEntry {
        record: from_canonical(&b.bytes(1)?, ServiceRecord::decode)?,
        signature: b.bytes(2)?,
        score: {
            let inner = b.nested(3)?;
            let score = read_score(&inner)?;
            b.seal_nested(3, &inner);
            score
        },
    })
}

// ---------------------------------------------------------------- consensus payloads

/// The TLV field that carries a consensus payload's opaque canonical bytes.
pub const CONSENSUS_PAYLOAD_FIELD: u16 = 1;

/// Wrap a consensus payload's strict canonical bytes in an `NDR2` frame.
///
/// The tolerant plane never re-spells a signed payload: its bytes are its identity, hashed and
/// compared by peers that must agree on a state root. This layer routes it and nothing more.
pub fn encode_consensus_frame(
    kind: u16,
    payload: &[u8],
    flags: u16,
    correlation_id: u64,
) -> Vec<u8> {
    let mut w = TlvWriter::new();
    w.bytes(CONSENSUS_PAYLOAD_FIELD, payload);
    crate::frame::NoderaFrame {
        epoch: crate::frame::WIRE_EPOCH,
        kind,
        flags,
        correlation_id,
        body: w.finish(),
    }
    .encode()
}

/// Validate a received frame in full and borrow its body.
///
/// Borrowed rather than copied because the bytes that get verified must be the ones that arrived:
/// re-encoding a decoded value checks this implementation against itself and quietly accepts a
/// record whose canonical form differs from what the peer signed.
///
/// The validation is not a formality. Checking only the magic would leave a frame with a lying
/// length, or with bytes appended after its last field, looking perfectly well formed to anything
/// that reads one field and stops — which is exactly how a signed payload could be surrounded by
/// content nobody verified.
pub fn validated_body(frame: &[u8]) -> Result<&[u8]> {
    if frame.len() < crate::frame::HEADER_BYTES {
        return Err(CodecError::UnexpectedEof {
            needed: crate::frame::HEADER_BYTES,
            remaining: frame.len(),
        });
    }
    // Full header validation: magic, epoch, and the declared body length against what follows.
    crate::frame::NoderaFrame::decode(frame)?;
    let body = &frame[crate::frame::HEADER_BYTES..];
    // And the body's own grammar, so trailing or malformed fields are refused rather than skipped.
    crate::tlv::TlvBody::parse(body)?;
    Ok(body)
}

/// Borrow the opaque canonical payload out of a received consensus frame.
pub fn consensus_payload(frame: &[u8]) -> Result<&[u8]> {
    let body = validated_body(frame)?;
    crate::tlv::field_slice(body, CONSENSUS_PAYLOAD_FIELD)?.ok_or_else(|| {
        CodecError::Malformed("consensus frame carries no opaque payload".to_owned())
    })
}

// ---------------------------------------------------------------- discovery bodies

/// Encode a discovery message's TLV body.
pub fn encode_discovery_body(msg: &DiscoveryMessage) -> Vec<u8> {
    let mut w = TlvWriter::new();
    match msg {
        DiscoveryMessage::TrackerQuery(m) => {
            w.bytes(1, &m.genesis_hash);
        }
        DiscoveryMessage::TrackerResponse(m) => {
            w.bytes(1, &m.genesis_hash)
                .str(2, &m.world_name)
                .list(3, &m.peers, write_peer_entry)
                .list(4, &m.seeders, write_seeders)
                .u64(5, m.world_player_count)
                .u64(6, m.stored_chunks)
                .u32(7, m.reliability_bps)
                .u16(8, world_health_code(m.health))
                .u64(9, m.retention_deadline_epoch_millis);
        }
        DiscoveryMessage::InventoryAdvertisement(m) => {
            w.bytes(1, &m.genesis_hash)
                .uuid(2, m.holder.msb, m.holder.lsb)
                .list(3, &m.holdings, write_holding);
        }
        DiscoveryMessage::TrackerAnnounce(m) => {
            // Signed over its own canonical frame, so it crosses whole and opaque.
            w.bytes(1, &legacy_announce_frame(m));
        }
        DiscoveryMessage::TrackerAnnounceAck(m) => {
            w.bool(1, m.accepted)
                .u32(2, m.next_announce_after_seconds)
                .str(3, &m.reason);
        }
        DiscoveryMessage::TrackerCatalogQuery(m) => {
            w.u32(1, m.limit);
        }
        DiscoveryMessage::TrackerCatalogResponse(m) => {
            w.list(1, &m.worlds, write_catalog_entry);
        }
        DiscoveryMessage::TrackerRoutesQuery(m) => {
            w.bytes(1, &m.genesis_hash);
        }
        DiscoveryMessage::TrackerRoutesResponse(m) => {
            w.bytes(1, &m.genesis_hash).list(2, &m.peers, |e, p| {
                e.uuid(1, p.peer.msb, p.peer.lsb)
                    .list(2, &p.routes, |s, route| {
                        s.str(1, route);
                    });
            });
        }
    }
    w.finish()
}

/// The legacy canonical frame of a tracker announce — the bytes its signature covers, plus the
/// signature.
pub fn legacy_announce_frame(m: &TrackerAnnounce) -> Vec<u8> {
    let mut w = CanonicalWriter::new();
    m.write_signed_portion(&mut w);
    w.write_bytes(&m.signature);
    w.into_vec()
}

/// Decode a discovery message from its kind and TLV body, with the overlay describing how the
/// received field set differed from this build's.
pub fn decode_discovery_body_with_overlay(
    kind: u16,
    body: &[u8],
) -> Result<(DiscoveryMessage, crate::tlv::TlvOverlay)> {
    let b = TlvBody::parse(body)?;
    let msg = decode_discovery_fields(kind, &b)?;
    let overlay = b.overlay();
    Ok((msg, overlay))
}

/// Decode a discovery message from its kind and TLV body.
pub fn decode_discovery_body(kind: u16, body: &[u8]) -> Result<DiscoveryMessage> {
    decode_discovery_body_with_overlay(kind, body).map(|(m, _)| m)
}

fn decode_discovery_fields(kind: u16, b: &TlvBody) -> Result<DiscoveryMessage> {
    Ok(match kind {
        message_tags::TRACKER_QUERY => DiscoveryMessage::TrackerQuery(TrackerQuery {
            genesis_hash: b.bytes(1)?,
        }),
        message_tags::TRACKER_RESPONSE => DiscoveryMessage::TrackerResponse(TrackerResponse {
            genesis_hash: b.bytes(1)?,
            world_name: b.str(2)?,
            peers: b.list(3, read_peer_entry)?,
            seeders: b.list(4, read_seeders)?,
            world_player_count: b.u64(5, 0)?,
            stored_chunks: b.u64(6, 0)?,
            reliability_bps: b.u32(7, 0)?,
            health: world_health_from_code_at(b, 8, b.u16(8, 0)?),
            retention_deadline_epoch_millis: b.u64(9, 0)?,
        }),
        message_tags::INVENTORY_ADVERTISEMENT => {
            let (msb, lsb) = b.uuid(2)?;
            DiscoveryMessage::InventoryAdvertisement(InventoryAdvertisement {
                genesis_hash: b.bytes(1)?,
                holder: NodeId::new(msb, lsb),
                holdings: b.list(3, read_holding)?,
            })
        }
        message_tags::TRACKER_ANNOUNCE => {
            DiscoveryMessage::TrackerAnnounce(decode_legacy_announce(&b.bytes(1)?)?)
        }
        message_tags::TRACKER_ANNOUNCE_ACK => {
            DiscoveryMessage::TrackerAnnounceAck(TrackerAnnounceAck {
                accepted: b.bool(1, false)?,
                next_announce_after_seconds: b.u32(2, 0)?,
                reason: b.str(3)?,
            })
        }
        message_tags::TRACKER_CATALOG_QUERY => {
            DiscoveryMessage::TrackerCatalogQuery(TrackerCatalogQuery {
                limit: b.u32(1, 0)?,
            })
        }
        message_tags::TRACKER_CATALOG_RESPONSE => {
            DiscoveryMessage::TrackerCatalogResponse(TrackerCatalogResponse {
                worlds: b.list(1, read_catalog_entry)?,
            })
        }
        message_tags::TRACKER_ROUTES_QUERY => {
            DiscoveryMessage::TrackerRoutesQuery(TrackerRoutesQuery {
                genesis_hash: b.bytes(1)?,
            })
        }
        message_tags::TRACKER_ROUTES_RESPONSE => {
            DiscoveryMessage::TrackerRoutesResponse(TrackerRoutesResponse {
                genesis_hash: b.bytes(1)?,
                peers: b.list(2, |e| {
                    let (msb, lsb) = e.uuid(1)?;
                    Ok(PeerRoutes {
                        peer: NodeId::new(msb, lsb),
                        routes: e.list(2, |s| s.str(1))?,
                    })
                })?,
            })
        }
        other => return Err(CodecError::UnknownTag(other)),
    })
}

/// Parse the opaque legacy announce frame carried inside kind 33.
pub fn decode_legacy_announce(raw: &[u8]) -> Result<TrackerAnnounce> {
    let mut r = CanonicalReader::new(raw);
    let tag = r.read_u16()?;
    if tag != message_tags::TRACKER_ANNOUNCE {
        return Err(CodecError::UnexpectedTag {
            expected: message_tags::TRACKER_ANNOUNCE,
            actual: tag,
        });
    }
    let version = r.read_u16()?;
    if version != crate::ENCODING_VERSION {
        return Err(CodecError::UnsupportedVersion { tag, version });
    }
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
    // 0 is the "unknown" sentinel; n+1 is a real count of n.
    let world_player_count = r.read_u64()? as i64 - 1;
    let announce_epoch_millis = r.read_u64()?;
    let signature = r.read_bytes_vec()?;
    r.expect_end()?;
    Ok(TrackerAnnounce {
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
        world_player_count,
        announce_epoch_millis,
        signature,
    })
}

// ---------------------------------------------------------------- rendezvous bodies

/// Encode a rendezvous message's TLV body.
pub fn encode_rendezvous_body(msg: &RendezvousMessage) -> Vec<u8> {
    let mut w = TlvWriter::new();
    match msg {
        RendezvousMessage::Register(m) => {
            w.nested(1, |n| write_signed_record(n, &m.signed));
        }
        RendezvousMessage::Discover(m) => {
            w.uuid(1, m.network_id.msb, m.network_id.lsb)
                .bytes(2, &m.genesis_hash)
                .u32(3, m.cursor)
                .u32(4, m.limit);
        }
        RendezvousMessage::Peers(m) => {
            w.u32(1, m.next_cursor)
                .list(2, &m.records, write_signed_record);
        }
        RendezvousMessage::Reserve(m) => {
            w.uuid(1, m.network_id.msb, m.network_id.lsb)
                .bytes(2, &m.genesis_hash)
                .uuid(3, m.peer.msb, m.peer.lsb);
        }
        RendezvousMessage::Reservation(m) => {
            w.bool(1, m.accepted)
                .str(2, &m.relay_route)
                .u64(3, m.expires_at_epoch_millis)
                .u64(4, m.max_bytes)
                .u64(5, m.max_duration_millis)
                .bytes(6, &m.proof)
                .str(7, &m.reason);
        }
        RendezvousMessage::Connect(m) => {
            w.uuid(1, m.network_id.msb, m.network_id.lsb)
                .bytes(2, &m.genesis_hash)
                .uuid(3, m.source.msb, m.source.lsb)
                .uuid(4, m.target.msb, m.target.lsb);
        }
        RendezvousMessage::Incoming(m) => {
            w.uuid(1, m.network_id.msb, m.network_id.lsb)
                .bytes(2, &m.genesis_hash)
                .uuid(3, m.source.msb, m.source.lsb)
                .uuid(4, m.target.msb, m.target.lsb)
                .bytes(5, &m.proof);
        }
        RendezvousMessage::PunchSync(m) => {
            w.uuid(1, m.network_id.msb, m.network_id.lsb)
                .bytes(2, &m.genesis_hash)
                .uuid(3, m.source.msb, m.source.lsb)
                .uuid(4, m.target.msb, m.target.lsb)
                .list(5, &m.observed_candidates, write_candidate)
                .u64(6, m.go_signal_epoch_millis);
        }
        RendezvousMessage::ObservedAddress(m) => {
            w.uuid(1, m.peer.msb, m.peer.lsb).str(2, &m.observed_route);
        }
    }
    w.finish()
}

fn network_id(b: &TlvBody, id: u16) -> Result<NetworkId> {
    let (msb, lsb) = b.uuid(id)?;
    Ok(NetworkId::new(msb, lsb))
}

fn node_id(b: &TlvBody, id: u16) -> Result<NodeId> {
    let (msb, lsb) = b.uuid(id)?;
    Ok(NodeId::new(msb, lsb))
}

/// Decode a rendezvous message with its overlay.
pub fn decode_rendezvous_body_with_overlay(
    kind: u16,
    body: &[u8],
) -> Result<(RendezvousMessage, crate::tlv::TlvOverlay)> {
    let b = TlvBody::parse(body)?;
    let msg = decode_rendezvous_fields(kind, &b)?;
    let overlay = b.overlay();
    Ok((msg, overlay))
}

/// Decode a rendezvous message from its kind and TLV body.
pub fn decode_rendezvous_body(kind: u16, body: &[u8]) -> Result<RendezvousMessage> {
    decode_rendezvous_body_with_overlay(kind, body).map(|(m, _)| m)
}

fn decode_rendezvous_fields(kind: u16, b: &TlvBody) -> Result<RendezvousMessage> {
    Ok(match kind {
        message_tags::RENDEZVOUS_REGISTER => RendezvousMessage::Register(RendezvousRegister {
            signed: {
                let inner = b.nested(1)?;
                let signed = read_signed_record(&inner)?;
                b.seal_nested(1, &inner);
                signed
            },
        }),
        message_tags::RENDEZVOUS_DISCOVER => RendezvousMessage::Discover(RendezvousDiscover {
            network_id: network_id(b, 1)?,
            genesis_hash: b.bytes(2)?,
            cursor: b.u32(3, 0)?,
            limit: b.u32(4, 0)?,
        }),
        message_tags::RENDEZVOUS_PEERS => RendezvousMessage::Peers(RendezvousPeers {
            next_cursor: b.u32(1, 0)?,
            records: b.list(2, read_signed_record)?,
        }),
        message_tags::RELAY_RESERVE => RendezvousMessage::Reserve(RelayReserve {
            network_id: network_id(b, 1)?,
            genesis_hash: b.bytes(2)?,
            peer: node_id(b, 3)?,
        }),
        message_tags::RELAY_RESERVATION => RendezvousMessage::Reservation(RelayReservation {
            accepted: b.bool(1, false)?,
            relay_route: b.str(2)?,
            expires_at_epoch_millis: b.u64(3, 0)?,
            max_bytes: b.u64(4, 0)?,
            max_duration_millis: b.u64(5, 0)?,
            proof: b.bytes(6)?,
            reason: b.str(7)?,
        }),
        message_tags::RELAY_CONNECT => RendezvousMessage::Connect(RelayConnect {
            network_id: network_id(b, 1)?,
            genesis_hash: b.bytes(2)?,
            source: node_id(b, 3)?,
            target: node_id(b, 4)?,
        }),
        message_tags::RELAY_INCOMING => RendezvousMessage::Incoming(RelayIncoming {
            network_id: network_id(b, 1)?,
            genesis_hash: b.bytes(2)?,
            source: node_id(b, 3)?,
            target: node_id(b, 4)?,
            proof: b.bytes(5)?,
        }),
        message_tags::PUNCH_SYNC => RendezvousMessage::PunchSync(PunchSync {
            network_id: network_id(b, 1)?,
            genesis_hash: b.bytes(2)?,
            source: node_id(b, 3)?,
            target: node_id(b, 4)?,
            observed_candidates: b.list(5, read_candidate)?,
            go_signal_epoch_millis: b.u64(6, 0)?,
        }),
        message_tags::OBSERVED_ADDRESS => RendezvousMessage::ObservedAddress(ObservedAddress {
            peer: node_id(b, 1)?,
            observed_route: b.str(2)?,
        }),
        other => return Err(CodecError::UnknownTag(other)),
    })
}

// ---------------------------------------------------------------- service bodies

/// Encode a service-directory message's TLV body.
pub fn encode_service_body(msg: &ServiceMessage) -> Vec<u8> {
    let mut w = TlvWriter::new();
    match msg {
        ServiceMessage::Announce(m) => {
            w.bytes(1, &canonical(|c| m.record.encode(c)))
                .bytes(2, &m.signature);
        }
        ServiceMessage::AnnounceAck(m) => {
            w.bool(1, m.accepted)
                .u32(2, m.next_announce_after_seconds)
                .str(3, &m.reason)
                .list(4, &m.directory, write_directory_entry);
        }
        ServiceMessage::DirectoryQuery(m) => {
            w.u16(1, service_kind_code(m.kind))
                .uuid(2, m.network_id.msb, m.network_id.lsb)
                .u32(3, m.limit);
        }
        ServiceMessage::DirectoryResponse(m) => {
            w.list(1, &m.entries, write_directory_entry);
        }
        ServiceMessage::ScoreReport(m) => {
            w.bytes(1, &legacy_score_report_frame(m));
        }
        ServiceMessage::DrainNotice(m) => {
            w.bytes(1, &canonical(|c| m.record.encode(c)))
                .bytes(2, &m.signature)
                .list(3, &m.replacements, write_directory_entry)
                .str(4, &m.reason);
        }
    }
    w.finish()
}

/// The legacy canonical frame of a score report — the bytes its signature covers, plus the
/// signature.
pub fn legacy_score_report_frame(m: &ServiceScoreReport) -> Vec<u8> {
    let mut w = CanonicalWriter::new();
    m.write_signed_portion(&mut w);
    w.write_bytes(&m.signature);
    w.into_vec()
}

/// Parse the opaque legacy score-report frame carried inside kind 71.
pub fn decode_legacy_score_report(raw: &[u8]) -> Result<ServiceScoreReport> {
    let mut r = CanonicalReader::new(raw);
    let tag = r.read_u16()?;
    if tag != message_tags::SERVICE_SCORE_REPORT {
        return Err(CodecError::UnexpectedTag {
            expected: message_tags::SERVICE_SCORE_REPORT,
            actual: tag,
        });
    }
    let version = r.read_u16()?;
    if version != crate::ENCODING_VERSION {
        return Err(CodecError::UnsupportedVersion { tag, version });
    }
    let report = ServiceScoreReport {
        reporter: NodeId::decode(&mut r)?,
        public_key: r.read_bytes_vec()?,
        network_id: NetworkId::decode(&mut r)?,
        observations: r.read_list(crate::service::ServiceObservation::decode)?,
        report_epoch_millis: r.read_u64()?,
        signature: r.read_bytes_vec()?,
    };
    r.expect_end()?;
    Ok(report)
}

/// Decode a service-directory message with its overlay.
pub fn decode_service_body_with_overlay(
    kind: u16,
    body: &[u8],
) -> Result<(ServiceMessage, crate::tlv::TlvOverlay)> {
    let b = TlvBody::parse(body)?;
    let msg = decode_service_fields(kind, &b)?;
    let overlay = b.overlay();
    Ok((msg, overlay))
}

/// Decode a service-directory message from its kind and TLV body.
pub fn decode_service_body(kind: u16, body: &[u8]) -> Result<ServiceMessage> {
    decode_service_body_with_overlay(kind, body).map(|(m, _)| m)
}

fn decode_service_fields(kind: u16, b: &TlvBody) -> Result<ServiceMessage> {
    Ok(match kind {
        message_tags::SERVICE_ANNOUNCE => ServiceMessage::Announce(ServiceAnnounce {
            record: from_canonical(&b.bytes(1)?, ServiceRecord::decode)?,
            signature: b.bytes(2)?,
        }),
        message_tags::SERVICE_ANNOUNCE_ACK => ServiceMessage::AnnounceAck(ServiceAnnounceAck {
            accepted: b.bool(1, false)?,
            next_announce_after_seconds: b.u32(2, 0)?,
            reason: b.str(3)?,
            directory: b.list(4, read_directory_entry)?,
        }),
        message_tags::SERVICE_DIRECTORY_QUERY => {
            ServiceMessage::DirectoryQuery(ServiceDirectoryQuery {
                kind: service_kind_from_code(b.u16(1, 0)?)?,
                network_id: network_id(b, 2)?,
                limit: b.u32(3, 0)?,
            })
        }
        message_tags::SERVICE_DIRECTORY_RESPONSE => {
            ServiceMessage::DirectoryResponse(ServiceDirectoryResponse {
                entries: b.list(1, read_directory_entry)?,
            })
        }
        message_tags::SERVICE_SCORE_REPORT => {
            ServiceMessage::ScoreReport(decode_legacy_score_report(&b.bytes(1)?)?)
        }
        message_tags::SERVICE_DRAIN_NOTICE => ServiceMessage::DrainNotice(ServiceDrainNotice {
            record: from_canonical(&b.bytes(1)?, ServiceRecord::decode)?,
            signature: b.bytes(2)?,
            replacements: b.list(3, read_directory_entry)?,
            reason: b.str(4)?,
        }),
        other => return Err(CodecError::UnknownTag(other)),
    })
}
