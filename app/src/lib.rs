//! Task 32: the Nodera companion app entrypoint (Tauri 2.x).
//!
//! Responsibilities:
//! * **System tray** presence (status icon + menu: Open dashboard / Pause seeding / Quit).
//! * **Window** that minimises to the tray instead of quitting (close = hide) so the node stays on.
//! * **Autostart** at login (all three OSes) via `tauri-plugin-autostart`.
//! * **Single instance** so only one daemon runs per machine.
//! * The **daemon supervisor** that runs the bundled headless Java peer (`daemon::supervise`).
//! * The **dashboard API** (`api`): a live link to the worker that the worker *pushes* into, a
//!   revisioned store, and one typed `nodera://dashboard` event the React pages render from.
//!
//! EXCLUDED from the `rust/` workspace gate — build with the Tauri toolchain (see Cargo.toml).

//! # Why this is a library
//!
//! Tauri's Android build does not run a `main`: the Activity loads this crate as a `cdylib` and
//! calls in through JNI. So the whole application lives here and `main.rs` is a two-line shim for
//! the desktop build. Splitting it is not optional and not cosmetic — a crate with only a binary
//! target fails the mobile build outright with "no library targets found".

//! # What is here, and what is not
//!
//! Only the parts that need a webview. Everything else — the control link, the settings document,
//! the tracker stores, the telemetry lane, the supervisor, the app's own peer — is `nodera-core`,
//! which the native Android activity reads through JNI and which nothing in this file is allowed to
//! be a prerequisite for.
//!
//! The modules below are re-exported at this crate's root rather than imported into each function,
//! so a path like `api::store::DashboardStore` still means what it used to and the move stayed a
//! move.
pub use nodera_core::{
    android, api, browser, config, control, daemon, logs, metrics, peer, power, settings, stores,
    system, telemetry,
};

mod shell;

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use config::PushSignal;
use nodera_core::core::NoderaCore;
use power::PauseHandle;
#[cfg(desktop)]
use tauri::menu::{Menu, MenuItem};
#[cfg(desktop)]
use tauri::tray::TrayIconBuilder;
use tauri::Emitter;
#[cfg(desktop)]
use tauri::{Manager, WindowEvent};

/// Loopback address of the WORKER's control endpoint (owned by `nodera-headless`, probed by both
/// the mod and this app). Keep in sync with the mod's `companion.controlEndpoint` default. Override
/// with `NODERA_CONTROL_PORT`.
fn control_addr() -> String {
    let port = std::env::var("NODERA_CONTROL_PORT").unwrap_or_else(|_| "25610".to_string());
    format!("127.0.0.1:{port}")
}

/// Whether this process may run alongside another copy of the app.
///
/// Normally it may not: one machine is one node, and a second window supervising a second worker
/// would fight the first for the control port — so the single-instance plugin focuses the existing
/// window instead. `NODERA_APP_MULTI=1` lifts that, which is exactly what a two-player development
/// stack needs: `scripts/dev.sh --play --with-app` runs one app **per simulated player**, each in
/// attach mode against its own already-running worker on its own control port, so the two never
/// contend for anything. Not something an end user should set.
#[cfg(desktop)]
fn multi_instance() -> bool {
    std::env::var("NODERA_APP_MULTI")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
}

/// Per-instance window title (`NODERA_APP_TITLE`), so two dev windows are tellable apart.
/// Empty/unset keeps the title from `tauri.conf.json`.
#[cfg(desktop)]
fn window_title() -> Option<String> {
    std::env::var("NODERA_APP_TITLE")
        .ok()
        .filter(|title| !title.trim().is_empty())
}

/// The one handle, from an owned `AppHandle`.
///
/// An async command that takes `State<'_, _>` must return a `Result` — Tauri will not let a borrowed
/// input escape into a future otherwise. Several of these commands answer with a plain value on
/// purpose (a `DirectoryEntry` list, an `Outcome` that already carries its own error string), and
/// wrapping them in `Result` to satisfy the macro would change the shape the interface reads. So
/// they take the handle, which is owned, and look the state up.
fn core_of(app: &tauri::AppHandle) -> Arc<NoderaCore> {
    use tauri::Manager as _;
    Arc::clone(&app.state::<Arc<NoderaCore>>())
}

/// Tauri command: machine + worker RAM/CPU for the resource tiles.
#[tauri::command]
fn get_system_stats(core: tauri::State<Arc<NoderaCore>>) -> system::SystemStats {
    core.system_stats()
}

/// Tauri command: the worker's recent log lines (oldest first, bounded ring).
#[tauri::command]
fn get_worker_logs(core: tauri::State<Arc<NoderaCore>>) -> Vec<String> {
    core.worker_logs()
}

/// Tauri command: the whole current picture, for the first paint.
///
/// A delegate rather than the command itself. `api::commands::dashboard` is a plain function on a
/// `&Arc<DashboardStore>`, because the Android front end calls it too and `tauri::State` means
/// nothing there — extracting the state is the one part of this that is genuinely about the shell.
#[tauri::command]
fn dashboard(core: tauri::State<Arc<NoderaCore>>) -> api::model::Dashboard {
    core.snapshot()
}

