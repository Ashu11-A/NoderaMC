//! The service directory: which rendezvous points and trackers exist, and how good each one is.
//!
//! ## What this adds to a tracker
//!
//! A tracker already answers *which worlds exist and who seeds them*. This adds *which rendezvous
//! points exist and which ones work*, which is the missing half of "a peer can find its way onto the
//! network with nothing configured but a tracker". Before it, a peer's rendezvous list was a string in
//! its own configuration: adding a rendezvous to the network reached nobody, and losing one was an
//! outage for everybody who had it configured.
//!
//! ## Scoring: evidence in, aggregate out
//!
//! Peers send **counters** ([`ServiceObservation`] — probes, successes, RTT percentiles), never
//! verdicts. The tracker combines them and publishes components, never a private judgement. Two
//! deliberate choices make this hard to game:
//!
//! * **Per-reporter probe influence is capped** ([`PROBE_CAP_PER_REPORTER`]). Otherwise one identity
//!   claiming a million probes outvotes a hundred honest peers, and scoring becomes the cheapest
//!   denial-of-service in the system: report every rival relay as dead and take over routing.
//! * **Latency is a median across reporters, not a mean.** With three honest reporters, one liar
//!   cannot move it at all — and a mean is exactly the statistic a single extreme value dominates.
//!
//! The composite the tracker publishes is [`ServiceScore::composite`], the *same function peers run*.
//! It is transmitted for dashboards and for peers that do not want to compute it, and recomputed by
//! peers that do. A tracker that inflates a favourite's number therefore changes nothing about where
//! traffic goes — the same boundary that keeps it from lying about worlds.
//!
//! ## Trust
//!
//! A record is admitted only if it verifies under the key it carries, is fresh, and matches the
//! trust-on-first-use binding for its id — the peer-announce rules, applied to services. What this
//! service still *cannot* do is invent a rendezvous: it can only hide honest ones or list an
//! attacker's validly self-signed one, which is why peers pin what worked and verify every row.

use std::collections::HashMap;

use nodera_codec::service::{
    ServiceDirectoryEntry, ServiceKind, ServiceLifecycle, ServiceObservation, ServiceRecord,
    ServiceScore,
};
use nodera_codec::sig;
use nodera_codec::types::{NetworkId, NodeId};

use crate::announce::within_window;

/// The most probes one reporter may contribute to an availability aggregate in a window.
///
/// Bounds how far a single identity can move a score. It is not a rate limit — a peer may probe as
/// often as it likes — it is an influence limit, which is the property that matters when the reporter
/// might be lying.
pub const PROBE_CAP_PER_REPORTER: u32 = 100;

/// Why a service announcement was refused. Stable codes; callers log them and count a strike.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServiceRejection {
    /// The signature did not verify against the record's own key.
    BadSignature,
    /// The record's issue time is outside the accepted clock-skew window.
    Stale,
    /// The id is already bound to a different key.
    IdentityMismatch,
    /// The directory is full.
    DirectoryFull,
    /// The record claims a lifecycle/deadline combination that cannot be honoured.
    Malformed,
}

impl ServiceRejection {
    /// The stable wire code.
    pub fn code(self) -> &'static str {
        match self {
            Self::BadSignature => "bad-signature",
            Self::Stale => "stale-record",
            Self::IdentityMismatch => "identity-mismatch",
            Self::DirectoryFull => "directory-full",
            Self::Malformed => "malformed-record",
        }
    }
}

/// What admitting a record did.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServiceOutcome {
    /// A new service appeared.
    Registered,
    /// An existing service refreshed or changed state.
    Refreshed,
    /// A `Stopped` record removed the service.
    Removed,
    /// A `Stopped` record for a service that was not listed.
    NotPresent,
}

impl ServiceOutcome {
    /// How the ack should describe this outcome: `(accepted, stable code)`.
    ///
    /// Three of the four are an admission and say nothing more. `NotPresent` is the one the
    /// announcer cannot work out for itself — it asked this tracker to drop a listing that was
    /// never here, which means either it has been announcing somewhere else or its records have
    /// been expiring between announces. Answering "accepted" to that hid a discovery outage behind
    /// a successful-looking round trip.
    pub fn ack(self) -> (bool, &'static str) {
        match self {
            Self::Registered | Self::Refreshed | Self::Removed => (true, ""),
            Self::NotPresent => (false, "not-listed"),
        }
    }
}

