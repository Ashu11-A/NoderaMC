//! The **service-directory** family: how peers learn which rendezvous points exist, how good each
//! one is, and when one is about to go away.
//!
//! Before this family, a peer's rendezvous list was a hand-written configuration string
//! (`NODERA_RENDEZVOUS_ENDPOINTS`) and a rendezvous restart was an outage for every peer that had
//! reserved there. Three messages fix that, and they all keep the trust boundary the services
//! already have: **a service directory is a hint, never authority.**
//!
//! * A service (rendezvous or tracker) self-announces a [`ServiceRecord`] to trackers — signed with
//!   its own service identity, exactly like a peer's `SignedPeerRecord`. The tracker verifies and
//!   stores; it cannot mint a record it did not receive.
//! * A peer asks a tracker for the directory, gets records **with their signatures**, and verifies
//!   each one itself. The same signature validates whether a record arrived in an announce, a
//!   directory answer, or a drain notice — the property that makes the tracker unable to lie about
//!   *who* a service is (`docs/rendezvous/REFERENCE.md`, mirrored here for services).
//! * A [`ServiceScore`] rides along, but its `composite_permille` is **derived**, not trusted:
//!   [`ServiceScore::composite`] is mirrored byte-for-byte in Java, so a peer recomputes the number
//!   from the components and ignores the field if they disagree. A lying tracker can hide services
//!   or invent unreachable ones — the same power it already had over worlds — and nothing more.
//!
//! Peers report what they measured ([`ServiceObservation`]: probe counts and RTT percentiles, never
//! an opinion) so the tracker aggregates raw counters instead of trusting one peer's arithmetic.

use crate::tags::{message_tags, type_tags};
use crate::types::{NetworkId, NodeId};
use crate::{CanonicalReader, CanonicalWriter, CodecError, Result, ENCODING_VERSION};

/// What kind of service a record describes (frozen ordinals).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum ServiceKind {
    /// A `nodera-rendezvous`: registration, discovery, hole-punch coordination, relay circuits.
    Rendezvous = 0,
    /// A `nodera-tracker`: world and peer discovery.
    Tracker = 1,
}

impl ServiceKind {
    /// Map a frozen ordinal to its kind.
    pub fn from_ordinal(ordinal: u8) -> Result<Self> {
        Ok(match ordinal {
            0 => Self::Rendezvous,
            1 => Self::Tracker,
            other => {
                return Err(CodecError::Malformed(format!(
                    "invalid ServiceKind ordinal {other}"
                )))
            }
        })
    }
}

/// Where a service is in its own lifecycle (frozen ordinals).
///
/// [`ServiceLifecycle::Draining`] is the load-bearing one: it is how a service that is about to
/// restart — for an update, or because an operator asked — tells the network *before* it stops
/// answering, so peers migrate on their own schedule instead of discovering the outage by failing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum ServiceLifecycle {
    /// Bound and warming up; it may refuse work.
    Starting = 0,
    /// Serving normally.
    Serving = 1,
    /// Finishing existing work, refusing new work, and about to stop at `drain_deadline`.
    Draining = 2,
    /// Stopped on purpose. A record in this state is a removal request, not an advertisement.
    Stopped = 3,
}

impl ServiceLifecycle {
    /// Map a frozen ordinal to its lifecycle state.
    pub fn from_ordinal(ordinal: u8) -> Result<Self> {
        Ok(match ordinal {
            0 => Self::Starting,
            1 => Self::Serving,
            2 => Self::Draining,
            3 => Self::Stopped,
            other => {
                return Err(CodecError::Malformed(format!(
                    "invalid ServiceLifecycle ordinal {other}"
                )))
            }
        })
    }

    /// Whether a peer should be routing new work to a service in this state.
    pub fn accepts_new_work(self) -> bool {
        matches!(self, Self::Serving)
    }
}

