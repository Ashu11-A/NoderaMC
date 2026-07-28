//! Asserts the Rust tag registry mirrors the Java one (Task 27 acceptance #2).
//!
//! Appending a tag on one side without the other fails here — the append-only wire contract is
//! only frozen if both implementations agree on the numbers.
//!
//! This check is **total**, not a hand-written list. It used to name each constant explicitly,
//! which meant a Rust constant nobody remembered to add to the list was mirrored by nothing, and a
//! renumbering *below* the `NEXT_TAG` watermark could pass. Both registries are now parsed — the
//! Rust one from this crate's own source, the Java one from `MessageCodec.java` and
//! `TypeTags.java` — and every constant on the Rust side must be present in Java with the same
//! value.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

/// This crate's tag registry, parsed rather than imported, so the test sees the constants that
/// *exist* rather than the ones somebody remembered to reference.
const RUST_TAGS: &str = include_str!("../src/tags.rs");

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

/// Every `pub const <NAME>: u16 = <VALUE>;` inside `pub mod <module>`.
fn rust_constants(module: &str) -> BTreeMap<String, u16> {
    let header = format!("pub mod {module} {{");
    let start = RUST_TAGS
        .find(&header)
        .unwrap_or_else(|| panic!("module {module} not found in nodera-codec tags.rs"))
        + header.len();
    let mut depth = 1usize;
    let mut end = start;
    for (offset, ch) in RUST_TAGS[start..].char_indices() {
        match ch {
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    end = start + offset;
                    break;
                }
            }
            _ => {}
        }
    }

    let mut out = BTreeMap::new();
    for line in RUST_TAGS[start..end].lines() {
        let line = line.trim();
        let Some(rest) = line.strip_prefix("pub const ") else {
            continue;
        };
        let Some((name, rest)) = rest.split_once(':') else {
            continue;
        };
        let Some((_, value)) = rest.split_once('=') else {
            continue;
        };
        let value: String = value
            .trim()
            .chars()
            .take_while(|c| c.is_ascii_digit())
            .collect();
        if let Ok(value) = value.parse::<u16>() {
            out.insert(name.trim().to_string(), value);
        }
    }
    assert!(!out.is_empty(), "no constants parsed out of {module}");
    out
}

/// Every `public static final int <NAME> = <VALUE>;` in a Java source.
fn java_constants(source: &str) -> BTreeMap<String, u16> {
    let mut out = BTreeMap::new();
    for line in source.lines() {
        let line = line.trim();
        let Some(idx) = line.find("public static final int ") else {
            continue;
        };
        let rest = &line[idx + "public static final int ".len()..];
        let Some((name, rest)) = rest.split_once('=') else {
            continue;
        };
        let value: String = rest
            .trim()
            .chars()
            .take_while(|c| c.is_ascii_digit())
            .collect();
        if let Ok(value) = value.parse::<u16>() {
            out.insert(name.trim().to_string(), value);
        }
    }
    assert!(!out.is_empty(), "no constants parsed out of the Java source");
    out
}

/// Assert every Rust constant is mirrored in Java under `java_name(rust_name)`.
fn assert_total_mirror(
    module: &str,
    file: &str,
    java_name: impl Fn(&str) -> String,
    watermark: (&str, &str),
) {
    let rust = rust_constants(module);
    let java = java_constants(&java_source(file));

    for (name, rust_value) in &rust {
        let expected = if name == watermark.0 {
            watermark.1.to_string()
        } else {
            java_name(name)
        };
        let java_value = java.get(&expected).unwrap_or_else(|| {
            panic!(
                "nodera-codec::{module}::{name} has no Java counterpart {expected} in {file} — \
                 either the Java constant was renamed or the Rust mirror invented a tag"
            )
        });
        assert_eq!(
            java_value, rust_value,
            "{file}:{expected} is {java_value} in Java but {rust_value} in nodera-codec — the tag \
             registry is a frozen, append-only wire contract; fix the mirror, never renumber"
        );
    }
}

