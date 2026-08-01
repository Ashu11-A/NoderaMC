//! What this machine can actually launch, and how.
//!
//! # Why the mod installer's answer is not enough
//!
//! [`crate::api::modinstall`] finds *game directories*, and says outright that enumerating instances
//! is the launcher's job rather than the installer's. That was the right call for dropping a jar
//! into `mods/`: a directory is all you need to write a file.
//!
//! Launching needs a **profile** — a version id, a game directory, maybe a Java path — and on a
//! third-party launcher it needs the instance's *name*, because the way you start it is to ask the
//! launcher to. So this module reads what the installer deliberately did not.
//!
//! # Nothing here is a guess
//!
//! Every target names the file it was read from. A launcher that invents a profile sends the player
//! into a game directory they do not play in, and the failure is invisible until the game starts
//! without their mods.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::Tier;

/// Something this machine can start.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct LaunchTarget {
    /// What the player calls it — an instance name, or a profile name.
    pub name: String,
    /// How it would be started.
    pub tier: Tier,
    /// The game directory the world's files and `mods/` live in.
    pub game_dir: String,
    /// The version id to resolve, when this app assembles the command itself.
    pub version_id: Option<String>,
    /// The launcher binary, for the tiers that delegate.
    pub launcher: Option<String>,
    /// The instance name to pass to that binary.
    pub instance: Option<String>,
    /// A Java path the profile chose, if it did.
    pub java: Option<String>,
    /// Whether the Nodera mod is in this target's `mods/`.
    ///
    /// Not a filter. A player who has not installed the mod yet should see their instance with an
    /// "install the mod" button, not an empty list that says nothing is playable.
    pub has_mod: bool,
    /// Epoch millis this profile was last played, when the launcher records it. The list is sorted
    /// by it, because "the one I actually play" is the only useful default.
    pub last_used: u64,
}

/// The `launcher_profiles.json` shape, as much as launching needs.
#[derive(Debug, Default, Deserialize)]
struct LauncherProfiles {
    #[serde(default)]
    profiles: std::collections::BTreeMap<String, Profile>,
}

#[derive(Debug, Default, Deserialize)]
struct Profile {
    #[serde(default)]
    name: String,
    #[serde(rename = "lastVersionId", default)]
    last_version_id: Option<String>,
    #[serde(rename = "gameDir", default)]
    game_dir: Option<String>,
    #[serde(rename = "javaDir", default)]
    java_dir: Option<String>,
    #[serde(rename = "lastUsed", default)]
    last_used: Option<String>,
    #[serde(rename = "type", default)]
    kind: Option<String>,
}

/// Is a Nodera jar in this directory's `mods/`?
///
/// The same name test the installer applies, so "installed" means the same thing on both screens.
fn has_mod(game_dir: &Path) -> bool {
    let Ok(entries) = std::fs::read_dir(game_dir.join("mods")) else {
        return false;
    };
    entries.flatten().any(|entry| {
        let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
        name.ends_with(".jar") && (name.starts_with("noderamc") || name.starts_with("nodera-"))
    })
}

/// ISO-8601 `lastUsed` to epoch millis, coarsely.
///
/// Only the ordering matters — this sorts a list — so the parse is deliberately shallow: digits out
/// of `2026-07-31T18:04:22.000Z` in order, which compares correctly without a date library. A field
/// that will not parse sorts last, which is the same place an absent one goes.
fn last_used_millis(raw: Option<&str>) -> u64 {
    let Some(raw) = raw else { return 0 };
    let digits: String = raw.chars().filter(char::is_ascii_digit).take(14).collect();
    digits.parse().unwrap_or(0)
}

/// Profiles the official launcher knows about, under one game root.
pub fn official_profiles(root: &Path) -> Vec<LaunchTarget> {
    let path = root.join("launcher_profiles.json");
    let Ok(text) = std::fs::read_to_string(&path) else {
        return Vec::new();
    };
    let Ok(document) = serde_json::from_str::<LauncherProfiles>(&text) else {
        return Vec::new();
    };

    let mut out: Vec<LaunchTarget> = document
        .profiles
        .into_values()
        .filter(|profile| {
            // `latest-release` and `latest-snapshot` are the launcher's own rolling entries. They
            // have no fixed version id, so this app cannot assemble a command line for them — and a
            // player using one is a player whose modded instance is somewhere else.
            profile.last_version_id.is_some() && profile.kind.as_deref() != Some("latest-snapshot")
        })
        .map(|profile| {
            let game_dir = profile
                .game_dir
                .map(PathBuf::from)
                .unwrap_or_else(|| root.to_path_buf());
            LaunchTarget {
                name: if profile.name.trim().is_empty() {
                    profile
                        .last_version_id
                        .clone()
                        .unwrap_or_else(|| "Minecraft".to_owned())
                } else {
                    profile.name.clone()
                },
                tier: Tier::Direct,
                has_mod: has_mod(&game_dir),
                game_dir: game_dir.display().to_string(),
                version_id: profile.last_version_id,
                launcher: None,
                instance: None,
                java: profile.java_dir,
                last_used: last_used_millis(profile.last_used.as_deref()),
            }
        })
        .collect();
    out.sort_by(|a, b| b.last_used.cmp(&a.last_used).then_with(|| a.name.cmp(&b.name)));
    out
}

