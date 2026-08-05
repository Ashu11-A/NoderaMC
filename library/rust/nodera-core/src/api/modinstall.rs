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
    /// Every path that was looked in, when it is not.
    ///
    /// Empty when the jar was found. "There is nothing to install" is not an answer anybody can act
    /// on — it was the whole of what this screen said for every build that ever shipped — so when
    /// the jar is missing the screen shows where it was expected to be, which is the difference
    /// between a broken installation and a development build somebody has not staged yet.
    #[serde(default)]
    pub bundled_looked_in: Vec<String>,
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

/// The jar's path inside the bundle, relative to the resource directory.
///
/// Contract with `app/tauri.<system>.conf.json`, which maps `build/nodera-neoforge.jar` to exactly
/// this target. A **plain file** key, not a glob: `tauri_utils::resources` gives a glob's matches
/// the map's target as a *directory* and a plain path the target as a *file*, and the glob form is
/// what once flattened the headless distribution into one folder (`scripts/release.sh`, `build_app`).
/// A plain key also makes a missing jar a `ResourcePathNotFound` at bundle time, which is the loud
/// failure this constant is half of.
const BUNDLED_JAR: &str = "resources/mod/nodera-neoforge.jar";

/// Every place the bundled mod jar can legitimately be, in the order they are tried.
///
/// # Why this is a list and not a path
///
/// It used to be the bare relative path `resources/mod/nodera-neoforge.jar`, which a process
/// resolves against its CURRENT WORKING DIRECTORY — the user's home directory when the app is
/// launched from a desktop menu. That is the identical defect [`crate::daemon::launcher_candidates`]
/// exists to document for the worker launcher, and it was live here at the same time: no per-OS
/// config declared the jar as a resource at all, so `bundled_available` was `false` in **every build
/// that has ever shipped** and the install screen said "this build carries no mod jar" permanently.
/// Both halves are fixed together, because fixing only the packaging would have left the app looking
/// for a bundled file in whatever directory it happened to be started from.
///
/// `resource_dir` is what Tauri resolves for the running bundle; `None` on a build with no bundle
/// (`cargo run`), which is exactly when the working-directory candidates are the right answer.
fn jar_candidates(resource_dir: Option<&Path>) -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(dir) = resource_dir {
        candidates.push(dir.join(BUNDLED_JAR));
        // Tauri has moved what `resource_dir()` points at between versions — at `<prefix>/lib/<app>`
        // the `resources/` segment is ours, elsewhere it is already included.
        candidates.push(dir.join("mod/nodera-neoforge.jar"));
    }
    // Beside the executable: a portable/extracted build, an AppImage, a zip drop.
    if let Ok(exe) = std::env::current_exe() {
        if let Some(exe_dir) = exe.parent() {
            candidates.push(exe_dir.join(BUNDLED_JAR));
        }
    }
    // The working directory, last, so a stale `resources/` beside wherever the app was launched
    // from can never shadow the one that shipped with it.
    candidates.push(PathBuf::from(BUNDLED_JAR));
    // The repository's own staging directory, so a checkout finds the jar `scripts/dev.sh` stages
    // and `scripts/release.sh` bundles. Same file, same name, one place.
    candidates.push(PathBuf::from("build/nodera-neoforge.jar"));
    candidates
}

