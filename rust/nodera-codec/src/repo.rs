//! The repository layout table, read from `/layout.properties`.
//!
//! # Why this is here
//!
//! Six places in this workspace used to locate the repository root by counting `..` segments —
//! `CARGO_MANIFEST_DIR.parent().parent()`, which encodes "this crate is exactly two levels below
//! the root" in six files that have no reason to know their own depth. Three of them then appended
//! a hardcoded `java/...` path on top. That is a layout table, written six times, in a form that
//! fails only when a test happens to run.
//!
//! So: walk up to the root instead of counting, and read directories from the one manifest that
//! Gradle, the Java harness, the shell suites and the release scripts all read. Its Java
//! counterpart is `dev.nodera.testkit.harness.LayoutManifest`.
//!
//! # Scope
//!
//! This is a **source-checkout tool**, not product API. It resolves paths inside a working copy and
//! means nothing to a service running from a container, which is why every caller today is a test.
//! It lives in `nodera-codec` because that is the one crate every other crate — including the
//! excluded `nodera-app` — already depends on.

use std::path::{Path, PathBuf};

/// The repository root: the directory holding both `VERSION` and `settings.gradle.kts`.
///
/// Both markers are required so a stray `VERSION` in a subdirectory cannot end the walk early.
/// `CARGO_MANIFEST_DIR` expands at compile time to *this* crate's directory even when the call
/// comes from another crate — which is the point: the walk terminates at the root regardless of how
/// deep the caller sits, so no caller has to know.
///
/// # Panics
///
/// If no directory above this crate holds both markers, which means the crate was built outside a
/// source checkout and no caller of this module can do anything useful anyway.
pub fn root() -> PathBuf {
    let start = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let mut directory = start.clone();
    loop {
        if directory.join("VERSION").is_file() && directory.join("settings.gradle.kts").is_file() {
            return directory;
        }
        if !directory.pop() {
            panic!(
                "no repository root (a directory holding both VERSION and settings.gradle.kts) \
                 above {}",
                start.display()
            );
        }
    }
}

/// An absolute directory from `layout.properties`, by full key.
///
/// Keys are the manifest's own: `module.core`, `crate.nodera-tracker`, `dir.cargoTarget`, …
///
/// # Panics
///
/// If the manifest is unreadable or has no such key — both of which are build-configuration errors
/// a test can only report, not recover from.
pub fn path(key: &str) -> PathBuf {
    let repo = root();
    let manifest = repo.join("layout.properties");
    let text = std::fs::read_to_string(&manifest).unwrap_or_else(|e| {
        panic!(
            "cannot read the layout manifest {}: {e}",
            manifest.display()
        )
    });
    let value = parse(&text, key).unwrap_or_else(|| {
        panic!("{} has no key '{key}'", manifest.display());
    });
    repo.join(value)
}

/// The directory of one Gradle module, by its project name (`core`, `neoforge-mod`, …).
pub fn module(name: &str) -> PathBuf {
    path(&format!("module.{name}"))
}

/// The directory of one Cargo crate, by its package name (`nodera-codec`, `nodera-app`, …).
pub fn crate_dir(name: &str) -> PathBuf {
    path(&format!("crate.{name}"))
}

/// A well-known directory that is neither a module nor a crate (`cargoTarget`, `artifacts`, …).
pub fn dir(name: &str) -> PathBuf {
    path(&format!("dir.{name}"))
}

/// `fixtures/wire` — the golden canonical frames both encodings are held to.
pub fn wire_fixtures() -> PathBuf {
    root().join("fixtures").join("wire")
}

/// Minimal `java.util.Properties` read: `key = value`, `#` comments, blank lines.
///
/// Deliberately not a dependency. The manifest format is constrained to exactly this subset —
/// no escapes, no continuations, no interpolation — precisely so five languages can each parse it
/// in ten lines rather than agreeing on a crate.
fn parse<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    text.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#') && !line.starts_with('!'))
        .filter_map(|line| line.split_once('='))
        .find(|(name, _)| name.trim() == key)
        .map(|(_, value)| value.trim())
}

/// True when `candidate` is inside the repository — the guard a path-composing test wants.
pub fn contains(candidate: &Path) -> bool {
    candidate.starts_with(root())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_root_holds_both_markers() {
        let root = root();
        assert!(
            root.join("VERSION").is_file(),
            "VERSION at {}",
            root.display()
        );
        assert!(root.join("settings.gradle.kts").is_file());
        assert!(root.join("layout.properties").is_file());
    }

    #[test]
    fn every_manifest_directory_exists() {
        let text = std::fs::read_to_string(root().join("layout.properties")).unwrap();
        let keys: Vec<&str> = text
            .lines()
            .map(str::trim)
            .filter(|l| !l.is_empty() && !l.starts_with('#'))
            .filter_map(|l| l.split_once('='))
            .map(|(k, _)| k.trim())
            .collect();
        assert!(keys.len() >= 15, "manifest looks truncated: {keys:?}");
        for key in keys {
            // `dir.cargoTarget` and `dir.artifacts` are build outputs: they exist after a build,
            // not in a clean checkout. Every other key names a directory that is tracked.
            if key == "dir.cargoTarget" || key == "dir.artifacts" || key == "dir.run" {
                continue;
            }
            let resolved = path(key);
            assert!(
                resolved.is_dir(),
                "{key} → {} is not a directory",
                resolved.display()
            );
            assert!(contains(&resolved), "{key} escapes the repository root");
        }
    }

    #[test]
    fn parse_ignores_comments_and_blanks() {
        let text = "# a comment = not a key\n\nmodule.core = java/core\n  dir.web  =  site  \n";
        assert_eq!(parse(text, "module.core"), Some("java/core"));
        assert_eq!(parse(text, "dir.web"), Some("site"));
        assert_eq!(parse(text, "missing"), None);
    }
}