/// Instances belonging to a Prism/MultiMC-shaped launcher.
///
/// The instance **name** is what matters: these are started by asking the launcher, not by building
/// a command line, so the version id and Java path are the launcher's business and not read here.
pub fn instances(launcher_root: &Path, binary: &str) -> Vec<LaunchTarget> {
    let instances = launcher_root.join("instances");
    let Ok(entries) = std::fs::read_dir(&instances) else {
        return Vec::new();
    };

    let mut out = Vec::new();
    for entry in entries.flatten() {
        let dir = entry.path();
        let config = dir.join("instance.cfg");
        if !config.is_file() {
            continue;
        }
        let text = std::fs::read_to_string(&config).unwrap_or_default();
        let folder = dir
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        let mut name = folder.clone();
        let mut last_used = 0u64;
        for line in text.lines() {
            if let Some(value) = line.strip_prefix("name=") {
                if !value.trim().is_empty() {
                    name = value.trim().to_owned();
                }
            }
            if let Some(value) = line.strip_prefix("lastLaunchTime=") {
                last_used = value.trim().parse().unwrap_or(0);
            }
        }
        // Both layouts are in the wild: `.minecraft` on Linux/Windows, `minecraft` on macOS.
        let game_dir = [".minecraft", "minecraft"]
            .iter()
            .map(|leaf| dir.join(leaf))
            .find(|candidate| candidate.is_dir())
            .unwrap_or_else(|| dir.join(".minecraft"));

        out.push(LaunchTarget {
            name,
            tier: Tier::Prism,
            has_mod: has_mod(&game_dir),
            game_dir: game_dir.display().to_string(),
            version_id: None,
            launcher: Some(binary.to_owned()),
            // The launcher is asked for the instance by its *folder*, which is stable; `name` is a
            // display string the player can rename at any time.
            instance: Some(folder),
            java: None,
            last_used,
        });
    }
    out.sort_by(|a, b| b.last_used.cmp(&a.last_used).then_with(|| a.name.cmp(&b.name)));
    out
}

/// Is this launcher binary on `PATH`?
fn on_path(binary: &str) -> bool {
    let Ok(path) = std::env::var("PATH") else {
        return false;
    };
    std::env::split_paths(&path).any(|dir| {
        dir.join(binary).is_file()
            || (cfg!(target_os = "windows") && dir.join(format!("{binary}.exe")).is_file())
    })
}

/// The launcher roots this machine might have, paired with the binary that drives each.
fn third_party_roots(home: &Path) -> Vec<(PathBuf, &'static str)> {
    // `mut` is used only on macOS and Windows, which is exactly why the attribute is here rather
    // than the `mut` removed: dropping it would compile on Linux and break the other two.
    #[allow(unused_mut)]
    let mut out = vec![
        (home.join(".local/share/PrismLauncher"), "prismlauncher"),
        (home.join(".local/share/multimc"), "multimc"),
    ];
    #[cfg(target_os = "macos")]
    {
        out.push((
            home.join("Library/Application Support/PrismLauncher"),
            "prismlauncher",
        ));
    }
    #[cfg(target_os = "windows")]
    {
        if let Ok(appdata) = std::env::var("APPDATA") {
            out.push((PathBuf::from(appdata).join("PrismLauncher"), "prismlauncher"));
        }
    }
    out
}

fn home() -> Option<PathBuf> {
    std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .ok()
        .map(PathBuf::from)
}

/// Everything this machine can launch, best first.
///
/// Ordering is by tier and then by how recently the player used it: a Prism instance auto-connects
/// and an official profile does not, so an instance the player launched yesterday is a better guess
/// than a profile they launched last year.
///
/// `NODERA_MINECRAFT_DIR` is honoured as the official root, exactly as the mod installer honours it
/// — which is what makes every failure path here scriptable from a test.
pub fn targets() -> Vec<LaunchTarget> {
    let mut out = Vec::new();

    if let Some(home) = home() {
        for (root, binary) in third_party_roots(&home) {
            if root.is_dir() && on_path(binary) {
                out.extend(instances(&root, binary));
            }
        }
    }

    for root in official_roots() {
        out.extend(official_profiles(&root));
    }

    out.sort_by(|a, b| {
        let rank = |t: Tier| if t.auto_connects() { 0 } else { 1 };
        rank(a.tier)
            .cmp(&rank(b.tier))
            .then_with(|| b.last_used.cmp(&a.last_used))
            .then_with(|| a.name.cmp(&b.name))
    });
    out
}

