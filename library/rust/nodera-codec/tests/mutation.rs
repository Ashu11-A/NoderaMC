//! The Rust half of the canonical-mutation pair.
//!
//! `java/transport/src/test/java/dev/nodera/protocol/CanonicalMutationFuzzTest.java` asserts the
//! same invariant on the same corpus: a frame either fails to decode, or it decodes to a value that
//! re-encodes to exactly the bytes it came from. Holding both implementations to it on the shared
//! golden files is what makes "the two codecs agree" a checkable claim rather than an assumption —
//! a decoder that quietly normalises its input (accepting an unsorted list, a non-0/1 boolean,
//! malformed UTF-8) produces a second wire spelling of a value that already has one, and the two
//! peers that hashed the two spellings disagree about a root while agreeing about the state.
//!
//! Mutations are deterministic — a fixed stride over byte offsets and a fixed set of bit patterns —
//! so a failure names the fixture and offset that reproduce it.

use nodera_codec::messages::DiscoveryMessage;
use nodera_codec::rendezvous::RendezvousMessage;
use nodera_codec::service::ServiceMessage;
use nodera_codec::tags::message_tags;
use nodera_codec::tombstone::{WorldDeletionGossip, WorldRevivalGossip};

mod common;
use common::{fixture_files, name_of, tag_of};

/// Bit patterns XOR-ed into a byte: low bit, high bit, and a full flip.
const PATTERNS: [u8; 3] = [0x01, 0x80, 0xFF];

/// Cap on mutation sites per fixture, so a large record does not dominate the run.
const MAX_SITES: usize = 96;

/// Body mutations start after the 22-byte `NDR2` header: mutating the header
/// selects a different message rather than corrupting this one.
const HEADER_BYTES: usize = nodera_codec::frame::HEADER_BYTES;

/// Decode with the family that owns `tag` and re-encode, or report why it was rejected.
fn round_trip_as(tag: u16, bytes: &[u8]) -> Result<Vec<u8>, String> {
    // Re-encoded WITH whatever this build could not read, because that is what forwarding does: a
    // peer that drops the fields it does not understand is a lossy relay between two peers that
    // understand each other perfectly well.
    // Both consensus kinds: strict canonical bytes inside one opaque field. `WORLD_REVIVAL_GOSSIP`
    // (76) used to be absent from this list, and 76 is not 66, not in the rendezvous range and not
    // in the service range — so every mutation of `world-revival-gossip.bin` fell through to
    // `DiscoveryMessage`, came back `UnknownTag(76)`, and was thrown away by the `if let Ok(…)`
    // below. `fixtures.rs` had always routed 76 correctly, which is why the two files disagreed.
    if tag == message_tags::WORLD_DELETION_GOSSIP || tag == message_tags::WORLD_REVIVAL_GOSSIP {
        let payload = nodera_codec::wire::consensus_payload(bytes).map_err(|e| e.to_string())?;
        let strict = if tag == message_tags::WORLD_DELETION_GOSSIP {
            WorldDeletionGossip::decode(payload)
                .map(|g| g.encode())
                .map_err(|e| e.to_string())?
        } else {
            WorldRevivalGossip::decode(payload)
                .map(|g| g.encode())
                .map_err(|e| e.to_string())?
        };
        let body = nodera_codec::tlv::TlvBody::parse(&bytes[nodera_codec::frame::HEADER_BYTES..])
            .map_err(|e| e.to_string())?;
        let _ = body.bytes(nodera_codec::wire::CONSENSUS_PAYLOAD_FIELD);
        let overlay = body.overlay();
        let mut w = nodera_codec::tlv::TlvWriter::new();
        w.bytes(nodera_codec::wire::CONSENSUS_PAYLOAD_FIELD, &strict);
        let rebuilt = overlay.apply_to(&w.finish()).map_err(|e| e.to_string())?;
        return Ok(nodera_codec::frame::NoderaFrame::event(tag, rebuilt).encode());
    }
    if (message_tags::RENDEZVOUS_REGISTER..=message_tags::OBSERVED_ADDRESS).contains(&tag) {
        let (m, overlay) =
            RendezvousMessage::decode_with_overlay(bytes).map_err(|e| e.to_string())?;
        m.encode_with_overlay(&overlay).map_err(|e| e.to_string())
    } else if (message_tags::SERVICE_ANNOUNCE..=message_tags::SERVICE_DRAIN_NOTICE).contains(&tag) {
        let (m, overlay) = ServiceMessage::decode_with_overlay(bytes).map_err(|e| e.to_string())?;
        m.encode_with_overlay(&overlay).map_err(|e| e.to_string())
    } else {
        let (m, overlay) =
            DiscoveryMessage::decode_with_overlay(bytes).map_err(|e| e.to_string())?;
        m.encode_with_overlay(&overlay).map_err(|e| e.to_string())
    }
}

