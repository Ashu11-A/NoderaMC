//! The rendezvous + relay message family the `nodera-rendezvous` service speaks (Task 29).
//!
//! Byte-identical to `MessageCodec`'s tags 35–43. Like the discovery family, this crate carries no
//! game/consensus/storage logic (Task 0 §4 rule 7): the service decodes, verifies signatures, and
//! forwards. Registration records are self-signed and verified against the *same* canonical bytes
//! (`SignedPeerRecord`, tag 91) whether the relay or a discovering peer checks them, so a lying
//! relay can hide or invent peers but never forge a record (RENDEZVOUS.md §8.1).

use crate::tags::message_tags;
use crate::types::{NetworkId, NodeId, PeerCandidate, SignedPeerRecord};
use crate::{CanonicalReader, CanonicalWriter, Result};

/// A signed record as it travels inside a register request or a discovery page: the canonical
/// [`SignedPeerRecord`] plus the Ed25519 signature over its [`SignedPeerRecord::signed_bytes`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedRecord {
    /// The canonical record.
    pub record: SignedPeerRecord,
    /// Ed25519 over `record.signed_bytes()`.
    pub signature: Vec<u8>,
}

impl SignedRecord {
    /// The legacy positional encoding, kept because the record's signature covers exactly these
    /// bytes; the TLV plane carries the result as one opaque field rather than re-spelling it.
    pub fn encode(&self, w: &mut CanonicalWriter) {
        self.record.encode(w);
        w.write_bytes(&self.signature);
    }

    /// Inverse of [`SignedRecord::encode`].
    pub fn decode(r: &mut CanonicalReader<'_>) -> Result<Self> {
        let record = SignedPeerRecord::decode(r)?;
        let signature = r.read_bytes_vec()?;
        Ok(Self { record, signature })
    }
}

/// A peer's signed self-registration (tag 35).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RendezvousRegister {
    /// The signed record and its signature.
    pub signed: SignedRecord,
}

/// A namespace discovery query (tag 36).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RendezvousDiscover {
    /// The network to search.
    pub network_id: NetworkId,
    /// The world (swarm id) to search.
    pub genesis_hash: Vec<u8>,
    /// Opaque page cursor (`0` starts from the beginning).
    pub cursor: u32,
    /// Maximum records to return (`0` lets the service choose its page size).
    pub limit: u32,
}

/// A page of discovered peer records (tag 37).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RendezvousPeers {
    /// Cursor to pass to the next `RendezvousDiscover`, or `0` when the page is the last.
    pub next_cursor: u32,
    /// The records in this page (each independently verifiable).
    pub records: Vec<SignedRecord>,
}

/// A peer reserving an inbound relay slot (tag 38).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayReserve {
    /// The network the reservation is scoped to.
    pub network_id: NetworkId,
    /// The world the reservation is scoped to.
    pub genesis_hash: Vec<u8>,
    /// The reserving peer (the future destination of inbound circuits).
    pub peer: NodeId,
}

/// The relay's answer to a reservation (tag 39).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayReservation {
    /// Whether a slot was granted.
    pub accepted: bool,
    /// The relay route a peer advertises as its relay candidate.
    pub relay_route: String,
    /// When the reservation expires (epoch millis), or `0` when refused.
    pub expires_at_epoch_millis: u64,
    /// Byte ceiling for a single circuit to this reservation.
    pub max_bytes: u64,
    /// Wall-clock ceiling for a single circuit (millis).
    pub max_duration_millis: u64,
    /// HMAC proof the relay validates on `CONNECT`/`INCOMING` (opaque to peers).
    pub proof: Vec<u8>,
    /// Empty when accepted; otherwise a short stable rejection code.
    pub reason: String,
}

/// A peer asking the relay to bridge it to a target's reserved slot (tag 40).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayConnect {
    /// The network the circuit is scoped to.
    pub network_id: NetworkId,
    /// The world the circuit is scoped to.
    pub genesis_hash: Vec<u8>,
    /// The connecting peer.
    pub source: NodeId,
    /// The reserved destination peer.
    pub target: NodeId,
}

/// The relay telling a reserver that a circuit is inbound (tag 41).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RelayIncoming {
    /// The network the circuit is scoped to.
    pub network_id: NetworkId,
    /// The world the circuit is scoped to.
    pub genesis_hash: Vec<u8>,
    /// The connecting peer.
    pub source: NodeId,
    /// The reserved destination peer (this reserver).
    pub target: NodeId,
    /// The reservation proof, echoed so the reserver can validate it is the slot holder.
    pub proof: Vec<u8>,
}