/// One service as the tracker holds it.
#[derive(Debug, Clone)]
struct StoredService {
    record: ServiceRecord,
    signature: Vec<u8>,
    last_seen_millis: u64,
    /// One row per reporting peer; the newest observation from each.
    observations: HashMap<NodeId, ServiceObservation>,
}

impl StoredService {
    /// How fresh what the tracker knows is, in permille.
    ///
    /// Decays linearly from full at the moment the record arrived to nothing at the moment it
    /// self-expires. A service refreshing on schedule sits near the top; one that stopped refreshing
    /// slides down and stops being chosen *before* it is swept, which is the point — a hard cliff at
    /// the TTL would keep a dead rendezvous top-ranked until the sweep tick.
    fn freshness_permille(&self, now_millis: u64) -> u32 {
        let expires = self.record.expires_at_epoch_millis;
        if now_millis <= self.last_seen_millis {
            return 1_000;
        }
        if expires <= self.last_seen_millis || now_millis >= expires {
            return 0;
        }
        let remaining = expires - now_millis;
        let span = expires - self.last_seen_millis;
        ((remaining as u128 * 1_000) / span as u128) as u32
    }

    fn is_expired(&self, now_millis: u64) -> bool {
        // A record from the future is treated as live rather than expired: a service with a fast
        // clock should be reachable, not invisible.
        self.record.expires_at_epoch_millis != 0 && now_millis > self.record.expires_at_epoch_millis
    }
}

/// Trust-on-first-use `NodeId` → public key bindings for services.
#[derive(Debug, Default)]
struct ServiceBindings {
    keys: HashMap<NodeId, Vec<u8>>,
}

impl ServiceBindings {
    fn accept(&mut self, service: NodeId, public_key: &[u8]) -> bool {
        match self.keys.get(&service) {
            Some(bound) => bound.as_slice() == public_key,
            None => {
                self.keys.insert(service, public_key.to_vec());
                true
            }
        }
    }

    fn forget(&mut self, service: &NodeId) {
        self.keys.remove(service);
    }
}

/// The tracker's directory of services.
#[derive(Debug, Default)]
pub struct ServiceDirectory {
    services: HashMap<NodeId, StoredService>,
    bindings: ServiceBindings,
    reports_accepted: u64,
    reports_rejected: u64,
}

impl ServiceDirectory {
    /// An empty directory.
    pub fn new() -> Self {
        Self::default()
    }

    /// How many services are listed.
    pub fn len(&self) -> usize {
        self.services.len()
    }

    /// Whether the directory is empty. Assertion helper; the service path reads [`Self::len`].
    #[cfg(test)]
    pub fn is_empty(&self) -> bool {
        self.services.is_empty()
    }

    /// Accepted and rejected score-report counts, for the operator log line.
    pub fn report_counts(&self) -> (u64, u64) {
        (self.reports_accepted, self.reports_rejected)
    }

    /// How many services of a kind are draining right now — the number that says a rollout is in
    /// progress, which is worth seeing in a log before peers start moving.
    pub fn draining_count(&self, kind: ServiceKind) -> usize {
        self.services
            .values()
            .filter(|s| s.record.kind == kind && s.record.lifecycle == ServiceLifecycle::Draining)
            .count()
    }

