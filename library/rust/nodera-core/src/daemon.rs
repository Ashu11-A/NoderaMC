//! Task 32 (Option B): supervise the bundled headless Nodera peer **worker** (`nodera-headless`, a
//! Java process built by the `application` plugin's installDist) — the always-on node that keeps a
//! player on the network with Minecraft closed and owns the control endpoint the mod probes.
//!
//! The supervisor launches the worker's `bin/nodera-headless` launcher, restarts it with backoff if
//! it dies, and marks the dashboard link down when it does (the authoritative liveness signal is
//! [`crate::api::link`], which is connected to the worker's control port).
//!
//! **Attach mode** (`NODERA_APP_ATTACH=1`): do NOT spawn a worker — one is already running (e.g.
//! started by `scripts/dev.sh`). The app only monitors + shows the UI. This prevents two workers
//! fighting over the control port in development.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use serde::Serialize;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::Notify;

use crate::api::store::DashboardStore;
use crate::logs::LogBuffer;
use crate::settings::{Settings, SettingsHandle};

/// Private Android handoff read by `NoderaWorker.kt` before it starts the in-process JVM worker.
#[cfg(any(target_os = "android", all(test, unix)))]
const WORKER_PROPERTIES_FILE: &str = "nodera-worker.properties";

/// True when the app should attach to an already-running worker instead of supervising its own.
pub fn attach_mode() -> bool {
    std::env::var("NODERA_APP_ATTACH")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
}

/// Who owns the worker process — what the UI needs to decide whether to offer a Restart button.
///
/// Exposed as its own shape rather than a bare boolean because "attached" and "may I restart it"
/// are different questions that happen to share an answer today; a future headless-remote mode
/// would separate them, and the UI would not have to change.
#[derive(Clone, Copy, Debug, Serialize)]
pub struct WorkerOwnership {
    /// `true` when the worker was started outside this app (`NODERA_APP_ATTACH=1`).
    pub attached: bool,
    /// `true` only when this app spawned the worker and may therefore stop it.
    pub can_restart: bool,
}

/// Report who owns the worker process.
///
/// **Never on mobile.** [`supervise`] — the only consumer of [`RestartSignal`] — is desktop-only,
/// and on Android the worker is a thread in this very process rather than a child of it. A process
/// that cannot outlive us cannot be cycled by us, so notifying the signal there wakes nobody and the
/// button that sent it is a silent lie. Offering nothing is the honest answer, and the UI already
/// gates the Restart button on this flag (M-NET-3).
pub fn ownership() -> WorkerOwnership {
    let attached = attach_mode();
    WorkerOwnership {
        attached,
        can_restart: !attached && crate::SUPERVISES_A_WORKER,
    }
}

/// Why a restart cannot be offered on this platform, or [`None`] when it can be.
///
/// Separate from [`ownership`] so the command can *say* it rather than fail silently: a user who
/// reaches the verb through an older frontend bundle gets a sentence, not a no-op.
pub fn restart_unavailable() -> Option<&'static str> {
    if attach_mode() {
        return Some(
            "this worker was started outside the app, so the app will not stop it — restart it \
             where you started it",
        );
    }
    if !crate::SUPERVISES_A_WORKER {
        return Some(
            "the worker runs inside this app on Android, so it cannot be restarted on its own — \
             close and reopen the app to apply these settings",
        );
    }
    None
}

/// Signal that asks the supervisor to cycle the worker.
///
/// A newtype rather than a bare `Notify` because Tauri keys managed state by type, and this app
/// carries a second unrelated `Notify` (the configuration push). Two bare `Arc<Notify>` states
/// would silently resolve to whichever was registered last.
#[derive(Default)]
pub struct RestartSignal(pub Notify);

