//! The registration registry: which peers registered in which `(network, world)` namespace.
//!
//! State is ephemeral (rendezvous.md §9.3): a restart loses nothing that matters because peers
//! re-register within one refresh interval. Nothing here is authoritative — it is a directory of
//! self-signed claims that discovering peers verify for themselves (§8.1).

use nodera_codec::rendezvous::SignedRecord;
use nodera_codec::types::{NetworkId, NodeId, RegistrationEvent};
use std::collections::HashMap;

/// A discovery namespace: a network + a world (rendezvous.md §3.1 / Task 29 Context).
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Namespace {
    /// The network id.
    pub network_id: NetworkId,
    /// The world's genesis hash.
    pub genesis_hash: Vec<u8>,
}

impl Namespace {
    /// Build a namespace key.
    pub fn new(network_id: NetworkId, genesis_hash: Vec<u8>) -> Self {
        Self {
            network_id,
            genesis_hash,
        }
    }
}

/// One peer's current registration, with the tracker-clock instant it was last accepted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredRecord {
    /// The signed record and its signature.
    pub signed: SignedRecord,
    /// Service-clock millis of the last accepted register/refresh.
    pub last_seen_millis: u64,
    /// The record's own self-declared expiry (millis); the effective expiry is the sooner of this
    /// and `last_seen + ttl`.
    pub expires_at_millis: u64,
}

impl StoredRecord {
    /// Whether this record has aged out at `now_millis`.
    ///
    /// The effective deadline is the sooner of the service TTL (`last_seen + ttl`) and the record's
    /// own self-declared `expiresAt` — a peer can ask to be forgotten early, and the service caps
    /// how long any single register keeps a record alive.
    pub fn is_expired(&self, now_millis: u64, ttl_millis: u64) -> bool {
        let effective = self
            .last_seen_millis
            .saturating_add(ttl_millis)
            .min(self.expires_at_millis);
        now_millis > effective
    }
}

/// One namespace's records.
#[derive(Debug, Clone, Default)]
pub struct NamespaceEntry {
    /// Current records, keyed by identity — never by address.
    pub records: HashMap<NodeId, StoredRecord>,
    /// Service-clock millis of the last activity, used to shed idle namespaces first.
    pub last_activity_millis: u64,
}

impl NamespaceEntry {
    /// Records not yet expired, in a reproducible order (by node id).
    pub fn live_records(&self, now_millis: u64, ttl_millis: u64) -> Vec<(&NodeId, &StoredRecord)> {
        let mut live: Vec<(&NodeId, &StoredRecord)> = self
            .records
            .iter()
            .filter(|(_, r)| !r.is_expired(now_millis, ttl_millis))
            .collect();
        live.sort_by_key(|(id, _)| (id.msb, id.lsb));
        live
    }
}

/// What a registration did to the registry.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RegisterOutcome {
    /// A new record was created.
    Registered,
    /// An existing record was refreshed/replaced.
    Refreshed,
    /// The record was removed (`Unregister`).
    Removed,
    /// `Unregister` for a peer that was not registered — harmless, still acknowledged.
    NotPresent,
    /// The namespace is at its record limit and this peer is not already in it.
    NamespaceFull,
    /// The service is at its namespace limit and this namespace is not already tracked.
    NamespaceLimit,
}

/// All namespaces known to this service.
#[derive(Debug, Default)]
pub struct Registry {
    namespaces: HashMap<Namespace, NamespaceEntry>,
}

impl Registry {
    /// An empty registry.
    pub fn new() -> Self {
        Self::default()
    }

    /// How many namespaces are tracked.
    pub fn namespace_count(&self) -> usize {
        self.namespaces.len()
    }

    /// Look up a namespace.
    pub fn namespace(&self, ns: &Namespace) -> Option<&NamespaceEntry> {
        self.namespaces.get(ns)
    }

    /// Apply a verified registration.
    ///
    /// The caller has already checked the signature, freshness, quota, and identity binding: this
    /// is pure bookkeeping so it can be unit-tested without sockets or clocks.
    pub fn apply(
        &mut self,
        signed: &SignedRecord,
        now_millis: u64,
        ttl_millis: u64,
        max_namespaces: usize,
        max_records: usize,
    ) -> RegisterOutcome {
        let record = &signed.record;
        let ns = Namespace::new(record.network_id, record.genesis_hash.clone());

        if record.event == RegistrationEvent::Unregister {
            let Some(entry) = self.namespaces.get_mut(&ns) else {
                return RegisterOutcome::NotPresent;
            };
            let removed = entry.records.remove(&record.peer).is_some();
            entry.last_activity_millis = now_millis;
            return if removed {
                RegisterOutcome::Removed
            } else {
                RegisterOutcome::NotPresent
            };
        }

        if !self.namespaces.contains_key(&ns)
            && self.namespaces.len() >= max_namespaces
            && !self.shed_idlest_namespace(now_millis, ttl_millis)
        {
            return RegisterOutcome::NamespaceLimit;
        }
        let entry = self.namespaces.entry(ns).or_default();
        entry.last_activity_millis = now_millis;

        let existing = entry.records.contains_key(&record.peer);
        if !existing && entry.records.len() >= max_records {
            let victim = entry
                .records
                .iter()
                .filter(|(_, r)| r.is_expired(now_millis, ttl_millis))
                .min_by_key(|(_, r)| r.last_seen_millis)
                .map(|(id, _)| *id);
            match victim {
                Some(id) => {
                    entry.records.remove(&id);
                }
                None => return RegisterOutcome::NamespaceFull,
            }
        }

        entry.records.insert(
            record.peer,
            StoredRecord {
                signed: signed.clone(),
                last_seen_millis: now_millis,
                expires_at_millis: record.expires_at_epoch_millis,
            },
        );

        if existing {
            RegisterOutcome::Refreshed
        } else {
            RegisterOutcome::Registered
        }
    }

