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

/// Who owns the worker process, and whether anything is actually waiting on a restart — everything
/// the UI needs to draw the Restart affordance.
///
/// Exposed as its own shape rather than a bare boolean because "attached" and "may I restart it"
/// are different questions that happen to share an answer today; a future headless-remote mode
/// would separate them, and the UI would not have to change.
#[derive(Clone, Debug, Serialize)]
pub struct WorkerOwnership {
    /// `true` when the worker was started outside this app (`NODERA_APP_ATTACH=1`).
    pub attached: bool,
    /// `true` only when this app spawned the worker and may therefore stop it.
    pub can_restart: bool,
    /// When `can_restart` is `false`, what the player should do instead — in words, from
    /// [`restart_unavailable`]. Empty when a restart is available.
    ///
    /// Carried on the wire so the UI renders the backend's own sentence instead of re-deriving one
    /// from `attached`. It had two branches of its own, which is two chances to disagree with the
    /// command that actually refuses.
    pub unavailable_reason: String,
    /// Dotted settings keys whose saved value differs from the value the **running** worker was
    /// started with. Empty means nothing is waiting on a restart.
    pub pending_restart_keys: Vec<String>,
    /// `false` when this app cannot know what the running worker was started with — attach mode,
    /// where somebody else's environment built it. `pending_restart_keys` is then not evidence of
    /// anything and the UI must say so rather than reading an empty list as "all applied".
    pub pending_known: bool,
}

