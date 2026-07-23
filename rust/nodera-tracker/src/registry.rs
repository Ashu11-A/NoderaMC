//! The swarm registry: which peers announced to which world, and what they hold.
//!
//! State is deliberately ephemeral (`docs/torrent/trackers.md` §23). A tracker restart loses
//! nothing that matters: peers re-announce within one interval. Nothing here is authoritative —
//! it is a directory of claims that peers verify for themselves.

use nodera_codec::messages::TrackerAnnounce;
use nodera_codec::types::{ManifestHolding, NodeCapabilities, NodeId};
use std::collections::HashMap;

/// One peer's current claim about itself on one world.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerRecord {
    /// Routes the peer advertises, in preference order.
    pub routes: Vec<String>,
    /// The address the announce actually came from — appended as a low-priority hint, never
    /// treated as proof of identity (`trackers.md` §5).
    pub observed_route: Option<String>,
    /// The peer's declared capabilities (roles included).
    pub capabilities: NodeCapabilities,
    /// Which pieces of which manifests the peer holds for this world.
    pub holdings: Vec<ManifestHolding>,
    /// Self-reported reliability in basis points.
    pub reliability_bps: u32,
    /// Tracker-clock millis of the last accepted announce.
    pub last_seen_millis: u64,
}

impl PeerRecord {
    /// Whether the peer claims a seeding role.
    pub fn is_seeder(&self) -> bool {
        self.capabilities.roles.iter().any(|r| r.is_seeder())
    }

    /// The peer's dial routes with the observed address appended last.
    pub fn dial_routes(&self) -> Vec<String> {
        let mut routes = self.routes.clone();
        if let Some(observed) = &self.observed_route {
            if !routes.iter().any(|r| r == observed) {
                routes.push(observed.clone());
            }
        }
        routes
    }
}

/// One world's peers plus the host-supplied display metadata.
#[derive(Debug, Clone, Default)]
pub struct Swarm {
    /// Display name, as registered by a host. Names decorate a world; the genesis hash identifies
    /// it.
    pub world_name: String,
    /// Task 22 drop deadline surfaced to the UI, or `0` when no countdown is running.
    pub retention_deadline_epoch_millis: u64,
    /// Current records, keyed by identity — never by address.
    pub peers: HashMap<NodeId, PeerRecord>,
    /// Tracker-clock millis of the last activity, used to shed idle worlds first.
    pub last_activity_millis: u64,
}

impl Swarm {
    /// Peers whose last announce is within `ttl_millis` of `now_millis`.
    ///
    /// Expiry is applied lazily on read as well as by the sweep, so a query never reports a peer
    /// the sweep has not reached yet.
    pub fn live_peers(&self, now_millis: u64, ttl_millis: u64) -> Vec<(&NodeId, &PeerRecord)> {
        let mut live: Vec<(&NodeId, &PeerRecord)> = self
            .peers
            .iter()
            .filter(|(_, record)| !is_expired(record.last_seen_millis, now_millis, ttl_millis))
            .collect();
        // HashMap order is unspecified; sorting makes every response reproducible for a given
        // state, which is what makes the sampling tests meaningful.
        live.sort_by_key(|(id, _)| (id.msb, id.lsb));
        live
    }
}

/// Whether a record last seen at `last_seen_millis` has aged out.
///
/// A record from the future (clock skew on either side) is never treated as expired — punishing a
/// peer for the tracker's clock being behind would evict healthy peers.
pub fn is_expired(last_seen_millis: u64, now_millis: u64, ttl_millis: u64) -> bool {
    now_millis.saturating_sub(last_seen_millis) > ttl_millis
}

/// All swarms known to this tracker.
#[derive(Debug, Default)]
pub struct Registry {
    swarms: HashMap<Vec<u8>, Swarm>,
}

