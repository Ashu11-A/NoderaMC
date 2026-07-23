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

mod control;
mod daemon;
mod logs;
mod metrics;
mod system;

use std::sync::Arc;
use std::time::Duration;

use logs::LogBuffer;
use metrics::MetricsHandle;
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

fn main() {
    let metrics = Arc::new(MetricsHandle::new());
    let system_stats = Arc::new(SystemHandle::new());
    let worker_logs = Arc::new(LogBuffer::new());

    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            // A second launch focuses the existing window rather than starting a second daemon.
            if let Some(win) = app.get_webview_window("main") {
                let _ = win.show();
                let _ = win.set_focus();
            }
        }))
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec![]),
        ))
        .manage(Arc::clone(&metrics))
        .manage(Arc::clone(&system_stats))
        .manage(Arc::clone(&worker_logs))
        .invoke_handler(tauri::generate_handler![
            get_metrics,
            get_system_stats,
            get_worker_logs
        ])
        .setup(move |app| {
            build_tray(app)?;

            // Close = hide to tray (keep the node alive), don't quit.
            if let Some(win) = app.get_webview_window("main") {
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
            tauri::async_runtime::spawn(async move {
                daemon::supervise(metrics_daemon, logs_daemon).await;
            });

            // Sample machine + worker RAM/CPU for the resource tiles.
            let system_sampler = Arc::clone(&system_stats);
            tauri::async_runtime::spawn(async move {
                system::sample(system_sampler).await;
            });

            // ...and monitor the worker's control endpoint for liveness (the authoritative signal).
            let metrics_ctl = Arc::clone(&metrics);
            tauri::async_runtime::spawn(async move {
                control::monitor(control_addr(), metrics_ctl).await;
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

fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Open dashboard", true, None::<&str>)?;
    let pause = MenuItem::with_id(app, "pause", "Pause seeding", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit Nodera", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open, &pause, &quit])?;

    TrayIconBuilder::new()
        .icon(app.default_window_icon().unwrap().clone())
        .tooltip("Nodera — connected")
        .menu(&menu)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "open" => {
                if let Some(win) = app.get_webview_window("main") {
                    let _ = win.show();
                    let _ = win.set_focus();
                }
            }
            "pause" => { /* toggles seeding on the supervised peer — live lane */ }
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;
    Ok(())
}
