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

mod android;
mod api;
mod config;
mod control;
mod daemon;
mod logs;
mod metrics;
mod peer;
mod power;
mod settings;
mod system;
mod telemetry;

use std::sync::Arc;
use std::time::Duration;

use config::{ConfigPusher, ConfigStatusHandle, PushSignal};
use daemon::RestartSignal;
use logs::LogBuffer;
use api::store::DashboardStore;
use power::PauseHandle;
use system::SystemHandle;
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

/// Tauri command: machine + worker RAM/CPU for the resource tiles.
#[tauri::command]
fn get_system_stats(state: tauri::State<Arc<SystemHandle>>) -> system::SystemStats {
    state.snapshot()
}

/// Tauri command: the worker's recent log lines (oldest first, bounded ring).
#[tauri::command]
fn get_worker_logs(state: tauri::State<Arc<LogBuffer>>) -> Vec<String> {
    state.snapshot()
}

/// Tauri command: the persisted settings document.
#[tauri::command]
fn get_settings(state: tauri::State<Arc<settings::SettingsHandle>>) -> settings::Settings {
    state.snapshot()
}

/// Tauri command: every setting's honest status — live, restart-required, unsupported by this
/// worker, or unenforced with a reason. Computed from the worker's own last reply, never asserted.
#[tauri::command]
fn get_setting_status(
    status: tauri::State<Arc<ConfigStatusHandle>>,
) -> Vec<settings::SettingStatus> {
    settings::setting_status(&status.snapshot().report())
}

/// Tauri command: the last configuration push's outcome, as soft status for the Settings screen.
#[tauri::command]
fn get_config_status(status: tauri::State<Arc<ConfigStatusHandle>>) -> config::ConfigStatus {
    status.snapshot()
}

/// Tauri command: the node's telemetry consent and emitter status.
///
/// Always read from the worker rather than cached here: the record lives on the node, and the app
/// is one of several things that may have changed it (the mod's `/nodera telemetry` is another).
#[tauri::command]
async fn get_telemetry_status() -> telemetry::TelemetryStatus {
    telemetry::status(&control_addr()).await
}

/// Tauri command: record the person's answer on the node.
///
/// Returns the status **as the worker reports it afterwards**, so the UI badges what was confirmed
/// rather than what was requested.
#[tauri::command]
async fn set_telemetry_consent(granted: bool) -> Result<telemetry::TelemetryStatus, String> {
    telemetry::set(&control_addr(), granted).await
}

/// Tauri command: what the configured collector says it accepts.
///
/// The disclosure is read from the service the user actually reports to. A failure here is not an
/// error state for the screen — it falls back to the bundled registry, labelled as a fallback.
#[tauri::command]
async fn get_collected_schema(endpoint: String) -> Result<String, String> {
    telemetry::collected_schema(&endpoint).await
}

/// Tauri command: kept as a thin shim over [`get_setting_status`] so an older frontend bundle keeps
/// working. New code should ask for the statuses, which say *why*.
#[tauri::command]
fn get_unenforced_settings(status: tauri::State<Arc<ConfigStatusHandle>>) -> Vec<String> {
    settings::Settings::unenforced(&status.snapshot().report())
}

/// Tauri command: who owns the worker process, so the UI knows whether to offer Restart.
#[tauri::command]
fn get_worker_ownership() -> daemon::WorkerOwnership {
    daemon::ownership()
}

/// Tauri command: cycle the worker so env-shaped settings (trackers, port range, archive dir) apply.
///
/// **Refuses in attach mode.** A worker started by `scripts/dev.sh` — or by an operator — is not
/// this app's process to kill; killing it would leave the dev stack silently short a node with no
/// supervisor to bring it back.
#[tauri::command]
fn restart_worker(restart: tauri::State<Arc<RestartSignal>>) -> Result<(), String> {
    if daemon::attach_mode() {
        return Err(
            "this worker was started outside the app, so the app will not stop it — restart it \
             where you started it"
                .to_owned(),
        );
    }
    restart.0.notify_one();
    Ok(())
}

