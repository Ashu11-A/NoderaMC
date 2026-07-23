//! Asserts the Rust tag registry mirrors the Java one (Task 27 acceptance #2).
//!
//! Appending a tag on one side without the other fails here — the append-only wire contract is
//! only frozen if both implementations agree on the numbers.

use std::path::{Path, PathBuf};

fn repo_root() -> PathBuf {
    // CARGO_MANIFEST_DIR = <repo>/rust/nodera-codec
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("repo root")
        .to_path_buf()
}

fn java_source(relative: &str) -> String {
    let path = repo_root().join(relative);
    std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read Java source {}: {e}", path.display()))
}

/// Extract `public static final int <NAME> = <VALUE>;` from a Java source, ignoring whitespace.
fn java_int_constant(source: &str, name: &str) -> Option<u16> {
    for line in source.lines() {
        let line = line.trim();
        let Some(idx) = line.find(name) else {
            continue;
        };
        // Guard against matching a longer identifier that merely contains `name`.
        let after = &line[idx + name.len()..];
        let before_ok = idx == 0
            || !line.as_bytes()[idx - 1].is_ascii_alphanumeric()
                && line.as_bytes()[idx - 1] != b'_';
        if !before_ok {
            continue;
        }
        let after = after.trim_start();
        if !after.starts_with('=') {
            continue;
        }
        let value: String = after[1..]
            .trim()
            .chars()
            .take_while(|c| c.is_ascii_digit())
            .collect();
        if !value.is_empty() {
            return value.parse().ok();
        }
    }
    None
}

fn assert_mirrors(source: &str, java_name: &str, rust_value: u16, file: &str) {
    let java_value = java_int_constant(source, java_name)
        .unwrap_or_else(|| panic!("{java_name} not found in {file}"));
    assert_eq!(
        java_value, rust_value,
        "{file}:{java_name} is {java_value} in Java but {rust_value} in nodera-codec — the tag \
         registry is a frozen, append-only wire contract; fix the mirror, never renumber"
    );
}

#[test]
fn type_tag_registry_mirrors_java() {
    const FILE: &str = "java/core/src/main/java/dev/nodera/core/crypto/TypeTags.java";
    let source = java_source(FILE);
    use nodera_codec::tags::type_tags::*;
    assert_mirrors(&source, "NODE_ID", NODE_ID, FILE);
    assert_mirrors(&source, "NODE_CAPABILITIES", NODE_CAPABILITIES, FILE);
    assert_mirrors(&source, "PEER_ROLE", PEER_ROLE, FILE);
    assert_mirrors(&source, "WORLD_HEALTH", WORLD_HEALTH, FILE);
    assert_mirrors(&source, "PEER_CANDIDATE", PEER_CANDIDATE, FILE);
    assert_mirrors(&source, "SIGNED_PEER_RECORD", SIGNED_PEER_RECORD, FILE);
    assert_mirrors(&source, "ENTITY_MUTATION", ENTITY_MUTATION, FILE);
    assert_mirrors(&source, "INVENTORY_CREDIT", INVENTORY_CREDIT, FILE);
    assert_mirrors(&source, "ENTITY_TRANSFER_CERT", ENTITY_TRANSFER_CERT, FILE);
    assert_mirrors(
        &source,
        "ENTITY_TRANSFER_PREPARED_EVENT",
        ENTITY_TRANSFER_PREPARED_EVENT,
        FILE,
    );
    assert_mirrors(
        &source,
        "ENTITY_TRANSFER_COMMITTED_EVENT",
        ENTITY_TRANSFER_COMMITTED_EVENT,
        FILE,
    );
    assert_mirrors(
        &source,
        "ENTITY_TRANSFER_INTENT",
        ENTITY_TRANSFER_INTENT,
        FILE,
    );
    assert_mirrors(
        &source,
        "CERTIFIED_WORLD_GENESIS",
        CERTIFIED_WORLD_GENESIS,
        FILE,
    );
    assert_mirrors(&source, "NEXT", NEXT, FILE);
}

