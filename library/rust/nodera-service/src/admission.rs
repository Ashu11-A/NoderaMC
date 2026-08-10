//! The two checks every unauthenticated service runs before it records anything.
//!
//! Both are pure — the clock and the table are passed in — so every rejection path is unit-testable
//! without a network, and both were written out identically by the tracker and the rendezvous.

use std::collections::HashMap;

use nodera_codec::types::NodeId;

/// Whether a record's own timestamp is close enough to ours to be neither stale nor replayed.
///
/// Symmetric on purpose: a timestamp too far in the *future* is as suspect as one too far in the
/// past, and a service that only checked one direction would accept a record minted to outlive
/// every honest refresh.
pub fn within_window(claimed_millis: u64, now_millis: u64, skew_millis: u64) -> bool {
    claimed_millis.abs_diff(now_millis) <= skew_millis
}

/// Remembers which public key first claimed each `NodeId`.
///
/// Nodera's `NodeId` is random, not derived from the key (`NodeIdentity.generate`), so a service
/// cannot check a binding cryptographically. Trust-on-first-use is the honest substitute: the first
/// key to claim an id keeps it for as long as the service remembers it, so an attacker cannot
/// hijack a live peer's id — while a fresh service, like any fresh observer, has to take the first
/// claim at face value.
///
/// That is a directory-level protection, **not** an authority claim: peers still verify world state
/// by hash and certificate chain, and nothing here can make a lie true.
#[derive(Debug, Default)]
pub struct IdentityBindings {
    bindings: HashMap<NodeId, Vec<u8>>,
}

impl IdentityBindings {
    /// An empty binding table.
    pub fn new() -> Self {
        Self::default()
    }

    /// Check the claim, recording it when the id is new.
    pub fn accept(&mut self, peer: NodeId, public_key: &[u8]) -> bool {
        match self.bindings.get(&peer) {
            Some(known) => known.as_slice() == public_key,
            None => {
                self.bindings.insert(peer, public_key.to_vec());
                true
            }
        }
    }

    /// Forget a binding, for a peer that left gracefully.
    ///
    /// Deliberate: a graceful departure releases the id, so an operator who rotates a key after
    /// decommissioning is not locked out of the identity they own.
    pub fn forget(&mut self, peer: &NodeId) {
        self.bindings.remove(peer);
    }

    /// How many identities are remembered.
    pub fn len(&self) -> usize {
        self.bindings.len()
    }

    /// Whether nothing is remembered.
    pub fn is_empty(&self) -> bool {
        self.bindings.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(n: u64) -> NodeId {
        NodeId::new(0, n)
    }

    #[test]
    fn the_window_is_symmetric_around_now() {
        assert!(within_window(1_000, 1_300, 300));
        assert!(within_window(1_600, 1_300, 300));
        assert!(!within_window(999, 1_300, 300));
        assert!(!within_window(1_601, 1_300, 300));
    }

    #[test]
    fn the_first_key_to_claim_an_id_keeps_it() {
        let mut bindings = IdentityBindings::new();
        assert!(bindings.accept(peer(1), b"key-one"));
        assert!(bindings.accept(peer(1), b"key-one"));
        // The hijack this exists to stop.
        assert!(!bindings.accept(peer(1), b"key-two"));
        assert_eq!(bindings.len(), 1);
    }

    #[test]
    fn a_graceful_departure_releases_the_id_for_a_rotated_key() {
        let mut bindings = IdentityBindings::new();
        assert!(bindings.accept(peer(1), b"key-one"));
        bindings.forget(&peer(1));
        assert!(bindings.is_empty());
        assert!(bindings.accept(peer(1), b"key-two"));
    }
}