/// The environment a freshly spawned worker inherits from the user's settings.
///
/// Pure and separately testable because it is the *whole* of transport #1 (see the config plan):
/// identity-shaped values the worker reads exactly once in `HeadlessPeerMain.main()` and has no
/// re-read path for. Until this existed the supervisor passed **no** environment at all, so a user
/// who edited Settings → Network was editing a file nothing read — the tracker list never reached
/// the JVM.
///
/// Two encoding rules that are contracts, not style:
/// * **A key whose value is empty is omitted entirely.** `HeadlessPeerMain.env()` treats blank as
///   absent and substitutes its own default, so sending `""` and omitting mean the same thing to
///   the worker — but only omitting says so to anyone reading `env` on the running process.
/// * **`NODERA_P2P_PORT_RANGE` is omitted when a random port is wanted.** Absent range means "do
///   not walk a range"; encoding it as `0-0` would read as a real one-wide range on port 0.
pub fn worker_env(settings: &Settings) -> Vec<(String, String)> {
    let mut env: Vec<(String, String)> = Vec::new();

    // Comma-separated, schemes preserved: `HeadlessPeerMain.parseTrackers` splits on ',' and
    // `TrackerClient.Endpoint.parse` understands `tcp://` / `udp://` / bare `host:port`.
    let trackers = crate::stores::merged(
        &settings.network.default_trackers,
        &settings.network.tracker_stores,
        crate::stores::ServiceKind::Tracker,
    )
    .join(",");
    if !trackers.trim().is_empty() {
        env.push(("NODERA_TRACKER_ENDPOINTS".to_owned(), trackers));
    }

    // The rendezvous list, which this function used to drop on the floor: the setting existed, the UI
    // wrote it, `NODERA_CONFIG` declared it restart-required — and nothing ever put it in the worker's
    // environment, so a user's configured relay was silently ignored and the worker fell back to its
    // 127.0.0.1 default. These are *seeds* now rather than the whole list (the worker discovers the
    // rest from its trackers), which is what makes the default harmless instead of isolating.
    let rendezvous = crate::stores::merged(
        &settings.network.rendezvous_endpoints,
        &settings.network.tracker_stores,
        crate::stores::ServiceKind::Rendezvous,
    )
    .join(",");
    if !rendezvous.trim().is_empty() {
        env.push(("NODERA_RENDEZVOUS_ENDPOINTS".to_owned(), rendezvous));
    }

    // Where the worker can read the app's synchronised service list. Redundant on the desktop —
    // the two variables above already carry it — but it is the ONLY path on Android, where the
    // worker runs inside this process and a process cannot set its own environment from Java. One
    // mechanism on both platforms beats two that diverge.
    env.push((
        "NODERA_SERVICES_FILE".to_owned(),
        crate::settings::sync_file_path()
            .to_string_lossy()
            .into_owned(),
    ));

    let archive_dir = settings.storage.peer_worlds_dir.trim();
    if !archive_dir.is_empty() {
        env.push(("NODERA_ARCHIVE_DIR".to_owned(), archive_dir.to_owned()));
    }

    env.extend(worker_port_settings(settings));

    env
}

/// Bind-time port settings shared by desktop process environment and Android Java properties.
fn worker_port_settings(settings: &Settings) -> Vec<(String, String)> {
    let mut settings_out = Vec::new();
    if settings.network.use_random_port {
        // Port 0 = let the OS assign. The advertised route comes from the bound socket, so this is
        // the only value that is correct without knowing anything about the machine's free ports.
        settings_out.push(("NODERA_P2P_PORT".to_owned(), "0".to_owned()));
    } else {
        settings_out.push((
            "NODERA_P2P_PORT".to_owned(),
            settings.network.port_range_start.to_string(),
        ));
        settings_out.push((
            "NODERA_P2P_PORT_RANGE".to_owned(),
            format!(
                "{}-{}",
                settings.network.port_range_start, settings.network.port_range_end
            ),
        ));
    }
    settings_out
}

/// Write Android's allowlisted Java-property handoff from the same settings desktop spawns with.
#[cfg(target_os = "android")]
pub fn write_worker_properties(settings: &Settings) -> Result<(), String> {
    let path = crate::settings::config_dir().join(WORKER_PROPERTIES_FILE);
    write_worker_properties_to(&path, settings)
}

#[cfg(any(target_os = "android", all(test, unix)))]
fn write_worker_properties_to(path: &std::path::Path, settings: &Settings) -> Result<(), String> {
    std::fs::create_dir_all(path.parent().expect("worker properties have a parent"))
        .map_err(|e| format!("could not create worker properties directory: {e}"))?;
    let temp = path.with_extension("properties.tmp");
    std::fs::write(&temp, worker_properties_body(settings))
        .map_err(|e| format!("could not write worker properties: {e}"))?;
    std::fs::rename(&temp, path).map_err(|e| format!("could not install worker properties: {e}"))
}

