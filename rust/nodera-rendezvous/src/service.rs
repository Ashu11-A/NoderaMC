//! The rendezvous service's request handling, with no IO in it.
//!
//! Sockets, clocks, and the circuit bridge live in [`crate::wire`]; everything that decides *what
//! the answer is* lives here and takes `now_millis` as a parameter. That split makes the whole
//! admission path — signatures, freshness, quotas, reservations, punch coordination — unit-testable
//! without spawning a listener. The service carries no game/consensus/storage logic (Task 0 §4
//! rule 7): it introduces and forwards; peers authenticate each other end-to-end.

use crate::config::Config;
use crate::discover;
use crate::limits::RequestQuota;
use crate::punch::PunchCoordinator;
use crate::register::{self, IdentityBindings};
use crate::registry::{Namespace, Registry};
use crate::reservation::ReservationKeeper;
use nodera_codec::rendezvous::{ObservedAddress, PunchSync, RelayConnect, RendezvousMessage};
use nodera_codec::types::NodeId;
use std::net::IpAddr;

/// The lead time before a coordinated hole-punch go-signal fires, giving both peers time to receive
/// the synchronized `PunchSync` before they dial.
const PUNCH_LEAD_MILLIS: u64 = 500;

/// What the service decided about a control frame — the caller ([`crate::wire`]) performs the IO.
#[derive(Debug)]
pub enum Decision {
    /// Send this frame back and keep reading control frames.
    Reply(Vec<u8>),
    /// A reservation was granted: reply with the reservation, then register this connection as the
    /// reserver's control channel so inbound circuits and forwarded frames reach it. The reserved
    /// loop keeps the reservation's own proof + limits to build `RelayIncoming` and meter circuits.
    Reserved {
        /// The namespace the reservation is scoped to.
        namespace: Namespace,
        /// The reserving peer.
        peer: NodeId,
        /// The granted reservation (proof + limits + relay route).
        reservation: nodera_codec::rendezvous::RelayReservation,
    },
    /// This connection asked to be bridged to `connect.target`'s reserved slot.
    Connect(RelayConnect),
    /// A stamped punch-sync to forward to `sync.target`'s control channel (best-effort).
    Forward(PunchSync),
    /// The frame could not be decoded or is not one a peer may send here.
    Drop(String),
}

/// The whole mutable service state.
#[derive(Debug)]
pub struct Rendezvous {
    config: Config,
    registry: Registry,
    bindings: IdentityBindings,
    quota: RequestQuota,
    keeper: ReservationKeeper,
    punch: PunchCoordinator,
    registrations: u64,
    discoveries: u64,
    reservations: u64,
    circuits: u64,
    rejected: u64,
}

impl Rendezvous {
    /// Build a service from validated config and a reservation keeper.
    pub fn new(config: Config, keeper: ReservationKeeper) -> Self {
        let quota = RequestQuota::new(
            u64::from(config.refresh_interval_seconds) * 1_000,
            config.per_ip_request_quota,
        );
        Self {
            config,
            registry: Registry::new(),
            bindings: IdentityBindings::new(),
            quota,
            keeper,
            punch: PunchCoordinator::new(),
            registrations: 0,
            discoveries: 0,
            reservations: 0,
            circuits: 0,
            rejected: 0,
        }
    }

    /// The configuration in force.
    pub fn config(&self) -> &Config {
        &self.config
    }

    /// Counters for the operator log line: (registrations, discoveries, reservations, circuits,
    /// rejected, namespaces).
    pub fn stats(&self) -> (u64, u64, u64, u64, u64, usize) {
        (
            self.registrations,
            self.discoveries,
            self.reservations,
            self.circuits,
            self.rejected,
            self.registry.namespace_count(),
        )
    }

    /// Count one bridged circuit (called by the wire layer).
    pub fn note_circuit(&mut self) {
        self.circuits += 1;
    }

    /// Re-validate a reservation just before bridging (defense in depth).
    ///
    /// The relay issued the proof, so this is belt-and-braces: it catches a reservation that has
    /// expired between the reserve and the connect, and re-checks the HMAC over the exact limits the
    /// circuit will be metered against, so a bug that carried mismatched limits cannot silently
    /// widen the ceiling.
    pub fn reservation_is_valid(
        &self,
        namespace: &Namespace,
        peer: NodeId,
        reservation: &nodera_codec::rendezvous::RelayReservation,
        now_millis: u64,
    ) -> bool {
        let limits = crate::reservation::ReservationLimits {
            expires_at_millis: reservation.expires_at_epoch_millis,
            max_bytes: reservation.max_bytes,
            max_duration_millis: reservation.max_duration_millis,
        };
        self.keeper
            .validate(namespace, peer, &limits, &reservation.proof, now_millis)
    }