/// Tauri command: the persisted settings document.
#[tauri::command]
fn get_settings(core: tauri::State<Arc<NoderaCore>>) -> settings::Settings {
    core.settings()
}

/// Tauri command: the tracker stores this install trusts.
#[tauri::command]
fn get_tracker_stores(core: tauri::State<Arc<NoderaCore>>) -> Vec<stores::TrackerStore> {
    core.tracker_stores()
}

/// Tauri command: a store URL that arrived by deep link and is waiting for the user to confirm it.
///
/// The link does **not** add the store. A page on any website can send the user here, so the app
/// holds the URL, shows it, and waits — the same shape Mihon uses, and for the same reason: the
/// decision being made is "I trust this publisher", and it has to be made by the person, with the
/// URL in front of them.
#[tauri::command]
fn take_pending_tracker_store(core: tauri::State<Arc<NoderaCore>>) -> Option<String> {
    core.take_pending_tracker_store()
}

/// Tauri command: the endpoints this node will actually dial, and where each one came from.
///
/// The settings screen used to render `network.default_trackers` — the box the user types into —
/// and call that the tracker list. It is not: the effective list is that box *plus* whatever the
/// stores contribute, which is what `worker_env`, `WorkerConfig::of` and the synchronisation file
/// have always built. An install whose only tracker came from the built-in store therefore read
/// "0 tracker(s)" in Settings while the store screen beside it read "1 tracker · 1 relay", and the
/// obvious conclusion — that adding a store does nothing — was wrong but entirely reasonable.
///
/// So the screens ask for this instead. Provenance comes with it, because "where did this address
/// come from" is the next question anyone has in front of a list they did not fully write.
#[tauri::command]
fn resolved_services(
    core: tauri::State<Arc<NoderaCore>>,
) -> HashMap<String, Vec<stores::ResolvedEndpoint>> {
    core.resolved_services()
}

/// Tauri command: open a web link outside this window.
///
/// Every outbound link in the interface comes through here, because in a Tauri v2 webview an
/// `<a target="_blank">` opens nothing at all — there is no tab to open — and an anchor that *did*
/// navigate would replace the interface with somebody's web page and no way back.
///
/// The URL is validated before the platform sees it ([`browser::check_url`]): `http` and `https`
/// only. That matters because most of what this opens is third-party data — a tracker store's
/// `homepage` is written by whoever published the index, and trusting a publisher to list trackers
/// is not trusting them to hand this device a `file://` or an `intent://`.
///
/// A link always opens something. The ladder ends in a webview on both platforms rather than in the
/// clipboard, which is what it used to do and which is not opening a link.
///
/// Returns which rung answered, for the log: `browser`, `custom-tab` or `webview`.
#[tauri::command]
async fn open_external(
    app: tauri::AppHandle,
    core: tauri::State<'_, Arc<NoderaCore>>,
    url: String,
) -> Result<String, String> {
    let core = Arc::clone(&core);
    // On a blocking pool: `startActivity` and the desktop openers all block, and the window that
    // has to keep drawing while the browser comes up is this one.
    tokio::task::spawn_blocking(move || {
        core.open_external(&shell::DesktopOpener(app), &url)
            .map(|how| how.to_owned())
    })
    .await
    .map_err(|e| format!("the open task failed: {e}"))?
}

/// Tauri command: where the project's own list is published.
///
/// The screen needs this to offer "add the official list" as a button rather than as an address the
/// user is expected to type. Read from the one constant instead of being spelled again in the UI:
/// a second copy is a second thing to update when the list moves, and the symptom would be a button
/// that adds a store nobody publishes.
#[tauri::command]
fn official_store_url() -> &'static str {
    stores::OFFICIAL_STORE_URL
}

/// Tauri command: fetch and validate a store index **without** remembering it.
///
/// This is what makes adding a store a decision rather than a leap. Before this existed the screen
/// asked the user to paste a URL and press Add, and the first thing they learned about what they had
/// just trusted was a row appearing in a list — a store that turned out to be the wrong address, or
/// a list of relays in the wrong hemisphere, was found out after it was already contributing
/// endpoints. Now the same fetch happens first and its result is shown: the name the publisher gives
/// itself, and every service it carries.
///
/// Nothing is persisted, so a preview that the user walks away from leaves no trace. The store is
/// fetched a second time by [`add_tracker_store`] if they go ahead, which is deliberate: a preview
/// the app then *stored* would be a list the user confirmed and a list the app kept, and those are
/// only the same thing until a publisher edits the file between the two calls.
#[tauri::command]
async fn preview_tracker_store(
    core: tauri::State<'_, Arc<NoderaCore>>,
    url: String,
) -> Result<stores::ServiceIndex, String> {
    let core = Arc::clone(&core);
    core.preview_tracker_store(url).await
}

/// Tauri command: fetch a store index, validate it, and remember it.
///
/// Called only after the user has confirmed. Fetching happens on a blocking pool because this is a
/// synchronous HTTPS client; holding a Tauri command thread on a network round trip would freeze
/// the window that is showing the dialog.
#[tauri::command]
async fn add_tracker_store(
    core: tauri::State<'_, Arc<NoderaCore>>,
    url: String,
) -> Result<stores::TrackerStore, String> {
    let core = Arc::clone(&core);
    core.add_tracker_store(url).await
}