/// A service's canonical, Ed25519-signable self-description (type tag 115).
///
/// The signature a service produces covers exactly this value's [`ServiceRecord::encode`] output,
/// so a peer verifies the *same bytes* the service signed no matter which message carried the
/// record. Capacity numbers are self-reported claims — a service can flatter itself, which is why
/// they are only one input to a score whose dominant term is what peers actually measured.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceRecord {
    /// The service's own node identity (services have identities; they still hold no world keys).
    pub service: NodeId,
    /// The service's X.509 or raw Ed25519 public key.
    pub public_key: Vec<u8>,
    /// Rendezvous or tracker.
    pub kind: ServiceKind,
    /// Where the service is in its lifecycle.
    pub lifecycle: ServiceLifecycle,
    /// The network this service serves.
    pub network_id: NetworkId,
    /// Dial routes, in the service's own preference order (`host:port`, `tcp://…`, `udp://…`).
    pub routes: Vec<String>,
    /// The product version of the running binary (root `VERSION`), for update visibility.
    pub version: String,
    /// Registrations (rendezvous) or announced peers (tracker) currently held.
    pub active_sessions: u32,
    /// The ceiling the operator configured for `active_sessions`; `0` means "unstated".
    pub max_sessions: u32,
    /// Relay circuits currently bridged. Always `0` for a tracker.
    pub active_circuits: u32,
    /// The ceiling for `active_circuits`; `0` means "unstated".
    pub max_circuits: u32,
    /// Requests refused in the last reporting window, for any reason.
    pub rejected_last_window: u32,
    /// The service's wall-clock at issue time — a freshness bound only.
    pub issued_at_epoch_millis: u64,
    /// When the record self-expires unless refreshed.
    pub expires_at_epoch_millis: u64,
    /// When a draining service intends to stop (epoch millis); `0` unless `lifecycle` is
    /// [`ServiceLifecycle::Draining`].
    pub drain_deadline_epoch_millis: u64,
}

impl ServiceRecord {
    /// Encode `tag(115) + version + body` — the exact bytes the signature covers.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(type_tags::SERVICE_RECORD, ENCODING_VERSION);
        self.service.encode(w);
        w.write_bytes(&self.public_key);
        w.write_u8(self.kind as u8);
        w.write_u8(self.lifecycle as u8);
        self.network_id.encode(w);
        w.write_list(&self.routes, |ww, route| {
            ww.write_string(route);
        });
        w.write_string(&self.version);
        w.write_u32(self.active_sessions);
        w.write_u32(self.max_sessions);
        w.write_u32(self.active_circuits);
        w.write_u32(self.max_circuits);
        w.write_u32(self.rejected_last_window);
        w.write_u64(self.issued_at_epoch_millis);
        w.write_u64(self.expires_at_epoch_millis);
        w.write_u64(self.drain_deadline_epoch_millis);
    }

    /// The canonical signed bytes of this record.
    pub fn signed_bytes(&self) -> Vec<u8> {
        let mut w = CanonicalWriter::with_capacity(256);
        self.encode(&mut w);
        w.into_vec()
    }

    /// Decode the inverse of [`ServiceRecord::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        r.read_frame_header(type_tags::SERVICE_RECORD, ENCODING_VERSION)?;
        let service = NodeId::decode(r)?;
        let public_key = r.read_bytes_vec()?;
        let kind = ServiceKind::from_ordinal(r.read_u8()?)?;
        let lifecycle = ServiceLifecycle::from_ordinal(r.read_u8()?)?;
        let network_id = NetworkId::decode(r)?;
        let routes = r.read_list(|rr| rr.read_string())?;
        let version = r.read_string()?;
        let active_sessions = r.read_u32()?;
        let max_sessions = r.read_u32()?;
        let active_circuits = r.read_u32()?;
        let max_circuits = r.read_u32()?;
        let rejected_last_window = r.read_u32()?;
        let issued_at_epoch_millis = r.read_u64()?;
        let expires_at_epoch_millis = r.read_u64()?;
        let drain_deadline_epoch_millis = r.read_u64()?;
        Ok(Self {
            service,
            public_key,
            kind,
            lifecycle,
            network_id,
            routes,
            version,
            active_sessions,
            max_sessions,
            active_circuits,
            max_circuits,
            rejected_last_window,
            issued_at_epoch_millis,
            expires_at_epoch_millis,
            drain_deadline_epoch_millis,
        })
    }

    /// Free capacity in permille, from the self-reported numbers.
    ///
    /// An unstated ceiling (`0`) reads as *fully free* rather than *full*: a service that declines
    /// to publish a limit must not be penalised into never being selected, because that would make
    /// silence the winning strategy for every operator who dislikes the scoring.
    pub fn capacity_permille(&self) -> u32 {
        let session_headroom = headroom_permille(self.active_sessions, self.max_sessions);
        let circuit_headroom = headroom_permille(self.active_circuits, self.max_circuits);
        session_headroom.min(circuit_headroom)
    }
}

