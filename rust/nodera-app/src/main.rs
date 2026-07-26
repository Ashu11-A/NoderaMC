//! Task 32: the Nodera companion app entrypoint (Tauri 2.x).
//!
//! Responsibilities:
//! * **System tray** presence (status icon + menu: Open dashboard / Pause seeding / Quit).
//! * **Window** that minimises to the tray instead of quitting (close = hide) so the node stays on.
//! * **Autostart** at login (all three OSes) via `tauri-plugin-autostart`.
//! * **Single instance** so only one daemon runs per machine.
//! * The **control endpoint** the mod probes (`control::serve`) and the **daemon supervisor** that
//!   runs the bundled headless Java peer (`daemon::supervise`, Option B).
//! * A **metrics pump** that emits the dashboard snapshot to the React frontend on a cadence.
//!
//! EXCLUDED from the `rust/` workspace gate — build with the Tauri toolchain (see Cargo.toml).

#![cfg_attr(all(not(debug_assertions), target_os = "windows"), windows_subsystem = "windows")]

mod config;
mod control;
mod daemon;
mod logs;
mod metrics;
mod power;
mod settings;
mod system;
mod telemetry;

use std::sync::Arc;
use std::time::Duration;

use config::{ConfigPusher, ConfigStatusHandle, PushSignal};
use daemon::RestartSignal;
use logs::LogBuffer;
use metrics::MetricsHandle;
use power::PauseHandle;
use system::SystemHandle;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{Emitter, Manager, WindowEvent};

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
fn multi_instance() -> bool {
    std::env::var("NODERA_APP_MULTI")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
}

/// Per-instance window title (`NODERA_APP_TITLE`), so two dev windows are tellable apart.
/// Empty/unset keeps the title from `tauri.conf.json`.
fn window_title() -> Option<String> {
    std::env::var("NODERA_APP_TITLE")
        .ok()
        .filter(|title| !title.trim().is_empty())
}

/// Tauri command: the React UI pulls the latest dashboard snapshot.
#[tauri::command]
fn get_metrics(state: tauri::State<Arc<MetricsHandle>>) -> metrics::Metrics {
    state.snapshot()
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
    use tauri_plugin_autostart::ManagerExt;
    let manager = app.autolaunch();
    let result = if auto_start {
        manager.enable()
    } else {
        manager.disable()
    };
    result.map_err(|e| format!("settings saved, but auto-start could not be applied: {e}"))
}

/// Tauri command: one world's piece grid, fetched on demand.
///
/// Deliberately pulled rather than pushed with the metrics snapshot: only the world the user has
/// selected is interesting, and a node seeding a dozen worlds should not be re-encoding every
/// bitmap once a second for a tab nobody is looking at.
#[tauri::command]
async fn get_piece_map(world_id: String) -> metrics::PieceMapView {
    let map = control::fetch_pieces(&control_addr(), &world_id).await;
    match map {
        Some(map) => metrics::PieceMapView::of(map),
        None => metrics::PieceMapView::default(),
    }
}

fn main() {
    let metrics = Arc::new(MetricsHandle::new());
    let system_stats = Arc::new(SystemHandle::new());
    let worker_logs = Arc::new(LogBuffer::new());
    let user_settings = Arc::new(settings::SettingsHandle::load());
    let config_status = Arc::new(ConfigStatusHandle::new());
    let pause = Arc::new(PauseHandle::new());
    let push_signal = Arc::new(PushSignal::default());
    let restart_signal = Arc::new(RestartSignal::default());

    let mut builder = tauri::Builder::default();
    if !multi_instance() {
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            // A second launch focuses the existing window rather than starting a second daemon.
            if let Some(win) = app.get_webview_window("main") {
                let _ = win.show();
                let _ = win.set_focus();
            }
        }));
    }

    builder
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec![]),
        ))
        .manage(Arc::clone(&metrics))
        .manage(Arc::clone(&system_stats))
        .manage(Arc::clone(&worker_logs))
        .manage(Arc::clone(&user_settings))
        .manage(Arc::clone(&config_status))
        .manage(Arc::clone(&pause))
        .manage(Arc::clone(&push_signal))
        .manage(Arc::clone(&restart_signal))
        .invoke_handler(tauri::generate_handler![
            get_metrics,
            get_system_stats,
            get_worker_logs,
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
            let metrics_daemon = Arc::clone(&metrics);
            let logs_daemon = Arc::clone(&worker_logs);
            let settings_daemon = Arc::clone(&user_settings);
            let restart_daemon = Arc::clone(&restart_signal);
            tauri::async_runtime::spawn(async move {
                daemon::supervise(metrics_daemon, logs_daemon, settings_daemon, restart_daemon)
                    .await;
            });

            // Sample machine + worker RAM/CPU for the resource tiles.
            let system_sampler = Arc::clone(&system_stats);
            tauri::async_runtime::spawn(async move {
                system::sample(system_sampler).await;
            });

            // ...and monitor the worker's control endpoint for liveness (the authoritative signal).
            // Its offline→online edge is also what re-pushes configuration to a worker that came
            // back without it — the worker holds config in memory only, by design.
            let metrics_ctl = Arc::clone(&metrics);
            let reconnect = Arc::clone(&push_signal.0);
            tauri::async_runtime::spawn(async move {
                control::monitor(control_addr(), metrics_ctl, reconnect).await;
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

            // Sample host power and pause transfers when the user's rules say to. Edge-triggered:
            // this must never become a heartbeat on the control socket.
            let settings_power = Arc::clone(&user_settings);
            let metrics_power = Arc::clone(&metrics);
            let pause_power = Arc::clone(&pause);
            let push_power = Arc::clone(&push_signal);
            tauri::async_runtime::spawn(async move {
                power::sample(settings_power, metrics_power, pause_power, push_power).await;
            });

            // Push the dashboard snapshots to the frontend every second.
            let handle = app.handle().clone();
            let metrics_ui = Arc::clone(&metrics);
            let system_ui = Arc::clone(&system_stats);
            tauri::async_runtime::spawn(async move {
                let mut tick = tokio::time::interval(Duration::from_secs(1));
                loop {
                    tick.tick().await;
                    let _ = handle.emit("nodera://metrics", metrics_ui.snapshot());
                    let _ = handle.emit("nodera://system", system_ui.snapshot());
                }
            });

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running the Nodera companion app");
}

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
