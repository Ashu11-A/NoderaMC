//! Hole-punch coordination (rendezvous.md §4.6, DCUtR-style).
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