/// Game roots the official launcher uses, in the order the mod installer probes them.
pub fn official_roots() -> Vec<PathBuf> {
    let mut out = Vec::new();
    if let Ok(explicit) = std::env::var("NODERA_MINECRAFT_DIR") {
        out.push(PathBuf::from(explicit));
    }
    if let Some(home) = home() {
        #[cfg(target_os = "windows")]
        if let Ok(appdata) = std::env::var("APPDATA") {
            out.push(PathBuf::from(appdata).join(".minecraft"));
        }
        #[cfg(target_os = "macos")]
        out.push(home.join("Library/Application Support/minecraft"));
        out.push(home.join(".minecraft"));
    }
    out.retain(|path| path.is_dir());
    out.dedup();
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nodera-discover-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn profiles_are_offered_newest_played_first() {
        let root = temp("profiles");
        std::fs::write(
            root.join("launcher_profiles.json"),
            r#"{"profiles":{
              "a":{"name":"Vanilla","lastVersionId":"1.21.1","lastUsed":"2024-01-02T00:00:00.000Z"},
              "b":{"name":"Modded","lastVersionId":"neoforge-21.1.77","lastUsed":"2026-07-30T10:00:00.000Z"}}}"#,
        )
        .unwrap();

        let found = official_profiles(&root);
        // "The one I actually play" is the only useful default, and it is the only thing the
        // launcher records that answers it.
        assert_eq!(found[0].name, "Modded");
        assert_eq!(found[1].name, "Vanilla");
        assert_eq!(found[0].version_id.as_deref(), Some("neoforge-21.1.77"));
        // No `gameDir` means the root itself, which is what the launcher does.
        assert_eq!(found[0].game_dir, root.display().to_string());
    }

    #[test]
    fn the_launchers_rolling_entries_are_left_out() {
        let root = temp("rolling");
        std::fs::write(
            root.join("launcher_profiles.json"),
            r#"{"profiles":{
              "s":{"name":"","lastVersionId":"latest-snapshot","type":"latest-snapshot"},
              "m":{"name":"Modded","lastVersionId":"neoforge-21.1.77"}}}"#,
        )
        .unwrap();

        let names: Vec<String> = official_profiles(&root).into_iter().map(|t| t.name).collect();
        // A rolling entry has no fixed version id to resolve, so a command line cannot be built for
        // it — offering it would be offering a button that fails at `Preparing`.
        assert_eq!(names, vec!["Modded".to_owned()]);
    }

    #[test]
    fn an_instance_reports_the_mod_and_is_addressed_by_its_folder() {
        let root = temp("instances");
        let instance = root.join("instances").join("modded-1211");
        std::fs::create_dir_all(instance.join(".minecraft").join("mods")).unwrap();
        std::fs::write(instance.join("instance.cfg"), "name=My Modded World\nlastLaunchTime=1700\n").unwrap();
        std::fs::write(
            instance.join(".minecraft").join("mods").join("nodera-neoforge-0.1.0.jar"),
            b"jar",
        )
        .unwrap();

        let found = instances(&root, "prismlauncher");
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].name, "My Modded World", "the display name is the player's");
        // Addressed by folder: `name` is a display string the player can change at any time, and a
        // launch that referred to it would break the moment they did.
        assert_eq!(found[0].instance.as_deref(), Some("modded-1211"));
        assert_eq!(found[0].tier, Tier::Prism);
        assert!(found[0].has_mod);
    }

    #[test]
    fn an_instance_without_the_mod_is_still_offered() {
        let root = temp("nomod");
        let instance = root.join("instances").join("plain");
        std::fs::create_dir_all(instance.join(".minecraft")).unwrap();
        std::fs::write(instance.join("instance.cfg"), "name=Plain\n").unwrap();

        let found = instances(&root, "prismlauncher");
        // Filtering it out would leave a player who has simply not installed the mod yet staring at
        // an empty list that says nothing is playable, rather than at their instance with a button.
        assert_eq!(found.len(), 1);
        assert!(!found[0].has_mod);
    }

    #[test]
    fn a_directory_that_is_not_an_instance_is_ignored() {
        let root = temp("junk");
        std::fs::create_dir_all(root.join("instances").join(".tmp")).unwrap();
        assert!(instances(&root, "prismlauncher").is_empty());
    }

    #[test]
    fn a_profiles_file_that_is_not_json_is_no_profiles_rather_than_a_panic() {
        let root = temp("broken");
        std::fs::write(root.join("launcher_profiles.json"), "{ this is not json").unwrap();
        assert!(official_profiles(&root).is_empty());
    }

    #[test]
    fn last_used_orders_without_a_date_library() {
        assert!(
            last_used_millis(Some("2026-07-30T10:00:00.000Z"))
                > last_used_millis(Some("2024-01-02T00:00:00.000Z"))
        );
        // Unparseable and absent both sort last, which is the same place.
        assert_eq!(last_used_millis(None), 0);
        assert_eq!(last_used_millis(Some("never")), 0);
    }
}
