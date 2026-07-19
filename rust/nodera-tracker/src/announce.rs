//! Announce admission: signature, freshness, identity binding.
//!
//! Everything here runs before the registry is touched, on bytes that arrived from an
//! unauthenticated socket. It is pure (clock and quota state are passed in) so every rejection
//! path is unit-testable without a network.

use crate::config::Config;
use nodera_codec::messages::TrackerAnnounce;
use nodera_codec::sig;
use nodera_codec::types::NodeId;
use std::collections::HashMap;

/// Why an announce was refused. The string form is what goes on the wire in the ack, so these
/// codes are stable and machine-readable — a peer can act on them without parsing prose.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rejection {
    /// The signature did not verify, or the key was malformed.
    BadSignature,
    /// The announce's own timestamp is outside the accepted window (stale or replayed).
    StaleAnnounce,
    /// This `NodeId` is already bound to a different public key on this tracker.
    IdentityMismatch,
    /// The source address exceeded its announce quota.
    Quota,
    /// The record is larger than the tracker accepts.
    TooLarge,
    /// The tracker is at its world limit and this world is not already tracked.
    WorldLimit,
    /// The world is at its peer limit.
    WorldFull,
}

impl Rejection {
    /// The stable code carried in the ack.
    pub fn code(self) -> &'static str {
        match self {
            Self::BadSignature => "bad-signature",
            Self::StaleAnnounce => "stale-announce",
            Self::IdentityMismatch => "identity-mismatch",
            Self::Quota => "quota",
            Self::TooLarge => "too-large",
            Self::WorldLimit => "world-limit",
            Self::WorldFull => "world-full",
        }
    }
}

/// Remembers which public key first claimed each `NodeId`.
///
/// Nodera's `NodeId` is random, not derived from the key (`NodeIdentity.generate`), so the tracker
/// cannot check a binding cryptographically. Trust-on-first-use is the honest substitute: the
/// first key to claim an id keeps it for as long as the tracker remembers it, so an attacker
/// cannot hijack a live peer's id — while a fresh tracker, like any fresh observer, has to take
/// the first claim at face value. That is a directory-level protection, not an authority claim:
/// peers still verify world state by hash and certificate chain.
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

    /// Forget a binding (used when a peer leaves gracefully).
    pub fn forget(&mut self, peer: &NodeId) {
        self.bindings.remove(peer);
    }

    /// How many identities are remembered.
    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.bindings.len()
    }
}

/// Validate an announce against the raw frame it arrived in.
///
/// `signed_portion` must be the byte range taken from the *received* frame
/// (`DiscoveryMessage::split_announce_signature`), never a re-encoding: verifying a re-encoding
/// would check this implementation against itself instead of against what the peer signed.
pub fn admit(
    announce: &TrackerAnnounce,
    signed_portion: &[u8],
    bindings: &mut IdentityBindings,
    config: &Config,
    now_millis: u64,
) -> Result<(), Rejection> {
    if sig::verify(&announce.public_key, signed_portion, &announce.signature).is_err() {
        return Err(Rejection::BadSignature);
    }
    if !within_window(
        announce.announce_epoch_millis,
        now_millis,
        config.clock_skew_millis(),
    ) {
        return Err(Rejection::StaleAnnounce);
    }
    if !bindings.accept(announce.peer, &announce.public_key) {
        return Err(Rejection::IdentityMismatch);
    }
    Ok(())
}