    /// Forget a circuit's punch coordination once it is torn down, so the map stays bounded.
    pub fn forget_punch(&mut self, a: NodeId, b: NodeId) {
        self.punch.forget(a, b);
    }

    /// Handle one decoded-from-the-wire control frame.
    pub fn handle_frame(
        &mut self,
        frame: &[u8],
        source: Option<IpAddr>,
        source_route: Option<String>,
        now_millis: u64,
    ) -> Decision {
        if frame.len() > self.config.max_frame_bytes {
            self.rejected += 1;
            return Decision::Drop(register::Rejection::TooLarge.code().to_owned());
        }
        let message = match RendezvousMessage::decode(frame) {
            Ok(message) => message,
            Err(e) => {
                self.rejected += 1;
                return Decision::Drop(e.to_string());
            }
        };

        match message {
            RendezvousMessage::Register(m) => {
                self.on_register(m.signed, source, source_route, now_millis)
            }
            RendezvousMessage::Discover(m) => {
                self.discoveries += 1;
                let page = discover::answer(&self.registry, &m, &self.config, now_millis);
                Decision::Reply(RendezvousMessage::Peers(page).encode())
            }
            RendezvousMessage::Reserve(m) => {
                self.on_reserve(m.network_id, m.genesis_hash, m.peer, source, now_millis)
            }
            RendezvousMessage::Connect(m) => Decision::Connect(m),
            RendezvousMessage::PunchSync(mut m) => {
                self.punch.stamp(&mut m, now_millis, PUNCH_LEAD_MILLIS);
                Decision::Forward(m)
            }
            // Server-originated messages a peer must not send inbound.
            other => {
                self.rejected += 1;
                Decision::Drop(format!("tag {} is not accepted from a peer", other.tag()))
            }
        }
    }

    fn on_register(
        &mut self,
        signed: nodera_codec::rendezvous::SignedRecord,
        source: Option<IpAddr>,
        source_route: Option<String>,
        now_millis: u64,
    ) -> Decision {
        if let Some(ip) = source {
            if !self.quota.admit(ip, now_millis) {
                self.rejected += 1;
                return Decision::Drop(register::Rejection::Quota.code().to_owned());
            }
        }
        if let Err(rejection) = register::admit(
            &signed,
            &mut self.bindings,
            now_millis,
            self.config.clock_skew_millis(),
        ) {
            self.rejected += 1;
            return Decision::Drop(rejection.code().to_owned());
        }
        let peer = signed.record.peer;
        let is_unregister =
            signed.record.event == nodera_codec::types::RegistrationEvent::Unregister;
        let outcome = self.registry.apply(
            &signed,
            now_millis,
            self.config.registration_ttl_millis(),
            self.config.max_namespaces,
            self.config.max_records_per_namespace,
        );
        use crate::registry::RegisterOutcome;
        match outcome {
            RegisterOutcome::NamespaceFull => {
                self.rejected += 1;
                Decision::Drop(register::Rejection::NamespaceFull.code().to_owned())
            }
            RegisterOutcome::NamespaceLimit => {
                self.rejected += 1;
                Decision::Drop(register::Rejection::NamespaceLimit.code().to_owned())
            }
            _ => {
                if is_unregister {
                    // A graceful departure releases the id binding, so a peer that rotates its key
                    // after leaving is not locked out of its own id.
                    self.bindings.forget(&peer);
                }
                self.registrations += 1;
                // Confirm the registration by reporting the peer's reflexive address (STUN-ish):
                // the peer learns the public route its NAT presents and can add it as a candidate.
                let observed = ObservedAddress {
                    peer,
                    observed_route: source_route.unwrap_or_default(),
                };
                Decision::Reply(RendezvousMessage::ObservedAddress(observed).encode())
            }
        }
    }