#[test]
fn message_tag_registry_mirrors_java() {
    const FILE: &str = "java/transport/src/main/java/dev/nodera/protocol/codec/MessageCodec.java";
    let source = java_source(FILE);
    use nodera_codec::tags::message_tags::*;
    assert_mirrors(&source, "TAG_TRACKER_QUERY", TRACKER_QUERY, FILE);
    assert_mirrors(&source, "TAG_TRACKER_RESPONSE", TRACKER_RESPONSE, FILE);
    assert_mirrors(
        &source,
        "TAG_INVENTORY_ADVERTISEMENT",
        INVENTORY_ADVERTISEMENT,
        FILE,
    );
    assert_mirrors(&source, "TAG_TRACKER_ANNOUNCE", TRACKER_ANNOUNCE, FILE);
    assert_mirrors(
        &source,
        "TAG_TRACKER_ANNOUNCE_ACK",
        TRACKER_ANNOUNCE_ACK,
        FILE,
    );
    assert_mirrors(
        &source,
        "TAG_RENDEZVOUS_REGISTER",
        RENDEZVOUS_REGISTER,
        FILE,
    );
    assert_mirrors(
        &source,
        "TAG_RENDEZVOUS_DISCOVER",
        RENDEZVOUS_DISCOVER,
        FILE,
    );
    assert_mirrors(&source, "TAG_RENDEZVOUS_PEERS", RENDEZVOUS_PEERS, FILE);
    assert_mirrors(&source, "TAG_RELAY_RESERVE", RELAY_RESERVE, FILE);
    assert_mirrors(&source, "TAG_RELAY_RESERVATION", RELAY_RESERVATION, FILE);
    assert_mirrors(&source, "TAG_RELAY_CONNECT", RELAY_CONNECT, FILE);
    assert_mirrors(&source, "TAG_RELAY_INCOMING", RELAY_INCOMING, FILE);
    assert_mirrors(&source, "TAG_PUNCH_SYNC", PUNCH_SYNC, FILE);
    assert_mirrors(&source, "TAG_OBSERVED_ADDRESS", OBSERVED_ADDRESS, FILE);
    assert_mirrors(
        &source,
        "TAG_TRACKER_ROUTES_QUERY",
        TRACKER_ROUTES_QUERY,
        FILE,
    );
    assert_mirrors(
        &source,
        "TAG_TRACKER_ROUTES_RESPONSE",
        TRACKER_ROUTES_RESPONSE,
        FILE,
    );
    assert_mirrors(&source, "TAG_EVENT_SYNC_QUERY", EVENT_SYNC_QUERY, FILE);
    assert_mirrors(&source, "TAG_EVENT_SYNC_ANSWER", EVENT_SYNC_ANSWER, FILE);
    assert_mirrors(&source, "NEXT_TAG", NEXT_TAG, FILE);
}

#[test]
fn encoding_version_mirrors_java() {
    const FILE: &str = "java/transport/src/main/java/dev/nodera/protocol/codec/MessageCodec.java";
    let source = java_source(FILE);
    assert_mirrors(
        &source,
        "ENCODING_VERSION",
        nodera_codec::ENCODING_VERSION,
        FILE,
    );
    assert_mirrors(
        &source,
        "SESSION_KEEP_ALIVE_ENCODING_VERSION",
        nodera_codec::SESSION_KEEP_ALIVE_ENCODING_VERSION,
        FILE,
    );
}

#[test]
fn supported_tags_are_a_subset_of_the_java_registry() {
    for tag in nodera_codec::tags::SUPPORTED_MESSAGE_TAGS {
        assert!(
            *tag <= nodera_codec::tags::message_tags::NEXT_TAG,
            "tag {tag} is above the highest assigned Java tag"
        );
    }
}
