//! The tracker's request handling, with no IO in it.
//!
//! Sockets, clocks and tasks live in [`crate::wire`]; everything that decides *what the answer is*
//! lives here and takes `now_millis` as a parameter. That split is what makes the whole admission
//! path — signatures, freshness, quotas, expiry, health transitions — unit-testable without
//! spawning a listener or sleeping.

use crate::announce::{self, within_window, IdentityBindings, Rejection};
use crate::config::Config;
use crate::deletion::DeletedWorlds;
use crate::limits::AnnounceQuota;
use crate::query;
use crate::registry::{AnnounceOutcome, Registry};
use crate::services::{ServiceDirectory, ServiceRejection};
use nodera_codec::messages::{AnnounceEvent, DiscoveryMessage, TrackerAnnounceAck};
use nodera_codec::service::{
    ServiceAnnounceAck, ServiceDirectoryEntry, ServiceDirectoryResponse, ServiceKind,
    ServiceMessage,
};
use nodera_codec::tags::message_tags;
use nodera_codec::tombstone::{WorldDeletionGossip, WorldRevivalGossip};
use std::net::IpAddr;

/// What the tracker did with a frame — the caller logs this; the peer gets the reply.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Handled {
    /// Send this frame back.
    Reply(Vec<u8>),
    /// The frame could not be decoded or is not one the tracker serves.
    Unsupported(String),
}

/// The whole mutable service state.
#[derive(Debug)]
pub struct Tracker {
    config: Config,
    registry: Registry,
    bindings: IdentityBindings,
    deleted: DeletedWorlds,
    quota: AnnounceQuota,
    /// Score reports get their own quota: they are cheap to send and expensive to aggregate, and
    /// sharing the announce budget would let a report flood starve the announces the world list
    /// depends on.
    report_quota: AnnounceQuota,
    directory: ServiceDirectory,
    announces_accepted: u64,
    announces_rejected: u64,
    queries_answered: u64,
}

impl Tracker {
    /// Build a tracker from validated config.
    pub fn new(config: Config) -> Self {
        let config_persist_dir = config.persist_dir.clone();
        let quota = AnnounceQuota::new(
            u64::from(config.announce_interval_seconds) * 1_000,
            config.per_ip_announce_quota,
        );
        let report_quota = AnnounceQuota::new(
            u64::from(config.announce_interval_seconds) * 1_000,
            config.per_ip_report_quota,
        );
        Self {
            config,
            registry: Registry::new(),
            bindings: IdentityBindings::new(),
            deleted: DeletedWorlds::new(config_persist_dir.map(|dir| dir.join("deleted-worlds"))),
            quota,
            report_quota,
            directory: ServiceDirectory::new(),
            announces_accepted: 0,
            announces_rejected: 0,
            queries_answered: 0,
        }
    }

    /// The configuration in force.
    pub fn config(&self) -> &Config {
        &self.config
    }

    /// Counters for the operator log line.
    pub fn stats(&self) -> (u64, u64, u64, usize) {
        (
            self.announces_accepted,
            self.announces_rejected,
            self.queries_answered,
            self.registry.world_count(),
        )
    }

    /// How many deletions this tracker remembers.
    ///
    /// Logged so an operator can see the cache exists and is bounded — a number that only grows is
    /// the symptom of a retention sweep that stopped running.
    pub fn deleted_world_count(&self) -> usize {
        self.deleted.len()
    }

    /// Whether this tracker holds a verified deletion for a world. The refusal path reads the
    /// notice directly, so this is an assertion helper.
    #[cfg(test)]
    pub fn is_world_deleted(&self, genesis_hash: &[u8]) -> bool {
        self.deleted.is_deleted(genesis_hash)
    }

    /// How many tracked worlds are healthy, degraded, and dead right now.
    ///
    /// Aggregate counts only — the world-level answer the telemetry window carries. Deliberately
    /// not a per-world breakdown: a list of worlds and their health is a directory, and this
    /// service already has one wire message for that, answered to peers who ask rather than pushed
    /// to a collector.
    pub fn world_health_counts(&self, now_millis: u64) -> (u64, u64, u64) {
        let (mut healthy, mut degraded, mut dead) = (0u64, 0u64, 0u64);
        for (_, swarm) in self.registry.swarms() {
            let seeders = swarm.peers.values().filter(|peer| peer.is_seeder()).count();
            match crate::health::classify(
                seeders,
                self.config.healthy_seeder_floor,
                swarm.retention_deadline_epoch_millis,
                now_millis,
            ) {
                nodera_codec::types::WorldHealth::Healthy => healthy += 1,
                nodera_codec::types::WorldHealth::Degraded => degraded += 1,
                nodera_codec::types::WorldHealth::Dead => dead += 1,
            }
        }
        (healthy, degraded, dead)
    }