/// Tauri command: forget a store, and everything it contributed.
#[tauri::command]
fn remove_tracker_store(core: tauri::State<Arc<NoderaCore>>, url: String) -> Result<(), String> {
    core.remove_tracker_store(url)
}

/// Tauri command: re-read every store.
///
/// A store that fails to refresh keeps the services it last reported and records why. Dropping them
/// would mean a web server having a bad minute costs the user every tracker they had, which is a
/// far worse outcome than a slightly stale list.
#[tauri::command]
async fn refresh_tracker_stores(
    core: tauri::State<'_, Arc<NoderaCore>>,
) -> Result<Vec<stores::TrackerStore>, String> {
    let core = Arc::clone(&core);
    core.refresh_tracker_stores().await
}

/// Tauri command: re-read one store.
///
/// The all-or-nothing refresh is the wrong shape for the case that actually happens: one store in a
/// list of several is failing, and the user wants to retry *that one* after fixing something at the
/// other end. Refreshing all of them to retry one makes every other row's timestamp lie about when
/// it was last confirmed.
#[tauri::command]
async fn refresh_tracker_store(
    core: tauri::State<'_, Arc<NoderaCore>>,
    url: String,
) -> Result<stores::TrackerStore, String> {
    let core = Arc::clone(&core);
    core.refresh_tracker_store(url).await
}

/// Tauri command: every setting's honest status — live, restart-required, unsupported by this
/// worker, or unenforced with a reason. Computed from the worker's own last reply, never asserted.
#[tauri::command]
fn get_setting_status(core: tauri::State<Arc<NoderaCore>>) -> Vec<settings::SettingStatus> {
    core.setting_status()
}

/// Tauri command: the last configuration push's outcome, as soft status for the Settings screen.
#[tauri::command]
fn get_config_status(core: tauri::State<Arc<NoderaCore>>) -> config::ConfigStatus {
    core.config_status()
}

/// Tauri command: the node's telemetry consent and emitter status.
///
/// Always read from the worker rather than cached here: the record lives on the node, and the app
/// is one of several things that may have changed it (the mod's `/nodera telemetry` is another).
#[tauri::command]
async fn get_telemetry_status(
    core: tauri::State<'_, Arc<NoderaCore>>,
) -> Result<telemetry::TelemetryStatus, String> {
    let core = Arc::clone(&core);
    Ok(core.telemetry_status().await)
}

/// Tauri command: record the person's answer.
///
/// Recorded locally first and handed to the worker second, so a node that is still starting — which
/// on a fresh install is the normal case — cannot turn a question into a dead end. The reply
/// carries both halves: `consent` is what the worker confirmed, `pending` says an answer is still
/// waiting to reach it. The UI badges the first and explains the second; neither is invented.
#[tauri::command]
async fn set_telemetry_consent(
    core: tauri::State<'_, Arc<NoderaCore>>,
    granted: bool,
) -> Result<telemetry::TelemetryStatus, String> {
    let core = Arc::clone(&core);
    Ok(core.set_telemetry_consent(granted).await)
}

/// Tauri command: what the configured collector says it accepts.
///
/// The disclosure is read from the service the user actually reports to. A failure here is not an
/// error state for the screen — it falls back to the bundled registry, labelled as a fallback.
#[tauri::command]
async fn get_collected_schema(
    core: tauri::State<'_, Arc<NoderaCore>>,
    endpoint: String,
) -> Result<String, String> {
    let core = Arc::clone(&core);
    core.collected_schema(endpoint).await
}

/// Tauri command: who owns the worker process, so the UI knows whether to offer Restart.
#[tauri::command]
fn get_worker_ownership(core: tauri::State<Arc<NoderaCore>>) -> daemon::WorkerOwnership {
    core.worker_ownership()
}

/// Tauri command: cycle the worker so env-shaped settings (trackers, port range, archive dir) apply.
///
/// **Refuses in attach mode.** A worker started by `scripts/dev.sh` — or by an operator — is not
/// this app's process to kill; killing it would leave the dev stack silently short a node with no
/// supervisor to bring it back.
///
/// **Refuses on mobile.** The supervisor that consumes the signal is `#[cfg(desktop)]`, so the
/// notification would wake nobody; the answer is a sentence telling the user what does work
/// (relaunching the app), not a no-op that looks like success.
#[tauri::command]
fn restart_worker(core: tauri::State<Arc<NoderaCore>>) -> Result<(), String> {
    core.restart_worker()
}

/// Tauri command: toggle the manual "pause seeding" flag, mirroring the tray item. Returns the new
/// effective pause state (which the battery rules can also be holding on).
#[tauri::command]
fn toggle_pause(core: tauri::State<Arc<NoderaCore>>) -> bool {
    core.toggle_pause()
}