/// A relayed observed-address exchange plus a synchronized go-signal (tag 42, DCUtR-style).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PunchSync {
    /// The network the punch is scoped to.
    pub network_id: NetworkId,
    /// The world the punch is scoped to.
    pub genesis_hash: Vec<u8>,
    /// The peer sending its observed addresses.
    pub source: NodeId,
    /// The peer the addresses are for.
    pub target: NodeId,
    /// The source's observed reachability candidates.
    pub observed_candidates: Vec<PeerCandidate>,
    /// A shared T-minus for the simultaneous dial (epoch millis); `0` requests one.
    pub go_signal_epoch_millis: u64,
}

/// The relay reporting a caller's reflexive address (tag 43, STUN-ish).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ObservedAddress {
    /// The peer whose address is reported.
    pub peer: NodeId,
    /// The address the relay observed the caller connecting from.
    pub observed_route: String,
}

/// Any rendezvous/relay frame this crate can decode.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RendezvousMessage {
    /// Tag 35.
    Register(RendezvousRegister),
    /// Tag 36.
    Discover(RendezvousDiscover),
    /// Tag 37.
    Peers(RendezvousPeers),
    /// Tag 38.
    Reserve(RelayReserve),
    /// Tag 39.
    Reservation(RelayReservation),
    /// Tag 40.
    Connect(RelayConnect),
    /// Tag 41.
    Incoming(RelayIncoming),
    /// Tag 42.
    PunchSync(PunchSync),
    /// Tag 43.
    ObservedAddress(ObservedAddress),
}

impl RendezvousMessage {
    /// The frozen wire tag of this message.
    pub fn tag(&self) -> u16 {
        match self {
            Self::Register(_) => message_tags::RENDEZVOUS_REGISTER,
            Self::Discover(_) => message_tags::RENDEZVOUS_DISCOVER,
            Self::Peers(_) => message_tags::RENDEZVOUS_PEERS,
            Self::Reserve(_) => message_tags::RELAY_RESERVE,
            Self::Reservation(_) => message_tags::RELAY_RESERVATION,
            Self::Connect(_) => message_tags::RELAY_CONNECT,
            Self::Incoming(_) => message_tags::RELAY_INCOMING,
            Self::PunchSync(_) => message_tags::PUNCH_SYNC,
            Self::ObservedAddress(_) => message_tags::OBSERVED_ADDRESS,
        }
    }

    /// Encode a complete `NDR2` frame carrying this message as an unsolicited event.
    pub fn encode(&self) -> Vec<u8> {
        self.encode_frame(crate::frame::flags::EVENT, 0)
    }

    /// Encode a complete `NDR2` frame with explicit routing metadata.
    pub fn encode_frame(&self, flags: u16, correlation_id: u64) -> Vec<u8> {
        crate::frame::NoderaFrame {
            epoch: crate::frame::WIRE_EPOCH,
            kind: self.tag(),
            flags,
            correlation_id,
            body: crate::wire::encode_rendezvous_body(self),
        }
        .encode()
    }

    /// Decode a complete `NDR2` frame.
    pub fn decode(frame: &[u8]) -> Result<Self> {
        Self::decode_with_overlay(frame).map(|(m, _)| m)
    }

    /// Decode a frame together with the overlay describing how its field set differed from this
    /// build's — what a forwarding peer needs in order to re-emit what it was given rather than
    /// its own idea of it.
    pub fn decode_with_overlay(frame: &[u8]) -> Result<(Self, crate::tlv::TlvOverlay)> {
        let parsed = crate::frame::NoderaFrame::decode(frame)?;
        crate::wire::decode_rendezvous_body_with_overlay(parsed.kind, &parsed.body)
    }