    /// Handle one decoded-from-the-wire frame.
    ///
    /// `source` is the observed peer address; it is only ever used for quota accounting and as a
    /// low-priority route hint — never as identity.
    pub fn handle_frame(
        &mut self,
        frame: &[u8],
        source: Option<IpAddr>,
        source_route: Option<String>,
        now_millis: u64,
    ) -> Handled {
        if frame.len() > self.config.max_frame_bytes {
            return self.reject(Rejection::TooLarge);
        }
        // The kind is legible from the header alone, so routing never depends on decoding a body.
        // That is the property that lets a service answer for a kind it cannot read rather than
        // hanging up on it.
        let kind = match nodera_codec::frame::NoderaFrame::peek_kind(frame) {
            Ok(kind) => kind,
            Err(e) => return Handled::Unsupported(e.to_string()),
        };
        // Deletions are their own family and are handled before the discovery decode, because the
        // discovery codec does not know kind 66 and would reject the frame as unsupported.
        if kind == message_tags::WORLD_REVIVAL_GOSSIP {
            return self.handle_revival(frame);
        }
        if kind == message_tags::WORLD_DELETION_GOSSIP {
            return self.handle_deletion(frame);
        }
        // The service-directory family is likewise its own codec: the discovery decoder does not
        // know kinds 67–72 and would reject them as unsupported.
        if (message_tags::SERVICE_ANNOUNCE..=message_tags::SERVICE_DRAIN_NOTICE).contains(&kind) {
            return self.handle_service_frame(frame, source, now_millis);
        }
        let message = match DiscoveryMessage::decode(frame) {
            Ok(message) => message,
            // An announce whose *body* this build cannot read is answered with a refusal, not a
            // hang-up. The tag is legible even when the fields behind it are not, so the tracker
            // can always say "I did not register you" — and that is the one thing the announcer
            // cannot work out for itself. Every other undecodable frame is still dropped.
            Err(e) if is_announce(frame) => {
                eprintln!("nodera-tracker: undecodable announce: {e}");
                return self.reject(Rejection::Undecodable);
            }
            Err(e) => return Handled::Unsupported(e.to_string()),
        };

        match message {
            DiscoveryMessage::TrackerQuery(q) => {
                self.queries_answered += 1;
                let response =
                    query::answer(&self.registry, &q.genesis_hash, &self.config, now_millis);
                Handled::Reply(DiscoveryMessage::TrackerResponse(response).encode())
            }
            DiscoveryMessage::TrackerCatalogQuery(q) => {
                self.queries_answered += 1;
                let response = query::catalog(&self.registry, q.limit, &self.config, now_millis);
                Handled::Reply(DiscoveryMessage::TrackerCatalogResponse(response).encode())
            }
            DiscoveryMessage::TrackerRoutesQuery(q) => {
                self.queries_answered += 1;
                let response =
                    query::routes(&self.registry, &q.genesis_hash, &self.config, now_millis);
                Handled::Reply(DiscoveryMessage::TrackerRoutesResponse(response).encode())
            }
            DiscoveryMessage::TrackerAnnounce(a) => {
                // A world its owner deleted is not re-listed, whoever announces it and however
                // valid their signature is — theirs proves who is speaking, not that the world
                // should exist. The announcer gets the owner's record back so it can verify the
                // refusal itself and delete its own copy; a peer that was offline during the
                // deletion learns about it here and nowhere else.
                if let Some(notice) = self.deleted.notice_for(&a.genesis_hash) {
                    self.announces_rejected += 1;
                    return Handled::Reply(notice.to_vec());
                }
                if let Some(ip) = source {
                    if !self.quota.admit(ip, now_millis) {
                        return self.reject(Rejection::Quota);
                    }
                }
                // Verify against the bytes as they arrived, not a re-encoding of the decoded value.
                let signed_portion = match DiscoveryMessage::split_announce_signature(frame) {
                    Ok((portion, _)) => portion,
                    Err(e) => return Handled::Unsupported(e.to_string()),
                };
                if let Err(rejection) = announce::admit(
                    &a,
                    signed_portion,
                    &mut self.bindings,
                    &self.config,
                    now_millis,
                ) {
                    return self.reject(rejection);
                }

                // Display metadata is honoured only from the world's FULL_ARCHIVE host (rule 0).
                // Taking a name from any announcer would let a passer-by rename someone's world in
                // every player's server list.
                if a.is_host() && a.event != AnnounceEvent::Stopped {
                    self.registry.set_world_metadata(
                        &a.genesis_hash,
                        a.world_name.clone(),
                        a.retention_deadline_epoch_millis,
                        now_millis,
                    );
                }

                let outcome = self.registry.apply_announce(
                    &a,
                    source_route,
                    now_millis,
                    self.config.max_worlds,
                    self.config.max_peers_per_world,
                    self.config.peer_ttl_millis(),
                );
                match outcome {
                    AnnounceOutcome::WorldFull => self.reject(Rejection::WorldFull),
                    AnnounceOutcome::WorldLimit => self.reject(Rejection::WorldLimit),
                    other => {
                        if other == AnnounceOutcome::Removed && a.event == AnnounceEvent::Stopped {
                            // A graceful departure releases the id binding too, so a peer that
                            // rotates its key after leaving is not locked out of its own id.
                            self.bindings.forget(&a.peer);
                        }
                        self.announces_accepted += 1;
                        Handled::Reply(
                            DiscoveryMessage::TrackerAnnounceAck(TrackerAnnounceAck {
                                accepted: true,
                                next_announce_after_seconds: self.config.announce_interval_seconds,
                                reason: String::new(),
                            })
                            .encode(),
                        )
                    }
                }
            }
            // Inventory gossip is peer-to-peer traffic; a tracker that accepted it would be
            // trusting a third party's claim about someone else's holdings. Holdings arrive only
            // inside a signed announce.
            other => Handled::Unsupported(format!("tag {} is not served here", other.tag())),
        }
    }

    /// Handle a service-directory frame (tags 67–72).
    ///
    /// Three of the six are inbound here. `ANNOUNCE_ACK` and `DIRECTORY_RESPONSE` are answers this
    /// service produces, and a `DRAIN_NOTICE` is pushed by a service to the peers holding its control
    /// channels — a tracker holds none, so receiving one means somebody is talking to the wrong port.
    fn handle_service_frame(
        &mut self,
        frame: &[u8],
        source: Option<IpAddr>,
        now_millis: u64,
    ) -> Handled {
        let message = match ServiceMessage::decode(frame) {
            Ok(message) => message,
            Err(e) => return Handled::Unsupported(e.to_string()),
        };
        match message {
            ServiceMessage::Announce(announce) => {
                if let Some(ip) = source {
                    if !self.quota.admit(ip, now_millis) {
                        return self.refuse_service("quota");
                    }
                }
                // Against the bytes as they arrived: the record's own signed range, extracted from
                // this frame rather than re-encoded from the decoded value.
                let signed_bytes = announce.record.signed_bytes();
                match self.directory.admit(
                    &announce.record,
                    &announce.signature,
                    &signed_bytes,
                    now_millis,
                    self.config.clock_skew_millis(),
                    self.config.max_services,
                ) {
                    Ok(outcome) => {
                        self.announces_accepted += 1;
                        // The ack carries the announcer's siblings, so a draining service gets its
                        // replacement list in the same round trip it uses to say it is draining —
                        // the round trip it is least able to make twice.
                        let directory = self.sibling_directory(
                            announce.record.kind,
                            announce.record.network_id,
                            &announce.record.service,
                            now_millis,
                        );
                        // What the directory actually did, rather than "accepted" for everything
                        // that verified. The one that matters is `NotPresent`: a relay announcing
                        // `Stopped` for an id this tracker never held used to be told "accepted",
                        // so a service that believed it was listed here had no way to find out it
                        // never was.
                        let (accepted, reason) = outcome.ack();
                        Handled::Reply(
                            ServiceMessage::AnnounceAck(ServiceAnnounceAck {
                                accepted,
                                next_announce_after_seconds: self.config.announce_interval_seconds,
                                reason: reason.to_owned(),
                                directory,
                            })
                            .encode(),
                        )
                    }
                    Err(rejection) => self.refuse_service(rejection.code()),
                }
            }
            ServiceMessage::DirectoryQuery(query) => {
                self.queries_answered += 1;
                let limit = if query.limit == 0 {
                    self.config.service_directory_page_limit
                } else {
                    (query.limit as usize).min(self.config.service_directory_page_limit)
                };
                let entries = self.directory.directory(
                    query.kind,
                    query.network_id,
                    limit,
                    now_millis,
                    self.config.service_report_max_age_millis(),
                );
                Handled::Reply(
                    ServiceMessage::DirectoryResponse(ServiceDirectoryResponse { entries })
                        .encode(),
                )
            }
            ServiceMessage::ScoreReport(_) => {
                if let Some(ip) = source {
                    if !self.report_quota.admit(ip, now_millis) {
                        return self.refuse_service("quota");
                    }
                }
                let (signed_bytes, signature) = match ServiceMessage::split_report_signature(frame)
                {
                    Ok(split) => split,
                    Err(e) => return Handled::Unsupported(e.to_string()),
                };
                let report = match ServiceMessage::decode(frame) {
                    Ok(ServiceMessage::ScoreReport(report)) => report,
                    _ => return Handled::Unsupported("not a score report".to_owned()),
                };
                if !within_window(
                    report.report_epoch_millis,
                    now_millis,
                    self.config.clock_skew_millis(),
                ) {
                    return self.refuse_service(ServiceRejection::Stale.code());
                }
                if nodera_codec::sig::verify(&report.public_key, signed_bytes, signature).is_err() {
                    return self.refuse_service(ServiceRejection::BadSignature.code());
                }
                // Attributed to one identity, so influence stays bounded per reporter.
                self.directory.record_observations(
                    report.reporter,
                    &report.observations,
                    self.config.service_report_max_reporters,
                );
                Handled::Reply(
                    ServiceMessage::AnnounceAck(ServiceAnnounceAck {
                        accepted: true,
                        next_announce_after_seconds: self.config.announce_interval_seconds,
                        reason: String::new(),
                        directory: Vec::new(),
                    })
                    .encode(),
                )
            }
            other => Handled::Unsupported(format!("tag {} is not served here", other.tag())),
        }
    }