/// Tauri command: validate + persist settings, and apply the ones that reach something real.
///
/// **Persist first, push second.** The push is a network round-trip to a process that may be down;
/// making the save depend on it would mean a user loses a setting because their worker crashed.
/// The push therefore happens asynchronously, debounced, and reports through
/// [`get_config_status`] as soft status — never as a save failure.
#[tauri::command]
fn save_settings(
    app: tauri::AppHandle,
    core: tauri::State<Arc<NoderaCore>>,
    next: settings::Settings,
) -> Result<(), String> {
    let auto_start = next.behavior.auto_start;
    core.save_settings(next)?;
    // Applied here rather than merely stored, because the OS — not the worker — is what enforces
    // it, and only this shell has the plugin that can. A plugin error is reported, never swallowed:
    // silently failing to register a login item while the toggle shows "on" is the worst of both
    // outcomes.
    #[cfg(desktop)]
    {
        use tauri_plugin_autostart::ManagerExt;
        let manager = app.autolaunch();
        let result = if auto_start {
            manager.enable()
        } else {
            manager.disable()
        };
        result.map_err(|e| format!("settings saved, but auto-start could not be applied: {e}"))
    }
    // Android has no login-item concept and the setting is hidden there. Saving it is still
    // honoured — a device that is later synced to a desktop keeps the preference — but claiming to
    // have enforced it would be a lie the UI would repeat.
    #[cfg(not(desktop))]
    {
        let _ = (app, auto_start);
        Ok(())
    }
}

/// Tauri command: one world's piece grid, fetched on demand.
///
/// Deliberately pulled rather than pushed on the dashboard stream: only the world the user has
/// selected is interesting, and a node seeding a dozen worlds should not be re-encoding every
/// bitmap several times a second for a tab nobody is looking at.
#[tauri::command]
async fn get_piece_map(app: tauri::AppHandle, world_id: String) -> metrics::PieceMapView {
    let core = core_of(&app);
    core.piece_map(world_id).await
}

/// Tauri command: answer the "shall I share this LAN world?" question.
///
/// `action` is `SHARE`, `DECLINE` or `STOP`. Nothing about a LAN world reaches the network until
/// this says so — detection is not consent.
#[tauri::command]
async fn lan_action(app: tauri::AppHandle, action: String, port: u16) -> api::network::Outcome {
    let core = core_of(&app);
    core.lan_action(action, port).await
}

/// Tauri command: what is joinable on the network right now.
#[tauri::command]
async fn browse_network(
    app: tauri::AppHandle,
    limit: Option<u32>,
) -> Vec<api::network::DirectoryEntry> {
    let core = core_of(&app);
    core.browse_network(limit).await
}

/// Tauri command: join a session. Returns the `127.0.0.1:<port>` to Direct Connect to.
///
/// Nothing is downloaded. The worker opens a tunnel to the host and binds a loopback port; the
/// player's own Minecraft then connects to it as though the host were on the same LAN.
#[tauri::command]
async fn join_world(app: tauri::AppHandle, session_id: String) -> api::network::Outcome {
    let core = core_of(&app);
    core.join_world(session_id).await
}

/// Tauri command: close a door opened by [`join_world`].
#[tauri::command]
async fn leave_world(app: tauri::AppHandle, session_id: String) -> api::network::Outcome {
    let core = core_of(&app);
    core.leave_world(session_id).await
}

/// Tauri command: mint the pasteable invitation for a world.
#[tauri::command]
async fn world_share_link(app: tauri::AppHandle, world_id: String) -> api::network::Outcome {
    let core = core_of(&app);
    core.world_share_link(world_id).await
}

/// Tauri command: save an invitation as a `.nodera` file and report where it went.
///
/// The app picks the folder (`~/Nodera`) rather than opening a file dialog: the point of the button
/// is to produce something to send in the next ten seconds, and a save dialog for a 200-byte text
/// file is three clicks of ceremony. The path is returned so the UI can show it, which is the part
/// people actually need.
#[tauri::command]
fn save_share_file(
    core: tauri::State<Arc<NoderaCore>>,
    name: String,
    uri: String,
) -> Result<String, String> {
    core.save_share_file(name, uri)
}

/// Tauri command: the world id inside a pasted link, or an error the UI can show immediately.
#[tauri::command]
fn parse_share_link(core: tauri::State<Arc<NoderaCore>>, uri: String) -> Result<String, String> {
    core.parse_share_link(uri)
}

/// Tauri command: this device's own peer identity and its last tracker exchange.
///
/// Mobile only in practice — on the desktop the Java worker is the peer and this would be a second
/// node on one machine — but the command exists on both so the same screen can be developed and
/// tested on a laptop.
#[tauri::command]
async fn peer_status(core: tauri::State<'_, Arc<NoderaCore>>) -> Result<peer::PeerStatus, String> {
    let core = Arc::clone(&core);
    core.peer_status().await
}

/// Tauri command: the round trip that proves this device is on the network.
///
/// Announces, then queries the tracker back and looks for **this device's own entry**. An accepted
/// announce alone would only say the tracker took the bytes.
#[tauri::command]
async fn peer_self_test(
    core: tauri::State<'_, Arc<NoderaCore>>,
) -> Result<peer::tracker::SelfTest, String> {
    let core = Arc::clone(&core);
    core.peer_self_test().await
}

