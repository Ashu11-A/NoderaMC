//! Cross-language golden-file conformance (Task 27 acceptance #2).
//!
//! The Java side emits `fixtures/wire/*.bin` from its own wire codec (see `WireFixtureTest`); this
//! crate decodes each file and re-encodes it, asserting byte identity. The corpus pins `NDR2`
//! frames with TLV infrastructure bodies; the frames these files used to hold live on in
//! `fixtures/wire/v1-rejected/`, where both implementations assert they are refused.
//! Byte identity — not field equality — is the contract: the same bytes are hashed and signed on
//! both sides, so a re-encoding that differs by one byte is a consensus break, not a nit.

use nodera_codec::messages::DiscoveryMessage;
use nodera_codec::rendezvous::RendezvousMessage;
use nodera_codec::service::ServiceMessage;
use nodera_codec::tags::message_tags;
use nodera_codec::tombstone::{WorldDeletionGossip, WorldRevivalGossip};

mod common;
use common::{fixture_files, fixtures_dir, name_of, tag_of};

/// Decode a golden frame with whichever family owns `tag`, and re-encode it.
///
/// A fixture carries its wire tag in its first two big-endian bytes, so the corpus can mix the
/// discovery family (Task 20/28), the rendezvous family (Task 29), and the service directory in one
/// directory; each is routed to the codec that owns it.
///
/// The tag is passed in rather than read from `bytes` so the malformed-input tests can route a
/// *truncated* frame — which may be shorter than its own tag — to the decoder that would really
/// have received it. Routing everything to `DiscoveryMessage`, as this file used to, meant a
/// rendezvous fixture was rejected for being an unknown tag and the test never proved the thing it
/// claimed to: that trailing bytes are detected.
fn round_trip_as(tag: u16, bytes: &[u8]) -> Result<Vec<u8>, String> {
    if tag == message_tags::WORLD_DELETION_GOSSIP {
        // A CONSENSUS kind: it crosses the tolerant plane as one opaque field holding its strict
        // canonical bytes, so the payload is unwrapped before the record is read and re-wrapped
        // after. Byte identity is necessary but not sufficient for this one anyway — the whole
        // point of the record is that the Rust side reaches the SAME verdict as Java on the same
        // signatures.
        let payload = nodera_codec::wire::consensus_payload(bytes).map_err(|e| e.to_string())?;
        let gossip = WorldDeletionGossip::decode(payload).map_err(|e| e.to_string())?;
        let verified = gossip
            .verified()
            .ok_or_else(|| "the golden deletion did not verify in Rust".to_string())?;
        if verified.world_id != gossip.world_id {
            return Err("envelope and proof disagree about the world".into());
        }
        return Ok(nodera_codec::wire::encode_consensus_frame(
            message_tags::WORLD_DELETION_GOSSIP,
            &gossip.encode(),
            nodera_codec::frame::flags::EVENT,
            0,
        ));
    }
    if tag == message_tags::WORLD_REVIVAL_GOSSIP {
        // The deletion's undo, handled identically: opaque strict bytes across the tolerant plane,
        // and the verdict — not just the byte layout — has to match Java's.
        let payload = nodera_codec::wire::consensus_payload(bytes).map_err(|e| e.to_string())?;
        let gossip = WorldRevivalGossip::decode(payload).map_err(|e| e.to_string())?;
        let verified = gossip
            .verified()
            .ok_or_else(|| "the golden restore did not verify in Rust".to_string())?;
        if verified.world_id != gossip.world_id {
            return Err("envelope and proof disagree about the world".into());
        }
        return Ok(nodera_codec::wire::encode_consensus_frame(
            message_tags::WORLD_REVIVAL_GOSSIP,
            &gossip.encode(),
            nodera_codec::frame::flags::EVENT,
            0,
        ));
    }
    if (message_tags::RENDEZVOUS_REGISTER..=message_tags::OBSERVED_ADDRESS).contains(&tag) {
        RendezvousMessage::decode(bytes)
            .map(|m| m.encode())
            .map_err(|e| e.to_string())
    } else if (message_tags::SERVICE_ANNOUNCE..=message_tags::SERVICE_DRAIN_NOTICE).contains(&tag) {
        // Byte identity is necessary but not sufficient here either. A service record is signed on
        // the Rust side and verified on the Java side — the opposite direction from every other
        // signed message in this corpus — so the score's composite is also asserted to be what Java
        // computed. A divergence there would silently reorder every peer's failover list.
        let message = ServiceMessage::decode(bytes).map_err(|e| e.to_string())?;
        if let ServiceMessage::DirectoryResponse(response) = &message {
            for entry in &response.entries {
                if entry.score.composite_permille != entry.score.recomputed_composite() {
                    return Err(format!(
                        "Java wrote composite {} where Rust computes {} — the score function has \
                         drifted between the implementations",
                        entry.score.composite_permille,
                        entry.score.recomputed_composite()
                    ));
                }
            }
        }
        Ok(message.encode())
    } else {
        DiscoveryMessage::decode(bytes)
            .map(|m| m.encode())
            .map_err(|e| e.to_string())
    }
}