    /// The directory of a kind, excluding one service — what an announcer needs and it alone.
    fn sibling_directory(
        &self,
        kind: ServiceKind,
        network_id: nodera_codec::types::NetworkId,
        exclude: &nodera_codec::types::NodeId,
        now_millis: u64,
    ) -> Vec<ServiceDirectoryEntry> {
        self.directory
            .directory(
                kind,
                network_id,
                self.config.service_directory_page_limit,
                now_millis,
                self.config.service_report_max_age_millis(),
            )
            .into_iter()
            .filter(|entry| &entry.record.service != exclude)
            .collect()
    }

    /// How many services are listed, and how many of each kind are draining.
    ///
    /// Draining counts belong in the operator log line: a rollout in progress is the state in which a
    /// surprised operator most needs to know that peers are moving on purpose.
    pub fn service_counts(&self) -> (usize, usize, usize) {
        (
            self.directory.len(),
            self.directory.draining_count(ServiceKind::Rendezvous),
            self.directory.draining_count(ServiceKind::Tracker),
        )
    }

    /// Accepted and rejected score reports.
    pub fn report_counts(&self) -> (u64, u64) {
        self.directory.report_counts()
    }

    fn refuse_service(&mut self, reason: &str) -> Handled {
        self.announces_rejected += 1;
        Handled::Reply(
            ServiceMessage::AnnounceAck(ServiceAnnounceAck {
                accepted: false,
                next_announce_after_seconds: self.config.announce_interval_seconds,
                reason: reason.to_owned(),
                directory: Vec::new(),
            })
            .encode(),
        )
    }

    /// Handle a world-deletion request (tag 66).
    ///
    /// The tracker verifies exactly what a peer verifies and has no extra authority: it cannot
    /// delete a world nobody asked it to, and it cannot refuse one whose owner did — beyond
    /// declining to serve, which is not a power over anyone else's copy. A record that does not
    /// verify is dropped and nothing changes.
    fn handle_deletion(&mut self, frame: &[u8]) -> Handled {
        // A consensus kind: its strict canonical bytes cross the tolerant plane inside one opaque
        // field, and those are the bytes the owner signed.
        let payload = match nodera_codec::wire::consensus_payload(frame) {
            Ok(payload) => payload,
            Err(e) => return Handled::Unsupported(e.to_string()),
        };
        let gossip = match WorldDeletionGossip::decode(payload) {
            Ok(gossip) => gossip,
            Err(e) => return Handled::Unsupported(e.to_string()),
        };
        let Some(tombstone) = gossip.verified() else {
            self.announces_rejected += 1;
            return self.reject(Rejection::BadSignature);
        };
        self.registry.remove_world(&tombstone.world_id);
        self.deleted.remember(&tombstone);
        // Echoed back, not merely acknowledged: the sender may be a relay rather than the owner,
        // and returning the record keeps the reply verifiable instead of asking anyone to take
        // this tracker's word for what happened.
        Handled::Reply(nodera_codec::wire::encode_consensus_frame(
            message_tags::WORLD_DELETION_GOSSIP,
            &gossip.encode(),
            nodera_codec::frame::flags::RESPONSE,
            0,
        ))
    }

    /// Handle a world-restore request (tag 76) — the deletion's undo.
    ///
    /// Same rules, opposite effect, and the same absence of authority: this tracker verifies the
    /// owner's signatures and then stops refusing the world. It cannot restore a world whose owner
    /// did not ask, and it cannot keep refusing one whose owner did — beyond declining to list it,
    /// which is not a power over anyone else's copy.
    fn handle_revival(&mut self, frame: &[u8]) -> Handled {
        let payload = match nodera_codec::wire::consensus_payload(frame) {
            Ok(payload) => payload,
            Err(e) => return Handled::Unsupported(e.to_string()),
        };
        let gossip = match WorldRevivalGossip::decode(payload) {
            Ok(gossip) => gossip,
            Err(e) => return Handled::Unsupported(e.to_string()),
        };
        let Some(revival) = gossip.verified() else {
            self.announces_rejected += 1;
            return self.reject(Rejection::BadSignature);
        };
        self.deleted.forget(&revival);
        // Echoed back for the same reason a deletion is: the sender may be a relay, and returning
        // the record keeps the reply verifiable rather than asking anyone to take this tracker's
        // word for what happened. The world is not re-listed here — it returns to the directory
        // through its owner's next ordinary announce, which is the only thing that can say where it
        // now lives.
        Handled::Reply(nodera_codec::wire::encode_consensus_frame(
            message_tags::WORLD_REVIVAL_GOSSIP,
            &gossip.encode(),
            nodera_codec::frame::flags::RESPONSE,
            0,
        ))
    }

    /// Register host-supplied world metadata directly.
    ///
    /// The production path is a host's own announce (see `handle_frame`); this is the seam tests
    /// use to set up a world without minting a host identity.
    #[cfg(test)]
    pub fn set_world_metadata(
        &mut self,
        genesis_hash: &[u8],
        world_name: String,
        retention_deadline_epoch_millis: u64,
        now_millis: u64,
    ) {
        self.registry.set_world_metadata(
            genesis_hash,
            world_name,
            retention_deadline_epoch_millis,
            now_millis,
        );
    }