#[cfg(any(target_os = "android", test))]
fn worker_properties_body(settings: &Settings) -> String {
    worker_port_settings(settings)
        .into_iter()
        .map(|(key, value)| format!("{key}={value}\n"))
        .collect()
}

/// The launcher's path inside the bundle, relative to the resource directory.
///
/// Contract with `app/tauri.<system>.conf.json`, which stages `build/nodera-headless/bin/*` and
/// `lib/*` under `resources/nodera-headless/`.
const BUNDLED_LAUNCHER: &str = "resources/nodera-headless/bin/nodera-headless";

/// Every place the worker launcher can legitimately be, in the order they are tried.
///
/// # Why this is a list and not a path
///
/// It used to be one bare relative path, `resources/nodera-headless/bin/nodera-headless`, which a
/// process resolves against its CURRENT WORKING DIRECTORY. That is the build tree when a developer
/// runs the app from the repository, so it worked everywhere anybody looked — and it is the user's
/// home directory when the app is launched from a desktop menu, where it can never work. An
/// installed `.deb` puts the launcher at `/usr/lib/Nodera/resources/nodera-headless/bin/`, and the
/// app reported
///
/// ```text
/// failed to start peer worker ("resources/nodera-headless/bin/nodera-headless"):
/// No such file or directory (os error 2)
/// ```
///
/// every backoff, forever. The bug was invisible until the release lane started producing real
/// installers, because until then nothing ever ran the app from anywhere but a checkout.
///
/// `resource_dir` is what Tauri resolves for the running bundle; it is `None` on a build that has
/// no bundle (`cargo run`), which is exactly when the working-directory candidates are the right
/// answer. Both are kept, because the app has to work installed AND from a checkout.
fn launcher_candidates(resource_dir: Option<&Path>) -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(dir) = resource_dir {
        candidates.push(dir.join(BUNDLED_LAUNCHER));
        // Tauri has moved what `resource_dir()` points at between versions — at `<prefix>/lib/<app>`
        // the `resources/` segment is ours, elsewhere it is already included. Trying both costs one
        // `is_file` and removes a whole class of "works on my packaging format".
        candidates.push(dir.join("nodera-headless/bin/nodera-headless"));
    }
    // Beside the executable: how a portable/extracted build is laid out, and what an AppImage or a
    // zip drop gives you.
    if let Ok(exe) = std::env::current_exe() {
        if let Some(exe_dir) = exe.parent() {
            candidates.push(exe_dir.join(BUNDLED_LAUNCHER));
        }
    }
    // The working directory, last: this is the development case, and it must not shadow an
    // installed bundle — a stale `resources/` in whatever directory the app happened to be
    // launched from would otherwise win over the one that shipped with it.
    candidates.push(PathBuf::from(BUNDLED_LAUNCHER));
    // The repository's own staging directory, so `cargo run` from a checkout finds the worker that
    // `scripts/dev.sh --build-only` just built.
    candidates.push(PathBuf::from("build/nodera-headless/bin/nodera-headless"));
    candidates
}

/// Locate the worker launcher: `NODERA_WORKER_BIN`, else the first bundled candidate that exists.
///
/// Returns the error case as `Err(every path tried)` rather than as a plausible-looking path that
/// does not exist. The old behaviour returned the latter, so the failure named one location and
/// gave no hint that others were possible — which is a bad message to receive three times a second
/// from a backoff loop.
fn worker_launcher(resource_dir: Option<&Path>) -> Result<PathBuf, Vec<PathBuf>> {
    if let Ok(explicit) = std::env::var("NODERA_WORKER_BIN") {
        // An explicit override is honoured even if it does not exist: the operator asked for that
        // exact path, and silently searching elsewhere would hide their typo.
        return Ok(PathBuf::from(explicit));
    }
    let candidates = launcher_candidates(resource_dir);
    match candidates.iter().find(|path| path.is_file()) {
        Some(found) => Ok(found.clone()),
        None => Err(candidates),
    }
}