/// Tauri command: whether this build runs its own peer instead of supervising a worker.
#[tauri::command]
fn is_mobile_build() -> bool {
    peer::is_mobile()
}

/// Tauri command: what this machine could start, and how each one would start it.
///
/// Read before the button is pressed, so the interface can name the instance and say whether the
/// player will land in the world or in the Multiplayer menu.
#[cfg(desktop)]
#[tauri::command]
async fn launch_targets(app: tauri::AppHandle) -> Vec<nodera_core::launch::discover::LaunchTarget> {
    core_of(&app).launch_targets().await
}

/// Tauri command: join a world and start the game in it.
///
/// Returns as soon as the launch is *under way*; everything after that arrives on
/// `nodera://launch`, because a launch is a sequence of states a player watches rather than a call
/// that answers once. The task owns the tunnel it opened and closes it when the game exits.
#[cfg(desktop)]
#[tauri::command]
async fn launch_play(
    app: tauri::AppHandle,
    world_id: String,
    world_name: String,
    profile: Option<String>,
) -> Result<(), String> {
    let core = core_of(&app);
    let sink: Arc<dyn nodera_core::launch::LaunchSink> =
        Arc::new(shell::TauriLaunchSink(app.clone()));
    tokio::spawn(async move { core.play(world_id, world_name, profile, sink).await });
    Ok(())
}

/// Tauri command: what this build is and what it is built out of.
///
/// The dependency list is embedded from a generated manifest, so it describes *this* binary rather
/// than whatever was last committed.
#[tauri::command]
fn about_build() -> api::about::About {
    api::about::about()
}

/// Tauri command: where Minecraft is, and whether the mod is installed there.
#[tauri::command]
fn mod_install_status() -> api::modinstall::ModInstallStatus {
    api::modinstall::status()
}

/// Tauri command: install the bundled mod jar into one Minecraft installation.
#[tauri::command]
fn install_mod(game_dir: String) -> Result<String, String> {
    api::modinstall::install(&game_dir)
}

/// Tauri command: remove the Nodera jar from one Minecraft installation.
#[tauri::command]
fn uninstall_mod(game_dir: String) -> Result<String, String> {
    api::modinstall::uninstall(&game_dir)
}

/// Tauri command: ask the worker to sign a fresh challenge with a world's private key.
///
/// See `api::commands::prove_world_admin` for what a success does and does not establish — it shows
/// this worker holds the key, and is not a cryptographic verification, which is the network's job.
#[tauri::command]
async fn prove_world_admin(app: tauri::AppHandle, world_id: String) -> api::commands::AdminProof {
    let core = core_of(&app);
    core.prove_world_admin(world_id).await
}

/// Tauri command: ask the worker to delete a world this peer owns, everywhere.
///
/// **Irreversible.** The worker refuses unless it holds the world's private key, and every peer
/// re-verifies the signed record before destroying its own copy — so this cannot be used against a
/// world this machine does not administer, whatever the page sends.
#[tauri::command]
async fn delete_world(
    app: tauri::AppHandle,
    world_id: String,
    reason: String,
) -> api::commands::DeleteOutcome {
    let core = core_of(&app);
    core.delete_world(world_id, reason).await
}

/// Tauri command: write the whole worker scrollback to a file the user picks.
///
/// The **entire** buffer, not the tail the console renders. A log the user is about to attach to a
/// bug report is worth nothing if it stops at whatever happened to be on screen.
///
/// # Returns
/// The chosen path, or `None` when the user cancelled the dialog — cancelling is an ordinary
/// outcome and must not surface as an error the page has to explain away.
#[tauri::command]
async fn save_worker_logs(
    app: tauri::AppHandle,
    core: tauri::State<'_, Arc<NoderaCore>>,
) -> Result<Option<String>, String> {
    use tauri_plugin_dialog::DialogExt;

    // Snapshotted before the dialog opens: the buffer keeps moving while the user browses, and a
    // file whose contents depend on how long somebody took to choose a folder is not reproducible.
    let content = core.worker_log_text();
    let suggested = format!(
        "nodera-worker-{}.log",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or_default()
    );
    let (tx, rx) = tokio::sync::oneshot::channel();
    app.dialog()
        .file()
        .set_title("Save worker logs")
        .set_file_name(&suggested)
        .add_filter("Log file", &["log", "txt"])
        .save_file(move |chosen| {
            let _ = tx.send(chosen);
        });
    let Some(chosen) = rx
        .await
        .map_err(|_| "the save dialog closed unexpectedly".to_owned())?
    else {
        return Ok(None);
    };
    let path = chosen
        .into_path()
        .map_err(|e| format!("that location cannot be written to: {e}"))?;
    std::fs::write(&path, content).map_err(|e| format!("could not write the log file: {e}"))?;
    Ok(Some(path.display().to_string()))
}

/// Tauri command: where world data can be kept on this device, and where it is kept now.
#[tauri::command]
fn storage_info(
    app: tauri::AppHandle,
    core: tauri::State<Arc<NoderaCore>>,
) -> api::storage::StorageInfo {
    core.storage_info(&app_data_dir(&app))
}