/// Locate the bundled jar: `NODERA_MOD_JAR`, else the first candidate that exists.
///
/// `NODERA_MOD_JAR` is how a development build points at
/// `endpoints/neoforge-mod/build/libs/nodera-neoforge.jar` without a packaging step. It is honoured
/// even when it does not exist: the operator asked for that exact path, and quietly searching
/// elsewhere would hide their typo.
///
/// Returns `Err(every path tried)` rather than one plausible-looking path, so the screen and the
/// error message can both say where it actually looked.
fn bundled_jar(resource_dir: Option<&Path>) -> Result<PathBuf, Vec<PathBuf>> {
    if let Ok(explicit) = std::env::var("NODERA_MOD_JAR") {
        return Ok(PathBuf::from(explicit));
    }
    let candidates = jar_candidates(resource_dir);
    match candidates.iter().find(|path| path.is_file()) {
        Some(found) => Ok(found.clone()),
        None => Err(candidates),
    }
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
///
/// `resource_dir` is the running bundle's resource directory, resolved by the shell — the only
/// place that holds a Tauri `AppHandle`. `None` means "this build has no bundle", not "look in the
/// current directory"; see [`jar_candidates`].
pub fn status(resource_dir: Option<&Path>) -> ModInstallStatus {
    let version = bundled_version();
    let (available, looked_in) = match bundled_jar(resource_dir) {
        // An explicit `NODERA_MOD_JAR` that does not exist lands here too, and reports the one path
        // it was told to use rather than claiming a jar it cannot open.
        Ok(jar) if jar.is_file() => (true, Vec::new()),
        Ok(jar) => (false, vec![jar.display().to_string()]),
        Err(tried) => (
            false,
            tried.iter().map(|path| path.display().to_string()).collect(),
        ),
    };
    ModInstallStatus {
        bundled_available: available,
        bundled_looked_in: looked_in,
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
pub fn install(game_dir: &str, resource_dir: Option<&Path>) -> Result<String, String> {
    let jar = bundled_jar(resource_dir).map_err(|tried| {
        let list = tried
            .iter()
            .map(|path| format!("\n  {}", path.display()))
            .collect::<String>();
        format!("this build of the app does not carry the mod jar. Looked in:{list}")
    })?;
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

    /// Both no-jar cases in ONE test, deliberately.
    ///
    /// `NODERA_MOD_JAR` is process-global and `cargo test` runs these threads concurrently, so two
    /// tests that disagree about whether the override is set are two tests that pass alone and fail
    /// together — which is exactly what happened when this was written as a pair.
    #[test]
    fn installing_without_a_bundled_jar_says_where_it_looked() {
        let dir = std::env::temp_dir().join(format!("nodera-nojar-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        // With an override: the one path the operator named, so a typo is visible as a typo rather
        // than buried in a list of places nobody asked for.
        let named = dir.join("does-not-exist.jar");
        std::env::set_var("NODERA_MOD_JAR", &named);
        let error = install(&dir.display().to_string(), None).expect_err("no jar to install");
        assert!(error.contains("does not carry the mod jar"), "{error}");
        assert!(error.contains(&named.display().to_string()), "{error}");
        assert!(!status(None).bundled_available);
        assert_eq!(
            status(None).bundled_looked_in,
            vec![named.display().to_string()]
        );
        std::env::remove_var("NODERA_MOD_JAR");

        // Without one: every candidate, in the message and on the screen. "This build carries no mod
        // jar, so there is nothing to install." was the whole of what a user was told, and it was
        // true of every build that ever shipped — nobody could act on it.
        let tried = bundled_jar(Some(&dir)).expect_err("nothing to find");
        assert!(tried.len() >= 3, "{tried:?}");
        let error = install(&dir.display().to_string(), Some(&dir)).expect_err("no jar");
        for path in &tried {
            assert!(
                error.contains(&path.display().to_string()),
                "every path tried must be in the message: {error}"
            );
        }
        let reported = status(Some(&dir));
        assert!(!reported.bundled_available);
        assert_eq!(
            reported.bundled_looked_in,
            tried
                .iter()
                .map(|path| path.display().to_string())
                .collect::<Vec<_>>(),
            "the screen shows the same list the error does"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_bundled_jar_is_looked_for_under_the_running_bundle_not_the_working_directory() {
        // The defect this closes: `bundled_jar` was the bare relative path
        // `resources/mod/nodera-neoforge.jar`, resolved against the process's working directory —
        // the user's home when the app is launched from a desktop menu. The worker launcher had the
        // identical bug and it took a shipped installer to find it.
        let bundle = std::env::temp_dir().join(format!("nodera-bundle-{}", std::process::id()));
        let resources = bundle.join("resources/mod");
        std::fs::create_dir_all(&resources).unwrap();
        std::fs::write(resources.join("nodera-neoforge.jar"), b"jar").unwrap();

        let candidates = jar_candidates(Some(&bundle));
        assert_eq!(
            candidates.first(),
            Some(&bundle.join(BUNDLED_JAR)),
            "the running bundle is tried first, before anything relative to the process"
        );
        assert!(
            candidates.contains(&PathBuf::from("build/nodera-neoforge.jar")),
            "a checkout must still find the jar `scripts/dev.sh` stages"
        );
        assert!(
            candidates
                .iter()
                .position(|path| path == Path::new(BUNDLED_JAR))
                > candidates.iter().position(|path| path.starts_with(&bundle)),
            "a stale `resources/` in the launch directory must never shadow the shipped one"
        );

        // The selection itself, without going through `bundled_jar` — that reads the process-global
        // `NODERA_MOD_JAR`, and the test that owns it runs on another thread.
        assert_eq!(
            candidates.iter().find(|path| path.is_file()),
            Some(&bundle.join(BUNDLED_JAR))
        );

        let _ = std::fs::remove_dir_all(&bundle);
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