/// Run the supervisor loop until the process exits. Restarts the worker with a capped backoff.
/// No-op (returns) in attach mode.
///
/// `restart` makes the *existing* backoff/respawn loop the only path that starts a worker: an
/// explicit restart kills the child and falls through to the same spawn, which recomputes
/// [`worker_env`] from the settings as they are now. There is deliberately no second spawn site to
/// keep in sync — a settings change plus a restart is how env-shaped configuration takes effect.
pub async fn supervise(
    store: Arc<DashboardStore>,
    logs: Arc<LogBuffer>,
    settings: Arc<SettingsHandle>,
    restart: Arc<RestartSignal>,
    // Resolved by the caller, which is the only place with an `AppHandle`. `None` means "this build
    // has no bundle", not "look in the current directory" — see `launcher_candidates`.
    resource_dir: Option<PathBuf>,
) {
    if attach_mode() {
        eprintln!("nodera-app: attach mode — not supervising a worker (one runs externally)");
        // The worker's output goes to its own log file in attach mode — follow it instead.
        crate::logs::tail_attach_log(logs).await;
        return;
    }

    let launcher = match worker_launcher(resource_dir.as_deref()) {
        Ok(path) => path,
        Err(tried) => {
            // Reported ONCE and then given up on, rather than retried forever. A missing launcher
            // is a broken installation, not a transient fault: no amount of backoff makes a file
            // appear, and the old loop printed the same misleading line every second for as long
            // as the app was open.
            let list = tried
                .iter()
                .map(|p| format!("  {}", p.display()))
                .collect::<Vec<_>>()
                .join("\n");
            eprintln!(
                "nodera-app: the bundled peer worker is missing. Looked in:\n{list}\n\
                 This build does not carry `nodera-headless`. Set NODERA_WORKER_BIN to a launcher, \
                 or run a release build — a packaged app stages it under resources/."
            );
            // The UI must say this too, not only stderr — a desktop user never sees stderr, and
            // "Offline" with no reason is the screen-of-zeros this dashboard was rebuilt to stop.
            store.mark_offline("the bundled peer worker is missing from this installation");
            return;
        }
    };
    let mut backoff = Duration::from_secs(1);
    let max_backoff = Duration::from_secs(30);

    loop {
        let env = worker_env(&settings.snapshot());
        let spawn = Command::new(&launcher)
            .envs(env)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true)
            .spawn();
        match spawn {
            Ok(mut child) => {
                backoff = Duration::from_secs(1); // healthy start resets backoff
                                                  // Stream the worker's output into the dashboard's log ring.
                if let Some(out) = child.stdout.take() {
                    let sink = Arc::clone(&logs);
                    tokio::spawn(async move {
                        let mut lines = BufReader::new(out).lines();
                        while let Ok(Some(line)) = lines.next_line().await {
                            sink.push(line);
                        }
                    });
                }
                if let Some(err) = child.stderr.take() {
                    let sink = Arc::clone(&logs);
                    tokio::spawn(async move {
                        let mut lines = BufReader::new(err).lines();
                        while let Ok(Some(line)) = lines.next_line().await {
                            sink.push(line);
                        }
                    });
                }
                // Whichever happens first wins. The `child.wait()` borrow ends with the select, so
                // the kill below is free to take its own mutable borrow.
                //
                // A restart requested while the worker was *already* down leaves a stored permit
                // that this select consumes immediately, cycling the fresh child once. Wasteful,
                // not wrong — the respawn reads the same settings either way, and the alternative
                // (dropping the permit) would silently ignore a restart the user asked for.
                let exited = tokio::select! {
                    status = child.wait() => Some(status),
                    _ = restart.0.notified() => None,
                };
                match exited {
                    Some(status) => {
                        logs.push(format!("nodera-app: peer worker exited: {status:?}"));
                        eprintln!("nodera-app: peer worker exited: {status:?}");
                    }
                    None => {
                        let _ = child.kill().await;
                        // A requested restart is not a crash, so it must not inherit crash backoff:
                        // a user who just pressed Restart should not wait 30 seconds because the
                        // worker happened to be flapping beforehand.
                        backoff = RESTART_SETTLE;
                        logs.push(
                            "nodera-app: restarting the peer worker to apply new settings"
                                .to_owned(),
                        );
                    }
                }
            }
            Err(e) => {
                logs.push(format!("nodera-app: failed to start peer worker: {e}"));
                eprintln!("nodera-app: failed to start peer worker ({launcher:?}): {e}");
            }
        }
        // The link reports its own state, but it can take a moment to notice; saying it here means
        // the screen reflects a worker we KNOW is gone the instant we stop it.
        store.mark_offline("the peer worker is not running");
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(max_backoff);
    }
}