/// This app's private directory, whatever the platform calls it.
fn app_data_dir(app: &tauri::AppHandle) -> std::path::PathBuf {
    use tauri::Manager as _;
    app.path()
        .app_data_dir()
        .unwrap_or_else(|_| settings::config_dir())
}

/// Tauri command: whether the first-run setup still has to be completed.
///
/// Kept on the Rust side rather than in browser storage because it gates a decision with real
/// consequences — where data is written, and whether anything is reported — and browser storage is
/// cleared by the very "clear app data" that also removes the answers.
#[tauri::command]
fn setup_state(core: tauri::State<Arc<NoderaCore>>) -> settings::SetupState {
    core.setup_state()
}

/// Tauri command: record that the user finished the first-run setup.
#[tauri::command]
fn complete_setup(core: tauri::State<Arc<NoderaCore>>, worlds_dir: String) -> Result<(), String> {
    core.complete_setup(worlds_dir)
}

/// Tauri command: open the system folder picker.
///
/// Returns as soon as the picker is on screen; the answer arrives asynchronously and is read with
/// [`picked_folder`], because an Android activity result cannot be awaited from here.
#[tauri::command]
fn pick_storage_folder() -> Result<(), String> {
    android::battery::pick_folder()
}

/// Tauri command: what the folder picker came back with.
#[tauri::command]
fn picked_folder(
    app: tauri::AppHandle,
    core: tauri::State<Arc<NoderaCore>>,
) -> api::storage::PickedFolder {
    core.picked_folder(&app_data_dir(&app))
}

/// Tauri command: whether the OS may stop this node in the background.
#[tauri::command]
fn battery_policy() -> android::battery::BatteryPolicy {
    android::battery::policy()
}

/// Tauri command: which connection this device is on, and whether the node is allowed to use it.
///
/// Read from the sampler's cache rather than re-probing, so the screen and the pause decision can
/// never disagree about what network this is.
#[tauri::command]
fn network_state(core: tauri::State<Arc<NoderaCore>>) -> android::network::NetworkState {
    core.network_state()
}

/// Tauri command: why transfers are paused, in words, or empty when they are not.
#[tauri::command]
fn pause_reason(core: tauri::State<Arc<NoderaCore>>) -> String {
    core.pause_reason()
}

/// Tauri command: why the stored settings could not be read, when they could not.
///
/// Empty in the normal case. Non-empty means the user is looking at defaults and their real
/// document is still on disk, untouched — which they have to be told, or they will conclude the app
/// forgot and re-enter everything on top of a file that was never the problem.
#[tauri::command]
fn settings_fault(core: tauri::State<Arc<NoderaCore>>) -> String {
    core.settings_fault()
}

/// Tauri command: open the system's battery-optimisation screen.
#[tauri::command]
fn open_battery_settings() -> Result<(), String> {
    android::battery::open_settings()
}

/// Tauri command: open this vendor's page on dontkillmyapp.com.
#[tauri::command]
fn open_battery_help(app: tauri::AppHandle) -> Result<(), String> {
    android::battery::open_help(&shell::DesktopOpener(app))
}