/// Free headroom of `used` against `ceiling`, in permille; an unstated ceiling is fully free.
fn headroom_permille(used: u32, ceiling: u32) -> u32 {
    if ceiling == 0 {
        return 1_000;
    }
    if used >= ceiling {
        return 0;
    }
    let free = u64::from(ceiling - used);
    // Integer-only: no float may enter a value two implementations must agree on.
    ((free * 1_000) / u64::from(ceiling)) as u32
}

/// The relative weights of the four score components, in the order availability, latency,
/// capacity, freshness. They sum to 100 so the composite is directly a permille of the ideal.
pub const SCORE_WEIGHTS: [u32; 4] = [40, 30, 20, 10];

/// The RTT at which the latency term reaches zero (millis).
///
/// A service 1.5 s away scores nothing for latency but can still win on availability — which is the
/// intended ordering: a slow rendezvous that is always up beats a fast one that is usually down,
/// because registration and discovery are latency-tolerant (`docs/rendezvous/REFERENCE.md`).
pub const LATENCY_CEILING_MILLIS: u32 = 1_500;

/// A tracker's aggregate opinion of one service (type tag 116).
///
/// Every field is a permille or a millisecond count so the two implementations cannot disagree
/// through floating point. `composite_permille` is **derived** — see [`ServiceScore::composite`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ServiceScore {
    /// Successful probes over total probes across reporters, in permille.
    pub availability_permille: u32,
    /// Median reported round-trip time (millis).
    pub rtt_p50_millis: u32,
    /// 95th-percentile reported round-trip time (millis).
    pub rtt_p95_millis: u32,
    /// Free capacity from the service's own record, in permille.
    pub capacity_permille: u32,
    /// How continuously the tracker itself has seen this service's heartbeats, in permille.
    pub freshness_permille: u32,
    /// How many distinct peers contributed observations.
    pub reporter_count: u32,
    /// The weighted composite in permille — a hint a peer recomputes rather than trusts.
    pub composite_permille: u32,
}

impl ServiceScore {
    /// The weighted composite of the four components, in permille (`0..=1000`).
    ///
    /// Integer-only and identical in Java (`ServiceScore.composite`), because a peer recomputes it
    /// from the components and drops the transmitted `composite_permille` when the two disagree.
    /// The latency term uses p95, not p50: a rendezvous whose tail is bad is bad, and a median
    /// hides exactly the stalls that make a peer sit on an unusable path.
    pub fn composite(
        availability_permille: u32,
        rtt_p95_millis: u32,
        capacity_permille: u32,
        freshness_permille: u32,
    ) -> u32 {
        let latency = latency_permille(rtt_p95_millis);
        let terms = [
            availability_permille.min(1_000),
            latency,
            capacity_permille.min(1_000),
            freshness_permille.min(1_000),
        ];
        let mut total: u64 = 0;
        for (term, weight) in terms.iter().zip(SCORE_WEIGHTS.iter()) {
            total += u64::from(*term) * u64::from(*weight);
        }
        let divisor: u64 = SCORE_WEIGHTS.iter().map(|w| u64::from(*w)).sum();
        (total / divisor) as u32
    }

    /// This score's composite, recomputed from its own components.
    pub fn recomputed_composite(&self) -> u32 {
        Self::composite(
            self.availability_permille,
            self.rtt_p95_millis,
            self.capacity_permille,
            self.freshness_permille,
        )
    }

    /// Fill `composite_permille` from the other fields.
    pub fn with_composite(mut self) -> Self {
        self.composite_permille = self.recomputed_composite();
        self
    }

    /// Encode `tag(116) + version + body`.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(type_tags::SERVICE_SCORE, ENCODING_VERSION);
        w.write_u32(self.availability_permille);
        w.write_u32(self.rtt_p50_millis);
        w.write_u32(self.rtt_p95_millis);
        w.write_u32(self.capacity_permille);
        w.write_u32(self.freshness_permille);
        w.write_u32(self.reporter_count);
        w.write_u32(self.composite_permille);
    }

    /// Decode the inverse of [`ServiceScore::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        r.read_frame_header(type_tags::SERVICE_SCORE, ENCODING_VERSION)?;
        Ok(Self {
            availability_permille: r.read_u32()?,
            rtt_p50_millis: r.read_u32()?,
            rtt_p95_millis: r.read_u32()?,
            capacity_permille: r.read_u32()?,
            freshness_permille: r.read_u32()?,
            reporter_count: r.read_u32()?,
            composite_permille: r.read_u32()?,
        })
    }
}

