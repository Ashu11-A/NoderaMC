//! Hole-punch coordination (RENDEZVOUS.md §4.6, DCUtR-style).
//!
//! Over an established circuit the two peers exchange observed addresses and agree a shared
//! T-minus; each then attempts a TCP simultaneous-open. The service only *relays* the exchange and
//! stamps a single synchronized go-signal so both sides fire together — it never dials. Failure is
//! not an error: `RELAYED` is a legal steady state (§7), so nothing here can break a working
//! circuit.

use nodera_codec::rendezvous::PunchSync;
use nodera_codec::types::NodeId;
use std::collections::HashMap;

/// A go-signal, once agreed for a circuit, is shared by both directions so the peers fire together.
///
/// The first `PunchSync` that crosses with `go_signal_epoch_millis == 0` is assigned `now + lead`;
/// that value is remembered for the circuit and stamped on every later `PunchSync` for it, so A and
/// B receive an identical T-minus regardless of which side asked first.
#[derive(Debug, Default)]
pub struct PunchCoordinator {
    agreed: HashMap<CircuitKey, u64>,
}

/// The unordered pair of peers a circuit connects — a punch is symmetric, so `(A,B)` and `(B,A)`
/// key the same coordination.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct CircuitKey {
    lo: NodeId,
    hi: NodeId,
}

impl CircuitKey {
    fn of(a: NodeId, b: NodeId) -> Self {
        if (a.msb, a.lsb) <= (b.msb, b.lsb) {
            Self { lo: a, hi: b }
        } else {
            Self { lo: b, hi: a }
        }
    }
}

/// How hard the two ends of a punch are to traverse, as four coarse classes.
///
/// This is the whole geographic-scale reachability question in one label: "of attempts between two
/// hard NATs, what fraction worked?" is the number that decides whether the relay lane can ever be
/// retired. Four classes, and no finer — anything more specific would begin to characterise
/// individual networks rather than the population.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum PairClass {
    EasyEasy,
    EasyHard,
    HardHard,
    Unknown,
}

impl PairClass {
    /// Classify from each side's observed hardness, where `None` means "never registered here".
    pub fn of(a: Option<bool>, b: Option<bool>) -> Self {
        match (a, b) {
            (Some(false), Some(false)) => PairClass::EasyEasy,
            (Some(true), Some(true)) => PairClass::HardHard,
            (Some(_), Some(_)) => PairClass::EasyHard,
            _ => PairClass::Unknown,
        }
    }

    /// The declared telemetry label. `'static`, so it cannot be confused with request data.
    pub fn label(self) -> &'static str {
        match self {
            PairClass::EasyEasy => "easy_easy",
            PairClass::EasyHard => "easy_hard",
            PairClass::HardHard => "hard_hard",
            PairClass::Unknown => "unknown",
        }
    }

    /// Every class, for reporting one row each.
    pub fn all() -> [PairClass; 4] {
        [
            PairClass::EasyEasy,
            PairClass::EasyHard,
            PairClass::HardHard,
            PairClass::Unknown,
        ]
    }
}

/// Punch outcomes, counted per pair class.
///
/// **Success is inferred, and the inference is stated rather than hidden.** This service never
/// learns whether a dial connected — it only stamps a go-signal. What it does see is whether the
/// same pair comes back for a *relay circuit*, which is what peers do when a punch fails. So a
/// punch followed by a relay between the same pair is counted as a failure, and one that is not is
/// counted as a success. The bias is knowable: a pair that punched, failed, and simply gave up
/// counts as a success it did not earn.
#[derive(Debug, Default)]
pub struct PunchOutcomes {
    attempts: HashMap<PairClass, u64>,
    relayed: HashMap<PairClass, u64>,
    pending: HashMap<CircuitKey, PairClass>,
}

impl PunchOutcomes {
    /// Count one coordinated punch between `a` and `b`.
    pub fn note_punch(&mut self, a: NodeId, b: NodeId, class: PairClass) {
        *self.attempts.entry(class).or_insert(0) += 1;
        self.pending.insert(CircuitKey::of(a, b), class);
    }

    /// Count one relay circuit; if this pair punched first, that punch did not work.
    pub fn note_relay(&mut self, a: NodeId, b: NodeId) {
        if let Some(class) = self.pending.remove(&CircuitKey::of(a, b)) {
            *self.relayed.entry(class).or_insert(0) += 1;
        }
    }

    /// Take the counts since the last drain: `(class, attempts, successes)` per class.
    ///
    /// Drained rather than read so the reporter emits a window rather than a running total, and so
    /// the pending map cannot grow without bound in a long-lived service.
    pub fn drain(&mut self) -> Vec<(PairClass, u64, u64)> {
        let mut rows = Vec::new();
        for class in PairClass::all() {
            let attempts = self.attempts.remove(&class).unwrap_or(0);
            if attempts == 0 {
                continue;
            }
            let relayed = self.relayed.remove(&class).unwrap_or(0);
            rows.push((class, attempts, attempts.saturating_sub(relayed)));
        }
        // Pending entries are dropped with the window: a punch whose pair never came back is
        // already counted as a success, and keeping the key would leak memory per peer pair.
        self.pending.clear();
        rows
    }
}