/// Tauri command: toggle the manual "pause seeding" flag, mirroring the tray item. Returns the new
/// effective pause state (which the battery rules can also be holding on).
#[tauri::command]
fn toggle_pause(pause: tauri::State<Arc<PauseHandle>>, push: tauri::State<Arc<PushSignal>>) -> bool {
    let paused = pause.toggle_manual();
    push.request();
    paused
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
    state: tauri::State<Arc<settings::SettingsHandle>>,
    push: tauri::State<Arc<PushSignal>>,
    next: settings::Settings,
) -> Result<(), String> {
    let auto_start = next.behavior.auto_start;
    state.save(next)?;
    push.request();
    // Auto-start is applied here rather than merely stored, because the OS — not the worker — is
    // what enforces it. A plugin error is reported, never swallowed: silently failing to register a
    // login item while the toggle shows "on" is the worst of both outcomes.
    #[cfg(desktop)]
    {
        use tauri_plugin_autostart::ManagerExt;
        let manager = app.autolaunch();
        let result = if auto_start {
            manager.enable()
        } else {
            manager.disable()
        };
        return result
            .map_err(|e| format!("settings saved, but auto-start could not be applied: {e}"));
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
async fn get_piece_map(world_id: String) -> metrics::PieceMapView {
    api::commands::dashboard_pieces(control_addr(), world_id).await
}

/// Tauri command: answer the "shall I share this LAN world?" question.
///
/// `action` is `SHARE`, `DECLINE` or `STOP`. Nothing about a LAN world reaches the network until
/// this says so — detection is not consent.
#[tauri::command]
async fn lan_action(action: String, port: u16) -> api::network::Outcome {
    api::network::lan_action(&control_addr(), &action, port).await
}

/// Tauri command: what is joinable on the network right now.
#[tauri::command]
async fn browse_network(limit: Option<u32>) -> Vec<api::network::DirectoryEntry> {
    api::network::directory(&control_addr(), limit.unwrap_or(50)).await
}

/// Tauri command: join a session. Returns the `127.0.0.1:<port>` to Direct Connect to.
///
/// Nothing is downloaded. The worker opens a tunnel to the host and binds a loopback port; the
/// player's own Minecraft then connects to it as though the host were on the same LAN.
#[tauri::command]
async fn join_world(session_id: String) -> api::network::Outcome {
    api::network::join_session(&control_addr(), &session_id).await
}

/// Tauri command: close a door opened by [`join_world`].
#[tauri::command]
async fn leave_world(session_id: String) -> api::network::Outcome {
    api::network::leave_session(&control_addr(), &session_id).await
}

/// Tauri command: mint the pasteable invitation for a world.
#[tauri::command]
async fn world_share_link(world_id: String) -> api::network::Outcome {
    api::network::share_link(&control_addr(), &world_id).await
}

/// Tauri command: save an invitation as a `.nodera` file and report where it went.
///
/// The app picks the folder (`~/Nodera`) rather than opening a file dialog: the point of the button
/// is to produce something to send in the next ten seconds, and a save dialog for a 200-byte text
/// file is three clicks of ceremony. The path is returned so the UI can show it, which is the part
/// people actually need.
#[tauri::command]
fn save_share_file(name: String, uri: String) -> Result<String, String> {
    let safe: String = name
        .chars()
        .map(|c| if c.is_alphanumeric() || c == '-' || c == '_' { c } else { '-' })
        .collect();
    let file = share_dir().join(format!(
        "{}.nodera",
        if safe.trim_matches('-').is_empty() { "world".to_owned() } else { safe }
    ));
    api::network::write_share_file(&file, &uri)?;
    Ok(file.display().to_string())
}

/// Where saved invitations go. Overridable so a test — or a user with an opinion — can move it.
pub(crate) fn share_dir() -> std::path::PathBuf {
    std::env::var("NODERA_SHARE_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|_| {
            std::path::PathBuf::from(
                std::env::var("HOME")
                    .or_else(|_| std::env::var("USERPROFILE"))
                    .unwrap_or_else(|_| ".".to_owned()),
            )
            .join("Nodera")
        })
}

/// Tauri command: read an invitation somebody sent as a file, and report the world it names.
#[tauri::command]
fn open_share_file(path: String) -> Result<String, String> {
    let uri = api::network::read_share_file(std::path::Path::new(&path))?;
    api::network::world_id_of(&uri)
        .map(|_| uri)
        .ok_or_else(|| "that invitation does not name a world".to_owned())
}

/// Tauri command: the world id inside a pasted link, or an error the UI can show immediately.
#[tauri::command]
fn parse_share_link(uri: String) -> Result<String, String> {
    api::network::world_id_of(&uri).ok_or_else(|| "that is not a Nodera invitation".to_owned())
}

/// Tauri command: this device's own peer identity and its last tracker exchange.
///
/// Mobile only in practice — on the desktop the Java worker is the peer and this would be a second
/// node on one machine — but the command exists on both so the same screen can be developed and
/// tested on a laptop.
#[tauri::command]
async fn peer_status(
    settings: tauri::State<'_, Arc<settings::SettingsHandle>>,
) -> Result<peer::PeerStatus, String> {
    let trackers = settings.snapshot().network.default_trackers;
    tauri::async_runtime::spawn_blocking(move || {
        let identity = peer::identity::PeerIdentity::load_or_create()?;
        // The SAME round the peer loop runs — announce, then ask the trackers back. Announcing
        // only would leave "peers seen" at zero on a screen the loop later fills in, so the number
        // would depend on which code path last wrote it. One round, one meaning.
        Ok(peer::announce_round(&identity, &trackers))
    })
    .await
    .map_err(|e| format!("the peer task failed: {e}"))?
}

/// Tauri command: the round trip that proves this device is on the network.
///
/// Announces, then queries the tracker back and looks for **this device's own entry**. An accepted
/// announce alone would only say the tracker took the bytes.
#[tauri::command]
async fn peer_self_test(
    settings: tauri::State<'_, Arc<settings::SettingsHandle>>,
) -> Result<peer::tracker::SelfTest, String> {
    let trackers = settings.snapshot().network.default_trackers;
    tauri::async_runtime::spawn_blocking(move || {
        let identity = peer::identity::PeerIdentity::load_or_create()?;
        Ok(peer::tracker::self_test(&identity, &trackers, Vec::new()))
    })
    .await
    .map_err(|e| format!("the peer task failed: {e}"))?
}

/// Tauri command: whether this build runs its own peer instead of supervising a worker.
#[tauri::command]
fn is_mobile_build() -> bool {
    peer::is_mobile()
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
async fn prove_world_admin(world_id: String) -> api::commands::AdminProof {
    api::commands::prove_world_admin(control_addr(), world_id).await
}

/// Tauri command: ask the worker to delete a world this peer owns, everywhere.
///
/// **Irreversible.** The worker refuses unless it holds the world's private key, and every peer
/// re-verifies the signed record before destroying its own copy — so this cannot be used against a
/// world this machine does not administer, whatever the page sends.
#[tauri::command]
async fn delete_world(world_id: String, reason: String) -> api::commands::DeleteOutcome {
    api::commands::delete_world(control_addr(), world_id, reason).await
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
    state: tauri::State<'_, Arc<LogBuffer>>,
) -> Result<Option<String>, String> {
    use tauri_plugin_dialog::DialogExt;

    // Snapshotted before the dialog opens: the buffer keeps moving while the user browses, and a
    // file whose contents depend on how long somebody took to choose a folder is not reproducible.
    let mut content = state.snapshot().join("\n");
    content.push('\n');
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
    let Some(chosen) = rx.await.map_err(|_| "the save dialog closed unexpectedly".to_owned())?
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
    settings: tauri::State<Arc<settings::SettingsHandle>>,
) -> api::storage::StorageInfo {
    api::storage::storage_info(&settings.snapshot().storage.peer_worlds_dir, &app_data_dir(&app))
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
fn setup_state(settings: tauri::State<Arc<settings::SettingsHandle>>) -> settings::SetupState {
    settings.snapshot().setup.clone()
}

/// Tauri command: record that the user finished the first-run setup.
#[tauri::command]
fn complete_setup(
    settings: tauri::State<Arc<settings::SettingsHandle>>,
    push: tauri::State<Arc<PushSignal>>,
    worlds_dir: String,
) -> Result<(), String> {
    let mut next = settings.snapshot();
    next.storage.peer_worlds_dir = worlds_dir;
    next.setup.completed = true;
    settings.save(next)?;
    push.request();
    Ok(())
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
fn picked_folder(app: tauri::AppHandle) -> api::storage::PickedFolder {
    api::storage::picked_folder(&app_data_dir(&app))
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
fn network_state(
    state: tauri::State<Arc<std::sync::Mutex<android::network::NetworkState>>>,
) -> android::network::NetworkState {
    state.lock().unwrap().clone()
}

/// Tauri command: why transfers are paused, in words, or empty when they are not.
#[tauri::command]
fn pause_reason(pause: tauri::State<Arc<PauseHandle>>) -> String {
    pause.reason().unwrap_or_default().to_owned()
}

/// Tauri command: why the stored settings could not be read, when they could not.
///
/// Empty in the normal case. Non-empty means the user is looking at defaults and their real
/// document is still on disk, untouched — which they have to be told, or they will conclude the app
/// forgot and re-enter everything on top of a file that was never the problem.
#[tauri::command]
fn settings_fault(settings: tauri::State<Arc<settings::SettingsHandle>>) -> String {
    settings.fault()
}

/// Tauri command: open the system's battery-optimisation screen.
#[tauri::command]
fn open_battery_settings() -> Result<(), String> {
    android::battery::open_settings()
}

/// Tauri command: open this vendor's page on dontkillmyapp.com.
#[tauri::command]
fn open_battery_help() -> Result<(), String> {
    android::battery::open_help()
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

    let dashboard = Arc::new(DashboardStore::new());
    let system_stats = Arc::new(SystemHandle::new());
    let worker_logs = Arc::new(LogBuffer::new());
    let user_settings = Arc::new(settings::SettingsHandle::load());
    let config_status = Arc::new(ConfigStatusHandle::new());
    let pause = Arc::new(PauseHandle::new());
    let push_signal = Arc::new(PushSignal::default());
    let restart_signal = Arc::new(RestartSignal::default());
    // Deliberately not probed here: on Android the Activity has not yet bound this process's
    // `Context`, so a read would panic (caught, but noisy) and be discarded ten seconds later
    // anyway. The sampler fills it in.
    let network_cache = Arc::new(std::sync::Mutex::new(android::network::pending()));

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
        .manage(Arc::clone(&dashboard))
        .manage(Arc::clone(&system_stats))
        .manage(Arc::clone(&worker_logs))
        .manage(Arc::clone(&user_settings))
        .manage(Arc::clone(&config_status))
        .manage(Arc::clone(&pause))
        .manage(Arc::clone(&push_signal))
        .manage(Arc::clone(&restart_signal))
        .manage(Arc::clone(&network_cache))
        .invoke_handler(tauri::generate_handler![
            api::commands::dashboard,
            api::commands::dashboard_world,
            prove_world_admin,
            lan_action,
            browse_network,
            join_world,
            leave_world,
            world_share_link,
            save_share_file,
            open_share_file,
            parse_share_link,
            about_build,
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
            get_unenforced_settings,
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
            // Android first: nothing below may touch the filesystem until the app knows which
            // directory it is allowed to write to.
            #[cfg(target_os = "android")]
            {
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
                    }
                    Err(e) => log::error!("no writable app directory: {e}"),
                }
            }

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
                let store_daemon = Arc::clone(&dashboard);
                let logs_daemon = Arc::clone(&worker_logs);
                let settings_daemon = Arc::clone(&user_settings);
                let restart_daemon = Arc::clone(&restart_signal);
                tauri::async_runtime::spawn(async move {
                    daemon::supervise(store_daemon, logs_daemon, settings_daemon, restart_daemon)
                        .await;
                });
            }

            // On Android the worker is started by `MainActivity` before the WebView loads — it runs
            // in this process, loaded from the APK's assets by `NoderaWorker.kt`. There is nothing
            // to supervise from here: a process that cannot outlive us cannot be restarted by us,
            // and the link below reports it exactly as it reports a desktop worker.
            #[cfg(not(desktop))]
            {
                log::info!("worker: started in-process by MainActivity; connecting to the control endpoint");
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

            // ...and open the live link to the worker. This is the authoritative liveness signal
            // and the only thing that writes the dashboard: the worker PUSHES state as it changes
            // (NODERA-WATCH), so the screen is current because the node said so, not because the
            // app guessed when to ask. Its offline→online edge is also what re-pushes configuration
            // to a worker that came back without it — the worker holds config in memory by design.
            let store_link = Arc::clone(&dashboard);
            let reconnect = Arc::clone(&push_signal.0);
            let link_app = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                api::link::run(control_addr(), store_link, link_app, reconnect).await;
            });

            // ...and the event stream beside it. Two connections on purpose: the link carries what
            // is TRUE of the node and this carries what HAPPENED to it. A prompt built on the first
            // would only fire when the app happened to be connected at the moment the player acted;
            // this one replays from a sequence number, so the order stops mattering.
            let events_app = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                api::events::run(control_addr(), events_app).await;
            });

            // Coalesce configuration pushes: one per settle window, however many saves arrive.
            let pusher = Arc::new(ConfigPusher {
                control_addr: control_addr(),
                settings: Arc::clone(&user_settings),
                pause: Arc::clone(&pause),
                status: Arc::clone(&config_status),
            });
            let push_loop = Arc::clone(&push_signal);
            tauri::async_runtime::spawn(async move {
                config::debounce_loop(pusher, push_loop).await;
            });

            // Connection rules, on every platform. Desktop reports the subject as unsupported and
            // the loop is inert there; on a phone this is what stops the node spending somebody's
            // data allowance on strangers' worlds. It shares `PauseHandle` with the battery rules
            // and clears independently of them, so walking onto Wi-Fi cannot resume a node that was
            // paused for battery.
            {
                let settings_network = Arc::clone(&user_settings);
                let pause_network = Arc::clone(&pause);
                let push_network = Arc::clone(&push_signal);
                let cache_network = Arc::clone(&network_cache);
                tauri::async_runtime::spawn(async move {
                    power::sample_network(
                        settings_network,
                        pause_network,
                        push_network,
                        cache_network,
                    )
                    .await;
                });
            }

            // Host power rules: desktop-only. The crate does not build for Android, and deciding
            // when a phone should stop working in the background is the phone's job — which is what
            // the battery-optimisation screen in Settings is about.
            #[cfg(desktop)]
            {
                let settings_power = Arc::clone(&user_settings);
                let store_power = Arc::clone(&dashboard);
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
                let paused = pause_state.toggle_manual();
                push.request();
                let _ = pause_item.set_text(if pause_state.manual_paused() {
                    "Resume seeding"
                } else {
                    "Pause seeding"
                });
                let _ = app.emit("nodera://pause", paused);
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;
    Ok(())
}