    /// Admit a signed service record.
    ///
    /// `signed_bytes` must be the bytes as they arrived (a re-encoding would verify this
    /// implementation against itself). `max_services` bounds the directory; `clock_skew_millis`
    /// bounds replay.
    pub fn admit(
        &mut self,
        record: &ServiceRecord,
        signature: &[u8],
        signed_bytes: &[u8],
        now_millis: u64,
        clock_skew_millis: u64,
        max_services: usize,
    ) -> Result<ServiceOutcome, ServiceRejection> {
        if record.lifecycle == ServiceLifecycle::Draining && record.drain_deadline_epoch_millis == 0
        {
            // "I am draining, at no particular time" gives a peer nothing to plan against, and it is
            // the shape a truncated or hand-written record has.
            return Err(ServiceRejection::Malformed);
        }
        if !within_window(record.issued_at_epoch_millis, now_millis, clock_skew_millis) {
            return Err(ServiceRejection::Stale);
        }
        if sig::verify(&record.public_key, signed_bytes, signature).is_err() {
            return Err(ServiceRejection::BadSignature);
        }
        if !self.bindings.accept(record.service, &record.public_key) {
            return Err(ServiceRejection::IdentityMismatch);
        }

        if record.lifecycle == ServiceLifecycle::Stopped {
            // A graceful stop releases the binding too, so an operator who rotates the service key
            // after decommissioning is not locked out of the id.
            let existed = self.services.remove(&record.service).is_some();
            self.bindings.forget(&record.service);
            return Ok(if existed {
                ServiceOutcome::Removed
            } else {
                ServiceOutcome::NotPresent
            });
        }

        if let Some(existing) = self.services.get_mut(&record.service) {
            existing.record = record.clone();
            existing.signature = signature.to_vec();
            existing.last_seen_millis = now_millis;
            return Ok(ServiceOutcome::Refreshed);
        }

        if self.services.len() >= max_services && !self.shed_one_expired(now_millis) {
            return Err(ServiceRejection::DirectoryFull);
        }
        self.services.insert(
            record.service,
            StoredService {
                record: record.clone(),
                signature: signature.to_vec(),
                last_seen_millis: now_millis,
                observations: HashMap::new(),
            },
        );
        Ok(ServiceOutcome::Registered)
    }

    /// Record one peer's observations.
    ///
    /// Observations about services this tracker has never heard of are dropped: storing them would
    /// let anyone grow the tracker's memory by reporting on ids that do not exist.
    pub fn record_observations(
        &mut self,
        reporter: NodeId,
        observations: &[ServiceObservation],
        max_reporters_per_service: usize,
    ) -> usize {
        let mut stored = 0;
        for observation in observations {
            let Some(service) = self.services.get_mut(&observation.service) else {
                continue;
            };
            if service.record.kind != observation.kind {
                continue;
            }
            if service.observations.len() >= max_reporters_per_service
                && !service.observations.contains_key(&reporter)
            {
                // Bounded per service, and the oldest report is the one that goes: a full table must
                // not freeze the score at whoever reported first.
                if let Some(oldest) = service
                    .observations
                    .iter()
                    .min_by_key(|(_, o)| o.observed_at_epoch_millis)
                    .map(|(id, _)| *id)
                {
                    service.observations.remove(&oldest);
                }
            }
            service.observations.insert(reporter, *observation);
            stored += 1;
        }
        if stored > 0 {
            self.reports_accepted += 1;
        } else {
            self.reports_rejected += 1;
        }
        stored
    }

    /// The aggregate score for one service, or `None` when it is not listed.
    ///
    /// The production path reads scores through [`Self::directory`], which aggregates every row in
    /// one pass; this is the single-service view the scoring tests assert on.
    #[cfg(test)]
    pub fn score_of(
        &self,
        service: &NodeId,
        now_millis: u64,
        report_max_age_millis: u64,
    ) -> Option<ServiceScore> {
        let stored = self.services.get(service)?;
        Some(aggregate(stored, now_millis, report_max_age_millis))
    }

    /// Answer a directory query: the services of `kind` on `network_id`, best first.
    ///
    /// Draining services stay listed. A peer that just lost its rendezvous should be able to see
    /// *why* rather than watch it vanish, and the drain deadline in the row is what lets it move
    /// deliberately. Filtering on lifecycle is the peer's job — it is the one deciding where its
    /// traffic goes.
    pub fn directory(
        &self,
        kind: ServiceKind,
        network_id: NetworkId,
        limit: usize,
        now_millis: u64,
        report_max_age_millis: u64,
    ) -> Vec<ServiceDirectoryEntry> {
        let mut rows: Vec<ServiceDirectoryEntry> = self
            .services
            .values()
            .filter(|s| s.record.kind == kind)
            .filter(|s| s.record.network_id == network_id)
            .filter(|s| !s.is_expired(now_millis))
            .map(|s| ServiceDirectoryEntry {
                record: s.record.clone(),
                signature: s.signature.clone(),
                score: aggregate(s, now_millis, report_max_age_millis),
            })
            .collect();
        // Deterministic: composite descending, then id ascending. Two trackers with the same
        // evidence answer identically, which is what makes a peer's merge stable.
        rows.sort_by(|a, b| {
            b.score
                .composite_permille
                .cmp(&a.score.composite_permille)
                .then_with(|| a.record.service.msb.cmp(&b.record.service.msb))
                .then_with(|| a.record.service.lsb.cmp(&b.record.service.lsb))
        });
        rows.truncate(limit);
        rows
    }