/// The latency term of the score: `0` when unmeasured, then linear from just under `1000` down to `0`
/// at [`LATENCY_CEILING_MILLIS`].
///
/// **Zero means unmeasured, not instant.** A `ServiceScore` with no reporters carries `rtt_p95 == 0`,
/// and reading that as a perfect round trip would score every unprobed service as the fastest thing in
/// the directory — so a service nobody has measured would outrank one measured as merely good. A real
/// sub-millisecond RTT loses a thousandth of the term to this, which is not a number anybody selects on.
pub fn latency_permille(rtt_millis: u32) -> u32 {
    if rtt_millis == 0 || rtt_millis >= LATENCY_CEILING_MILLIS {
        return 0;
    }
    let remaining = u64::from(LATENCY_CEILING_MILLIS - rtt_millis);
    ((remaining * 1_000) / u64::from(LATENCY_CEILING_MILLIS)) as u32
}

/// One peer's measurement of one service over a window (type tag 117).
///
/// Counters and percentiles only — deliberately not a verdict. A peer that reported "this service
/// is bad" would be asking the tracker to trust its judgement; a peer that reports "I probed it 20
/// times and 3 answered" lets the tracker aggregate and lets other peers' numbers outvote a liar.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ServiceObservation {
    /// The service this observation is about.
    pub service: NodeId,
    /// Which kind of service it is (so a report needs no directory lookup to be usable).
    pub kind: ServiceKind,
    /// Probes attempted in the window.
    pub probes: u32,
    /// Probes that got a well-formed answer.
    pub successes: u32,
    /// Median round-trip time over the successful probes (millis).
    pub rtt_p50_millis: u32,
    /// 95th-percentile round-trip time over the successful probes (millis).
    pub rtt_p95_millis: u32,
    /// When the window closed (epoch millis) — a freshness bound.
    pub observed_at_epoch_millis: u64,
}

impl ServiceObservation {
    /// Encode `tag(117) + version + body`.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(type_tags::SERVICE_OBSERVATION, ENCODING_VERSION);
        self.service.encode(w);
        w.write_u8(self.kind as u8);
        w.write_u32(self.probes);
        w.write_u32(self.successes);
        w.write_u32(self.rtt_p50_millis);
        w.write_u32(self.rtt_p95_millis);
        w.write_u64(self.observed_at_epoch_millis);
    }

    /// Decode the inverse of [`ServiceObservation::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        r.read_frame_header(type_tags::SERVICE_OBSERVATION, ENCODING_VERSION)?;
        Ok(Self {
            service: NodeId::decode(r)?,
            kind: ServiceKind::from_ordinal(r.read_u8()?)?,
            probes: r.read_u32()?,
            successes: r.read_u32()?,
            rtt_p50_millis: r.read_u32()?,
            rtt_p95_millis: r.read_u32()?,
            observed_at_epoch_millis: r.read_u64()?,
        })
    }

    /// Availability in permille over this window; a window with no probes is not evidence, so it
    /// reads as `0` successes of `0` probes and contributes nothing.
    pub fn availability_permille(&self) -> u32 {
        if self.probes == 0 {
            return 0;
        }
        let successes = u64::from(self.successes.min(self.probes));
        ((successes * 1_000) / u64::from(self.probes)) as u32
    }
}

/// One directory row: a service's signed record plus the answering tracker's score (type tag 118).
///
/// The signature travels with the record so the row is verifiable on its own. A peer that trusted
/// the row because it came from a tracker would have made the tracker authority over which
/// rendezvous its traffic goes through — precisely the thing the trust model forbids.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceDirectoryEntry {
    /// The service's canonical record.
    pub record: ServiceRecord,
    /// Ed25519 over `record.signed_bytes()`.
    pub signature: Vec<u8>,
    /// The answering tracker's aggregate score for this service.
    pub score: ServiceScore,
}

impl ServiceDirectoryEntry {
    /// Encode `tag(118) + version + body`.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(type_tags::SERVICE_DIRECTORY_ENTRY, ENCODING_VERSION);
        self.record.encode(w);
        w.write_bytes(&self.signature);
        self.score.encode(w);
    }

    /// Decode the inverse of [`ServiceDirectoryEntry::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        r.read_frame_header(type_tags::SERVICE_DIRECTORY_ENTRY, ENCODING_VERSION)?;
        Ok(Self {
            record: ServiceRecord::decode(r)?,
            signature: r.read_bytes_vec()?,
            score: ServiceScore::decode(r)?,
        })
    }
}

/// A service's signed self-announcement to a tracker (tag 67).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceAnnounce {
    /// The canonical record.
    pub record: ServiceRecord,
    /// Ed25519 over `record.signed_bytes()`.
    pub signature: Vec<u8>,
}

