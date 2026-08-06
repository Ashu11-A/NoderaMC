//! Registration admission: signature, freshness, identity binding.
//!
//! Everything here runs before the registry is touched, on bytes from an unauthenticated socket. It
//! is pure (clock and binding state are passed in) so every rejection path is unit-testable without
//! a network. Self-registration only, Ed25519-verified, TTL'd, size-capped (docs/rendezvous/REFERENCE.md).

use nodera_codec::rendezvous::SignedRecord;
use nodera_codec::sig;
pub use nodera_service::admission::{within_window, IdentityBindings};

/// Why a registration was refused. The string form is stable and machine-readable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rejection {
    /// The signature did not verify against the record's canonical bytes, or the key was malformed.
    BadSignature,
    /// The record's `issuedAt` is outside the accepted window (stale or replayed).
    StaleRecord,
    /// This `NodeId` is already bound to a different public key on this service.
    IdentityMismatch,
    /// The source address exceeded its request quota.
    Quota,
    /// The frame is larger than the service accepts.
    TooLarge,
    /// The service is at its namespace limit and this namespace is not already tracked.
    NamespaceLimit,
    /// The namespace is at its record limit.
    NamespaceFull,
}

impl Rejection {
    /// The stable code carried in logs / diagnostics.
    pub fn code(self) -> &'static str {
        match self {
            Self::BadSignature => "bad-signature",
            Self::StaleRecord => "stale-record",
            Self::IdentityMismatch => "identity-mismatch",
            Self::Quota => "quota",
            Self::TooLarge => "too-large",
            Self::NamespaceLimit => "namespace-limit",
            Self::NamespaceFull => "namespace-full",
        }
    }
}

/// Validate a signed record against the freshness window and identity table.
///
/// The signature is verified over the record's own canonical bytes
/// ([`nodera_codec::types::SignedPeerRecord::signed_bytes`]) — the same bytes a discovering peer
/// verifies, so the relay never vouches for a record it could not itself check.
pub fn admit(
    signed: &SignedRecord,
    bindings: &mut IdentityBindings,
    now_millis: u64,
    clock_skew_millis: u64,
) -> Result<(), Rejection> {
    let record = &signed.record;
    if sig::verify(
        &record.public_key,
        &record.signed_bytes(),
        &signed.signature,
    )
    .is_err()
    {
        return Err(Rejection::BadSignature);
    }
    if !within_window(record.issued_at_epoch_millis, now_millis, clock_skew_millis) {
        return Err(Rejection::StaleRecord);
    }
    if !bindings.accept(record.peer, &record.public_key) {
        return Err(Rejection::IdentityMismatch);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{signed_record, TestSigner, ISSUED_AT};
    use nodera_codec::types::{NetworkId, RegistrationEvent};

    const NET: NetworkId = NetworkId { msb: 1, lsb: 2 };
    const SKEW: u64 = 300_000;

    #[test]
    fn a_correctly_signed_fresh_record_is_admitted() {
        let signed = signed_record(1, NET, b"world", RegistrationEvent::Register, 0);
        admit(&signed, &mut IdentityBindings::new(), ISSUED_AT, SKEW).unwrap();
    }

    #[test]
    fn a_tampered_field_invalidates_the_signature() {
        let mut signed = signed_record(1, NET, b"world", RegistrationEvent::Register, 0);
        signed.record.issued_at_epoch_millis += 1; // now the signature covers the old value
        assert_eq!(
            admit(&signed, &mut IdentityBindings::new(), ISSUED_AT, SKEW),
            Err(Rejection::BadSignature)
        );
    }

    #[test]
    fn a_record_outside_the_window_is_refused_in_both_directions() {
        let signed = signed_record(1, NET, b"world", RegistrationEvent::Register, 0);
        for now in [ISSUED_AT + SKEW + 1, ISSUED_AT.saturating_sub(SKEW + 1)] {
            assert_eq!(
                admit(&signed, &mut IdentityBindings::new(), now, SKEW),
                Err(Rejection::StaleRecord)
            );
        }
        admit(
            &signed,
            &mut IdentityBindings::new(),
            ISSUED_AT + SKEW,
            SKEW,
        )
        .unwrap();
    }

    #[test]
    fn a_second_key_cannot_take_over_a_known_identity() {
        let mut bindings = IdentityBindings::new();
        let first = signed_record(7, NET, b"world", RegistrationEvent::Register, 0);
        admit(&first, &mut bindings, ISSUED_AT, SKEW).unwrap();

        // Same NodeId (0,7), a different, validly-signing key pair.
        let record = {
            let mut r = first.record.clone();
            r.public_key = vec![0u8; 32];
            r
        };
        let impostor = TestSigner::new(200).sign(record);
        assert_eq!(
            admit(&impostor, &mut bindings, ISSUED_AT, SKEW),
            Err(Rejection::IdentityMismatch)
        );
        assert_eq!(bindings.len(), 1);
    }

    #[test]
    fn rejection_codes_are_stable() {
        assert_eq!(Rejection::BadSignature.code(), "bad-signature");
        assert_eq!(Rejection::Quota.code(), "quota");
        assert_eq!(Rejection::NamespaceFull.code(), "namespace-full");
    }
}