/// What an announce did to the registry — the caller turns this into an ack and a log line.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AnnounceOutcome {
    /// A new record was created.
    Registered,
    /// An existing record was replaced/refreshed.
    Refreshed,
    /// The record was removed (`STOPPED`).
    Removed,
    /// `STOPPED` for a peer that was not registered — harmless, still acknowledged.
    NotPresent,
    /// The world is at `max_peers_per_world` and this peer is not already in it.
    WorldFull,
    /// The tracker is at `max_worlds` and this world is not already tracked.
    WorldLimit,
}

impl Registry {
    /// An empty registry.
    pub fn new() -> Self {
        Self::default()
    }

    /// How many worlds are tracked.
    pub fn world_count(&self) -> usize {
        self.swarms.len()
    }

    /// Look up a world.
    pub fn swarm(&self, genesis_hash: &[u8]) -> Option<&Swarm> {
        self.swarms.get(genesis_hash)
    }

    /// Every tracked world, for the catalog listing. Iteration order is unspecified
    /// (`HashMap`) — the catalog builder sorts before answering.
    pub fn swarms(&self) -> impl Iterator<Item = (&Vec<u8>, &Swarm)> {
        self.swarms.iter()
    }

    /// Register or update a world's host-supplied display metadata.
    ///
    /// Called from the announce path only for a `FULL_ARCHIVE` host: names decorate a world, and
    /// letting any announcer set one would rename other people's worlds in every server list.
    pub fn set_world_metadata(
        &mut self,
        genesis_hash: &[u8],
        world_name: String,
        retention_deadline_epoch_millis: u64,
        now_millis: u64,
    ) {
        let swarm = self.swarms.entry(genesis_hash.to_vec()).or_default();
        swarm.world_name = world_name;
        swarm.retention_deadline_epoch_millis = retention_deadline_epoch_millis;
        swarm.last_activity_millis = now_millis;
    }

    /// Apply a verified announce.
    ///
    /// The caller has already checked the signature, the freshness window, and the quotas: this
    /// function is pure registry bookkeeping so it can be unit-tested without sockets or clocks.
    pub fn apply_announce(
        &mut self,
        announce: &TrackerAnnounce,
        observed_route: Option<String>,
        now_millis: u64,
        max_worlds: usize,
        max_peers_per_world: usize,
        ttl_millis: u64,
    ) -> AnnounceOutcome {
        use nodera_codec::messages::AnnounceEvent;

        let key = announce.genesis_hash.clone();
        if announce.event == AnnounceEvent::Stopped {
            let Some(swarm) = self.swarms.get_mut(&key) else {
                return AnnounceOutcome::NotPresent;
            };
            let removed = swarm.peers.remove(&announce.peer).is_some();
            swarm.last_activity_millis = now_millis;
            return if removed {
                AnnounceOutcome::Removed
            } else {
                AnnounceOutcome::NotPresent
            };
        }

        if !self.swarms.contains_key(&key)
            && self.swarms.len() >= max_worlds
            && !self.shed_idlest_world(now_millis, ttl_millis)
        {
            return AnnounceOutcome::WorldLimit;
        }
        let swarm = self.swarms.entry(key).or_default();
        swarm.last_activity_millis = now_millis;

        let existing = swarm.peers.contains_key(&announce.peer);
        if !existing && swarm.peers.len() >= max_peers_per_world {
            // Drop the oldest expired record first; only refuse if every slot is live.
            let victim = swarm
                .peers
                .iter()
                .filter(|(_, r)| is_expired(r.last_seen_millis, now_millis, ttl_millis))
                .min_by_key(|(_, r)| r.last_seen_millis)
                .map(|(id, _)| *id);
            match victim {
                Some(id) => {
                    swarm.peers.remove(&id);
                }
                None => return AnnounceOutcome::WorldFull,
            }
        }

        // A record replaces the previous record for the same identity: the newest signed claim
        // wins, so a peer that moves address or drops a manifest is never merged with its past.
        swarm.peers.insert(
            announce.peer,
            PeerRecord {
                routes: announce.routes.clone(),
                observed_route,
                capabilities: announce.capabilities.clone(),
                holdings: announce.holdings.clone(),
                reliability_bps: announce.reliability_bps,
                last_seen_millis: now_millis,
            },
        );

        if existing {
            AnnounceOutcome::Refreshed
        } else {
            AnnounceOutcome::Registered
        }
    }

