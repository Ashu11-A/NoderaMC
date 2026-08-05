//! Installing the NoderaMC mod from the app.
//!
//! # Why the app does this at all
//!
//! The mod does not work without the worker, and the worker ships with this app. Making people go
//! and find a jar somewhere, work out which loader they have, and drop it in the right folder — for
//! a component whose only job is to talk to software they have already installed — is a step that
//! exists purely because nobody automated it. The app knows where Minecraft is, knows which mod
//! build matches the worker it supervises, and can put the two together.
//!
//! # It only ever installs the jar it shipped with
//!
//! There is no download here, and that is deliberate rather than a limitation. The app and the mod
//! are built and versioned together; fetching a jar from the internet at install time would mean the
//! mod could be a different build from the worker it must speak to, and it would put a code-fetching
//! path into a desktop app for no benefit. The jar is a bundled resource.
//!
//! # It never deletes anything it did not write
//!
//! Installing writes exactly one file, named for this build. An older Nodera jar is reported so the
//! UI can offer to remove it, and removal is a separate, explicit action — an installer that tidies
//! a mods folder on its own eventually removes something somebody wanted.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// A Minecraft installation this app can install into.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct MinecraftInstall {
    /// The game directory (`.minecraft` or a launcher profile's own).
    pub path: String,
    /// The `mods` folder inside it, which may not exist yet.
    pub mods_path: String,
    /// Whether that folder already exists — a strong hint that a loader is installed.
    pub has_mods_folder: bool,
    /// Whether a NoderaMC jar is already there, and which.
    pub installed_jar: String,
    /// Whether the installed jar is the one this app ships.
    pub up_to_date: bool,
}

/// What the installer knows before anybody presses a button.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ModInstallStatus {
    /// The mod build this app carries, or empty when it was built without one.
    pub bundled_version: String,
    /// Whether the bundled jar is actually present — a dev build may not carry it.
    pub bundled_available: bool,
    /// Every Minecraft installation found. Empty is a normal answer, not an error.
    pub installs: Vec<MinecraftInstall>,
}

/// The file this app writes into a `mods/` folder. Versioned so two builds never collide silently.
///
/// Deliberately the same name the release publishes (`scripts/lib/release.sh`): somebody who
/// downloaded `nodera-neoforge-latest.jar` by hand and somebody who pressed the button in this app
/// end up with the same file name, so "do I already have it?" is answerable by looking.
fn jar_name(version: &str) -> String {
    format!("nodera-neoforge-{version}.jar")
}

/// Anything that looks like a Nodera mod jar, whoever put it there.
fn is_nodera_jar(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.ends_with(".jar") && (lower.starts_with("noderamc") || lower.starts_with("nodera-"))
}

/// Where the bundled mod jar lives.
///
/// `NODERA_MOD_JAR` overrides it, which is how a development build points at
/// `endpoints/neoforge-mod/build/libs/nodera-neoforge.jar` without a packaging step.
///
/// The default is a bundled Tauri resource. No release bundle declares it yet — the mod jar is
/// published as its own release asset instead — so in a shipped app `status()` reports
/// `bundled_available: false` and `install()` says which path it looked in. That is the honest
/// answer for a build that does not carry it, and it is why the check exists rather than the
/// install failing at the copy.
fn bundled_jar() -> PathBuf {
    std::env::var("NODERA_MOD_JAR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("resources/mod/nodera-neoforge.jar"))
}

/// The product version, stamped at build time from the repository `VERSION`.
pub fn bundled_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

/// Every plausible Minecraft game directory on this machine.
///
/// Deliberately a *list*: people run several launchers, several profiles, and several instances, and
/// an installer that guessed one and wrote there would put the mod somewhere the player does not
/// launch from — the most confusing possible failure, because nothing goes wrong until the game
/// starts without it.
fn candidate_game_dirs() -> Vec<PathBuf> {
    let mut out = Vec::new();
    let home = dirs_home();
    if let Some(home) = home {
        #[cfg(target_os = "windows")]
        {
            if let Ok(appdata) = std::env::var("APPDATA") {
                out.push(PathBuf::from(appdata).join(".minecraft"));
            }
        }
        #[cfg(target_os = "macos")]
        {
            out.push(home.join("Library/Application Support/minecraft"));
        }
        out.push(home.join(".minecraft"));
        // Common third-party launchers keep instances under their own roots. Only the roots are
        // probed; enumerating every instance is the launcher's job, not ours.
        out.push(home.join(".local/share/PrismLauncher"));
        out.push(home.join(".local/share/multimc"));
        out.push(home.join("curseforge/minecraft/Instances"));
    }
    if let Ok(explicit) = std::env::var("NODERA_MINECRAFT_DIR") {
        out.insert(0, PathBuf::from(explicit));
    }
    out.retain(|path| path.is_dir());
    out.dedup();
    out
}

fn dirs_home() -> Option<PathBuf> {
    std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .ok()
        .map(PathBuf::from)
}

/// Inspect one game directory.
fn inspect(path: &Path, version: &str) -> MinecraftInstall {
    let mods = path.join("mods");
    let mut installed = String::new();
    if let Ok(entries) = std::fs::read_dir(&mods) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if is_nodera_jar(&name) {
                installed = name;
                break;
            }
        }
    }
    MinecraftInstall {
        path: path.display().to_string(),
        mods_path: mods.display().to_string(),
        has_mods_folder: mods.is_dir(),
        up_to_date: installed == jar_name(version),
        installed_jar: installed,
    }
}