    /// Drop services whose records have expired, and stale observations along with them.
    pub fn sweep(&mut self, now_millis: u64, report_max_age_millis: u64) -> usize {
        let before = self.services.len();
        self.services.retain(|_, s| !s.is_expired(now_millis));
        for service in self.services.values_mut() {
            service
                .observations
                .retain(|_, o| o.observed_at_epoch_millis + report_max_age_millis >= now_millis);
        }
        before - self.services.len()
    }

    fn shed_one_expired(&mut self, now_millis: u64) -> bool {
        if let Some(id) = self
            .services
            .iter()
            .find(|(_, s)| s.is_expired(now_millis))
            .map(|(id, _)| *id)
        {
            self.services.remove(&id);
            return true;
        }
        false
    }
}

/// Combine one service's evidence into a score.
fn aggregate(stored: &StoredService, now_millis: u64, report_max_age_millis: u64) -> ServiceScore {
    let fresh: Vec<&ServiceObservation> = stored
        .observations
        .values()
        .filter(|o| o.observed_at_epoch_millis + report_max_age_millis >= now_millis)
        .collect();

    let mut probes: u64 = 0;
    let mut successes: u64 = 0;
    let mut p50s: Vec<u32> = Vec::with_capacity(fresh.len());
    let mut p95s: Vec<u32> = Vec::with_capacity(fresh.len());
    for observation in &fresh {
        // Capped per reporter: influence, not rate. Successes are scaled with the same factor so a
        // capped reporter keeps its ratio instead of being read as mostly-failed.
        let counted = observation.probes.min(PROBE_CAP_PER_REPORTER);
        if counted == 0 {
            continue;
        }
        let scaled_successes = (u64::from(observation.successes) * u64::from(counted))
            / u64::from(observation.probes.max(1));
        probes += u64::from(counted);
        successes += scaled_successes;
        if observation.successes > 0 {
            p50s.push(observation.rtt_p50_millis);
            p95s.push(observation.rtt_p95_millis);
        }
    }

    let availability_permille = if probes == 0 {
        0
    } else {
        ((successes * 1_000) / probes) as u32
    };
    let capacity_permille = stored.record.capacity_permille();
    let freshness_permille = stored.freshness_permille(now_millis);
    let score = ServiceScore {
        availability_permille,
        rtt_p50_millis: median(&mut p50s),
        rtt_p95_millis: median(&mut p95s),
        capacity_permille,
        freshness_permille,
        reporter_count: fresh.len() as u32,
        composite_permille: 0,
    };
    score.with_composite()
}

