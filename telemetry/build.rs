//! Stamp the product version from the repository's root `VERSION` file into the binary.
//!
//! Cargo cannot read a package version from a file, so the crate's `version` field stays a mirror
//! (kept honest by `scripts/version.sh --check`) and the version this binary *prints* comes from
//! here — one file, read at compile time, invalidated when it changes.

use std::path::PathBuf;

fn main() {
    let version = find_version().unwrap_or_else(|| {
        // A crate built outside the repository (vendored, published tarball) has no VERSION file.
        // Falling back to the Cargo metadata keeps that build working and keeps the fallback
        // visible in the output rather than pretending the file was found.
        println!("cargo:warning=VERSION not found; falling back to the Cargo package version");
        env!("CARGO_PKG_VERSION").to_owned()
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