/// Build and run the application.
///
/// `mobile_entry_point` is what the Android Activity calls; on desktop `main.rs` calls it directly.
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // Route this process's log lines into logcat under one tag, so an installed build can be
    // watched with `adb logcat -s NoderaMC` instead of being a black box.
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("NoderaMC"),
        );
        // A Rust panic on Android aborts the process with nothing in the Java crash buffer, so the
        // app "just closes" and the reason dies with it. This puts the panic — message and location
        // — into logcat under the app's own tag before the process goes.
        std::panic::set_hook(Box::new(|info| {
            log::error!("PANIC: {info}");
        }));
    }

    // One handle, built once. Every command below is a delegate onto it, and the Android activity
    // holds the same struct — so there is exactly one construction of the application's state and
    // the two front ends cannot end up with different ideas of what it contains.
    let core = Arc::new(NoderaCore::new());
    let dashboard_store = Arc::clone(&core.dashboard);
    let system_stats = Arc::clone(&core.system);
    let worker_logs = Arc::clone(&core.logs);
    let user_settings = Arc::clone(&core.settings);
    let pause = Arc::clone(&core.pause);
    let push_signal = Arc::clone(&core.push);
    let pending_store = Arc::clone(&core.pending_store);

    #[allow(unused_mut)]
    let mut builder = tauri::Builder::default();
    // Both of these are desktop OS integrations with no Android meaning: an app is already
    // single-instance there, and "launch at login" is not a thing a phone offers.
    #[cfg(desktop)]
    if !multi_instance() {
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            // A second launch focuses the existing window rather than starting a second daemon.
            if let Some(win) = app.get_webview_window("main") {
                let _ = win.show();
                let _ = win.set_focus();
            }
        }));
    }
    #[cfg(desktop)]
    {
        builder = builder.plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec![]),
        ));
    }

    builder
        .plugin(tauri_plugin_dialog::init())
        // Opening a link outside this window. Registered for its Rust API only — the interface
        // never calls the plugin directly, it calls `open_external`, which validates the URL
        // first (see `browser::check_url`). No JS capability is granted for the same reason.
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_deep_link::init())
        .manage(Arc::clone(&core))
        .invoke_handler(tauri::generate_handler![
            get_tracker_stores,
            take_pending_tracker_store,
            official_store_url,
            resolved_services,
            open_external,
            preview_tracker_store,
            add_tracker_store,
            remove_tracker_store,
            refresh_tracker_store,
            refresh_tracker_stores,
            dashboard,
            prove_world_admin,
            lan_action,
            browse_network,
            join_world,
            leave_world,
            world_share_link,
            save_share_file,
            parse_share_link,
            about_build,
            #[cfg(desktop)]
            launch_targets,
            #[cfg(desktop)]
            launch_play,
            peer_status,
            peer_self_test,
            is_mobile_build,
            mod_install_status,
            install_mod,
            uninstall_mod,
            get_system_stats,
            get_worker_logs,
            save_worker_logs,
            storage_info,
            setup_state,
            complete_setup,
            pick_storage_folder,
            picked_folder,
            battery_policy,
            network_state,
            pause_reason,
            settings_fault,
            open_battery_settings,
            open_battery_help,
            delete_world,
            get_piece_map,
            get_settings,
            get_setting_status,
            get_config_status,
            get_worker_ownership,
            restart_worker,
            toggle_pause,
            save_settings,
            get_telemetry_status,
            set_telemetry_consent,
            get_collected_schema
        ])
        .setup(move |app| {
            // An incoming `nodera://tracker-store?url=…` link. It records the URL and does nothing
            // else: a page on any website can send a user here, so the app shows the URL and waits
            // for them to answer. Acting on the link itself would let a link change whose
            // infrastructure this node talks to, which is a decision that belongs to the person.
            //
            // Registered before the window exists so a cold start — the app launched *by* the link —
            // still has the URL waiting when the frontend asks for it.
            {
                use tauri_plugin_deep_link::DeepLinkExt as _;
                let pending = Arc::clone(&pending_store);
                app.deep_link().on_open_url(move |event| {
                    for url in event.urls() {
                        if let Some(store) = stores::store_url_from_deep_link(url.as_str()) {
                            log::info!("deep link: a tracker store was offered");
                            pending.offer(store);
                        }
                    }
                });
            }

            // Android first: nothing below may touch the filesystem until the app knows which
            // directory it is allowed to write to.
            #[cfg(target_os = "android")]
            let android_worker_properties_ready = {
                use tauri::Manager as _;
                match app.path().app_data_dir() {
                    Ok(dir) => {
                        let _ = std::fs::create_dir_all(&dir);
                        log::info!("storage: {}", dir.display());
                        settings::set_android_data_dir(dir);
                        // The handle was built before this directory was known, so it is holding
                        // defaults read from a path that did not exist. Re-read now, or every
                        // answer the user has ever given is invisible to this process.
                        user_settings.reload();
                        // Materialise validated settings now, but do not signal readiness until the
                        // end of this setup hook. Control port is intentionally absent.
                        match daemon::write_worker_properties(&user_settings.snapshot()) {
                            Ok(()) => true,
                            Err(reason) => {
                                log::error!("could not prepare Android worker properties: {reason}");
                                false
                            }
                        }
                    }
                    Err(e) => {
                        log::error!("no writable app directory: {e}");
                        false
                    }
                }
            };

            #[cfg(desktop)]
            {
                build_tray(app, Arc::clone(&pause), Arc::clone(&push_signal))?;

                // Close = hide to tray (keep the node alive), don't quit.
                if let Some(win) = app.get_webview_window("main") {
                    // Name the window after whichever node this instance is watching, so a
                    // multi-instance dev stack does not present two identical "Nodera" windows.
                    if let Some(title) = window_title() {
                        let _ = win.set_title(&title);
                    }
                    let w = win.clone();
                    win.on_window_event(move |event| {
                        if let WindowEvent::CloseRequested { api, .. } = event {
                            api.prevent_close();
                            let _ = w.hide();
                        }
                    });
                }

                // Background async work on Tauri's async runtime (tokio).
                // Supervise the worker (unless attach mode, where scripts/dev.sh already runs it)...
                let store_daemon = Arc::clone(&dashboard_store);
                let logs_daemon = Arc::clone(&worker_logs);
                let settings_daemon = Arc::clone(&user_settings);
                let restart_daemon = Arc::clone(&core.restart);
                // Where THIS bundle keeps its resources. Resolved here because `setup` is the only
                // place holding an `AppHandle`, and passed in rather than looked up inside the
                // supervisor so the path logic stays testable without a running Tauri app.
                //
                // `Err` is not a failure: a `cargo run` build has no bundle, and the supervisor's
                // other candidates are the right answer there.
                let resource_dir = {
                    use tauri::Manager as _;
                    app.path().resource_dir().ok()
                };
                tauri::async_runtime::spawn(async move {
                    daemon::supervise(
                        store_daemon,
                        logs_daemon,
                        settings_daemon,
                        restart_daemon,
                        resource_dir,
                    )
                    .await;
                });
            }

            // On Android the two-signal startup gate calls `NoderaWorker.start` after context and
            // property handoff are both ready. It runs in this process, loaded from the APK's
            // assets. There is nothing to supervise from here: a process that cannot outlive us
            // cannot be restarted by us, and the link reports it like a desktop worker.
            #[cfg(not(desktop))]
            {
                log::info!("worker: awaiting deterministic in-process startup; connecting to the control endpoint");
                // The worker's own log file is the Activity screen's source. Same tailer the
                // desktop uses in attach mode — the worker is somebody else's process there too.
                let logs_tail = Arc::clone(&worker_logs);
                tauri::async_runtime::spawn(async move {
                    logs::tail_attach_log(logs_tail).await;
                });
            }

            // Sampling the worker as an OS PROCESS is desktop-only: on Android it is a thread in
            // this very process, so there is no separate pid to watch and the tiles it feeds are
            // about a machine, not a phone.
            #[cfg(desktop)]
            {
                let system_sampler = Arc::clone(&system_stats);
                tauri::async_runtime::spawn(async move {
                    system::sample(system_sampler).await;
                });
            }

            // Everything that is the same on both shells, started in one place: the live link,
            // the event stream, the store refresh, the configuration pusher, the connection rules
            // and the telemetry reconciler. Written out here AND in the Android bridge they would
            // drift, and the drift would be invisible — a build that forgot the store refresh would
            // simply stop learning about new trackers, with nothing on screen to say so.
            //
            // The two sinks are this shell's: a Tauri event per push. Android calls back into
            // Kotlin instead, which is the only difference between the two.
            let link_sink: Arc<dyn api::link::Sink> =
                Arc::new(shell::TauriSink::new(app.handle().clone()));
            let event_sink: Arc<dyn api::events::EventSink> =
                Arc::new(shell::TauriEventSink(app.handle().clone()));
            core.start_shared_loops(link_sink, event_sink);

            // Host power rules: desktop-only. The crate does not build for Android, and deciding
            // when a phone should stop working in the background is the phone's job — which is what
            // the battery-optimisation screen in Settings is about.
            #[cfg(desktop)]
            {
                let settings_power = Arc::clone(&user_settings);
                let store_power = Arc::clone(&dashboard_store);
                let pause_power = Arc::clone(&pause);
                let push_power = Arc::clone(&push_signal);
                tauri::async_runtime::spawn(async move {
                    power::sample(settings_power, store_power, pause_power, push_power).await;
                });
            }

            // Resource tiles still ride a cadence — CPU and RAM are sampled, not announced, so
            // there is no change to be pushed. The dashboard itself is NOT emitted from here: it is
            // emitted by the link, at the moment a snapshot is accepted, so an event means "this
            // just changed" rather than "a second has passed".
            let handle = app.handle().clone();
            let system_ui = Arc::clone(&system_stats);
            tauri::async_runtime::spawn(async move {
                let mut tick = tokio::time::interval(Duration::from_secs(2));
                loop {
                    tick.tick().await;
                    let _ = handle.emit("nodera://system", system_ui.snapshot());
                }
            });

            // A telemetry answer given while the worker was still starting is delivered here, not
            // lost. First run is precisely when the node is least likely to be listening, and the
            // person answering deserves the flow to move on regardless.
            {
                let settings_consent = Arc::clone(&user_settings);
                tauri::async_runtime::spawn(async move {
                    telemetry::reconcile_loop(control_addr(), settings_consent).await;
                });
            }

            // Last setup action: only now may the context/settings gate start the Java worker.
            #[cfg(target_os = "android")]
            if android_worker_properties_ready {
                android::worker::settings_ready();
            }

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running the Nodera companion app");
}

#[cfg(desktop)]
fn build_tray(
    app: &tauri::App,
    pause_state: Arc<PauseHandle>,
    push: Arc<PushSignal>,
) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Open dashboard", true, None::<&str>)?;
    let pause = MenuItem::with_id(app, "pause", "Pause seeding", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit Nodera", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &pause, &quit])?;
    let pause_item = pause.clone();

    TrayIconBuilder::new()
        .icon(app.default_window_icon().unwrap().clone())
        // Same per-instance name as the window: two dev tray icons both reading "Nodera" would be
        // indistinguishable in the system tray.
        .tooltip(window_title().unwrap_or_else(|| "Nodera — connected".to_owned()))
        .menu(&menu)
        .on_menu_event(move |app, event| match event.id().as_ref() {
            "open" => {
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.show();
                    let _ = win.set_focus();
                }
            }
            // Real, not decorative: it flips the manual half of the pause flag and asks for a
            // configuration push, which is the same path the battery rules take. The item relabels
            // itself, because a toggle that never changes appearance reads as a dead menu entry —
            // which is exactly what this one was.
            "pause" => {
                pause_state.toggle_manual();
                push.request();
                let _ = pause_item.set_text(if pause_state.manual_paused() {
                    "Resume seeding"
                } else {
                    "Pause seeding"
                });
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;
    Ok(())
}
