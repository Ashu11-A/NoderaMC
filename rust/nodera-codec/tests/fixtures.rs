//! Cross-language golden-file conformance (Task 27 acceptance #2).
//!
//! The Java side emits `fixtures/wire/*.bin` from its own `MessageCodec` (see
//! `WireFixtureTest`); this crate decodes each file and re-encodes it, asserting byte identity.
//! Byte identity — not field equality — is the contract: the same bytes are hashed and signed on
//! both sides, so a re-encoding that differs by one byte is a consensus break, not a nit.

use nodera_codec::messages::DiscoveryMessage;
use nodera_codec::rendezvous::RendezvousMessage;
use nodera_codec::tags::message_tags;
use std::path::{Path, PathBuf};

/// Decode a golden frame with whichever family owns its tag, and re-encode it.
///
/// A fixture carries its wire tag in its first two big-endian bytes, so the corpus can mix the
/// discovery family (Task 20/28) and the rendezvous family (Task 29) in one directory; each is
/// routed to the codec that owns it.
fn round_trip(golden: &[u8]) -> Result<Vec<u8>, String> {
    let tag = u16::from_be_bytes([golden[0], golden[1]]);
    if (message_tags::RENDEZVOUS_REGISTER..=message_tags::OBSERVED_ADDRESS).contains(&tag) {
        RendezvousMessage::decode(golden)
            .map(|m| m.encode())
            .map_err(|e| e.to_string())
    } else {
        DiscoveryMessage::decode(golden)
            .map(|m| m.encode())
            .map_err(|e| e.to_string())
    }
}

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
        let name = path.file_name().unwrap().to_string_lossy().into_owned();
        let reencoded = round_trip(&golden)
            .unwrap_or_else(|e| panic!("{name}: Java-emitted fixture failed to decode: {e}"));
        assert_eq!(
            reencoded, golden,
            "{name}: re-encoding differs from the Java golden bytes"
        );
    }
}

#[test]
fn truncating_a_fixture_is_rejected_rather_than_panicking() {
    // Every prefix of a valid frame is malformed input arriving pre-auth: it must error cleanly.
    for path in fixture_files() {
        let golden = std::fs::read(&path).expect("read fixture");
        for cut in 1..golden.len() {
            let _ = DiscoveryMessage::decode(&golden[..cut]);
        }
    }
}

#[test]
fn appending_to_a_fixture_is_rejected() {
    for path in fixture_files() {
        let mut bytes = std::fs::read(&path).expect("read fixture");
        bytes.push(0x00);
        assert!(
            DiscoveryMessage::decode(&bytes).is_err(),
            "{}: trailing byte must invalidate the frame",
            path.display()
        );
    }
}