impl PunchCoordinator {
    /// An empty coordinator.
    pub fn new() -> Self {
        Self::default()
    }

    /// Stamp `sync` with the circuit's shared go-signal, minting one (`now + lead_millis`) the
    /// first time. Returns the go-signal that was stamped.
    pub fn stamp(&mut self, sync: &mut PunchSync, now_millis: u64, lead_millis: u64) -> u64 {
        let key = CircuitKey::of(sync.source, sync.target);
        let go = *self
            .agreed
            .entry(key)
            .or_insert_with(|| now_millis.saturating_add(lead_millis));
        sync.go_signal_epoch_millis = go;
        go
    }

    /// Forget a circuit's coordination once it is torn down, so the map does not grow unbounded.
    pub fn forget(&mut self, a: NodeId, b: NodeId) {
        self.agreed.remove(&CircuitKey::of(a, b));
    }

    /// How many circuits are being coordinated.
    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.agreed.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nodera_codec::types::NetworkId;

    fn sync(source: NodeId, target: NodeId, go: u64) -> PunchSync {
        PunchSync {
            network_id: NetworkId::new(1, 2),
            genesis_hash: b"world".to_vec(),
            source,
            target,
            observed_candidates: Vec::new(),
            go_signal_epoch_millis: go,
        }
    }

    #[test]
    fn both_directions_receive_the_same_go_signal() {
        let mut coord = PunchCoordinator::new();
        let a = NodeId::new(1, 1);
        let b = NodeId::new(2, 2);

        let mut forward = sync(a, b, 0);
        let first = coord.stamp(&mut forward, 10_000, 500);
        assert_eq!(first, 10_500);

        // The reverse direction, asking later, gets the identical T-minus.
        let mut reverse = sync(b, a, 0);
        let second = coord.stamp(&mut reverse, 12_000, 500);
        assert_eq!(second, 10_500);
        assert_eq!(
            forward.go_signal_epoch_millis,
            reverse.go_signal_epoch_millis
        );
        assert_eq!(coord.len(), 1);
    }

    #[test]
    fn forgetting_a_circuit_frees_it() {
        let mut coord = PunchCoordinator::new();
        let a = NodeId::new(1, 1);
        let b = NodeId::new(2, 2);
        coord.stamp(&mut sync(a, b, 0), 10_000, 500);
        assert_eq!(coord.len(), 1);
        coord.forget(a, b);
        assert_eq!(coord.len(), 0);
    }
}

#[cfg(test)]
mod outcome_tests {
    use super::*;

    fn node(byte: u8) -> NodeId {
        NodeId {
            msb: byte as u64,
            lsb: byte as u64,
        }
    }

    #[test]
    fn a_pair_is_classified_from_both_sides_hardness() {
        assert_eq!(PairClass::of(Some(false), Some(false)), PairClass::EasyEasy);
        assert_eq!(PairClass::of(Some(true), Some(true)), PairClass::HardHard);
        assert_eq!(PairClass::of(Some(true), Some(false)), PairClass::EasyHard);
        assert_eq!(PairClass::of(Some(false), Some(true)), PairClass::EasyHard);
        assert_eq!(PairClass::of(None, Some(true)), PairClass::Unknown);
    }

    #[test]
    fn every_class_label_is_a_declared_enum_member() {
        for class in PairClass::all() {
            assert!(["easy_easy", "easy_hard", "hard_hard", "unknown"].contains(&class.label()));
        }
    }

    /// The inference: a punch followed by a relay between the same pair did not work.
    #[test]
    fn a_relay_after_a_punch_counts_the_punch_as_a_failure() {
        let mut outcomes = PunchOutcomes::default();
        outcomes.note_punch(node(1), node(2), PairClass::HardHard);
        outcomes.note_punch(node(3), node(4), PairClass::HardHard);
        outcomes.note_relay(node(2), node(1)); // symmetric: the pair, not the direction

        let rows = outcomes.drain();
        assert_eq!(rows, vec![(PairClass::HardHard, 2, 1)]);
    }

    #[test]
    fn a_relay_between_peers_that_never_punched_changes_nothing() {
        let mut outcomes = PunchOutcomes::default();
        outcomes.note_relay(node(9), node(8));
        assert!(outcomes.drain().is_empty());
    }

    #[test]
    fn draining_clears_the_window_and_the_pending_map() {
        let mut outcomes = PunchOutcomes::default();
        outcomes.note_punch(node(1), node(2), PairClass::EasyEasy);
        assert_eq!(outcomes.drain().len(), 1);
        assert!(outcomes.drain().is_empty(), "a window is reported once");
        // A relay arriving after the window cannot retroactively fail a punch that was already
        // counted — which is the bounded-memory trade this design accepts.
        outcomes.note_relay(node(1), node(2));
        assert!(outcomes.drain().is_empty());
    }
}
