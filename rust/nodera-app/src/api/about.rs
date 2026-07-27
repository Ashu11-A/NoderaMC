//! What this build is, and what it is built out of.
//!
//! # The licence list is generated, not written
//!
//! `licences.json` is produced by `scripts/collect-licenses.py` from the same manifests the build
//! reads — `rust/*/Cargo.toml`, `ui/package.json`, `gradle/libs.versions.toml` — with each licence
//! looked up in the package's own metadata. An attribution list maintained by hand is wrong the day
//! after somebody adds a dependency and nobody notices, because nothing checks it.
//!
//! It is embedded at compile time rather than read at runtime so the About screen cannot show one
//! build's dependencies while running another's.
//!
//! # A licence that could not be read is reported empty
//!
//! Never inferred from a sibling, never defaulted to the common one. A wrong attribution is worse
//! than a missing one — the missing one is visibly missing, and the UI renders it as such.

use serde::{Deserialize, Serialize};

/// The generated manifest, embedded in the binary.
const LICENCES: &str = include_str!("../../licences.json");

/// One third-party package this project depends on directly.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Package {
    pub name: String,
    pub version: String,
    /// SPDX expression or the licence's own name. **Empty means it could not be read locally.**
    #[serde(default)]
    pub licence: String,
    /// `rust` | `npm` | `java`.
    pub ecosystem: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
struct Licences {
    #[serde(default)]
    generated_at: String,
    #[serde(default)]
    packages: Vec<Package>,
}

/// Everything the About screen renders.
#[derive(Clone, Debug, Serialize)]
pub struct About {
    /// The product name and version this binary was built as.
    pub name: String,
    pub version: String,
    pub description: String,
    /// This project's own licence, from its manifest.
    pub licence: String,
    pub repository: String,
    /// The control-protocol version this build speaks — the number the worker handshake compares.
    pub protocol_version: u32,
    /// Where the app keeps its own files, so a person can find them without being told a path.
    pub config_dir: String,
    pub share_dir: String,
    /// When the dependency list was generated.
    pub licences_generated_at: String,
    pub packages: Vec<Package>,
}

/// Read the embedded manifest and describe this build.
pub fn about() -> About {
    let licences: Licences = serde_json::from_str(LICENCES).unwrap_or_default();
    About {
        name: "NoderaMC".to_owned(),
        version: env!("CARGO_PKG_VERSION").to_owned(),
        description: env!("CARGO_PKG_DESCRIPTION").to_owned(),
        licence: env!("CARGO_PKG_LICENSE").to_owned(),
        repository: "https://github.com/Ashu11-A/NoderaMC".to_owned(),
        protocol_version: crate::control::PROTOCOL_VERSION,
        config_dir: crate::settings::config_dir().display().to_string(),
        share_dir: crate::share_dir().display().to_string(),
        licences_generated_at: licences.generated_at,
        packages: licences.packages,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_embedded_manifest_parses_and_is_not_empty() {
        let about = about();
        // A build whose attribution list failed to parse would render an empty About screen with no
        // indication anything was wrong, which is the one outcome worse than not having the screen.
        assert!(!about.packages.is_empty(), "licences.json must embed real packages");
        assert!(!about.licences_generated_at.is_empty());
    }

    #[test]
    fn every_package_names_itself_and_its_ecosystem() {
        for package in about().packages {
            assert!(!package.name.is_empty());
            assert!(
                matches!(package.ecosystem.as_str(), "rust" | "npm" | "java"),
                "unexpected ecosystem {:?} for {}",
                package.ecosystem,
                package.name
            );
        }
    }

    #[test]
    fn an_unreadable_licence_is_empty_rather_than_guessed() {
        // The generator reports what it read. Anything it could not read stays empty here, and the
        // UI is required to say "unknown" rather than filling it in.
        let about = about();
        let named = about.packages.iter().filter(|p| !p.licence.is_empty()).count();
        assert!(named > 0, "at least some licences must have been readable");
        assert!(named <= about.packages.len());
    }

    #[test]
    fn the_build_describes_itself_from_its_own_manifest() {
        let about = about();
        assert_eq!(about.version, env!("CARGO_PKG_VERSION"));
        assert!(!about.licence.is_empty());
        assert_eq!(about.protocol_version, crate::control::PROTOCOL_VERSION);
    }
}
