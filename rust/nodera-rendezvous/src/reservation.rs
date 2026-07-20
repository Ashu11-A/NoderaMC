//! Relay reservations and their stateless HMAC proofs (rendezvous.md §4.2/§8.4).
//!
//! A peer that expects inbound relayed connects reserves a slot before advertising a relay
//! candidate; the reservation carries expiry + byte/duration limits + an HMAC proof. The proof lets
//! the service — and the reserver — re-validate a reservation without shared state: no reservation,
//! no circuit, which closes the open-relay abuse hole. The relay is for establishment and fallback,
//! not free transit.

use crate::config::Config;
use crate::registry::Namespace;
use hmac::{Hmac, Mac};
use nodera_codec::rendezvous::RelayReservation;
use nodera_codec::types::NodeId;
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

/// The bounds a granted reservation carries.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ReservationLimits {
    /// When the reservation expires (epoch millis).
    pub expires_at_millis: u64,
    /// Byte ceiling for a single circuit.
    pub max_bytes: u64,
    /// Wall-clock ceiling for a single circuit (millis).
    pub max_duration_millis: u64,
}

/// Issues and validates reservation proofs under a single HMAC key.
///
/// The key never leaves the process; a peer cannot forge a proof, and a stale or tampered proof
/// fails validation. Constant-time comparison avoids leaking the key one byte at a time.
#[derive(Debug, Clone)]
pub struct ReservationKeeper {
    key: Vec<u8>,
    relay_route: String,
}

impl ReservationKeeper {
    /// Build a keeper with the given HMAC key and the route peers advertise as their relay
    /// candidate.
    pub fn new(key: Vec<u8>, relay_route: String) -> Self {
        Self { key, relay_route }
    }

    /// Grant a reservation for `peer` in `namespace`, valid from `now_millis`.
    pub fn grant(
        &self,
        namespace: &Namespace,
        peer: NodeId,
        now_millis: u64,
        config: &Config,
    ) -> RelayReservation {
        let limits = ReservationLimits {
            expires_at_millis: now_millis.saturating_add(config.reservation_ttl_millis()),
            max_bytes: config.reservation_max_bytes,
            max_duration_millis: config.reservation_max_duration_millis(),
        };
        let proof = self.proof(namespace, peer, &limits);
        RelayReservation {
            accepted: true,
            relay_route: self.relay_route.clone(),
            expires_at_epoch_millis: limits.expires_at_millis,
            max_bytes: limits.max_bytes,
            max_duration_millis: limits.max_duration_millis,
            proof,
            reason: String::new(),
        }
    }

    /// Compute the HMAC proof binding a reservation to its namespace, peer, and limits.
    pub fn proof(
        &self,
        namespace: &Namespace,
        peer: NodeId,
        limits: &ReservationLimits,
    ) -> Vec<u8> {
        let mut mac = HmacSha256::new_from_slice(&self.key).expect("HMAC accepts any key length");
        mac.update(&namespace.network_id.msb.to_be_bytes());
        mac.update(&namespace.network_id.lsb.to_be_bytes());
        mac.update(&(namespace.genesis_hash.len() as u32).to_be_bytes());
        mac.update(&namespace.genesis_hash);
        mac.update(&peer.msb.to_be_bytes());
        mac.update(&peer.lsb.to_be_bytes());
        mac.update(&limits.expires_at_millis.to_be_bytes());
        mac.update(&limits.max_bytes.to_be_bytes());
        mac.update(&limits.max_duration_millis.to_be_bytes());
        mac.finalize().into_bytes().to_vec()
    }

    /// Whether `proof` is a valid, unexpired reservation for `peer` in `namespace` with `limits`.
    pub fn validate(
        &self,
        namespace: &Namespace,
        peer: NodeId,
        limits: &ReservationLimits,
        proof: &[u8],
        now_millis: u64,
    ) -> bool {
        if now_millis > limits.expires_at_millis {
            return false;
        }
        let expected = self.proof(namespace, peer, limits);
        constant_time_eq(&expected, proof)
    }
}

/// Constant-time byte-slice equality — no early return on the first differing byte.
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    const NET: nodera_codec::types::NetworkId = nodera_codec::types::NetworkId { msb: 1, lsb: 2 };

    fn keeper() -> ReservationKeeper {
        ReservationKeeper::new(vec![0x42; 32], "198.51.100.9:25601".to_owned())
    }

    fn ns() -> Namespace {
        Namespace::new(NET, b"world".to_vec())
    }

    #[test]
    fn a_granted_reservation_validates() {
        let keeper = keeper();
        let config = Config::default();
        let grant = keeper.grant(&ns(), NodeId::new(1, 2), 10_000, &config);
        assert!(grant.accepted);
        let limits = ReservationLimits {
            expires_at_millis: grant.expires_at_epoch_millis,
            max_bytes: grant.max_bytes,
            max_duration_millis: grant.max_duration_millis,
        };
        assert!(keeper.validate(&ns(), NodeId::new(1, 2), &limits, &grant.proof, 20_000));
    }

    #[test]
    fn a_proof_for_a_different_peer_is_rejected() {
        let keeper = keeper();
        let grant = keeper.grant(&ns(), NodeId::new(1, 2), 10_000, &Config::default());
        let limits = ReservationLimits {
            expires_at_millis: grant.expires_at_epoch_millis,
            max_bytes: grant.max_bytes,
            max_duration_millis: grant.max_duration_millis,
        };
        assert!(!keeper.validate(&ns(), NodeId::new(9, 9), &limits, &grant.proof, 20_000));
    }

    #[test]
    fn a_tampered_proof_is_rejected() {
        let keeper = keeper();
        let grant = keeper.grant(&ns(), NodeId::new(1, 2), 10_000, &Config::default());
        let mut proof = grant.proof.clone();
        proof[0] ^= 0xFF;
        let limits = ReservationLimits {
            expires_at_millis: grant.expires_at_epoch_millis,
            max_bytes: grant.max_bytes,
            max_duration_millis: grant.max_duration_millis,
        };
        assert!(!keeper.validate(&ns(), NodeId::new(1, 2), &limits, &proof, 20_000));
    }

    #[test]
    fn an_expired_reservation_is_rejected() {
        let keeper = keeper();
        let grant = keeper.grant(&ns(), NodeId::new(1, 2), 10_000, &Config::default());
        let limits = ReservationLimits {
            expires_at_millis: grant.expires_at_epoch_millis,
            max_bytes: grant.max_bytes,
            max_duration_millis: grant.max_duration_millis,
        };
        let after = grant.expires_at_epoch_millis + 1;
        assert!(!keeper.validate(&ns(), NodeId::new(1, 2), &limits, &grant.proof, after));
    }

    #[test]
    fn a_different_key_does_not_validate_anothers_proof() {
        let grant = keeper().grant(&ns(), NodeId::new(1, 2), 10_000, &Config::default());
        let other = ReservationKeeper::new(vec![0x99; 32], "r".to_owned());
        let limits = ReservationLimits {
            expires_at_millis: grant.expires_at_epoch_millis,
            max_bytes: grant.max_bytes,
            max_duration_millis: grant.max_duration_millis,
        };
        assert!(!other.validate(&ns(), NodeId::new(1, 2), &limits, &grant.proof, 20_000));
    }
}