    /// Encode a frame, re-emitting fields this build did not understand when it was decoded.
    pub fn encode_with_overlay(&self, overlay: &crate::tlv::TlvOverlay) -> Result<Vec<u8>> {
        Ok(crate::frame::NoderaFrame {
            epoch: crate::frame::WIRE_EPOCH,
            kind: self.tag(),
            flags: crate::frame::flags::EVENT,
            correlation_id: 0,
            body: overlay.apply_to(&crate::wire::encode_rendezvous_body(self))?,
        }
        .encode())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{CandidateKind, NodeCapabilities, PeerRole, RegistrationEvent};

    fn caps() -> NodeCapabilities {
        NodeCapabilities {
            logical_cores: 8,
            memory_bytes: 16 << 30,
            latency_ms: 40,
            reliability_bits: 0x3FF0_0000_0000_0000,
            max_primary_regions: 4,
            max_validator_regions: 8,
            accepts_worker: true,
            roles: vec![PeerRole::PartialArchive],
        }
    }

    fn record() -> SignedPeerRecord {
        SignedPeerRecord {
            network_id: NetworkId::new(1, 2),
            genesis_hash: vec![0x11; 32],
            peer: NodeId::new(3, 4),
            public_key: vec![0x66; 44],
            event: RegistrationEvent::Register,
            candidates: vec![PeerCandidate {
                kind: CandidateKind::Host,
                address: "10.0.0.4:25566".to_owned(),
                priority: 100,
            }],
            capabilities: caps(),
            issued_at_epoch_millis: 1_700_000_000_000,
            expires_at_epoch_millis: 1_700_000_300_000,
        }
    }

    fn samples() -> Vec<RendezvousMessage> {
        vec![
            RendezvousMessage::Register(RendezvousRegister {
                signed: SignedRecord {
                    record: record(),
                    signature: vec![0x77; 64],
                },
            }),
            RendezvousMessage::Discover(RendezvousDiscover {
                network_id: NetworkId::new(1, 2),
                genesis_hash: vec![0x11; 32],
                cursor: 0,
                limit: 50,
            }),
            RendezvousMessage::Peers(RendezvousPeers {
                next_cursor: 7,
                records: vec![SignedRecord {
                    record: record(),
                    signature: vec![0x77; 64],
                }],
            }),
            RendezvousMessage::Reserve(RelayReserve {
                network_id: NetworkId::new(1, 2),
                genesis_hash: vec![0x11; 32],
                peer: NodeId::new(3, 4),
            }),
            RendezvousMessage::Reservation(RelayReservation {
                accepted: true,
                relay_route: "relay.example:25601".to_owned(),
                expires_at_epoch_millis: 1_700_000_300_000,
                max_bytes: 64 << 20,
                max_duration_millis: 300_000,
                proof: vec![0x55; 32],
                reason: String::new(),
            }),
            RendezvousMessage::Connect(RelayConnect {
                network_id: NetworkId::new(1, 2),
                genesis_hash: vec![0x11; 32],
                source: NodeId::new(5, 6),
                target: NodeId::new(3, 4),
            }),
            RendezvousMessage::Incoming(RelayIncoming {
                network_id: NetworkId::new(1, 2),
                genesis_hash: vec![0x11; 32],
                source: NodeId::new(5, 6),
                target: NodeId::new(3, 4),
                proof: vec![0x55; 32],
            }),
            RendezvousMessage::PunchSync(PunchSync {
                network_id: NetworkId::new(1, 2),
                genesis_hash: vec![0x11; 32],
                source: NodeId::new(5, 6),
                target: NodeId::new(3, 4),
                observed_candidates: vec![PeerCandidate {
                    kind: CandidateKind::ServerReflexive,
                    address: "198.51.100.7:40000".to_owned(),
                    priority: 50,
                }],
                go_signal_epoch_millis: 1_700_000_001_000,
            }),
            RendezvousMessage::ObservedAddress(ObservedAddress {
                peer: NodeId::new(5, 6),
                observed_route: "198.51.100.7:40000".to_owned(),
            }),
        ]
    }

    #[test]
    fn every_message_round_trips_byte_exactly() {
        for msg in samples() {
            let bytes = msg.encode();
            let decoded = RendezvousMessage::decode(&bytes).unwrap();
            assert_eq!(decoded, msg, "decode mismatch for tag {}", msg.tag());
            assert_eq!(decoded.encode(), bytes, "re-encode mismatch");
        }
    }

    #[test]
    fn trailing_bytes_reject_the_frame() {
        // The frame declares its own body length, so an extra byte is caught at the header rather
        // than after a body has already been interpreted.
        let mut bytes = samples()[0].encode();
        bytes.push(0);
        assert!(RendezvousMessage::decode(&bytes).is_err());
    }

    #[test]
    fn a_register_records_signature_covers_the_canonical_record_bytes() {
        // The signature field is over `record.signed_bytes()`, not the register frame — so the
        // same signature validates whether the record arrives in a register or a discovery page.
        let signed = SignedRecord {
            record: record(),
            signature: vec![0xAB; 64],
        };
        let in_register = RendezvousMessage::Register(RendezvousRegister {
            signed: signed.clone(),
        })
        .encode();
        let in_page = RendezvousMessage::Peers(RendezvousPeers {
            next_cursor: 0,
            records: vec![signed.clone()],
        })
        .encode();
        let from_register = match RendezvousMessage::decode(&in_register).unwrap() {
            RendezvousMessage::Register(m) => m.signed.record.signed_bytes(),
            _ => unreachable!(),
        };
        let from_page = match RendezvousMessage::decode(&in_page).unwrap() {
            RendezvousMessage::Peers(m) => m.records[0].record.signed_bytes(),
            _ => unreachable!(),
        };
        assert_eq!(from_register, from_page);
        assert_eq!(from_register, record().signed_bytes());
    }

    #[test]
    fn an_unknown_kind_is_named_rather_than_guessed_at() {
        let frame = crate::frame::NoderaFrame::event(9_999, Vec::new()).encode();
        assert!(matches!(
            RendezvousMessage::decode(&frame),
            Err(crate::CodecError::UnknownTag(9_999))
        ));
    }
}
