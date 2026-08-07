//! Stamp the product version from the repository's root `VERSION` file into a binary.
//!
//! Cargo cannot read a package version from a file, so each crate's `version` field stays a mirror
//! (kept honest by `scripts/version.sh --check`) and the version a binary *prints* comes from here —
//! one file, read at compile time, invalidated when it changes.
//!
//! All three service build scripts were byte-identical copies of this (verified: same md5, and they
//! had been that way since 2026-07-28). Each is now two lines that call [`stamp_version`].

use std::path::PathBuf;

/// Emit `NODERA_VERSION` for the crate being built.
///
/// Called from a `build.rs` `main`. Falls back to the Cargo package version — loudly — for a crate
/// built outside the repository (vendored, or from a published tarball), where there is no `VERSION`
/// file to find.
pub fn stamp_version() {
    let version = find_version().unwrap_or_else(|| {
        println!("cargo:warning=VERSION not found; falling back to the Cargo package version");
        std::env::var("CARGO_PKG_VERSION").unwrap_or_else(|_| "0.0.0".to_owned())
    });
    println!("cargo:rustc-env=NODERA_VERSION={version}");
}

/// Walk up from the crate directory until a `VERSION` file appears.
fn find_version() -> Option<String> {
    let mut directory = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").ok()?);
    loop {
        let candidate = directory.join("VERSION");
        if candidate.is_file() {
            println!("cargo:rerun-if-changed={}", candidate.display());
            let text = std::fs::read_to_string(&candidate).ok()?;
            return text
                .lines()
                .map(|line| line.split('#').next().unwrap_or("").trim())
                .find(|line| !line.is_empty())
                .map(str::to_owned);
        }
        if !directory.pop() {
            return None;
        }
    }
}