    /// Expire silent peers, idle quota counters, deletions past the retention window, and services
    /// whose records lapsed.
    pub fn sweep(&mut self, now_millis: u64) -> usize {
        self.quota.sweep(now_millis);
        self.report_quota.sweep(now_millis);
        self.deleted.prune(now_millis);
        self.directory
            .sweep(now_millis, self.config.service_report_max_age_millis());
        self.registry
            .sweep(now_millis, self.config.peer_ttl_millis())
    }

    fn reject(&mut self, rejection: Rejection) -> Handled {
        self.announces_rejected += 1;
        // Even a rejection carries an interval: a peer that hot-loops after being refused is worse
        // for the tracker than the announce it just refused.
        Handled::Reply(
            DiscoveryMessage::TrackerAnnounceAck(TrackerAnnounceAck {
                accepted: false,
                next_announce_after_seconds: self.config.announce_interval_seconds,
                reason: rejection.code().to_owned(),
            })
            .encode(),
        )
    }
}

/// Whether a frame claims to be a tracker announce, read from the tag alone.
///
/// The tag is the first two bytes of every canonical frame and does not move between encoding
/// versions, so it stays legible when the body does not.
fn is_announce(frame: &[u8]) -> bool {
    nodera_codec::frame::NoderaFrame::peek_kind(frame)
        .is_ok_and(|kind| kind == message_tags::TRACKER_ANNOUNCE)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{
        announce as build_announce, caps, holding, host_caps, seeder_caps, TestSigner,
    };
    use nodera_codec::types::WorldHealth;

    fn config() -> Config {
        Config {
            announce_interval_seconds: 30,
            peer_ttl_seconds: 60,
            sample_size: 10,
            seeder_floor: 5,
            healthy_seeder_floor: 1,
            per_ip_announce_quota: 5,
            ..Config::default()
        }
    }

    fn signed_frame(
        signer: &TestSigner,
        n: u64,
        event: AnnounceEvent,
        seeder: bool,
        now: u64,
    ) -> Vec<u8> {
        let mut a = build_announce(
            n,
            b"world",
            event,
            if seeder { seeder_caps() } else { caps() },
        );
        a.announce_epoch_millis = now;
        if seeder {
            a.holdings = vec![holding(0x22, &[0b1111_1111])];
        }
        signer.sign_announce(&mut a);
        DiscoveryMessage::TrackerAnnounce(a).encode()
    }

    /// The Java-emitted golden deletion — the same bytes a peer would relay to a tracker.
    fn deletion_frame() -> Vec<u8> {
        let path = nodera_codec::repo::wire_fixtures().join("world-deletion-gossip.bin");
        std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()))
    }

    /// The deletion's strict canonical payload — a consensus kind crosses the tolerant plane as
    /// one opaque field, so the frame is unwrapped before the record is read.
    fn deletion_payload() -> Vec<u8> {
        nodera_codec::wire::consensus_payload(&deletion_frame())
            .expect("opaque consensus payload")
            .to_vec()
    }

    fn deleted_world_id() -> Vec<u8> {
        WorldDeletionGossip::decode(&deletion_payload())
            .expect("decode")
            .world_id
    }

    /// A signed announce for the world the golden deletion names.
    fn announce_for_deleted_world(signer: &TestSigner, now: u64) -> Vec<u8> {
        let mut a = build_announce(1, &deleted_world_id(), AnnounceEvent::Started, host_caps());
        a.announce_epoch_millis = now;
        signer.sign_announce(&mut a);
        DiscoveryMessage::TrackerAnnounce(a).encode()
    }

    #[test]
    fn a_verified_deletion_unlists_the_world_and_is_remembered() {
        let mut tracker = Tracker::new(config());
        let signer = TestSigner::new(7);
        let now = 10_000;
        tracker.handle_frame(&announce_for_deleted_world(&signer, now), None, None, now);
        assert_eq!(tracker.stats().3, 1, "the world starts out listed");

        let handled = tracker.handle_frame(&deletion_frame(), None, None, now);

        assert_eq!(tracker.stats().3, 0, "the world is no longer listed");
        assert!(tracker.is_world_deleted(&deleted_world_id()));
        // The reply is the owner's record, not this tracker's say-so.
        match handled {
            Handled::Reply(frame) => assert!(WorldDeletionGossip::decode(
                nodera_codec::wire::consensus_payload(&frame).expect("opaque payload")
            )
            .expect("decode")
            .verified()
            .is_some()),
            other => panic!("expected the record echoed back, got {other:?}"),
        }
    }

    #[test]
    fn a_forged_deletion_changes_nothing() {
        let mut tracker = Tracker::new(config());
        let signer = TestSigner::new(7);
        let now = 10_000;
        tracker.handle_frame(&announce_for_deleted_world(&signer, now), None, None, now);
        let mut forged = deletion_frame();
        let last = forged.len() - 1;
        forged[last] ^= 0x01; // one bit of the owner's signature

        tracker.handle_frame(&forged, None, None, now);

        assert_eq!(tracker.stats().3, 1, "the world is still listed");
        assert!(!tracker.is_world_deleted(&deleted_world_id()));
    }

    #[test]
    fn a_deleted_world_cannot_be_re_listed_and_the_announcer_is_handed_the_proof() {
        let mut tracker = Tracker::new(config());
        let signer = TestSigner::new(7);
        let now = 10_000;
        tracker.handle_frame(&deletion_frame(), None, None, now);

        // A peer that was offline during the deletion announces the world as usual. Its own
        // signature is perfectly valid — and irrelevant, because it proves who is speaking, not
        // that the world should exist.
        let handled =
            tracker.handle_frame(&announce_for_deleted_world(&signer, now), None, None, now);

        assert_eq!(tracker.stats().3, 0, "the world was not re-listed");
        match handled {
            Handled::Reply(frame) => {
                let notice = WorldDeletionGossip::decode(&frame).expect("decode");
                assert!(
                    notice.verified().is_some(),
                    "the refusal must carry proof the announcer can check itself"
                );
                assert_eq!(notice.world_id, deleted_world_id());
            }
            other => panic!("expected a deletion notice, got {other:?}"),
        }
    }

    #[test]
    fn a_deletion_is_forgotten_after_the_retention_window() {
        let mut tracker = Tracker::new(config());
        let issued = WorldDeletionGossip::decode(&deletion_payload())
            .expect("decode")
            .verified()
            .expect("verify")
            .issued_at_epoch as u64;
        tracker.handle_frame(&deletion_frame(), None, None, issued);

        tracker.sweep(issued + crate::deletion::RETENTION_MILLIS);
        assert!(
            tracker.is_world_deleted(&deleted_world_id()),
            "still inside"
        );

        tracker.sweep(issued + crate::deletion::RETENTION_MILLIS + 1);
        assert!(!tracker.is_world_deleted(&deleted_world_id()));
    }

    #[test]
    fn a_malformed_deletion_frame_is_unsupported_rather_than_fatal() {
        let mut tracker = Tracker::new(config());
        let truncated = &deletion_frame()[..8];

        assert!(matches!(
            tracker.handle_frame(truncated, None, None, 10_000),
            Handled::Unsupported(_)
        ));
    }

    fn query_frame(genesis: &[u8]) -> Vec<u8> {
        DiscoveryMessage::TrackerQuery(nodera_codec::messages::TrackerQuery {
            genesis_hash: genesis.to_vec(),
        })
        .encode()
    }

    fn response(handled: Handled) -> nodera_codec::messages::TrackerResponse {
        match handled {
            Handled::Reply(frame) => match DiscoveryMessage::decode(&frame).unwrap() {
                DiscoveryMessage::TrackerResponse(r) => r,
                other => panic!("expected a response, got {other:?}"),
            },
            other => panic!("expected a reply, got {other:?}"),
        }
    }

    fn ack(handled: Handled) -> TrackerAnnounceAck {
        match handled {
            Handled::Reply(frame) => match DiscoveryMessage::decode(&frame).unwrap() {
                DiscoveryMessage::TrackerAnnounceAck(a) => a,
                other => panic!("expected an ack, got {other:?}"),
            },
            other => panic!("expected a reply, got {other:?}"),
        }
    }

    /// A peer speaking a newer announce body must be *told*, not hung up on.
    ///
    /// This is the live failure that made the whole network look empty: the announce grew a field,
    /// the deployed trackers could not decode it, and they closed the connection without a word.
    /// Every peer went on believing it was listed, every query answered "0 peers", and every join
    /// ended in "no routable seeder" — with nothing anywhere saying why. A refusal carrying a code
    /// costs one frame and turns a silent network-wide outage into a log line.
    #[test]
    fn an_announce_this_build_cannot_decode_is_refused_rather_than_dropped() {
        let mut tracker = Tracker::new(config());
        let signer = TestSigner::new(1);
        let mut frame = signed_frame(&signer, 1, AnnounceEvent::Started, false, 10_000);
        // A body this build cannot read: the tag stays legible, the fields behind it do not.
        frame.truncate(frame.len() - 8);

        let refusal = ack(tracker.handle_frame(&frame, None, None, 10_000));

        assert!(!refusal.accepted);
        assert_eq!(refusal.reason, "undecodable-announce");
        assert!(
            refusal.next_announce_after_seconds > 0,
            "a refused peer is still paced, not invited to hot-loop"
        );
        // Frames that are not announces keep being dropped: there is no useful answer to give.
        assert!(matches!(
            tracker.handle_frame(&[0xff, 0xff, 0x00], None, None, 10_000),
            Handled::Unsupported(_)
        ));
    }

    #[test]
    fn announce_then_query_returns_the_peer() {
        let mut tracker = Tracker::new(config());
        let signer = TestSigner::new(1);
        let accepted = ack(tracker.handle_frame(
            &signed_frame(&signer, 1, AnnounceEvent::Started, true, 10_000),
            None,
            Some("203.0.113.9:40000".to_owned()),
            10_000,
        ));
        assert!(accepted.accepted);
        assert_eq!(accepted.next_announce_after_seconds, 30);

        let r = response(tracker.handle_frame(&query_frame(b"world"), None, None, 10_000));
        assert_eq!(r.peers.len(), 1);
        // The announcing peer reported no population (the fixture is a seeder), so the world has
        // none to report. This asserted `1` — the peer count under a player's name.
        assert_eq!(r.world_player_count, 0);
        assert_eq!(r.health, WorldHealth::Healthy);
        assert_eq!(r.stored_chunks, 8);
    }

    #[test]
    fn only_the_host_may_name_a_world_and_set_its_countdown() {
        let mut tracker = Tracker::new(config());

        // A passer-by claims the world is called something else, with a countdown.
        let mut impostor = build_announce(2, b"world", AnnounceEvent::Started, seeder_caps());
        impostor.world_name = "not-your-world".to_owned();
        impostor.retention_deadline_epoch_millis = 42;
        impostor.announce_epoch_millis = 10_000;
        TestSigner::new(2).sign_announce(&mut impostor);
        assert!(
            ack(tracker.handle_frame(
                &DiscoveryMessage::TrackerAnnounce(impostor).encode(),
                None,
                None,
                10_000
            ))
            .accepted
        );

        let r = response(tracker.handle_frame(&query_frame(b"world"), None, None, 10_000));
        assert_eq!(
            r.world_name, "",
            "a non-host cannot name someone else's world"
        );
        assert_eq!(r.retention_deadline_epoch_millis, 0);

        // The FULL_ARCHIVE host does set it.
        let mut host = build_announce(1, b"world", AnnounceEvent::Started, host_caps());
        host.world_name = "nodera-overworld".to_owned();
        host.retention_deadline_epoch_millis = 500_000;
        host.announce_epoch_millis = 10_000;
        TestSigner::new(1).sign_announce(&mut host);
        assert!(
            ack(tracker.handle_frame(
                &DiscoveryMessage::TrackerAnnounce(host).encode(),
                None,
                None,
                10_000
            ))
            .accepted
        );

        let named = response(tracker.handle_frame(&query_frame(b"world"), None, None, 10_000));
        assert_eq!(named.world_name, "nodera-overworld");
        assert_eq!(named.retention_deadline_epoch_millis, 500_000);
    }

    #[test]
    fn an_invalid_signature_is_rejected_with_a_stable_reason() {
        let mut tracker = Tracker::new(config());
        let mut frame = signed_frame(
            &TestSigner::new(1),
            1,
            AnnounceEvent::Started,
            false,
            10_000,
        );
        // Corrupt the last byte of the signature.
        *frame.last_mut().unwrap() ^= 0xFF;
        let rejected = ack(tracker.handle_frame(&frame, None, None, 10_000));
        assert!(!rejected.accepted);
        assert_eq!(rejected.reason, "bad-signature");
        assert!(rejected.next_announce_after_seconds > 0, "still back off");

        let r = response(tracker.handle_frame(&query_frame(b"world"), None, None, 10_000));
        assert!(
            r.peers.is_empty(),
            "a rejected announce never reaches the registry"
        );
    }

    #[test]
    fn the_per_ip_quota_refuses_a_flood() {
        let mut tracker = Tracker::new(config());
        let signer = TestSigner::new(1);
        let ip: IpAddr = "203.0.113.1".parse().unwrap();
        for n in 1..=5u64 {
            let frame = signed_frame(&signer, n, AnnounceEvent::Started, false, 10_000);
            assert!(ack(tracker.handle_frame(&frame, Some(ip), None, 10_000)).accepted);
        }
        let frame = signed_frame(&signer, 6, AnnounceEvent::Started, false, 10_000);
        let refused = ack(tracker.handle_frame(&frame, Some(ip), None, 10_000));
        assert!(!refused.accepted);
        assert_eq!(refused.reason, "quota");
    }

    #[test]
    fn a_stopped_announce_removes_the_peer_immediately() {
        let mut tracker = Tracker::new(config());
        let signer = TestSigner::new(1);
        tracker.handle_frame(
            &signed_frame(&signer, 1, AnnounceEvent::Started, true, 10_000),
            None,
            None,
            10_000,
        );
        tracker.handle_frame(
            &signed_frame(&signer, 1, AnnounceEvent::Stopped, true, 11_000),
            None,
            None,
            11_000,
        );
        let r = response(tracker.handle_frame(&query_frame(b"world"), None, None, 11_000));
        assert!(r.peers.is_empty());
    }

    #[test]
    fn silence_past_the_ttl_expires_a_peer_and_starts_the_countdown_surface() {
        let mut tracker = Tracker::new(config());
        let signer = TestSigner::new(1);
        tracker.handle_frame(
            &signed_frame(&signer, 1, AnnounceEvent::Started, true, 10_000),
            None,
            None,
            10_000,
        );
        tracker.set_world_metadata(b"world", "w".to_owned(), 200_000, 10_000);

        let later = 10_000 + 60_001;
        let r = response(tracker.handle_frame(&query_frame(b"world"), None, None, later));
        assert!(r.peers.is_empty(), "expired on read, before the sweep runs");
        assert_eq!(
            r.health,
            WorldHealth::Degraded,
            "inside the retention window"
        );
        assert_eq!(r.retention_deadline_epoch_millis, 200_000);

        assert_eq!(tracker.sweep(later), 1);

        let dead = response(tracker.handle_frame(&query_frame(b"world"), None, None, 300_000));
        assert_eq!(
            dead.health,
            WorldHealth::Dead,
            "countdown expired with no seeder"
        );
        assert_eq!(dead.world_name, "w");
    }

    #[test]
    fn a_returning_seeder_cancels_the_death_verdict() {
        let mut tracker = Tracker::new(config());
        tracker.set_world_metadata(b"world", "w".to_owned(), 200_000, 0);
        let dead = response(tracker.handle_frame(&query_frame(b"world"), None, None, 300_000));
        assert_eq!(dead.health, WorldHealth::Dead);

        let signer = TestSigner::new(1);
        tracker.handle_frame(
            &signed_frame(&signer, 1, AnnounceEvent::Started, true, 300_000),
            None,
            None,
            300_000,
        );
        let alive = response(tracker.handle_frame(&query_frame(b"world"), None, None, 300_000));
        assert_eq!(alive.health, WorldHealth::Healthy);
    }

    #[test]
    fn an_oversized_frame_is_refused_before_decoding() {
        let mut tracker = Tracker::new(Config {
            max_frame_bytes: 16,
            ..config()
        });
        let frame = signed_frame(
            &TestSigner::new(1),
            1,
            AnnounceEvent::Started,
            false,
            10_000,
        );
        assert!(frame.len() > 16);
        assert_eq!(
            ack(tracker.handle_frame(&frame, None, None, 10_000)).reason,
            "too-large"
        );
    }

    #[test]
    fn garbage_and_unserved_tags_do_not_crash_the_service() {
        let mut tracker = Tracker::new(config());
        assert!(matches!(
            tracker.handle_frame(&[0xFF, 0xFF, 0x00], None, None, 0),
            Handled::Unsupported(_)
        ));
        let inventory = DiscoveryMessage::InventoryAdvertisement(
            nodera_codec::messages::InventoryAdvertisement {
                genesis_hash: vec![1; 32],
                holder: nodera_codec::types::NodeId::new(0, 1),
                holdings: vec![],
            },
        )
        .encode();
        assert!(matches!(
            tracker.handle_frame(&inventory, None, None, 0),
            Handled::Unsupported(_)
        ));
    }

    #[test]
    fn queries_for_an_unknown_world_answer_instead_of_hanging() {
        let mut tracker = Tracker::new(config());
        let r = response(tracker.handle_frame(&query_frame(b"nobody-here"), None, None, 0));
        assert_eq!(r.genesis_hash, b"nobody-here".to_vec());
        assert!(r.peers.is_empty());
    }

    // --- the service directory, driven through the real dispatch path ---

    const SERVICE_NOW: u64 = 1_700_000_000_000;

    fn service_ack(handled: Handled) -> ServiceAnnounceAck {
        match handled {
            Handled::Reply(bytes) => match ServiceMessage::decode(&bytes).expect("decodable ack") {
                ServiceMessage::AnnounceAck(ack) => ack,
                other => panic!("expected an announce ack, got tag {}", other.tag()),
            },
            Handled::Unsupported(reason) => panic!("unsupported: {reason}"),
        }
    }

    fn service_directory(handled: Handled) -> Vec<ServiceDirectoryEntry> {
        match handled {
            Handled::Reply(bytes) => {
                match ServiceMessage::decode(&bytes).expect("decodable answer") {
                    ServiceMessage::DirectoryResponse(response) => response.entries,
                    other => panic!("expected a directory response, got tag {}", other.tag()),
                }
            }
            Handled::Unsupported(reason) => panic!("unsupported: {reason}"),
        }
    }

    fn announce_service_frame(
        record: &nodera_codec::service::ServiceRecord,
        signature: &[u8],
    ) -> Vec<u8> {
        ServiceMessage::Announce(nodera_codec::service::ServiceAnnounce {
            record: record.clone(),
            signature: signature.to_vec(),
        })
        .encode()
    }

    fn directory_query_frame(kind: ServiceKind) -> Vec<u8> {
        ServiceMessage::DirectoryQuery(nodera_codec::service::ServiceDirectoryQuery {
            kind,
            network_id: nodera_codec::types::NetworkId::new(0, 0),
            limit: 0,
        })
        .encode()
    }

    fn signed_report(
        signer: &crate::test_support::TestSigner,
        service: u64,
        probes: u32,
        successes: u32,
        rtt_p95: u32,
        now_millis: u64,
    ) -> Vec<u8> {
        use nodera_codec::service::{ServiceObservation, ServiceScoreReport};
        let mut report = ServiceScoreReport {
            reporter: nodera_codec::types::NodeId::new(900, service),
            public_key: signer.x509_public_key(),
            network_id: nodera_codec::types::NetworkId::new(0, 0),
            observations: vec![ServiceObservation {
                service: nodera_codec::types::NodeId::new(service, service),
                kind: ServiceKind::Rendezvous,
                probes,
                successes,
                rtt_p50_millis: rtt_p95 / 2,
                rtt_p95_millis: rtt_p95,
                observed_at_epoch_millis: now_millis,
            }],
            report_epoch_millis: now_millis,
            signature: Vec::new(),
        };
        report.signature = signer.sign(&report.signed_portion());
        ServiceMessage::ScoreReport(report).encode()
    }

    #[test]
    fn a_rendezvous_announces_itself_and_peers_can_then_discover_it() {
        // The whole point of the lane: a peer with nothing configured but this tracker can find a
        // rendezvous it was never told about.
        let mut tracker = Tracker::new(config());
        let signer = crate::test_support::TestSigner::new(7);
        let (record, signature) =
            crate::test_support::service_record(&signer, 11, SERVICE_NOW, |_| {});
        let ack = service_ack(tracker.handle_frame(
            &announce_service_frame(&record, &signature),
            None,
            None,
            SERVICE_NOW,
        ));
        assert!(ack.accepted, "reason: {}", ack.reason);
        assert!(
            ack.next_announce_after_seconds > 0,
            "the ack must pace the announcer"
        );

        let listed = service_directory(tracker.handle_frame(
            &directory_query_frame(ServiceKind::Rendezvous),
            None,
            None,
            SERVICE_NOW,
        ));
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].record.routes, record.routes);
        // Verifiable without trusting this tracker.
        nodera_codec::sig::verify(
            &listed[0].record.public_key,
            &listed[0].record.signed_bytes(),
            &listed[0].signature,
        )
        .unwrap();
    }

    /// A stop for a service this tracker never listed is not an admission.
    ///
    /// The relay's own log says "announced, accepted", so an `accepted: true` here made a service
    /// that had never reached this tracker — or whose records were expiring between announces —
    /// indistinguishable from one that was listed the whole time.
    #[test]
    fn a_stop_for_an_id_the_tracker_never_held_is_not_acked_as_accepted() {
        let mut tracker = Tracker::new(config());
        let signer = crate::test_support::TestSigner::new(7);
        let (record, signature) =
            crate::test_support::service_record(&signer, 11, SERVICE_NOW, |r| {
                r.lifecycle = nodera_codec::service::ServiceLifecycle::Stopped;
                r.drain_deadline_epoch_millis = SERVICE_NOW + 30_000;
            });
        let ack = service_ack(tracker.handle_frame(
            &announce_service_frame(&record, &signature),
            None,
            None,
            SERVICE_NOW,
        ));
        assert!(!ack.accepted);
        assert_eq!(ack.reason, "not-listed");

        // And the ordinary path still acks plainly: a stop for a service that *was* listed.
        let (serving, serving_signature) =
            crate::test_support::service_record(&signer, 11, SERVICE_NOW, |_| {});
        assert!(
            service_ack(tracker.handle_frame(
                &announce_service_frame(&serving, &serving_signature),
                None,
                None,
                SERVICE_NOW,
            ))
            .accepted
        );
        let removed = service_ack(tracker.handle_frame(
            &announce_service_frame(&record, &signature),
            None,
            None,
            SERVICE_NOW,
        ));
        assert!(removed.accepted, "reason: {}", removed.reason);
        assert_eq!(removed.reason, "");
    }

    #[test]
    fn a_tampered_service_record_is_refused_and_never_listed() {
        let mut tracker = Tracker::new(config());
        let signer = crate::test_support::TestSigner::new(7);
        let (record, signature) =
            crate::test_support::service_record(&signer, 11, SERVICE_NOW, |_| {});
        let moved = nodera_codec::service::ServiceRecord {
            routes: vec!["attacker.example:25601".to_owned()],
            ..record
        };
        let ack = service_ack(tracker.handle_frame(
            &announce_service_frame(&moved, &signature),
            None,
            None,
            SERVICE_NOW,
        ));
        assert!(!ack.accepted);
        assert_eq!(ack.reason, "bad-signature");
        assert!(service_directory(tracker.handle_frame(
            &directory_query_frame(ServiceKind::Rendezvous),
            None,
            None,
            SERVICE_NOW
        ))
        .is_empty());
    }

    #[test]
    fn an_announcing_service_is_told_about_its_siblings_but_not_itself() {
        // This is what lets a draining rendezvous name replacements without a second round trip.
        let mut tracker = Tracker::new(config());
        for (seed, id) in [(1u8, 11u64), (2, 12), (3, 13)] {
            let signer = crate::test_support::TestSigner::new(seed);
            let (record, signature) =
                crate::test_support::service_record(&signer, id, SERVICE_NOW, |_| {});
            let ack = service_ack(tracker.handle_frame(
                &announce_service_frame(&record, &signature),
                None,
                None,
                SERVICE_NOW,
            ));
            assert!(ack.accepted);
            assert!(
                ack.directory
                    .iter()
                    .all(|entry| entry.record.service != record.service),
                "a service does not need to be told about itself"
            );
        }
        // The third announcer sees the two that came before it.
        let signer = crate::test_support::TestSigner::new(3);
        let (record, signature) =
            crate::test_support::service_record(&signer, 13, SERVICE_NOW, |_| {});
        let ack = service_ack(tracker.handle_frame(
            &announce_service_frame(&record, &signature),
            None,
            None,
            SERVICE_NOW,
        ));
        assert_eq!(ack.directory.len(), 2);
    }

    #[test]
    fn a_signed_score_report_moves_the_published_ordering() {
        let mut tracker = Tracker::new(config());
        for (seed, id) in [(1u8, 11u64), (2, 12)] {
            let signer = crate::test_support::TestSigner::new(seed);
            let (record, signature) =
                crate::test_support::service_record(&signer, id, SERVICE_NOW, |_| {});
            assert!(
                service_ack(tracker.handle_frame(
                    &announce_service_frame(&record, &signature),
                    None,
                    None,
                    SERVICE_NOW
                ))
                .accepted
            );
        }
        let reporter = crate::test_support::TestSigner::new(9);
        assert!(
            service_ack(tracker.handle_frame(
                &signed_report(&reporter, 11, 20, 2, 40, SERVICE_NOW),
                None,
                None,
                SERVICE_NOW
            ))
            .accepted
        );
        assert!(
            service_ack(tracker.handle_frame(
                &signed_report(&reporter, 12, 20, 20, 40, SERVICE_NOW),
                None,
                None,
                SERVICE_NOW
            ))
            .accepted
        );

        let listed = service_directory(tracker.handle_frame(
            &directory_query_frame(ServiceKind::Rendezvous),
            None,
            None,
            SERVICE_NOW,
        ));
        assert_eq!(
            listed[0].record.service,
            nodera_codec::types::NodeId::new(12, 12),
            "the rendezvous peers could actually reach ranks first"
        );
        assert_eq!(listed[0].score.availability_permille, 1_000);
        assert_eq!(listed[1].score.availability_permille, 100);
        assert_eq!(tracker.report_counts(), (2, 0));
    }

    #[test]
    fn an_unsigned_score_report_is_refused() {
        // Otherwise scoring is the cheapest attack in the system: report every rival relay as dead.
        let mut tracker = Tracker::new(config());
        let signer = crate::test_support::TestSigner::new(1);
        let (record, signature) =
            crate::test_support::service_record(&signer, 11, SERVICE_NOW, |_| {});
        assert!(
            service_ack(tracker.handle_frame(
                &announce_service_frame(&record, &signature),
                None,
                None,
                SERVICE_NOW
            ))
            .accepted
        );

        let reporter = crate::test_support::TestSigner::new(9);
        let mut frame = signed_report(&reporter, 11, 20, 0, 40, SERVICE_NOW);
        // Corrupt the last signature byte: the frame still decodes, so only verification can catch it.
        let last = frame.len() - 1;
        frame[last] ^= 0xFF;
        let ack = service_ack(tracker.handle_frame(&frame, None, None, SERVICE_NOW));
        assert!(!ack.accepted);
        assert_eq!(ack.reason, "bad-signature");
        let listed = service_directory(tracker.handle_frame(
            &directory_query_frame(ServiceKind::Rendezvous),
            None,
            None,
            SERVICE_NOW,
        ));
        assert_eq!(
            listed[0].score.reporter_count, 0,
            "a refused report must not reach the aggregate"
        );
    }

    #[test]
    fn a_stale_score_report_is_refused() {
        let mut tracker = Tracker::new(config());
        let signer = crate::test_support::TestSigner::new(1);
        let (record, signature) =
            crate::test_support::service_record(&signer, 11, SERVICE_NOW, |_| {});
        assert!(
            service_ack(tracker.handle_frame(
                &announce_service_frame(&record, &signature),
                None,
                None,
                SERVICE_NOW
            ))
            .accepted
        );
        let reporter = crate::test_support::TestSigner::new(9);
        let old = SERVICE_NOW - tracker.config().clock_skew_millis() - 1;
        let ack = service_ack(tracker.handle_frame(
            &signed_report(&reporter, 11, 20, 0, 40, old),
            None,
            None,
            SERVICE_NOW,
        ));
        assert!(!ack.accepted);
        assert_eq!(ack.reason, "stale-record");
    }

    #[test]
    fn a_drain_announcement_is_visible_to_a_querying_peer_with_its_deadline() {
        // A peer that lost its rendezvous can see why and when, instead of watching it vanish.
        let mut tracker = Tracker::new(config());
        let signer = crate::test_support::TestSigner::new(4);
        let (serving, serving_sig) =
            crate::test_support::service_record(&signer, 21, SERVICE_NOW, |_| {});
        assert!(
            service_ack(tracker.handle_frame(
                &announce_service_frame(&serving, &serving_sig),
                None,
                None,
                SERVICE_NOW
            ))
            .accepted
        );
        let (draining, draining_sig) =
            crate::test_support::service_record(&signer, 21, SERVICE_NOW, |r| {
                r.lifecycle = nodera_codec::service::ServiceLifecycle::Draining;
                r.drain_deadline_epoch_millis = SERVICE_NOW + 30_000;
            });
        assert!(
            service_ack(tracker.handle_frame(
                &announce_service_frame(&draining, &draining_sig),
                None,
                None,
                SERVICE_NOW
            ))
            .accepted
        );

        let listed = service_directory(tracker.handle_frame(
            &directory_query_frame(ServiceKind::Rendezvous),
            None,
            None,
            SERVICE_NOW,
        ));
        assert_eq!(
            listed[0].record.lifecycle,
            nodera_codec::service::ServiceLifecycle::Draining
        );
        assert_eq!(
            listed[0].record.drain_deadline_epoch_millis,
            SERVICE_NOW + 30_000
        );
        assert_eq!(tracker.service_counts(), (1, 1, 0));
    }

    #[test]
    fn a_report_flood_cannot_starve_the_announce_budget() {
        // Separate quotas: reports are cheap to send and expensive to aggregate, so sharing the
        // announce budget would let a flood push the world list off the air.
        let mut tracker = Tracker::new(Config {
            per_ip_announce_quota: 2,
            per_ip_report_quota: 1,
            ..config()
        });
        let signer = crate::test_support::TestSigner::new(1);
        let (record, signature) =
            crate::test_support::service_record(&signer, 11, SERVICE_NOW, |_| {});
        let ip: IpAddr = "198.51.100.9".parse().unwrap();
        assert!(
            service_ack(tracker.handle_frame(
                &announce_service_frame(&record, &signature),
                Some(ip),
                None,
                SERVICE_NOW
            ))
            .accepted
        );

        let reporter = crate::test_support::TestSigner::new(9);
        assert!(
            service_ack(tracker.handle_frame(
                &signed_report(&reporter, 11, 5, 5, 20, SERVICE_NOW),
                Some(ip),
                None,
                SERVICE_NOW
            ))
            .accepted
        );
        let flooded = service_ack(tracker.handle_frame(
            &signed_report(&reporter, 11, 5, 5, 20, SERVICE_NOW),
            Some(ip),
            None,
            SERVICE_NOW,
        ));
        assert!(!flooded.accepted);
        assert_eq!(flooded.reason, "quota");
        // The announce budget is untouched by the report flood.
        assert!(
            service_ack(tracker.handle_frame(
                &announce_service_frame(&record, &signature),
                Some(ip),
                None,
                SERVICE_NOW
            ))
            .accepted
        );
    }

    #[test]
    fn a_drain_notice_sent_to_a_tracker_is_not_served() {
        // A tracker holds no peer control channels, so receiving one means somebody is talking to the
        // wrong port — answered as unsupported rather than silently absorbed.
        let mut tracker = Tracker::new(config());
        let signer = crate::test_support::TestSigner::new(1);
        let (record, signature) =
            crate::test_support::service_record(&signer, 11, SERVICE_NOW, |r| {
                r.lifecycle = nodera_codec::service::ServiceLifecycle::Draining;
                r.drain_deadline_epoch_millis = SERVICE_NOW + 1_000;
            });
        let frame = ServiceMessage::DrainNotice(nodera_codec::service::ServiceDrainNotice {
            record,
            signature,
            replacements: Vec::new(),
            reason: "update".to_owned(),
        })
        .encode();
        assert!(matches!(
            tracker.handle_frame(&frame, None, None, SERVICE_NOW),
            Handled::Unsupported(_)
        ));
    }

    #[test]
    fn an_expired_service_record_stops_being_answered() {
        let mut tracker = Tracker::new(config());
        let signer = crate::test_support::TestSigner::new(1);
        let (record, signature) =
            crate::test_support::service_record(&signer, 11, SERVICE_NOW, |_| {});
        assert!(
            service_ack(tracker.handle_frame(
                &announce_service_frame(&record, &signature),
                None,
                None,
                SERVICE_NOW
            ))
            .accepted
        );
        let later = SERVICE_NOW + 300_001;
        assert!(service_directory(tracker.handle_frame(
            &directory_query_frame(ServiceKind::Rendezvous),
            None,
            None,
            later
        ))
        .is_empty());
        tracker.sweep(later);
        assert_eq!(tracker.service_counts(), (0, 0, 0));
    }
}