#[test]
fn decoded_mutations_re_encode_to_their_own_bytes() {
    let mut violations: Vec<String> = Vec::new();
    // The cardinality this test never reported. A rejected mutation is indistinguishable from an
    // unreachable one here — both are "no violation" — so without counting what actually decoded,
    // a routing bug, an always-`Err` decoder and an empty corpus all read as a pass. That is
    // precisely how tag 76 went unexercised: it was routed to the wrong family, rejected as an
    // unknown tag, and the `if let Ok(…)` below threw the rejection away.
    let mut unroutable: Vec<String> = Vec::new();
    let mut never_decoded: Vec<String> = Vec::new();
    let mut fixtures = 0usize;
    let mut accepted_total = 0usize;

    let files = fixture_files();
    assert!(
        !files.is_empty(),
        "no golden fixtures found — this test asserted nothing"
    );

    for path in files {
        let golden = std::fs::read(&path).expect("read fixture");
        let name = name_of(&path);
        // Through `common::tag_of`, which reads the NDR2 header. This line used to be
        // `u16::from_be_bytes([golden[0], golden[1]])` — the first two bytes of the magic, which
        // is no tag at all, so `round_trip_as` fell through to `DiscoveryMessage` for every
        // fixture and the rendezvous and service frames were decode-failed and silently skipped.
        // Extracting the helper is what made the two files' disagreement visible.
        let tag = tag_of(&golden);
        fixtures += 1;

        // Routing, asserted before anything is mutated: if the UNMUTATED golden frame does not
        // decode, every mutation of it is being skipped for a reason that has nothing to do with
        // the mutation, and the loop below proves nothing about this file.
        if let Err(why) = round_trip_as(tag, &golden) {
            unroutable.push(format!("{name} (kind {tag}): {why}"));
            continue;
        }

        let stride = std::cmp::max(1, golden.len() / MAX_SITES);
        let mut accepted = 0usize;
        let mut offset = HEADER_BYTES;
        while offset < golden.len() {
            for pattern in PATTERNS {
                let mut mutated = golden.clone();
                mutated[offset] ^= pattern;
                if mutated == golden {
                    continue;
                }
                if let Ok(reencoded) = round_trip_as(tag, &mutated) {
                    accepted += 1;
                    if reencoded != mutated {
                        violations.push(format!(
                            "{name} @{offset}^{pattern:02x}: accepted a frame that re-encodes to \
                             different bytes"
                        ));
                    }
                }
            }
            offset += stride;
        }
        accepted_total += accepted;
        if accepted == 0 {
            never_decoded.push(format!("{name} (kind {tag})"));
        }
    }

    assert!(
        unroutable.is_empty(),
        "these fixtures do not decode AT ALL, so every mutation of them is silently skipped — \
         the tag is routed to the wrong codec family:\n{}",
        unroutable.join("\n")
    );
    assert!(
        never_decoded.is_empty(),
        "these fixtures decoded as golden bytes but rejected every single mutation, so the \
         re-encode invariant was never exercised on them:\n{}",
        never_decoded.join("\n")
    );
    assert!(
        accepted_total >= fixtures,
        "only {accepted_total} mutated frames decoded across {fixtures} fixtures — the corpus is \
         being skipped rather than checked"
    );
    assert!(
        violations.is_empty(),
        "a frame that decodes must re-encode to itself; these do not:\n{}",
        violations.join("\n")
    );
}
