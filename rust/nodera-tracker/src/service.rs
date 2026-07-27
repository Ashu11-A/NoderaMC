//! The tracker's request handling, with no IO in it.
//!
//! Sockets, clocks and tasks live in [`crate::wire`]; everything that decides *what the answer is*
//! lives here and takes `now_millis` as a parameter. That split is what makes the whole admission
//! path — signatures, freshness, quotas, expiry, health transitions — unit-testable without
//! spawning a listener or sleeping.

use crate::announce::{self, IdentityBindings, Rejection};
use crate::config::Config;
use crate::deletion::DeletedWorlds;
use crate::limits::AnnounceQuota;
use crate::query;
use crate::registry::{AnnounceOutcome, Registry};
use nodera_codec::messages::{AnnounceEvent, DiscoveryMessage, TrackerAnnounceAck};
use nodera_codec::tags::message_tags;
use nodera_codec::tombstone::WorldDeletionGossip;
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
        Self {
            config,
            registry: Registry::new(),
            bindings: IdentityBindings::new(),
            deleted: DeletedWorlds::new(
                config_persist_dir.map(|dir| dir.join("deleted-worlds")),
            ),
            quota,
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

    /// Whether this tracker holds a verified deletion for a world.
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
        // Deletions are their own family and are handled before the discovery decode, because the
        // discovery codec does not know tag 66 and would reject the frame as unsupported.
        if frame.len() >= 2
            && u16::from_be_bytes([frame[0], frame[1]]) == message_tags::WORLD_DELETION_GOSSIP
        {
            return self.handle_deletion(frame);
        }
        let message = match DiscoveryMessage::decode(frame) {
            Ok(message) => message,
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

    /// Handle a world-deletion request (tag 66).
    ///
    /// The tracker verifies exactly what a peer verifies and has no extra authority: it cannot
    /// delete a world nobody asked it to, and it cannot refuse one whose owner did — beyond
    /// declining to serve, which is not a power over anyone else's copy. A record that does not
    /// verify is dropped and nothing changes.
    fn handle_deletion(&mut self, frame: &[u8]) -> Handled {
        let gossip = match WorldDeletionGossip::decode(frame) {
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
        Handled::Reply(gossip.encode())
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

    /// Expire silent peers, idle quota counters, and deletions past the retention window.
    pub fn sweep(&mut self, now_millis: u64) -> usize {
        self.quota.sweep(now_millis);
        self.deleted.prune(now_millis);
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
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .and_then(std::path::Path::parent)
            .expect("repo root")
            .join("fixtures/wire/world-deletion-gossip.bin");
        std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()))
    }

    fn deleted_world_id() -> Vec<u8> {
        WorldDeletionGossip::decode(&deletion_frame())
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
            Handled::Reply(frame) => assert!(
                WorldDeletionGossip::decode(&frame)
                    .expect("decode")
                    .verified()
                    .is_some()
            ),
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
        let handled = tracker.handle_frame(&announce_for_deleted_world(&signer, now), None, None, now);

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
        let issued = WorldDeletionGossip::decode(&deletion_frame())
            .expect("decode")
            .verified()
            .expect("verify")
            .issued_at_epoch as u64;
        tracker.handle_frame(&deletion_frame(), None, None, issued);

        tracker.sweep(issued + crate::deletion::RETENTION_MILLIS);
        assert!(tracker.is_world_deleted(&deleted_world_id()), "still inside");

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
        assert_eq!(r.world_player_count, 1);
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
}