    fn on_reserve(
        &mut self,
        network_id: nodera_codec::types::NetworkId,
        genesis_hash: Vec<u8>,
        peer: NodeId,
        source: Option<IpAddr>,
        now_millis: u64,
    ) -> Decision {
        if let Some(ip) = source {
            if !self.quota.admit(ip, now_millis) {
                self.rejected += 1;
                return Decision::Drop(register::Rejection::Quota.code().to_owned());
            }
        }
        let namespace = Namespace::new(network_id, genesis_hash);
        let reservation = self
            .keeper
            .grant(&namespace, peer, now_millis, &self.config);
        self.reservations += 1;
        Decision::Reserved {
            namespace,
            peer,
            reservation,
        }
    }

    /// Expire silent records and idle quota counters.
    pub fn sweep(&mut self, now_millis: u64) -> usize {
        self.quota.sweep(now_millis);
        self.registry
            .sweep(now_millis, self.config.registration_ttl_millis())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{signed_record, TestSigner, ISSUED_AT};
    use nodera_codec::rendezvous::{RendezvousDiscover, RendezvousPeers};
    use nodera_codec::types::{NetworkId, RegistrationEvent, SignedPeerRecord};

    const NET: NetworkId = NetworkId { msb: 1, lsb: 2 };

    fn config() -> Config {
        Config {
            refresh_interval_seconds: 30,
            registration_ttl_seconds: 60,
            discover_page_limit: 10,
            per_ip_request_quota: 5,
            ..Config::default()
        }
    }

    fn service() -> Rendezvous {
        Rendezvous::new(
            config(),
            ReservationKeeper::new(vec![0x42; 32], "198.51.100.9:25601".to_owned()),
        )
    }

    fn register_frame(signed: &nodera_codec::rendezvous::SignedRecord) -> Vec<u8> {
        RendezvousMessage::Register(nodera_codec::rendezvous::RendezvousRegister {
            signed: signed.clone(),
        })
        .encode()
    }

    fn discover_frame() -> Vec<u8> {
        RendezvousMessage::Discover(RendezvousDiscover {
            network_id: NET,
            genesis_hash: b"world".to_vec(),
            cursor: 0,
            limit: 0,
        })
        .encode()
    }

    fn peers(decision: Decision) -> RendezvousPeers {
        match decision {
            Decision::Reply(frame) => match RendezvousMessage::decode(&frame).unwrap() {
                RendezvousMessage::Peers(p) => p,
                other => panic!("expected peers, got {other:?}"),
            },
            other => panic!("expected a reply, got {other:?}"),
        }
    }

    #[test]
    fn register_then_discover_returns_the_record() {
        let mut svc = service();
        let signed = signed_record(1, NET, b"world", RegistrationEvent::Register, 0);
        let reply = svc.handle_frame(
            &register_frame(&signed),
            None,
            Some("203.0.113.9:40000".to_owned()),
            ISSUED_AT,
        );
        // The register is confirmed with the reflexive address.
        match reply {
            Decision::Reply(frame) => match RendezvousMessage::decode(&frame).unwrap() {
                RendezvousMessage::ObservedAddress(o) => {
                    assert_eq!(o.observed_route, "203.0.113.9:40000");
                    assert_eq!(o.peer, NodeId::new(0, 1));
                }
                other => panic!("expected observed-address, got {other:?}"),
            },
            other => panic!("expected a reply, got {other:?}"),
        }

        let page = peers(svc.handle_frame(&discover_frame(), None, None, ISSUED_AT));
        assert_eq!(page.records.len(), 1);
        assert_eq!(page.records[0].record.peer, NodeId::new(0, 1));
    }

    #[test]
    fn a_bad_signature_is_dropped_and_never_registered() {
        let mut svc = service();
        let mut signed = signed_record(1, NET, b"world", RegistrationEvent::Register, 0);
        signed.signature[0] ^= 0xFF;
        assert!(matches!(
            svc.handle_frame(&register_frame(&signed), None, None, ISSUED_AT),
            Decision::Drop(_)
        ));
        let page = peers(svc.handle_frame(&discover_frame(), None, None, ISSUED_AT));
        assert!(page.records.is_empty());
    }

    #[test]
    fn the_per_ip_quota_refuses_a_flood_of_registrations() {
        let mut svc = service();
        let ip: IpAddr = "203.0.113.1".parse().unwrap();
        for n in 1..=5u8 {
            let signed = signed_record(n, NET, b"world", RegistrationEvent::Register, 0);
            assert!(matches!(
                svc.handle_frame(&register_frame(&signed), Some(ip), None, ISSUED_AT),
                Decision::Reply(_)
            ));
        }
        let signed = signed_record(6, NET, b"world", RegistrationEvent::Register, 0);
        assert!(matches!(
            svc.handle_frame(&register_frame(&signed), Some(ip), None, ISSUED_AT),
            Decision::Drop(_)
        ));
    }

    #[test]
    fn a_reserve_grants_a_validatable_reservation() {
        let mut svc = service();
        let reserve = RendezvousMessage::Reserve(nodera_codec::rendezvous::RelayReserve {
            network_id: NET,
            genesis_hash: b"world".to_vec(),
            peer: NodeId::new(0, 1),
        })
        .encode();
        match svc.handle_frame(&reserve, None, None, ISSUED_AT) {
            Decision::Reserved {
                namespace,
                peer,
                reservation,
            } => {
                assert_eq!(peer, NodeId::new(0, 1));
                assert_eq!(namespace.genesis_hash, b"world");
                assert!(reservation.accepted);
                assert!(reservation.max_bytes > 0);
                assert!(!reservation.proof.is_empty());
            }
            other => panic!("expected a reservation, got {other:?}"),
        }
    }

    #[test]
    fn a_connect_is_handed_up_for_routing() {
        let mut svc = service();
        let connect = RendezvousMessage::Connect(RelayConnect {
            network_id: NET,
            genesis_hash: b"world".to_vec(),
            source: NodeId::new(0, 5),
            target: NodeId::new(0, 1),
        })
        .encode();
        assert!(matches!(
            svc.handle_frame(&connect, None, None, ISSUED_AT),
            Decision::Connect(_)
        ));
    }

    #[test]
    fn a_punch_sync_is_stamped_and_forwarded() {
        let mut svc = service();
        let sync = RendezvousMessage::PunchSync(PunchSync {
            network_id: NET,
            genesis_hash: b"world".to_vec(),
            source: NodeId::new(0, 5),
            target: NodeId::new(0, 1),
            observed_candidates: Vec::new(),
            go_signal_epoch_millis: 0,
        })
        .encode();
        match svc.handle_frame(&sync, None, None, ISSUED_AT) {
            Decision::Forward(m) => {
                assert_eq!(m.go_signal_epoch_millis, ISSUED_AT + PUNCH_LEAD_MILLIS);
            }
            other => panic!("expected a forward, got {other:?}"),
        }
    }

    #[test]
    fn an_unregister_forgets_the_record() {
        let mut svc = service();
        svc.handle_frame(
            &register_frame(&signed_record(
                1,
                NET,
                b"world",
                RegistrationEvent::Register,
                0,
            )),
            None,
            None,
            ISSUED_AT,
        );
        svc.handle_frame(
            &register_frame(&signed_record(
                1,
                NET,
                b"world",
                RegistrationEvent::Unregister,
                0,
            )),
            None,
            None,
            ISSUED_AT,
        );
        let page = peers(svc.handle_frame(&discover_frame(), None, None, ISSUED_AT));
        assert!(page.records.is_empty());
    }

    #[test]
    fn a_garbage_frame_is_dropped_without_crashing() {
        let mut svc = service();
        assert!(matches!(
            svc.handle_frame(&[0xFF, 0xFF, 0x00], None, None, ISSUED_AT),
            Decision::Drop(_)
        ));
    }

    // Keeps `SignedPeerRecord`/`TestSigner` imports meaningful even as the suite evolves.
    #[test]
    fn a_hand_built_record_registers_like_any_other() {
        let mut svc = service();
        let record = SignedPeerRecord {
            network_id: NET,
            genesis_hash: b"world".to_vec(),
            peer: NodeId::new(0, 42),
            public_key: vec![0u8; 32],
            event: RegistrationEvent::Register,
            candidates: Vec::new(),
            capabilities: crate::test_support::caps(),
            issued_at_epoch_millis: ISSUED_AT,
            expires_at_epoch_millis: u64::MAX,
        };
        let signed = TestSigner::new(42).sign(record);
        assert!(matches!(
            svc.handle_frame(&register_frame(&signed), None, None, ISSUED_AT),
            Decision::Reply(_)
        ));
    }
}