/// The median of `values`, or `0` when there are none.
///
/// A median, not a mean: one reporter claiming a 30-second RTT must not drag the number, and with
/// three honest reporters it cannot move it at all.
fn median(values: &mut [u32]) -> u32 {
    if values.is_empty() {
        return 0;
    }
    values.sort_unstable();
    values[values.len() / 2]
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    struct Signed {
        record: ServiceRecord,
        signature: Vec<u8>,
        bytes: Vec<u8>,
    }

    fn sign(seed: u8, id: u64, mutate: impl FnOnce(&mut ServiceRecord)) -> Signed {
        let key = SigningKey::from_bytes(&[seed; 32]);
        let public = sig::x509_public_key(&key.verifying_key().to_bytes());
        let mut record = ServiceRecord {
            service: NodeId::new(id, id),
            public_key: public,
            kind: ServiceKind::Rendezvous,
            lifecycle: ServiceLifecycle::Serving,
            network_id: NetworkId::new(1, 2),
            routes: vec![format!("rdv{id}.example:25601")],
            version: "0.1.0".to_owned(),
            active_sessions: 10,
            max_sessions: 1_000,
            active_circuits: 1,
            max_circuits: 100,
            rejected_last_window: 0,
            issued_at_epoch_millis: NOW,
            expires_at_epoch_millis: NOW + 300_000,
            drain_deadline_epoch_millis: 0,
        };
        mutate(&mut record);
        let bytes = record.signed_bytes();
        let signature = key.sign(&bytes).to_bytes().to_vec();
        Signed {
            record,
            signature,
            bytes,
        }
    }

    const NOW: u64 = 1_700_000_000_000;
    const SKEW: u64 = 300_000;
    const REPORT_AGE: u64 = 600_000;

    fn admit(
        dir: &mut ServiceDirectory,
        signed: &Signed,
        now: u64,
    ) -> Result<ServiceOutcome, ServiceRejection> {
        dir.admit(
            &signed.record,
            &signed.signature,
            &signed.bytes,
            now,
            SKEW,
            100,
        )
    }

    fn observation(service: u64, probes: u32, successes: u32, p95: u32) -> ServiceObservation {
        ServiceObservation {
            service: NodeId::new(service, service),
            kind: ServiceKind::Rendezvous,
            probes,
            successes,
            rtt_p50_millis: p95 / 2,
            rtt_p95_millis: p95,
            observed_at_epoch_millis: NOW,
        }
    }

    #[test]
    fn a_valid_record_is_registered_and_listed() {
        let mut dir = ServiceDirectory::new();
        let signed = sign(1, 5, |_| {});
        assert_eq!(
            admit(&mut dir, &signed, NOW),
            Ok(ServiceOutcome::Registered)
        );
        let rows = dir.directory(
            ServiceKind::Rendezvous,
            NetworkId::new(1, 2),
            10,
            NOW,
            REPORT_AGE,
        );
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].record.service, NodeId::new(5, 5));
        // The signature travels with the row so a peer can verify it without trusting us.
        sig::verify(
            &rows[0].record.public_key,
            &rows[0].record.signed_bytes(),
            &rows[0].signature,
        )
        .unwrap();
    }

    #[test]
    fn a_tampered_record_never_reaches_the_directory() {
        let mut dir = ServiceDirectory::new();
        let signed = sign(1, 5, |_| {});
        let mut moved = signed.record.clone();
        moved.routes = vec!["attacker.example:25601".to_owned()];
        let bytes = moved.signed_bytes();
        assert_eq!(
            dir.admit(&moved, &signed.signature, &bytes, NOW, SKEW, 100),
            Err(ServiceRejection::BadSignature)
        );
        assert!(dir.is_empty());
    }

    #[test]
    fn a_second_key_cannot_take_over_an_id() {
        // Trust-on-first-use: whoever registered an id keeps it, so a peer's pinned rendezvous cannot
        // be replaced by somebody who merely knows its id.
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        assert_eq!(
            admit(&mut dir, &sign(2, 5, |_| {}), NOW),
            Err(ServiceRejection::IdentityMismatch)
        );
    }

    #[test]
    fn a_stale_record_is_refused() {
        let mut dir = ServiceDirectory::new();
        let signed = sign(1, 5, |r| r.issued_at_epoch_millis = NOW - SKEW - 1);
        assert_eq!(admit(&mut dir, &signed, NOW), Err(ServiceRejection::Stale));
    }

    #[test]
    fn a_draining_record_without_a_deadline_is_refused() {
        // "Draining, at no particular time" is unplannable, and it is the shape a truncated record has.
        let mut dir = ServiceDirectory::new();
        let signed = sign(1, 5, |r| r.lifecycle = ServiceLifecycle::Draining);
        assert_eq!(
            admit(&mut dir, &signed, NOW),
            Err(ServiceRejection::Malformed)
        );
    }

    #[test]
    fn a_draining_service_stays_listed_with_its_deadline() {
        // A peer that just lost its rendezvous must be able to see why, and when.
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        let draining = sign(1, 5, |r| {
            r.lifecycle = ServiceLifecycle::Draining;
            r.drain_deadline_epoch_millis = NOW + 30_000;
        });
        assert_eq!(
            admit(&mut dir, &draining, NOW),
            Ok(ServiceOutcome::Refreshed)
        );
        let rows = dir.directory(
            ServiceKind::Rendezvous,
            NetworkId::new(1, 2),
            10,
            NOW,
            REPORT_AGE,
        );
        assert_eq!(rows[0].record.lifecycle, ServiceLifecycle::Draining);
        assert_eq!(rows[0].record.drain_deadline_epoch_millis, NOW + 30_000);
        assert_eq!(dir.draining_count(ServiceKind::Rendezvous), 1);
    }

    #[test]
    fn a_stopped_record_removes_the_service_and_releases_the_id() {
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        let stopped = sign(1, 5, |r| r.lifecycle = ServiceLifecycle::Stopped);
        assert_eq!(admit(&mut dir, &stopped, NOW), Ok(ServiceOutcome::Removed));
        assert!(dir.is_empty());
        // Released binding: an operator who rotates the key after decommissioning is not locked out.
        assert!(admit(&mut dir, &sign(2, 5, |_| {}), NOW).is_ok());
    }

    #[test]
    fn a_kind_or_network_mismatch_is_not_listed() {
        let mut dir = ServiceDirectory::new();
        assert!(admit(
            &mut dir,
            &sign(1, 5, |r| r.kind = ServiceKind::Tracker),
            NOW
        )
        .is_ok());
        assert!(dir
            .directory(
                ServiceKind::Rendezvous,
                NetworkId::new(1, 2),
                10,
                NOW,
                REPORT_AGE
            )
            .is_empty());
        assert_eq!(
            dir.directory(
                ServiceKind::Tracker,
                NetworkId::new(1, 2),
                10,
                NOW,
                REPORT_AGE
            )
            .len(),
            1
        );
        assert!(dir
            .directory(
                ServiceKind::Tracker,
                NetworkId::new(9, 9),
                10,
                NOW,
                REPORT_AGE
            )
            .is_empty());
    }

    #[test]
    fn measured_availability_orders_the_directory() {
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        assert!(admit(&mut dir, &sign(2, 6, |_| {}), NOW).is_ok());
        dir.record_observations(NodeId::new(100, 1), &[observation(5, 20, 4, 30)], 32);
        dir.record_observations(NodeId::new(100, 1), &[observation(6, 20, 20, 30)], 32);
        let rows = dir.directory(
            ServiceKind::Rendezvous,
            NetworkId::new(1, 2),
            10,
            NOW,
            REPORT_AGE,
        );
        assert_eq!(
            rows[0].record.service,
            NodeId::new(6, 6),
            "the reachable one ranks first"
        );
        assert_eq!(rows[0].score.availability_permille, 1_000);
        assert_eq!(rows[1].score.availability_permille, 200);
        assert_eq!(rows[0].score.reporter_count, 1);
    }

    #[test]
    fn one_reporter_cannot_outvote_many_by_claiming_more_probes() {
        // The influence cap is what stops scoring from being a denial-of-service: report every rival
        // relay as dead a million times and take over routing.
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        for honest in 0..3u64 {
            dir.record_observations(NodeId::new(200, honest), &[observation(5, 20, 20, 30)], 32);
        }
        dir.record_observations(
            NodeId::new(999, 999),
            &[observation(5, 1_000_000, 0, 30)],
            32,
        );
        let score = dir.score_of(&NodeId::new(5, 5), NOW, REPORT_AGE).unwrap();
        // 3 × 20 honest successes against a liar capped at 100 probes: still a clear majority up.
        assert!(
            score.availability_permille >= 350,
            "a capped liar should not sink an available service: {}",
            score.availability_permille
        );
        assert_eq!(score.reporter_count, 4);
    }

    #[test]
    fn latency_is_a_median_so_three_honest_reporters_beat_one_liar() {
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        for honest in 0..3u64 {
            dir.record_observations(NodeId::new(200, honest), &[observation(5, 10, 10, 40)], 32);
        }
        dir.record_observations(NodeId::new(999, 1), &[observation(5, 10, 10, 30_000)], 32);
        let score = dir.score_of(&NodeId::new(5, 5), NOW, REPORT_AGE).unwrap();
        assert_eq!(
            score.rtt_p95_millis, 40,
            "the median is untouched by one outlier"
        );
    }

    #[test]
    fn a_report_about_an_unknown_service_is_dropped() {
        // Otherwise anyone grows the tracker's memory by reporting on ids that do not exist.
        let mut dir = ServiceDirectory::new();
        assert_eq!(
            dir.record_observations(NodeId::new(1, 1), &[observation(404, 5, 5, 10)], 32),
            0
        );
        assert!(dir.is_empty());
        assert_eq!(dir.report_counts(), (0, 1));
    }

    #[test]
    fn a_report_whose_kind_disagrees_with_the_record_is_dropped() {
        let mut dir = ServiceDirectory::new();
        assert!(admit(
            &mut dir,
            &sign(1, 5, |r| r.kind = ServiceKind::Tracker),
            NOW
        )
        .is_ok());
        assert_eq!(
            dir.record_observations(NodeId::new(1, 1), &[observation(5, 5, 5, 10)], 32),
            0
        );
    }

    #[test]
    fn the_reporter_table_is_bounded_and_drops_the_oldest() {
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        for reporter in 0..5u64 {
            let mut o = observation(5, 10, 10, 20);
            o.observed_at_epoch_millis = NOW - (5 - reporter) * 1_000;
            dir.record_observations(NodeId::new(300, reporter), &[o], 2);
        }
        let score = dir.score_of(&NodeId::new(5, 5), NOW, REPORT_AGE).unwrap();
        assert_eq!(
            score.reporter_count, 2,
            "bounded, and not frozen at the first two"
        );
    }

    #[test]
    fn stale_observations_stop_counting() {
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        dir.record_observations(NodeId::new(1, 1), &[observation(5, 10, 10, 20)], 32);
        let later = NOW + REPORT_AGE + 1;
        let score = dir.score_of(&NodeId::new(5, 5), later, REPORT_AGE).unwrap();
        assert_eq!(score.reporter_count, 0);
        assert_eq!(score.availability_permille, 0);
    }

    #[test]
    fn freshness_decays_between_the_refresh_and_the_expiry() {
        // A hard cliff at the TTL would keep a dead rendezvous top-ranked until the sweep tick.
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        assert_eq!(
            dir.score_of(&NodeId::new(5, 5), NOW, REPORT_AGE)
                .unwrap()
                .freshness_permille,
            1_000
        );
        let half = dir
            .score_of(&NodeId::new(5, 5), NOW + 150_000, REPORT_AGE)
            .unwrap()
            .freshness_permille;
        assert!(
            (490..=510).contains(&half),
            "halfway through the TTL: {half}"
        );
        assert_eq!(
            dir.score_of(&NodeId::new(5, 5), NOW + 300_000, REPORT_AGE)
                .unwrap()
                .freshness_permille,
            0
        );
    }

    #[test]
    fn an_expired_record_is_swept_and_not_listed() {
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        let later = NOW + 300_001;
        assert!(dir
            .directory(
                ServiceKind::Rendezvous,
                NetworkId::new(1, 2),
                10,
                later,
                REPORT_AGE
            )
            .is_empty());
        assert_eq!(dir.sweep(later, REPORT_AGE), 1);
        assert!(dir.is_empty());
    }

    #[test]
    fn a_full_directory_reuses_an_expired_slot_before_refusing() {
        let mut dir = ServiceDirectory::new();
        let first = sign(1, 5, |_| {});
        assert!(dir
            .admit(&first.record, &first.signature, &first.bytes, NOW, SKEW, 1)
            .is_ok());
        let second = sign(2, 6, |_| {});
        assert_eq!(
            dir.admit(
                &second.record,
                &second.signature,
                &second.bytes,
                NOW,
                SKEW,
                1
            ),
            Err(ServiceRejection::DirectoryFull)
        );
        // Once the first record has expired its slot is reusable rather than permanently held.
        let later = NOW + 300_001;
        let third = sign(3, 7, |r| {
            r.issued_at_epoch_millis = later;
            r.expires_at_epoch_millis = later + 300_000;
        });
        assert_eq!(
            dir.admit(
                &third.record,
                &third.signature,
                &third.bytes,
                later,
                SKEW,
                1
            ),
            Ok(ServiceOutcome::Registered)
        );
    }

    #[test]
    fn the_limit_truncates_the_answer_after_sorting_not_before() {
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        assert!(admit(&mut dir, &sign(2, 6, |_| {}), NOW).is_ok());
        dir.record_observations(NodeId::new(1, 1), &[observation(6, 20, 20, 10)], 32);
        let rows = dir.directory(
            ServiceKind::Rendezvous,
            NetworkId::new(1, 2),
            1,
            NOW,
            REPORT_AGE,
        );
        assert_eq!(rows.len(), 1);
        assert_eq!(
            rows[0].record.service,
            NodeId::new(6, 6),
            "the best row survives the cut"
        );
    }

    #[test]
    fn the_published_composite_is_what_a_peer_recomputes() {
        // The whole non-authority argument: a peer running the same function on the same components
        // gets the same number, so the transmitted one is a convenience and never a lever.
        let mut dir = ServiceDirectory::new();
        assert!(admit(&mut dir, &sign(1, 5, |_| {}), NOW).is_ok());
        dir.record_observations(NodeId::new(1, 1), &[observation(5, 20, 19, 60)], 32);
        let score = dir.score_of(&NodeId::new(5, 5), NOW, REPORT_AGE).unwrap();
        assert_eq!(score.composite_permille, score.recomputed_composite());
    }
}