    /// Drop expired records; returns how many were removed.
    pub fn sweep(&mut self, now_millis: u64, ttl_millis: u64) -> usize {
        let mut removed = 0;
        for entry in self.namespaces.values_mut() {
            let before = entry.records.len();
            entry
                .records
                .retain(|_, r| !r.is_expired(now_millis, ttl_millis));
            removed += before - entry.records.len();
        }
        self.namespaces.retain(|_, e| !e.records.is_empty());
        removed
    }

    /// Evict the least recently active namespace that holds no live records.
    fn shed_idlest_namespace(&mut self, now_millis: u64, ttl_millis: u64) -> bool {
        let victim = self
            .namespaces
            .iter()
            .filter(|(_, e)| e.live_records(now_millis, ttl_millis).is_empty())
            .min_by_key(|(_, e)| e.last_activity_millis)
            .map(|(k, _)| k.clone());
        match victim {
            Some(key) => {
                self.namespaces.remove(&key);
                true
            }
            None => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::signed_record;
    use nodera_codec::types::RegistrationEvent;

    const TTL: u64 = 300_000;
    const NET: NetworkId = NetworkId { msb: 1, lsb: 2 };

    fn apply(reg: &mut Registry, signed: &SignedRecord, now: u64) -> RegisterOutcome {
        reg.apply(signed, now, TTL, 100, 100)
    }

    fn ns() -> Namespace {
        Namespace::new(NET, b"world".to_vec())
    }

    #[test]
    fn register_then_refresh_replaces_in_place() {
        let mut reg = Registry::new();
        let first = signed_record(1, NET, b"world", RegistrationEvent::Register, 0);
        assert_eq!(apply(&mut reg, &first, 1_000), RegisterOutcome::Registered);
        let second = signed_record(1, NET, b"world", RegistrationEvent::Refresh, 0);
        assert_eq!(apply(&mut reg, &second, 2_000), RegisterOutcome::Refreshed);
        assert_eq!(reg.namespace(&ns()).unwrap().records.len(), 1);
    }

    #[test]
    fn unregister_removes_and_is_harmless_when_unknown() {
        let mut reg = Registry::new();
        apply(
            &mut reg,
            &signed_record(1, NET, b"world", RegistrationEvent::Register, 0),
            1_000,
        );
        assert_eq!(
            apply(
                &mut reg,
                &signed_record(1, NET, b"world", RegistrationEvent::Unregister, 0),
                1_500
            ),
            RegisterOutcome::Removed
        );
        assert_eq!(
            apply(
                &mut reg,
                &signed_record(1, NET, b"world", RegistrationEvent::Unregister, 0),
                1_600
            ),
            RegisterOutcome::NotPresent
        );
    }

    #[test]
    fn namespaces_are_isolated_by_network_and_world() {
        let mut reg = Registry::new();
        let other_net = NetworkId::new(9, 9);
        apply(
            &mut reg,
            &signed_record(1, NET, b"alpha", RegistrationEvent::Register, 0),
            1_000,
        );
        apply(
            &mut reg,
            &signed_record(2, NET, b"beta", RegistrationEvent::Register, 0),
            1_000,
        );
        apply(
            &mut reg,
            &signed_record(3, other_net, b"alpha", RegistrationEvent::Register, 0),
            1_000,
        );
        assert_eq!(reg.namespace_count(), 3);
    }

    #[test]
    fn a_silent_record_expires_after_the_ttl() {
        let mut reg = Registry::new();
        apply(
            &mut reg,
            &signed_record(1, NET, b"world", RegistrationEvent::Register, 0),
            1_000,
        );
        assert_eq!(
            reg.namespace(&ns())
                .unwrap()
                .live_records(1_000 + TTL, TTL)
                .len(),
            1
        );
        assert!(reg
            .namespace(&ns())
            .unwrap()
            .live_records(1_001 + TTL, TTL)
            .is_empty());
        assert_eq!(reg.sweep(1_001 + TTL, TTL), 1);
    }

    #[test]
    fn a_self_declared_expiry_before_the_ttl_wins() {
        let mut reg = Registry::new();
        // Record self-expires at 5_000 even though the TTL would keep it to 301_000.
        let signed = signed_record(1, NET, b"world", RegistrationEvent::Register, 5_000);
        apply(&mut reg, &signed, 1_000);
        assert!(reg
            .namespace(&ns())
            .unwrap()
            .live_records(5_001, TTL)
            .is_empty());
    }

    #[test]
    fn the_namespace_limit_sheds_an_idle_namespace_before_refusing() {
        let mut reg = Registry::new();
        reg.apply(
            &signed_record(1, NET, b"idle", RegistrationEvent::Register, 0),
            1_000,
            TTL,
            1,
            10,
        );
        let now = 1_000 + TTL + 1;
        assert_eq!(
            reg.apply(
                &signed_record(2, NET, b"fresh", RegistrationEvent::Register, 0),
                now,
                TTL,
                1,
                10
            ),
            RegisterOutcome::Registered
        );
        assert_eq!(
            reg.apply(
                &signed_record(3, NET, b"third", RegistrationEvent::Register, 0),
                now,
                TTL,
                1,
                10
            ),
            RegisterOutcome::NamespaceLimit
        );
    }
}