/// What the install screen renders.
pub fn status() -> ModInstallStatus {
    let version = bundled_version();
    let jar = bundled_jar();
    ModInstallStatus {
        bundled_available: jar.is_file(),
        installs: candidate_game_dirs()
            .iter()
            .map(|path| inspect(path, &version))
            .collect(),
        bundled_version: version,
    }
}

/// Copy the bundled jar into one installation's `mods` folder.
///
/// Returns the path written. Creates the folder if it is missing — a player who has installed
/// NeoForge but never a mod has no `mods` folder, and refusing them would be pedantry.
pub fn install(game_dir: &str) -> Result<String, String> {
    let jar = bundled_jar();
    if !jar.is_file() {
        return Err(format!(
            "this build of the app does not carry the mod jar (looked in {})",
            jar.display()
        ));
    }
    let target_dir = PathBuf::from(game_dir).join("mods");
    std::fs::create_dir_all(&target_dir)
        .map_err(|e| format!("could not create {}: {e}", target_dir.display()))?;
    let target = target_dir.join(jar_name(&bundled_version()));
    std::fs::copy(&jar, &target)
        .map_err(|e| format!("could not write {}: {e}", target.display()))?;
    Ok(target.display().to_string())
}

/// Remove a Nodera jar this app can see.
///
/// Refuses anything that is not one, by name. An installer with a delete button that can be pointed
/// at an arbitrary path is a foot-gun in a folder full of other people's work.
pub fn uninstall(game_dir: &str) -> Result<String, String> {
    let mods = PathBuf::from(game_dir).join("mods");
    let entries =
        std::fs::read_dir(&mods).map_err(|e| format!("could not read {}: {e}", mods.display()))?;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if is_nodera_jar(&name) {
            std::fs::remove_file(entry.path())
                .map_err(|e| format!("could not remove {name}: {e}"))?;
            return Ok(name);
        }
    }
    Err("no NoderaMC jar is installed there".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_nodera_jars_are_recognised() {
        assert!(is_nodera_jar("noderamc-0.1.0.jar"));
        assert!(is_nodera_jar("NoderaMC-0.1.0.jar"));
        assert!(is_nodera_jar("nodera-neoforge-latest.jar"));
        assert!(is_nodera_jar("nodera-paper-0.1.0.jar"));
        // The uninstall button must never be able to reach somebody else's mod.
        assert!(!is_nodera_jar("sodium-fabric-0.5.jar"));
        assert!(!is_nodera_jar("create-1.20.jar"));
        assert!(!is_nodera_jar("noderamc-0.1.0.jar.disabled"));
        assert!(!is_nodera_jar("README.txt"));
    }

    #[test]
    fn the_installed_name_is_versioned_so_two_builds_never_collide() {
        // The release's own name for the same artefact — see `jar_name`.
        assert_eq!(jar_name("0.1.0"), "nodera-neoforge-0.1.0.jar");
        assert_ne!(jar_name("0.1.0"), jar_name("0.2.0"));
    }

    #[test]
    fn an_install_reports_whether_it_already_has_this_build() {
        let dir = std::env::temp_dir().join(format!("nodera-mods-{}", std::process::id()));
        let mods = dir.join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        std::fs::write(mods.join(jar_name("9.9.9")), b"jar").unwrap();

        let older = inspect(&dir, "1.0.0");
        assert_eq!(older.installed_jar, jar_name("9.9.9"));
        assert!(!older.up_to_date, "a different build is not up to date");
        assert!(older.has_mods_folder);

        let same = inspect(&dir, "9.9.9");
        assert!(same.up_to_date);

        // A jar installed by an older app, under the name that app used. It is still recognised as
        // ours — so uninstall can reach it — and it is still not this build.
        std::fs::remove_file(mods.join(jar_name("9.9.9"))).unwrap();
        std::fs::write(mods.join("noderamc-9.9.9.jar"), b"jar").unwrap();
        let legacy = inspect(&dir, "9.9.9");
        assert_eq!(legacy.installed_jar, "noderamc-9.9.9.jar");
        assert!(
            !legacy.up_to_date,
            "the name changed, so a legacy jar must read as replaceable rather than current"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_directory_with_no_mods_folder_is_still_a_candidate() {
        let dir = std::env::temp_dir().join(format!("nodera-plain-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        let install = inspect(&dir, "1.0.0");

        // A player who has NeoForge but has never installed a mod has no folder yet. Refusing them
        // would be refusing exactly the person this feature is for.
        assert!(!install.has_mods_folder);
        assert!(install.installed_jar.is_empty());

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn installing_without_a_bundled_jar_says_so_rather_than_failing_obscurely() {
        let dir = std::env::temp_dir().join(format!("nodera-nojar-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::env::set_var("NODERA_MOD_JAR", dir.join("does-not-exist.jar"));

        let error = install(&dir.display().to_string()).expect_err("no jar to install");
        assert!(error.contains("does not carry the mod jar"), "{error}");

        std::env::remove_var("NODERA_MOD_JAR");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn uninstall_refuses_a_folder_with_nothing_of_ours_in_it() {
        let dir = std::env::temp_dir().join(format!("nodera-other-{}", std::process::id()));
        let mods = dir.join("mods");
        std::fs::create_dir_all(&mods).unwrap();
        std::fs::write(mods.join("sodium.jar"), b"not ours").unwrap();

        let error = uninstall(&dir.display().to_string()).expect_err("nothing of ours");
        assert!(error.contains("no NoderaMC jar"), "{error}");
        assert!(
            mods.join("sodium.jar").exists(),
            "somebody else's mod must survive"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }
}