/// A tracker's answer to a service announcement (tag 68).
///
/// The `directory` field is what makes a seamless handover possible: a draining rendezvous learns
/// its own replacements in the same round trip it uses to say it is draining, so it can name them
/// to the peers it is about to disconnect instead of leaving them to rediscover blind.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceAnnounceAck {
    /// Whether the record was admitted.
    pub accepted: bool,
    /// How long the service should wait before announcing again (paces the tracker's own load).
    pub next_announce_after_seconds: u32,
    /// Empty when accepted; otherwise a short stable rejection code.
    pub reason: String,
    /// Sibling services of the same kind, best first — the announcer's own failover set.
    pub directory: Vec<ServiceDirectoryEntry>,
}

/// A peer asking a tracker which services it knows (tag 69).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceDirectoryQuery {
    /// Which kind of service to list.
    pub kind: ServiceKind,
    /// The network the caller serves.
    pub network_id: NetworkId,
    /// Maximum rows to return; `0` lets the tracker choose its page size.
    pub limit: u32,
}

/// A tracker's directory answer, best score first (tag 70).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceDirectoryResponse {
    /// The rows, sorted by descending `score.composite_permille` then ascending service id.
    pub entries: Vec<ServiceDirectoryEntry>,
}

/// A peer's signed report of what it measured (tag 71).
///
/// Signed like a tracker announce — over the frame minus the trailing signature — so the tracker
/// can attribute the report to one identity and cap how much one identity may move a score.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceScoreReport {
    /// The reporting peer.
    pub reporter: NodeId,
    /// The reporter's public key.
    pub public_key: Vec<u8>,
    /// The network the measurements were taken in.
    pub network_id: NetworkId,
    /// One row per measured service.
    pub observations: Vec<ServiceObservation>,
    /// The reporter's wall-clock at report time — a freshness bound.
    pub report_epoch_millis: u64,
    /// Ed25519 over the frame up to but excluding this field.
    pub signature: Vec<u8>,
}

impl ServiceScoreReport {
    /// Encode everything the signature covers: the frame minus the trailing signature field.
    ///
    /// Callers verifying a *received* report should prefer the received byte range
    /// ([`ServiceMessage::split_report_signature`]) — re-encoding a decoded value would verify this
    /// implementation against itself rather than against what the peer signed.
    pub fn signed_portion(&self) -> Vec<u8> {
        let mut w = CanonicalWriter::with_capacity(512);
        self.write_signed_portion(&mut w);
        w.into_vec()
    }

    pub fn write_signed_portion(&self, w: &mut CanonicalWriter) {
        w.write_frame_header(message_tags::SERVICE_SCORE_REPORT, ENCODING_VERSION);
        self.reporter.encode(w);
        w.write_bytes(&self.public_key);
        self.network_id.encode(w);
        w.write_list(&self.observations, |ww, o| o.encode(ww));
        w.write_u64(self.report_epoch_millis);
    }
}

/// A service telling peers directly that it is going away (tag 72).
///
/// Pushed down every control channel the service holds, *not* only published to a tracker: a peer
/// that reserved a relay slot has an open socket to the service, and that socket is the fastest and
/// most reliable way to reach exactly the peers who are about to be hurt. The tracker path is the
/// belt; this is the braces.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceDrainNotice {
    /// The draining service's record, with `lifecycle = Draining` and a `drain_deadline`.
    pub record: ServiceRecord,
    /// Ed25519 over `record.signed_bytes()` — so a peer cannot be evicted by a forged notice.
    pub signature: Vec<u8>,
    /// Where to go instead, best first, as the service last heard it from a tracker.
    pub replacements: Vec<ServiceDirectoryEntry>,
    /// A short stable code: `update`, `operator`, `shutdown`.
    pub reason: String,
}

impl ServiceDrainNotice {
    /// The service is restarting to install a newer release.
    pub const REASON_UPDATE: &'static str = "update";
    /// An operator asked the service to stop.
    pub const REASON_OPERATOR: &'static str = "operator";
    /// The process received a termination signal.
    pub const REASON_SHUTDOWN: &'static str = "shutdown";
}

/// Any service-directory frame this crate can decode.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ServiceMessage {
    /// Tag 67.
    Announce(ServiceAnnounce),
    /// Tag 68.
    AnnounceAck(ServiceAnnounceAck),
    /// Tag 69.
    DirectoryQuery(ServiceDirectoryQuery),
    /// Tag 70.
    DirectoryResponse(ServiceDirectoryResponse),
    /// Tag 71.
    ScoreReport(ServiceScoreReport),
    /// Tag 72.
    DrainNotice(ServiceDrainNotice),
}