#[test]
fn every_java_fixture_round_trips_byte_exactly() {
    let files = fixture_files();
    assert!(
        !files.is_empty(),
        "no golden fixtures found in {} — run `./gradlew :protocol:test` to emit them",
        fixtures_dir().display()
    );
    for path in files {
        let golden = std::fs::read(&path).expect("read fixture");
        let name = name_of(&path);
        let reencoded = round_trip_as(tag_of(&golden), &golden)
            .unwrap_or_else(|e| panic!("{name}: Java-emitted fixture failed to decode: {e}"));
        assert_eq!(
            reencoded, golden,
            "{name}: re-encoding differs from the Java golden bytes"
        );
    }
}

#[test]
fn truncating_a_fixture_is_rejected_rather_than_panicking() {
    // Every prefix of a valid frame is malformed input arriving pre-auth: it must error cleanly,
    // and it must ERROR — the previous version of this test called the decoder and discarded the
    // result, so a decoder that happily accepted a half-frame would have passed it.
    for path in fixture_files() {
        let golden = std::fs::read(&path).expect("read fixture");
        let tag = tag_of(&golden);
        let name = name_of(&path);
        for cut in 1..golden.len() {
            assert!(
                round_trip_as(tag, &golden[..cut]).is_err(),
                "{name}: a {cut}-byte prefix of a {}-byte frame decoded as valid",
                golden.len()
            );
        }
    }
}

#[test]
fn appending_to_a_fixture_is_rejected() {
    for path in fixture_files() {
        let mut bytes = std::fs::read(&path).expect("read fixture");
        let tag = tag_of(&bytes);
        bytes.push(0x00);
        assert!(
            round_trip_as(tag, &bytes).is_err(),
            "{}: trailing byte must invalidate the frame",
            path.display()
        );
    }
}

#[test]
fn every_retired_v1_frame_is_refused_rather_than_misparsed() {
    // The flag day, asserted rather than assumed. Each of these is a frame this project really used
    // to emit; a peer still speaking it must be told so at the first four bytes, because the
    // alternative is what the old frame actually did — begin parsing and report nonsense.
    let dir = fixtures_dir().join("v1-rejected");
    let entries =
        std::fs::read_dir(&dir).unwrap_or_else(|e| panic!("cannot read {}: {e}", dir.display()));
    let mut seen = 0usize;
    for entry in entries {
        let path = entry.expect("dir entry").path();
        if path.extension().is_none_or(|ext| ext != "bin") {
            continue;
        }
        seen += 1;
        let v1 = std::fs::read(&path).expect("read fixture");
        assert!(
            nodera_codec::frame::NoderaFrame::decode(&v1).is_err(),
            "{}: a pre-NDR2 frame must be refused, not interpreted",
            path.display()
        );
    }
    assert!(seen > 0, "the v1 rejection set is empty");
}