#[test]
fn type_tag_registry_mirrors_java() {
    assert_total_mirror(
        "type_tags",
        "java/core/src/main/java/dev/nodera/core/crypto/TypeTags.java",
        |name| name.to_string(),
        ("NEXT", "NEXT"),
    );
}

/// The Java schema, parsed out of `WireRegistry.java`: `kind -> (name, plane)`.
///
/// Parsed rather than trusted, for the same reason the type-tag mirror is: the point of the check
/// is to see what the schema *declares*, not what somebody remembered to also write down here.
fn java_schema() -> BTreeMap<u16, (String, String)> {
    const FILE: &str = "java/transport/src/main/java/dev/nodera/protocol/wire/WireRegistry.java";
    let source = java_source(FILE);
    let mut out = BTreeMap::new();
    for line in source.lines() {
        let line = line.trim();
        let Some(rest) = line.strip_prefix("new WireKind(") else {
            continue;
        };
        let mut parts = rest.split(',');
        let Some(kind) = parts.next() else { continue };
        let Some(name) = parts.next() else { continue };
        let Some(plane) = parts.next() else { continue };
        let Ok(kind) = kind.trim().parse::<u16>() else {
            continue;
        };
        let name = name.trim().trim_matches('"').to_string();
        let plane = plane.trim().to_string();
        if let Some(previous) = out.insert(kind, (name.clone(), plane)) {
            panic!("kind {kind} is declared twice in {FILE}: {previous:?} and {name}");
        }
    }
    assert!(
        !out.is_empty(),
        "no WireKind rows parsed out of {FILE} — the row syntax changed and this mirror went blind"
    );
    out
}

#[test]
fn message_kind_registry_mirrors_the_java_schema() {
    // `kinds.rs` is generated from `WireRegistry.java`. Comparing the generated artefact against a
    // fresh parse of its source is what makes "generated" mean something: a stale file fails here,
    // and so does a kind that exists on one side only.
    let java = java_schema();
    let rust: BTreeMap<u16, (String, String)> = nodera_codec::kinds::KINDS
        .iter()
        .map(|row| {
            let plane = match row.plane {
                nodera_codec::kinds::Plane::Consensus => "CONSENSUS",
                nodera_codec::kinds::Plane::Infrastructure => "INFRASTRUCTURE",
            };
            (row.kind, (row.name.to_string(), plane.to_string()))
        })
        .collect();

    assert_eq!(
        rust, java,
        "nodera-codec/src/kinds.rs has drifted from WireRegistry.java. It is generated: run\n    \
         ./gradlew :transport:test --tests '*WireSchemaGeneratorTest' -Dnodera.wire.regenerate=true"
    );
}

#[test]
fn message_kinds_are_unique_and_contiguous() {
    // A renumbering *below* the watermark keeps `NEXT_TAG` intact, so the watermark alone proves
    // nothing. Contiguity does: a kind cannot be moved without either colliding or leaving a hole.
    let java = java_schema();
    let next = nodera_codec::kinds::message_tags::NEXT_TAG;
    assert_eq!(
        java.len(),
        usize::from(next),
        "WireRegistry declares {} kinds but NEXT_TAG is {next}",
        java.len()
    );
    for expected in 1..=next {
        assert!(
            java.contains_key(&expected),
            "kind {expected} is unassigned — the registry must stay contiguous"
        );
    }
}

#[test]
fn encoding_version_mirrors_java() {
    const FILE: &str = "java/transport/src/main/java/dev/nodera/protocol/codec/MessageCodec.java";
    let java = java_constants(&java_source(FILE));
    for (name, rust_value) in [
        ("ENCODING_VERSION", nodera_codec::ENCODING_VERSION),
        (
            "SESSION_KEEP_ALIVE_ENCODING_VERSION",
            nodera_codec::SESSION_KEEP_ALIVE_ENCODING_VERSION,
        ),
    ] {
        let java_value = java
            .get(name)
            .unwrap_or_else(|| panic!("{name} not found in {FILE}"));
        assert_eq!(
            *java_value, rust_value,
            "{FILE}:{name} is {java_value} in Java but {rust_value} in nodera-codec"
        );
    }
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
