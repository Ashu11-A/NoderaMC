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
use nodera_codec::tombstone::WorldDeletionGossip;
use std::path::{Path, PathBuf};

/// Bit patterns XOR-ed into a byte: low bit, high bit, and a full flip.
const PATTERNS: [u8; 3] = [0x01, 0x80, 0xFF];

/// Cap on mutation sites per fixture, so a large record does not dominate the run.
const MAX_SITES: usize = 96;

/// Body mutations start after the 22-byte `NDR2` header: mutating the header
/// selects a different message rather than corrupting this one.
const HEADER_BYTES: usize = nodera_codec::frame::HEADER_BYTES;

fn fixtures_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("repo root")
        .join("fixtures")
        .join("wire")
}

fn fixture_files() -> Vec<PathBuf> {
    let dir = fixtures_dir();
    let entries = std::fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("cannot read fixtures dir {}: {e}", dir.display()));
    let mut files: Vec<PathBuf> = entries
        .map(|e| e.expect("dir entry").path())
        .filter(|p| p.extension().is_some_and(|ext| ext == "bin"))
        .collect();
    files.sort();
    files
}

/// Decode with the family that owns `tag` and re-encode, or report why it was rejected.
fn round_trip_as(tag: u16, bytes: &[u8]) -> Result<Vec<u8>, String> {
    // Re-encoded WITH whatever this build could not read, because that is what forwarding does: a
    // peer that drops the fields it does not understand is a lossy relay between two peers that
    // understand each other perfectly well.
    if tag == message_tags::WORLD_DELETION_GOSSIP {
        // A consensus kind: strict canonical bytes inside one opaque field.
        let payload = nodera_codec::wire::consensus_payload(bytes).map_err(|e| e.to_string())?;
        let gossip = WorldDeletionGossip::decode(payload).map_err(|e| e.to_string())?;
        let body = nodera_codec::tlv::TlvBody::parse(&bytes[nodera_codec::frame::HEADER_BYTES..])
            .map_err(|e| e.to_string())?;
        let _ = body.bytes(nodera_codec::wire::CONSENSUS_PAYLOAD_FIELD);
        let overlay = body.overlay();
        let mut w = nodera_codec::tlv::TlvWriter::new();
        w.bytes(
            nodera_codec::wire::CONSENSUS_PAYLOAD_FIELD,
            &gossip.encode(),
        );
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

    for path in fixture_files() {
        let golden = std::fs::read(&path).expect("read fixture");
        let name = path.file_name().unwrap().to_string_lossy().into_owned();
        let tag = u16::from_be_bytes([golden[0], golden[1]]);
        let stride = std::cmp::max(1, golden.len() / MAX_SITES);

        let mut offset = HEADER_BYTES;
        while offset < golden.len() {
            for pattern in PATTERNS {
                let mut mutated = golden.clone();
                mutated[offset] ^= pattern;
                if mutated == golden {
                    continue;
                }
                if let Ok(reencoded) = round_trip_as(tag, &mutated) {
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
    }

    assert!(
        violations.is_empty(),
        "a frame that decodes must re-encode to itself; these do not:\n{}",
        violations.join("\n")
    );
}