/// Whether an announce timestamp is inside the accepted window in either direction.
///
/// Both directions matter: a captured announce from the past must not resurrect a departed peer,
/// and a far-future timestamp must not buy a record extra life.
pub fn within_window(announce_millis: u64, now_millis: u64, skew_millis: u64) -> bool {
    let delta = announce_millis.abs_diff(now_millis);
    delta <= skew_millis
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{announce, caps, TestSigner};
    use nodera_codec::messages::{AnnounceEvent, DiscoveryMessage};

    fn signed(signer: &TestSigner, n: u64, now: u64) -> (TrackerAnnounce, Vec<u8>) {
        let mut a = announce(n, b"world", AnnounceEvent::Started, caps());
        a.announce_epoch_millis = now;
        signer.sign_announce(&mut a);
        let frame = DiscoveryMessage::TrackerAnnounce(a.clone()).encode();
        (a, frame)
    }

    #[test]
    fn a_correctly_signed_fresh_announce_is_admitted() {
        let signer = TestSigner::new(1);
        let (a, frame) = signed(&signer, 1, 10_000);
        let (portion, _) = DiscoveryMessage::split_announce_signature(&frame).unwrap();
        admit(
            &a,
            portion,
            &mut IdentityBindings::new(),
            &Config::default(),
            10_000,
        )
        .unwrap();
    }

    #[test]
    fn a_tampered_field_invalidates_the_signature() {
        let signer = TestSigner::new(1);
        let (mut a, frame) = signed(&signer, 1, 10_000);
        let (portion, _) = DiscoveryMessage::split_announce_signature(&frame).unwrap();
        // A middlebox flipping one byte of the record in flight: the signature covers those bytes,
        // so verification of the received range fails.
        a.reliability_bps = 1;
        let mut tampered = portion.to_vec();
        *tampered.last_mut().unwrap() ^= 0xFF;
        assert_eq!(
            admit(
                &a,
                &tampered,
                &mut IdentityBindings::new(),
                &Config::default(),
                10_000,
            ),
            Err(Rejection::BadSignature)
        );
    }

    #[test]
    fn an_unsigned_announce_is_refused() {
        let a = announce(1, b"world", AnnounceEvent::Started, caps());
        let frame = DiscoveryMessage::TrackerAnnounce(a.clone()).encode();
        let (portion, _) = DiscoveryMessage::split_announce_signature(&frame).unwrap();
        assert_eq!(
            admit(
                &a,
                portion,
                &mut IdentityBindings::new(),
                &Config::default(),
                a.announce_epoch_millis
            ),
            Err(Rejection::BadSignature)
        );
    }

    #[test]
    fn announces_outside_the_window_are_refused_in_both_directions() {
        let signer = TestSigner::new(1);
        let config = Config::default();
        let skew = config.clock_skew_millis();

        let (a, frame) = signed(&signer, 1, 1_000_000);
        let (portion, _) = DiscoveryMessage::split_announce_signature(&frame).unwrap();
        for now in [1_000_000 + skew + 1, 1_000_000 - skew - 1] {
            assert_eq!(
                admit(&a, portion, &mut IdentityBindings::new(), &config, now),
                Err(Rejection::StaleAnnounce)
            );
        }
        // Exactly at the edge is still accepted.
        admit(
            &a,
            portion,
            &mut IdentityBindings::new(),
            &config,
            1_000_000 + skew,
        )
        .unwrap();
    }

    #[test]
    fn a_second_key_cannot_take_over_a_known_identity() {
        let mut bindings = IdentityBindings::new();
        let first = TestSigner::new(1);
        let (a, frame) = signed(&first, 7, 10_000);
        let (portion, _) = DiscoveryMessage::split_announce_signature(&frame).unwrap();
        admit(&a, portion, &mut bindings, &Config::default(), 10_000).unwrap();

        // Same NodeId, different key pair, validly signed by that other key.
        let impostor = TestSigner::new(2);
        let (b, frame_b) = signed(&impostor, 7, 10_000);
        let (portion_b, _) = DiscoveryMessage::split_announce_signature(&frame_b).unwrap();
        assert_eq!(
            admit(&b, portion_b, &mut bindings, &Config::default(), 10_000),
            Err(Rejection::IdentityMismatch)
        );
        assert_eq!(bindings.len(), 1);
    }

    #[test]
    fn rejection_codes_are_stable() {
        assert_eq!(Rejection::BadSignature.code(), "bad-signature");
        assert_eq!(Rejection::Quota.code(), "quota");
        assert_eq!(Rejection::WorldFull.code(), "world-full");
    }
}