    /// Drop expired records; returns how many were removed.
    ///
    /// Worlds that end up empty are kept only if a host registered metadata for them — an empty
    /// named world still answers queries (with a countdown), which is exactly what the UI needs
    /// while a host reboots.
    pub fn sweep(&mut self, now_millis: u64, ttl_millis: u64) -> usize {
        let mut removed = 0;
        for swarm in self.swarms.values_mut() {
            let before = swarm.peers.len();
            swarm
                .peers
                .retain(|_, r| !is_expired(r.last_seen_millis, now_millis, ttl_millis));
            removed += before - swarm.peers.len();
        }
        self.swarms
            .retain(|_, s| !s.peers.is_empty() || !s.world_name.is_empty());
        removed
    }

    /// Evict the least recently active world that holds no live peers.
    fn shed_idlest_world(&mut self, now_millis: u64, ttl_millis: u64) -> bool {
        let victim = self
            .swarms
            .iter()
            .filter(|(_, s)| s.live_peers(now_millis, ttl_millis).is_empty())
            .min_by_key(|(_, s)| s.last_activity_millis)
            .map(|(k, _)| k.clone());
        match victim {
            Some(key) => {
                self.swarms.remove(&key);
                true
            }
            None => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{announce, caps, seeder_caps};
    use nodera_codec::messages::AnnounceEvent;

    const TTL: u64 = 300_000;

    fn apply(registry: &mut Registry, a: &TrackerAnnounce, now: u64) -> AnnounceOutcome {
        registry.apply_announce(a, None, now, 100, 100, TTL)
    }

    #[test]
    fn started_registers_and_a_second_announce_refreshes_in_place() {
        let mut registry = Registry::new();
        let first = announce(1, b"world", AnnounceEvent::Started, seeder_caps());
        assert_eq!(
            apply(&mut registry, &first, 1_000),
            AnnounceOutcome::Registered
        );

        let mut second = announce(1, b"world", AnnounceEvent::Heartbeat, seeder_caps());
        second.routes = vec!["10.0.0.9:25599".to_owned()];
        assert_eq!(
            apply(&mut registry, &second, 2_000),
            AnnounceOutcome::Refreshed
        );

        let swarm = registry.swarm(b"world").unwrap();
        assert_eq!(
            swarm.peers.len(),
            1,
            "a re-announce replaces, never duplicates"
        );
        let record = swarm.peers.values().next().unwrap();
        assert_eq!(record.routes, vec!["10.0.0.9:25599".to_owned()]);
        assert_eq!(record.last_seen_millis, 2_000);
    }

    #[test]
    fn stopped_removes_immediately_and_is_harmless_when_unknown() {
        let mut registry = Registry::new();
        apply(
            &mut registry,
            &announce(1, b"world", AnnounceEvent::Started, caps()),
            1_000,
        );
        assert_eq!(
            apply(
                &mut registry,
                &announce(1, b"world", AnnounceEvent::Stopped, caps()),
                1_500
            ),
            AnnounceOutcome::Removed
        );
        assert!(registry.swarm(b"world").unwrap().peers.is_empty());
        assert_eq!(
            apply(
                &mut registry,
                &announce(1, b"world", AnnounceEvent::Stopped, caps()),
                1_600
            ),
            AnnounceOutcome::NotPresent
        );
    }

    #[test]
    fn a_silent_peer_expires_after_the_ttl() {
        let mut registry = Registry::new();
        apply(
            &mut registry,
            &announce(1, b"world", AnnounceEvent::Started, caps()),
            1_000,
        );
        // Still live one millisecond before the deadline; gone one millisecond after.
        assert_eq!(
            registry
                .swarm(b"world")
                .unwrap()
                .live_peers(1_000 + TTL, TTL)
                .len(),
            1
        );
        assert!(registry
            .swarm(b"world")
            .unwrap()
            .live_peers(1_001 + TTL, TTL)
            .is_empty());
        assert_eq!(registry.sweep(1_001 + TTL, TTL), 1);
    }

    #[test]
    fn worlds_are_isolated() {
        let mut registry = Registry::new();
        apply(
            &mut registry,
            &announce(1, b"alpha", AnnounceEvent::Started, caps()),
            1_000,
        );
        apply(
            &mut registry,
            &announce(2, b"beta", AnnounceEvent::Started, caps()),
            1_000,
        );
        assert_eq!(registry.swarm(b"alpha").unwrap().peers.len(), 1);
        assert_eq!(registry.swarm(b"beta").unwrap().peers.len(), 1);
        assert_eq!(registry.world_count(), 2);
    }

    #[test]
    fn a_full_world_refuses_new_peers_but_still_refreshes_known_ones() {
        let mut registry = Registry::new();
        let a = announce(1, b"world", AnnounceEvent::Started, caps());
        registry.apply_announce(&a, None, 1_000, 10, 1, TTL);

        let b = announce(2, b"world", AnnounceEvent::Started, caps());
        assert_eq!(
            registry.apply_announce(&b, None, 1_000, 10, 1, TTL),
            AnnounceOutcome::WorldFull
        );
        assert_eq!(
            registry.apply_announce(&a, None, 2_000, 10, 1, TTL),
            AnnounceOutcome::Refreshed
        );
    }

    #[test]
    fn a_full_world_reuses_an_expired_slot_rather_than_refusing() {
        let mut registry = Registry::new();
        let a = announce(1, b"world", AnnounceEvent::Started, caps());
        registry.apply_announce(&a, None, 1_000, 10, 1, TTL);

        let b = announce(2, b"world", AnnounceEvent::Started, caps());
        let now = 1_000 + TTL + 1;
        assert_eq!(
            registry.apply_announce(&b, None, now, 10, 1, TTL),
            AnnounceOutcome::Registered
        );
        assert_eq!(registry.swarm(b"world").unwrap().peers.len(), 1);
    }

    #[test]
    fn the_world_limit_sheds_an_idle_world_before_refusing() {
        let mut registry = Registry::new();
        registry.apply_announce(
            &announce(1, b"idle", AnnounceEvent::Started, caps()),
            None,
            1_000,
            1,
            10,
            TTL,
        );
        // The idle world's only peer has expired, so it may be shed for a new world.
        let now = 1_000 + TTL + 1;
        assert_eq!(
            registry.apply_announce(
                &announce(2, b"fresh", AnnounceEvent::Started, caps()),
                None,
                now,
                1,
                10,
                TTL
            ),
            AnnounceOutcome::Registered
        );
        assert!(registry.swarm(b"idle").is_none());

        // With the new world live, a third world cannot displace it.
        assert_eq!(
            registry.apply_announce(
                &announce(3, b"third", AnnounceEvent::Started, caps()),
                None,
                now,
                1,
                10,
                TTL
            ),
            AnnounceOutcome::WorldLimit
        );
    }

    #[test]
    fn the_observed_address_is_appended_as_a_hint_not_substituted() {
        let mut registry = Registry::new();
        let a = announce(1, b"world", AnnounceEvent::Started, caps());
        registry.apply_announce(&a, Some("203.0.113.9:40000".to_owned()), 1_000, 10, 10, TTL);
        let record = registry
            .swarm(b"world")
            .unwrap()
            .peers
            .values()
            .next()
            .unwrap();
        let routes = record.dial_routes();
        assert_eq!(
            routes.first().unwrap(),
            &a.routes[0],
            "claimed route stays first"
        );
        assert_eq!(routes.last().unwrap(), "203.0.113.9:40000");
    }

    #[test]
    fn a_record_from_the_future_is_not_treated_as_expired() {
        assert!(!is_expired(10_000, 1_000, TTL));
    }
}