impl ServiceMessage {
    /// The frozen wire tag of this message.
    pub fn tag(&self) -> u16 {
        match self {
            Self::Announce(_) => message_tags::SERVICE_ANNOUNCE,
            Self::AnnounceAck(_) => message_tags::SERVICE_ANNOUNCE_ACK,
            Self::DirectoryQuery(_) => message_tags::SERVICE_DIRECTORY_QUERY,
            Self::DirectoryResponse(_) => message_tags::SERVICE_DIRECTORY_RESPONSE,
            Self::ScoreReport(_) => message_tags::SERVICE_SCORE_REPORT,
            Self::DrainNotice(_) => message_tags::SERVICE_DRAIN_NOTICE,
        }
    }

    /// Encode a complete `NDR2` frame carrying this message as an unsolicited event.
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
            body: crate::wire::encode_service_body(self),
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
        crate::wire::decode_service_body_with_overlay(parsed.kind, &parsed.body)
    }

    /// Encode a frame, re-emitting fields this build did not understand when it was decoded.
    pub fn encode_with_overlay(&self, overlay: &crate::tlv::TlvOverlay) -> Result<Vec<u8>> {
        Ok(crate::frame::NoderaFrame {
            epoch: crate::frame::WIRE_EPOCH,
            kind: self.tag(),
            flags: crate::frame::flags::EVENT,
            correlation_id: 0,
            body: overlay.apply_to(&crate::wire::encode_service_body(self))?,
        }
        .encode())
    }

    /// The exact byte range of a received score-report frame that its signature covers.
    ///
    /// Verification uses the bytes as they arrived, never a re-encoding of the decoded value — the
    /// same rule `split_announce_signature` follows, for the same reason. The report crosses the
    /// tolerant plane whole and opaque, so the span lives inside field 1 of the TLV body.
    pub fn split_report_signature(frame: &[u8]) -> Result<(&[u8], &[u8])> {
        let kind = crate::frame::NoderaFrame::peek_kind(frame)?;
        if kind != message_tags::SERVICE_SCORE_REPORT {
            return Err(CodecError::UnknownTag(kind));
        }
        let body = crate::wire::validated_body(frame)?;
        let opaque = crate::tlv::field_slice(body, 1)?.ok_or_else(|| {
            CodecError::Malformed("score report carries no opaque payload".to_owned())
        })?;
        let report = crate::wire::decode_legacy_score_report(opaque)?;
        let trailer = report
            .signature
            .len()
            .checked_add(4)
            .ok_or_else(|| CodecError::Malformed("signature length overflow".to_owned()))?;
        let split = opaque.len().checked_sub(trailer).ok_or_else(|| {
            CodecError::Malformed("score report shorter than its signature".to_owned())
        })?;
        Ok((&opaque[..split], &opaque[split + 4..]))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record() -> ServiceRecord {
        ServiceRecord {
            service: NodeId::new(9, 10),
            public_key: vec![0x42; 44],
            kind: ServiceKind::Rendezvous,
            lifecycle: ServiceLifecycle::Serving,
            network_id: NetworkId::new(1, 2),
            routes: vec![
                "rdv.example:25601".to_owned(),
                "tcp://198.51.100.9:25601".to_owned(),
            ],
            version: "0.1.0".to_owned(),
            active_sessions: 120,
            max_sessions: 5_000,
            active_circuits: 4,
            max_circuits: 64,
            rejected_last_window: 3,
            issued_at_epoch_millis: 1_700_000_000_000,
            expires_at_epoch_millis: 1_700_000_300_000,
            drain_deadline_epoch_millis: 0,
        }
    }

    fn score() -> ServiceScore {
        ServiceScore {
            availability_permille: 980,
            rtt_p50_millis: 35,
            rtt_p95_millis: 90,
            capacity_permille: 900,
            freshness_permille: 1_000,
            reporter_count: 7,
            composite_permille: 0,
        }
        .with_composite()
    }

    fn entry() -> ServiceDirectoryEntry {
        ServiceDirectoryEntry {
            record: record(),
            signature: vec![0x77; 64],
            score: score(),
        }
    }

    fn samples() -> Vec<ServiceMessage> {
        vec![
            ServiceMessage::Announce(ServiceAnnounce {
                record: record(),
                signature: vec![0x77; 64],
            }),
            ServiceMessage::AnnounceAck(ServiceAnnounceAck {
                accepted: true,
                next_announce_after_seconds: 120,
                reason: String::new(),
                directory: vec![entry()],
            }),
            ServiceMessage::DirectoryQuery(ServiceDirectoryQuery {
                kind: ServiceKind::Rendezvous,
                network_id: NetworkId::new(1, 2),
                limit: 8,
            }),
            ServiceMessage::DirectoryResponse(ServiceDirectoryResponse {
                entries: vec![entry()],
            }),
            ServiceMessage::ScoreReport(ServiceScoreReport {
                reporter: NodeId::new(3, 4),
                public_key: vec![0x66; 44],
                network_id: NetworkId::new(1, 2),
                observations: vec![ServiceObservation {
                    service: NodeId::new(9, 10),
                    kind: ServiceKind::Rendezvous,
                    probes: 20,
                    successes: 19,
                    rtt_p50_millis: 35,
                    rtt_p95_millis: 90,
                    observed_at_epoch_millis: 1_700_000_060_000,
                }],
                report_epoch_millis: 1_700_000_060_000,
                signature: vec![0x88; 64],
            }),
            ServiceMessage::DrainNotice(ServiceDrainNotice {
                record: ServiceRecord {
                    lifecycle: ServiceLifecycle::Draining,
                    drain_deadline_epoch_millis: 1_700_000_030_000,
                    ..record()
                },
                signature: vec![0x77; 64],
                replacements: vec![entry()],
                reason: "update".to_owned(),
            }),
        ]
    }

    #[test]
    fn every_message_round_trips_byte_exactly() {
        for msg in samples() {
            let bytes = msg.encode();
            let decoded = ServiceMessage::decode(&bytes).unwrap();
            assert_eq!(decoded, msg, "decode mismatch for tag {}", msg.tag());
            assert_eq!(decoded.encode(), bytes, "re-encode mismatch");
        }
    }

    #[test]
    fn trailing_bytes_reject_the_frame() {
        // The frame declares its own body length, so an extra byte is caught at the header
        // rather than after a body has already been interpreted.
        let mut bytes = samples()[0].encode();
        bytes.push(0);
        assert!(ServiceMessage::decode(&bytes).is_err());
    }

    #[test]
    fn an_unknown_kind_is_named_rather_than_guessed_at() {
        let frame = crate::frame::NoderaFrame::event(9_999, Vec::new()).encode();
        assert!(matches!(
            ServiceMessage::decode(&frame),
            Err(CodecError::UnknownTag(9_999))
        ));
    }

    #[test]
    fn a_records_signature_covers_the_same_bytes_in_every_carrier() {
        // The whole point of signing the nested record rather than each frame: a peer that verified
        // a record in a directory answer has verified the record the service announced, and a
        // tracker cannot re-word a record it is merely carrying.
        let announced = ServiceMessage::Announce(ServiceAnnounce {
            record: record(),
            signature: vec![0xAB; 64],
        })
        .encode();
        let listed = ServiceMessage::DirectoryResponse(ServiceDirectoryResponse {
            entries: vec![ServiceDirectoryEntry {
                record: record(),
                signature: vec![0xAB; 64],
                score: score(),
            }],
        })
        .encode();
        let drained = ServiceMessage::DrainNotice(ServiceDrainNotice {
            record: record(),
            signature: vec![0xAB; 64],
            replacements: vec![],
            reason: "update".to_owned(),
        })
        .encode();

        let from_announce = match ServiceMessage::decode(&announced).unwrap() {
            ServiceMessage::Announce(m) => m.record.signed_bytes(),
            _ => unreachable!(),
        };
        let from_directory = match ServiceMessage::decode(&listed).unwrap() {
            ServiceMessage::DirectoryResponse(m) => m.entries[0].record.signed_bytes(),
            _ => unreachable!(),
        };
        let from_drain = match ServiceMessage::decode(&drained).unwrap() {
            ServiceMessage::DrainNotice(m) => m.record.signed_bytes(),
            _ => unreachable!(),
        };
        assert_eq!(from_announce, from_directory);
        assert_eq!(from_announce, from_drain);
        assert_eq!(from_announce, record().signed_bytes());
    }

    #[test]
    fn the_report_signature_range_is_the_received_bytes() {
        let msg = ServiceMessage::ScoreReport(ServiceScoreReport {
            reporter: NodeId::new(3, 4),
            public_key: vec![0x66; 44],
            network_id: NetworkId::new(1, 2),
            observations: vec![],
            report_epoch_millis: 7,
            signature: vec![0x99; 64],
        });
        let frame = msg.encode();
        let (signed, signature) = ServiceMessage::split_report_signature(&frame).unwrap();
        assert_eq!(signature, &[0x99; 64]);
        let ServiceMessage::ScoreReport(report) = msg else {
            unreachable!()
        };
        assert_eq!(signed, report.signed_portion().as_slice());
    }

    #[test]
    fn an_unstated_ceiling_reads_as_free_not_full() {
        // Penalising silence would make "publish no limits" the winning move for every operator.
        let unstated = ServiceRecord {
            max_sessions: 0,
            max_circuits: 0,
            active_sessions: 9_999,
            ..record()
        };
        assert_eq!(unstated.capacity_permille(), 1_000);
    }

    #[test]
    fn capacity_takes_the_tighter_of_the_two_ceilings() {
        let tight = ServiceRecord {
            active_sessions: 100,
            max_sessions: 1_000, // 900 permille free
            active_circuits: 60,
            max_circuits: 64, // 62 permille free — this one binds
            ..record()
        };
        assert_eq!(tight.capacity_permille(), 62);
        let full = ServiceRecord {
            active_circuits: 64,
            max_circuits: 64,
            ..record()
        };
        assert_eq!(full.capacity_permille(), 0);
    }

    #[test]
    fn the_latency_term_is_linear_to_the_ceiling_and_then_zero() {
        // Zero is the unmeasured sentinel, not an instant round trip: otherwise a service nobody has
        // probed would score as the fastest thing in the directory.
        assert_eq!(latency_permille(0), 0);
        assert_eq!(latency_permille(1), 999);
        assert_eq!(latency_permille(LATENCY_CEILING_MILLIS / 2), 500);
        assert_eq!(latency_permille(LATENCY_CEILING_MILLIS), 0);
        assert_eq!(latency_permille(LATENCY_CEILING_MILLIS * 10), 0);
    }

    #[test]
    fn availability_outweighs_latency_in_the_composite() {
        // The ordering this scoring exists to produce: registration and discovery are
        // latency-tolerant, so a slow-but-up rendezvous must beat a fast-but-flaky one.
        let slow_but_up = ServiceScore::composite(1_000, 400, 1_000, 1_000);
        let fast_but_flaky = ServiceScore::composite(500, 10, 1_000, 1_000);
        assert!(
            slow_but_up > fast_but_flaky,
            "slow_but_up {slow_but_up} should beat fast_but_flaky {fast_but_flaky}"
        );
    }

    #[test]
    fn a_perfect_service_scores_one_thousand_and_a_dead_one_scores_its_capacity_only() {
        assert_eq!(ServiceScore::composite(1_000, 1, 1_000, 1_000), 999);
        // Nothing reachable, nothing fresh: only the self-reported capacity term survives, and it
        // is weighted 20 of 100 — so a service nobody can reach cannot outrank a working one.
        assert_eq!(ServiceScore::composite(0, 10_000, 1_000, 0), 200);
    }

    #[test]
    fn a_transmitted_composite_is_checkable_against_its_components() {
        let honest = score();
        assert_eq!(honest.composite_permille, honest.recomputed_composite());
        let lying = ServiceScore {
            composite_permille: 1_000,
            ..score()
        };
        assert_ne!(lying.composite_permille, lying.recomputed_composite());
    }

    #[test]
    fn an_observation_with_no_probes_is_not_evidence() {
        let empty = ServiceObservation {
            service: NodeId::new(9, 10),
            kind: ServiceKind::Rendezvous,
            probes: 0,
            successes: 0,
            rtt_p50_millis: 0,
            rtt_p95_millis: 0,
            observed_at_epoch_millis: 1,
        };
        assert_eq!(empty.availability_permille(), 0);
        let good = ServiceObservation {
            probes: 20,
            successes: 19,
            ..empty
        };
        assert_eq!(good.availability_permille(), 950);
    }

    #[test]
    fn only_serving_accepts_new_work() {
        assert!(ServiceLifecycle::Serving.accepts_new_work());
        assert!(!ServiceLifecycle::Starting.accepts_new_work());
        assert!(!ServiceLifecycle::Draining.accepts_new_work());
        assert!(!ServiceLifecycle::Stopped.accepts_new_work());
    }

    #[test]
    fn invalid_ordinals_are_rejected_rather_than_defaulted() {
        assert!(ServiceKind::from_ordinal(2).is_err());
        assert!(ServiceLifecycle::from_ordinal(4).is_err());
    }
}