/// Pause between killing a worker and respawning it.
///
/// Not zero: the worker owns the loopback control port, and a respawn that races the dying
/// process's socket teardown fails to bind — which the supervisor would then treat as a crash and
/// back off from, turning a restart into an outage.
const RESTART_SETTLE: Duration = Duration::from_millis(500);

#[cfg(test)]
mod tests {
    use super::*;

    fn env_of(settings: &Settings) -> std::collections::HashMap<String, String> {
        worker_env(settings).into_iter().collect()
    }

    /// The bundle layout is checked BEFORE the working directory.
    ///
    /// This ordering is the fix. The old code had only the working-directory path, so an installed
    /// app resolved it against whatever directory the desktop menu happened to launch it from — the
    /// user's home — and could never find the worker it shipped with.
    #[test]
    fn an_installed_bundle_is_preferred_over_the_working_directory() {
        let dir = std::env::temp_dir().join(format!("nodera-launcher-{}", std::process::id()));
        let bundled = dir.join(BUNDLED_LAUNCHER);
        std::fs::create_dir_all(bundled.parent().unwrap()).unwrap();
        std::fs::write(&bundled, b"#!/bin/sh\n").unwrap();

        let candidates = launcher_candidates(Some(&dir));
        assert_eq!(
            candidates.first(),
            Some(&bundled),
            "the resource directory must be tried first, or a stray ./resources in the launch \
             directory shadows the launcher the app was installed with"
        );
        assert!(
            candidates.iter().any(|p| p == Path::new(BUNDLED_LAUNCHER)),
            "the working-directory candidate must remain, for `cargo run` from a checkout"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A build with no bundle still has somewhere to look.
    #[test]
    fn a_build_with_no_bundle_falls_back_to_the_checkout() {
        let candidates = launcher_candidates(None);
        assert!(candidates.iter().any(|p| p == Path::new(BUNDLED_LAUNCHER)));
        assert!(candidates
            .iter()
            .any(|p| p == Path::new("build/nodera-headless/bin/nodera-headless")));
    }

    /// A missing launcher reports every path it tried, not one plausible-looking guess.
    #[test]
    fn a_missing_launcher_names_everywhere_it_looked() {
        // SAFETY: single-threaded within this test; the variable is removed immediately after.
        unsafe { std::env::remove_var("NODERA_WORKER_BIN") };
        let empty = std::env::temp_dir().join(format!("nodera-nothing-{}", std::process::id()));
        let tried = worker_launcher(Some(&empty))
            .expect_err("nothing is installed under an empty directory");
        assert!(
            tried.len() >= 3,
            "every candidate should be reported: {tried:?}"
        );
        assert!(tried.iter().any(|p| p.starts_with(&empty)));
    }

    /// An explicit override wins, and is NOT second-guessed when it does not exist.
    ///
    /// Silently searching elsewhere would turn an operator's typo into a worker started from
    /// somewhere they did not ask for, which is worse than the error.
    #[test]
    fn an_explicit_override_is_honoured_verbatim() {
        // SAFETY: set and removed within this test.
        unsafe { std::env::set_var("NODERA_WORKER_BIN", "/nonexistent/on/purpose") };
        let found = worker_launcher(None).expect("an override is always accepted");
        assert_eq!(found, Path::new("/nonexistent/on/purpose"));
        unsafe { std::env::remove_var("NODERA_WORKER_BIN") };
    }

    /// Settings with no stores at all, for the tests that are about the user's own two lists.
    fn without_stores() -> Settings {
        let mut settings = Settings::default();
        settings.network.tracker_stores = vec![];
        settings
    }

    /// The bug this function exists to fix: the tracker list the user typed must reach the JVM.
    #[test]
    fn the_user_tracker_list_reaches_the_worker_as_a_comma_separated_list() {
        let mut settings = without_stores();
        settings.network.default_trackers = vec![
            "tcp://a.example:25600".to_owned(),
            "udp://b.example:25601".to_owned(),
        ];
        assert_eq!(
            env_of(&settings)["NODERA_TRACKER_ENDPOINTS"],
            "tcp://a.example:25600,udp://b.example:25601"
        );
    }

    /// The user's own entries keep their place, and a store adds to them.
    #[test]
    fn a_store_adds_endpoints_without_displacing_the_users_own() {
        let mut settings = Settings::default();
        settings.network.default_trackers = vec!["tcp://mine.example:25600".to_owned()];
        let sent = env_of(&settings)["NODERA_TRACKER_ENDPOINTS"].clone();
        assert!(
            sent.starts_with("tcp://mine.example:25600,"),
            "the user's own tracker must come first: {sent}"
        );
        assert!(
            sent.split(',').count() > 1,
            "the built-in store contributes: {sent}"
        );
    }

    /// A fresh install has somewhere to look, which on a handset it previously did not.
    #[test]
    fn a_default_install_reaches_the_worker_with_trackers_from_the_built_in_store() {
        let env = env_of(&Settings::default());
        assert!(
            env.contains_key("NODERA_TRACKER_ENDPOINTS"),
            "a default install must not start the worker with no trackers at all"
        );
        assert!(env.contains_key("NODERA_RENDEZVOUS_ENDPOINTS"));
    }

    /// An empty archive directory means "the worker's own default", which is said by *omitting* the
    /// key — never by sending `""`, which reads as a configured empty path.
    #[test]
    fn an_empty_archive_dir_omits_the_key_rather_than_sending_an_empty_string() {
        let mut settings = Settings::default();
        settings.storage.peer_worlds_dir = "   ".to_owned();
        assert!(!env_of(&settings).contains_key("NODERA_ARCHIVE_DIR"));

        settings.storage.peer_worlds_dir = "/srv/nodera".to_owned();
        assert_eq!(env_of(&settings)["NODERA_ARCHIVE_DIR"], "/srv/nodera");
    }

    /// The key is omitted only when there is nothing from *either* source. Sending `""` would read
    /// as "configured, and empty" rather than "not configured".
    #[test]
    fn an_empty_tracker_list_omits_the_key_too() {
        let mut settings = without_stores();
        settings.network.default_trackers = vec![];
        assert!(!env_of(&settings).contains_key("NODERA_TRACKER_ENDPOINTS"));
    }

    #[test]
    fn a_random_port_is_port_zero_with_no_range_to_walk() {
        let mut settings = Settings::default();
        settings.network.use_random_port = true;
        let env = env_of(&settings);
        assert_eq!(env["NODERA_P2P_PORT"], "0");
        assert!(
            !env.contains_key("NODERA_P2P_PORT_RANGE"),
            "an absent range is how 'do not walk a range' is said"
        );
    }

    #[test]
    fn a_fixed_range_sends_its_first_port_and_the_whole_range() {
        let mut settings = Settings::default();
        settings.network.use_random_port = false;
        settings.network.port_range_start = 37000;
        settings.network.port_range_end = 37009;
        let env = env_of(&settings);
        assert_eq!(env["NODERA_P2P_PORT"], "37000");
        assert_eq!(env["NODERA_P2P_PORT_RANGE"], "37000-37009");
    }

    #[test]
    fn android_properties_match_the_fixed_port_settings_sent_on_desktop() {
        let mut settings = Settings::default();
        settings.network.use_random_port = false;
        settings.network.port_range_start = 42186;
        settings.network.port_range_end = 42190;

        assert_eq!(
            worker_properties_body(&settings),
            "NODERA_P2P_PORT=42186\nNODERA_P2P_PORT_RANGE=42186-42190\n"
        );
    }

    #[test]
    fn android_properties_cannot_move_the_control_endpoint() {
        let body = worker_properties_body(&Settings::default());
        assert_eq!(body, "NODERA_P2P_PORT=0\n");
        assert!(!body.contains("NODERA_CONTROL"));
        assert!(!body.contains("NODERA_P2P_PORT_RANGE"));
    }

    #[cfg(unix)]
    #[test]
    fn first_launch_and_changed_settings_replace_the_android_property_handoff() {
        let dir = std::env::temp_dir().join(format!(
            "nodera-worker-properties-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let path = dir.join(WORKER_PROPERTIES_FILE);
        let _ = std::fs::remove_dir_all(&dir);

        let defaults = Settings::default();
        write_worker_properties_to(&path, &defaults).unwrap();
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            "NODERA_P2P_PORT=0\n"
        );

        let mut fixed = defaults.clone();
        fixed.network.use_random_port = false;
        fixed.network.port_range_start = 42186;
        fixed.network.port_range_end = 42190;
        write_worker_properties_to(&path, &fixed).unwrap();
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            "NODERA_P2P_PORT=42186\nNODERA_P2P_PORT_RANGE=42186-42190\n"
        );

        write_worker_properties_to(&path, &defaults).unwrap();
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            "NODERA_P2P_PORT=0\n"
        );
        assert!(!path.with_extension("properties.tmp").exists());
        let _ = std::fs::remove_dir_all(dir);
    }

    /// Nothing else may creep in: every key here is one the worker actually reads, and an unread
    /// key in the process environment is a claim the app cannot honour.
    #[test]
    fn no_keys_beyond_the_documented_set_are_ever_sent() {
        let mut settings = Settings::default();
        settings.storage.peer_worlds_dir = "/srv/nodera".to_owned();
        settings.network.use_random_port = false;
        settings.network.rendezvous_endpoints = vec!["rdv.example:25601".to_owned()];
        for (key, _) in worker_env(&settings) {
            assert!(
                matches!(
                    key.as_str(),
                    "NODERA_TRACKER_ENDPOINTS"
                        | "NODERA_RENDEZVOUS_ENDPOINTS"
                        | "NODERA_SERVICES_FILE"
                        | "NODERA_ARCHIVE_DIR"
                        | "NODERA_P2P_PORT"
                        | "NODERA_P2P_PORT_RANGE"
                ),
                "unexpected worker env key {key}"
            );
        }
    }

    #[test]
    fn the_configured_rendezvous_endpoints_reach_the_worker() {
        // The bug this asserts against: the setting existed, the UI wrote it, NODERA-CONFIG declared it
        // restart-required, and nothing ever put it in the worker's environment — so a user's
        // configured relay was silently ignored.
        let mut settings = without_stores();
        settings.network.rendezvous_endpoints = vec![
            "rdv-a.example:25601".to_owned(),
            "rdv-b.example:25601".to_owned(),
        ];
        let env = env_of(&settings);
        assert_eq!(
            env["NODERA_RENDEZVOUS_ENDPOINTS"],
            "rdv-a.example:25601,rdv-b.example:25601"
        );
    }

    #[test]
    fn no_rendezvous_configured_sends_no_key_at_all() {
        // An empty setting must not become an empty env var: the worker's own default is a seed, and
        // an empty string would parse to no endpoints and no seeds.
        let settings = without_stores();
        assert!(settings.network.rendezvous_endpoints.is_empty());
        assert!(!env_of(&settings).contains_key("NODERA_RENDEZVOUS_ENDPOINTS"));
    }

    #[test]
    fn ownership_refuses_restart_exactly_when_the_worker_is_someone_elses() {
        // `attach_mode` reads the process environment, so assert the invariant rather than mutate
        // it: a test that sets NODERA_APP_ATTACH would race every other test in the binary.
        let owned = ownership();
        assert_eq!(
            owned.can_restart,
            !owned.attached && crate::SUPERVISES_A_WORKER
        );
    }

    /// M-NET-3. The Restart button was offered on Android and did nothing: the signal it notifies
    /// is consumed only by `supervise`, which is desktop-only. Both the flag the UI gates on
    /// and the verb itself now answer for the platform they are compiled for.
    #[test]
    fn restart_is_never_offered_where_nothing_consumes_the_signal() {
        if crate::SUPERVISES_A_WORKER {
            // Desktop, not attached (the test binary sets no NODERA_APP_ATTACH): a restart works.
            if !attach_mode() {
                assert!(ownership().can_restart);
                assert!(restart_unavailable().is_none());
            }
        } else {
            assert!(!ownership().can_restart);
            let why = restart_unavailable().expect("mobile must refuse with a reason");
            assert!(why.contains("Android"), "the refusal must say why: {why}");
        }
    }

    #[test]
    fn every_refusal_names_a_thing_the_user_can_actually_do() {
        if let Some(why) = restart_unavailable() {
            assert!(why.contains("restart it where you started it") || why.contains("reopen"));
        }
    }
}