/// Report who owns the worker process, and what (if anything) is waiting on a restart.
///
/// `pending` is [`None`] when the answer is unknowable — see [`WorkerOwnership::pending_known`].
///
/// **Never on mobile.** [`supervise`] — the only consumer of [`RestartSignal`] — is desktop-only,
/// and on Android the worker is a thread in this very process rather than a child of it. A process
/// that cannot outlive us cannot be cycled by us, so notifying the signal there wakes nobody and the
/// button that sent it is a silent lie. Saying so is the honest answer (M-NET-3) — and the UI now
/// prints [`Self::unavailable_reason`] rather than hiding the control with no explanation.
///
/// `can_restart` is defined as "[`restart_unavailable`] has no objection" rather than as its own
/// copy of the same condition: the two used to be written out separately, and a flag that says yes
/// while the command says no is a button that fails when pressed.
pub fn ownership(pending: Option<Vec<String>>) -> WorkerOwnership {
    let unavailable = restart_unavailable();
    WorkerOwnership {
        attached: attach_mode(),
        can_restart: unavailable.is_none(),
        unavailable_reason: unavailable.unwrap_or_default().to_owned(),
        pending_known: pending.is_some(),
        pending_restart_keys: pending.unwrap_or_default(),
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

/// The spawn-environment keys the worker reads exactly once, and the settings key each one is.
///
/// # Which settings cannot be applied to a running worker, and why
///
/// Exactly these two, and the reason is the same in both cases: the value is consumed while the
/// worker is *building* something it then holds open for its whole life, and there is no code path
/// that takes it apart again.
///
/// * **`network.port_range`** — `HeadlessPeerMain` binds the listening socket once at startup. A new
///   port cannot be moved to without closing that socket, which drops every peer session on it and
///   invalidates the address this node has already announced to trackers and rendezvous. A worker
///   that did this to itself mid-run would look, from outside, exactly like a node that crashed.
/// * **`network.rendezvous_endpoints`** — the relay set is turned into live registrations at
///   startup; changing the list means deregistering from relays that are leaving and handshaking
///   with ones that are arriving, and nothing on the worker side implements either half. Pushing a
///   new list to a running worker changes a field nobody reads again.
///
/// So the honest way to apply them is to restart the process that read them — which is what
/// [`apply_bind_time_changes`] does, automatically and within [`SETTLE_INTERVAL`], rather than
/// leaving a banner for the user to act on. Everything else in [`worker_env`] either has a live
/// `NODERA-CONFIG` path (the tracker list, the bandwidth caps, the archive directory) or is only a
/// file path, and none of it belongs here.
///
/// Two env keys map onto one settings key because the listening port is one decision spread over
/// three controls: `use_random_port`, `port_range_start` and `port_range_end` all end up as
/// `NODERA_P2P_PORT` plus an optional `NODERA_P2P_PORT_RANGE`. `network.port_range` is the name the
/// enforcement table already gives that decision, so it is the name the banner uses too.
///
/// Comparing the *env* rather than the settings is what makes this exact in both directions: with a
/// random port asked for, the port range is not in the environment at all, so editing it changes
/// nothing the worker read and nothing is reported pending — which is the truth.
const BIND_TIME_ENV: [(&str, &str); 3] = [
    ("NODERA_P2P_PORT", "network.port_range"),
    ("NODERA_P2P_PORT_RANGE", "network.port_range"),
    (
        "NODERA_RENDEZVOUS_ENDPOINTS",
        "network.rendezvous_endpoints",
    ),
];

/// What the running worker was actually started with.
///
/// # The banner this exists to clear
///
/// The worker answers `restart_required` for a bind-time key **whenever it is pushed one**, because
/// its reply describes the key's *scope*, not whether the value differs from the one it is running.
/// The app sends `network.port_range` and `network.rendezvous_endpoints` in every single push — they
/// are plain fields of [`crate::config::WorkerConfig`] — so the reply named them every time, and a
/// banner reading "some changes apply when the peer worker restarts" was therefore **permanently
/// true**. Restarting did not clear it; nothing could, because it was never about a change.
///
/// The app is the one process that can answer the real question: it spawned the worker, so it knows
/// the environment that worker holds. Comparing that against the environment the settings *would*
/// produce now is the difference between "this key is bind-time" and "you have changed it since".
#[derive(Default)]
pub struct LaunchedWorker {
    /// The env of the live child, or `None` when no child of ours is running.
    inner: std::sync::Mutex<Option<Vec<(String, String)>>>,
}

impl LaunchedWorker {
    /// Remember what a freshly spawned worker was handed.
    pub fn record(&self, env: &[(String, String)]) {
        *self.inner.lock().unwrap() = Some(env.to_vec());
    }

    /// Forget it, because the child is gone. The next spawn reads the settings as they are then, so
    /// a worker that is not running has nothing pending by construction.
    pub fn forget(&self) {
        *self.inner.lock().unwrap() = None;
    }

    /// Settings keys whose bind-time value has moved since the running worker was started.
    ///
    /// [`None`] means *unknowable*, not *none*, and it is the answer wherever the running worker is
    /// not one this app spawned: in attach mode its environment was built by whoever started it, and
    /// on Android it is a thread inside this very process that never went through [`supervise`].
    /// Reporting an empty list there would claim everything is applied on exactly the installs where
    /// the app knows least (A-UX-1) — the same condition [`restart_unavailable`] already names, so
    /// it is asked once rather than re-derived here.
    pub fn pending_restart_keys(&self, settings: &Settings) -> Option<Vec<String>> {
        if restart_unavailable().is_some() {
            return None;
        }
        // No child of ours: either it has not started yet or it just died, and both are about to be
        // spawned from the settings as they are now.
        let Some(running) = self.inner.lock().unwrap().clone() else {
            return Some(Vec::new());
        };
        Some(bind_time_changes(&running, settings))
    }
}

/// The bind-time keys on which `running` and `settings` disagree, in table order, without repeats.
///
/// An absent env key and an empty one are the same thing — [`worker_env`] omits a key rather than
/// sending a blank, and `HeadlessPeerMain.env()` substitutes its default for either — so both sides
/// are read through the same "missing means empty" lens. Otherwise turning a relay list from one
/// entry to none would compare `Some("")` against `None` and report a change forever.
fn bind_time_changes(running: &[(String, String)], settings: &Settings) -> Vec<String> {
    let wanted = worker_env(settings);
    let value = |env: &[(String, String)], key: &str| {
        env.iter()
            .find(|(name, _)| name == key)
            .map(|(_, value)| value.clone())
            .unwrap_or_default()
    };
    let mut changed: Vec<String> = Vec::new();
    for (env_key, settings_key) in BIND_TIME_ENV {
        if value(running, env_key) != value(&wanted, env_key)
            && !changed.iter().any(|held| held == settings_key)
        {
            changed.push(settings_key.to_owned());
        }
    }
    changed
}

/// How long a bind-time change must hold still before the worker is cycled for it.
///
/// The user asked for these to apply immediately, and a banner they have to notice and act on is
/// not immediate. But "on save" is not usable either: the relay list is a text field that saves per
/// keystroke, so applying on save would restart the worker once per character typed. Requiring the
/// same pending set twice in a row, one interval apart, is what makes a settled edit — rather than
/// an edit in progress — the thing that triggers a restart.
const SETTLE_INTERVAL: Duration = Duration::from_secs(2);

/// Cycle the worker when a bind-time setting has actually changed and stopped moving.
///
/// Runs forever beside [`supervise`], and does nothing at all on a build or a run where a restart is
/// not this app's to perform. The comparison is against the environment the *running* worker holds,
/// so the restart it triggers immediately makes the pending set empty — there is no second cycle,
/// and a worker that is down is never restarted for a change the next spawn will pick up anyway.
pub async fn apply_bind_time_changes(
    launched: Arc<LaunchedWorker>,
    settings: Arc<SettingsHandle>,
    restart: Arc<RestartSignal>,
    logs: Arc<LogBuffer>,
) {
    if restart_unavailable().is_some() {
        return;
    }
    let mut previous: Vec<String> = Vec::new();
    loop {
        tokio::time::sleep(SETTLE_INTERVAL).await;
        let pending = launched
            .pending_restart_keys(&settings.snapshot())
            .unwrap_or_default();
        if !pending.is_empty() && pending == previous {
            logs.push(format!(
                "nodera-app: restarting the peer worker to apply {}",
                pending.join(", ")
            ));
            restart.0.notify_one();
            previous.clear();
            continue;
        }
        previous = pending;
    }
}

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
///
/// Both things that send that signal go through it: the Restart control in Settings, and
/// [`apply_bind_time_changes`], which sends it on the user's behalf when a bind-time setting has
/// actually moved. Run that task beside this one, or those settings only apply when something else
/// happens to cycle the worker.
pub async fn supervise(
    store: Arc<DashboardStore>,
    logs: Arc<LogBuffer>,
    settings: Arc<SettingsHandle>,
    restart: Arc<RestartSignal>,
    // Records what each child was actually started with, so the app can tell a bind-time key from a
    // bind-time key the user has *changed*. See `LaunchedWorker`.
    launched: Arc<LaunchedWorker>,
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
    // Slower than the link's, on purpose: a reconnect costs a socket and a respawn costs a JVM.
    let mut backoff = crate::backoff::Backoff::new(Duration::from_secs(1), Duration::from_secs(30));

    loop {
        let env = worker_env(&settings.snapshot());
        let spawn = Command::new(&launcher)
            .envs(env.clone())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .kill_on_drop(true)
            .spawn();
        match spawn {
            Ok(mut child) => {
                backoff.reset(); // healthy start resets backoff
                                 // What this child holds, so a later settings edit
                                 // can be compared against it rather than guessed
                                 // at. Recorded here — beside the one spawn — so
                                 // there is no second place to keep in step.
                launched.record(&env);
                // Stream the worker's output into the dashboard's log ring.
                if let Some(out) = child.stdout.take() {
                    tokio::spawn(pump_lines(out, Arc::clone(&logs)));
                }
                if let Some(err) = child.stderr.take() {
                    tokio::spawn(pump_lines(err, Arc::clone(&logs)));
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
                        backoff.restart_at(RESTART_SETTLE);
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
        // Both arms above leave no child: it exited, or we killed it. Whatever it was launched with
        // is now history, and the respawn at the top of this loop reads the settings as they are
        // then — so a worker that is down has nothing pending, by construction rather than by a
        // comparison that could go stale.
        launched.forget();
        // The link reports its own state, but it can take a moment to notice; saying it here means
        // the screen reflects a worker we KNOW is gone the instant we stop it.
        store.mark_offline("the peer worker is not running");
        backoff.wait().await;
    }
}

/// Stream one of the worker's output pipes into the dashboard's log ring until the pipe closes.
///
/// The read error is handled rather than treated as end of stream. `while let Ok(Some(line))` reads
/// an `Err` — which is what a single non-UTF-8 byte in the worker's output produces — the same way
/// it reads a clean EOF, so one such line used to kill the pump permanently: the log panel went
/// empty and stayed empty for the life of the app, with the worker still running and still talking.
/// A malformed line is skipped, and only a closed pipe ends the loop.
async fn pump_lines<R>(pipe: R, sink: Arc<LogBuffer>)
where
    R: tokio::io::AsyncRead + Unpin,
{
    let mut lines = BufReader::new(pipe).lines();
    loop {
        match lines.next_line().await {
            Ok(Some(line)) => sink.push(line),
            Ok(None) => return,
            Err(e) if e.kind() == std::io::ErrorKind::InvalidData => {
                // Not fatal, and not silent: a worker printing a stack trace with a stray byte in
                // it must not cost the user every line after it.
                sink.push(format!(
                    "nodera-app: skipped an unreadable worker log line: {e}"
                ));
            }
            Err(e) => {
                sink.push(format!("nodera-app: worker log stream ended: {e}"));
                return;
            }
        }
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
        let owned = ownership(Some(Vec::new()));
        assert_eq!(
            owned.can_restart,
            !owned.attached && crate::SUPERVISES_A_WORKER
        );
    }

    /// A refusal has to arrive with its sentence. The UI used to keep its own copy of both branches
    /// and pick between them on `attached`, which is one more place for the reason the app *shows*
    /// to drift from the reason the command actually gives.
    #[test]
    fn a_worker_that_cannot_be_restarted_says_why_on_the_wire() {
        let owned = ownership(None);
        assert_eq!(owned.can_restart, restart_unavailable().is_none());
        assert_eq!(owned.unavailable_reason.is_empty(), owned.can_restart);
    }

    /// A-UX-1: an unknown may not be rendered as a zero. `pending_known: false` is the whole reason
    /// the list is optional — an empty `pending_restart_keys` beside it would read as "everything
    /// you have changed is already applied", which is exactly what the app cannot tell in attach
    /// mode or on a phone.
    #[test]
    fn an_unknowable_pending_set_is_reported_as_unknown_not_as_empty() {
        let unknown = ownership(None);
        assert!(!unknown.pending_known);
        assert!(unknown.pending_restart_keys.is_empty());

        let known = ownership(Some(vec!["network.port_range".to_owned()]));
        assert!(known.pending_known);
        assert_eq!(known.pending_restart_keys, vec!["network.port_range"]);
    }

    /* ------------------------------------------------- the banner that was permanently true */

    /// The shipped bug. The worker answers `restart_required` for a bind-time key *whenever it is
    /// pushed one* — the reply describes the key's scope, not whether the value moved — and the app
    /// pushes `network.port_range` and `network.rendezvous_endpoints` on every single save. So the
    /// banner was true from first launch to uninstall, and restarting could not clear it.
    ///
    /// The real question is this one: does the worker that is *running* hold what the settings now
    /// say? Same settings, same env, nothing pending.
    #[test]
    fn a_worker_running_the_current_settings_has_nothing_pending() {
        let settings = without_stores();
        let launched = LaunchedWorker::default();
        launched.record(&worker_env(&settings));

        assert!(bind_time_changes(&worker_env(&settings), &settings).is_empty());
        assert_eq!(launched.pending_restart_keys(&settings), Some(Vec::new()));
    }

    #[test]
    fn moving_the_port_or_the_relay_list_is_what_makes_something_pending() {
        let mut before = without_stores();
        before.network.use_random_port = false;
        let running = worker_env(&before);

        let mut after = before.clone();
        after.network.port_range_start = 40000;
        assert_eq!(
            bind_time_changes(&running, &after),
            vec!["network.port_range"]
        );

        // Asking for a random port is the same decision by another control, and reports under the
        // same name — `network.port_range` is what the enforcement table calls "which port to bind".
        let mut after = before.clone();
        after.network.use_random_port = true;
        assert_eq!(
            bind_time_changes(&running, &after),
            vec!["network.port_range"]
        );

        let mut after = before.clone();
        after.network.rendezvous_endpoints = vec!["tcp://relay.example:25601".to_owned()];
        assert_eq!(
            bind_time_changes(&running, &after),
            vec!["network.rendezvous_endpoints"]
        );

        // Both at once report both, once each — the port is two env keys and one settings key, so a
        // naive walk of the table would name it twice and the banner would repeat itself.
        let mut after = before.clone();
        after.network.port_range_start = 40000;
        after.network.port_range_end = 40010;
        after.network.rendezvous_endpoints = vec!["tcp://relay.example:25601".to_owned()];
        assert_eq!(
            bind_time_changes(&running, &after),
            vec!["network.port_range", "network.rendezvous_endpoints"]
        );
    }

    /// A setting with a live `NODERA-CONFIG` path is not a restart, however much the worker's reply
    /// says `restart_required`. If this ever fails, something has been added to `worker_env` without
    /// being classified — and the banner is on its way back.
    #[test]
    fn a_setting_the_worker_can_be_told_about_live_never_becomes_pending() {
        let before = without_stores();
        let running = worker_env(&before);

        let mut after = before.clone();
        after.network.default_trackers = vec!["tcp://new.example:25600".to_owned()];
        after.network.max_upload_bytes_per_sec = 1_000_000;
        assert!(bind_time_changes(&running, &after).is_empty());
    }

    /// ...and neither is a control the worker never saw. With a random port asked for, the range is
    /// not in the environment at all, so editing it cannot be something the running worker is out of
    /// date on. Comparing settings instead of env would have restarted the worker for nothing.
    #[test]
    fn editing_a_range_the_worker_was_never_given_is_not_a_pending_change() {
        let before = without_stores();
        assert!(before.network.use_random_port);
        let running = worker_env(&before);

        let mut after = before.clone();
        after.network.port_range_start = 40000;
        after.network.port_range_end = 40010;
        assert!(bind_time_changes(&running, &after).is_empty());
    }

    /// The asymmetry that would have made the banner permanent from the other end: `worker_env`
    /// omits an empty key rather than sending a blank, so a relay list emptied back out compares an
    /// absent key against an absent key — not `Some("")` against `None`.
    #[test]
    fn emptying_a_list_settles_instead_of_reporting_a_change_forever() {
        let mut with_relay = without_stores();
        with_relay.network.rendezvous_endpoints = vec!["tcp://relay.example:25601".to_owned()];
        let empty = without_stores();

        // Removing it is a change...
        assert_eq!(
            bind_time_changes(&worker_env(&with_relay), &empty),
            vec!["network.rendezvous_endpoints"]
        );
        // ...and once the worker is running without it, it is not a change any more.
        assert!(bind_time_changes(&worker_env(&empty), &empty).is_empty());
    }

    /// A worker that is not running has nothing pending: the next spawn reads the settings as they
    /// are then. Reporting a change here would restart a worker that is already going to pick it up.
    #[test]
    fn a_worker_that_is_not_running_is_never_waiting_on_a_restart() {
        let mut settings = without_stores();
        settings.network.use_random_port = false;
        let launched = LaunchedWorker::default();
        launched.record(&worker_env(&settings));
        settings.network.port_range_start = 40000;
        assert_eq!(
            launched.pending_restart_keys(&settings),
            Some(vec!["network.port_range".to_owned()]),
            "a running worker on the old port is exactly the case this reports"
        );

        launched.forget();
        assert_eq!(
            launched.pending_restart_keys(&settings),
            Some(Vec::new()),
            "the supervisor forgets the child it lost, and the respawn takes the new port"
        );
    }

    /// Where the app cannot see the worker's environment it must say so rather than answer `[]`.
    #[test]
    fn an_unowned_worker_reports_pending_as_unknowable() {
        let launched = LaunchedWorker::default();
        let pending = launched.pending_restart_keys(&without_stores());
        assert_eq!(pending.is_none(), restart_unavailable().is_some());
    }

    /// M-NET-3. The Restart button was offered on Android and did nothing: the signal it notifies
    /// is consumed only by `supervise`, which is desktop-only. Both the flag the UI gates on
    /// and the verb itself now answer for the platform they are compiled for.
    #[test]
    fn restart_is_never_offered_where_nothing_consumes_the_signal() {
        if crate::SUPERVISES_A_WORKER {
            // Desktop, not attached (the test binary sets no NODERA_APP_ATTACH): a restart works.
            if !attach_mode() {
                assert!(ownership(Some(Vec::new())).can_restart);
                assert!(restart_unavailable().is_none());
            }
        } else {
            assert!(!ownership(None).can_restart);
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
