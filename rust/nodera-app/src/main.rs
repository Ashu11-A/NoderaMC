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
mod metrics;

use std::sync::Arc;
use std::time::Duration;

use metrics::MetricsHandle;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{Emitter, Manager, WindowEvent};

/// Loopback address the control endpoint binds and the mod probes. Keep in sync with the mod's
/// `companion.controlEndpoint` default (127.0.0.1:25610).
const CONTROL_BIND: &str = "127.0.0.1:25610";

/// Tauri command: the React UI pulls the latest dashboard snapshot.
#[tauri::command]
fn get_metrics(state: tauri::State<Arc<MetricsHandle>>) -> metrics::Metrics {
    state.snapshot()
}

fn main() {
    let metrics = Arc::new(MetricsHandle::new());

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
        .invoke_handler(tauri::generate_handler![get_metrics])
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
            let metrics_ctl = Arc::clone(&metrics);
            tauri::async_runtime::spawn(async move {
                if let Err(e) = control::serve(CONTROL_BIND, metrics_ctl).await {
                    eprintln!("nodera-app: control endpoint failed on {CONTROL_BIND}: {e}");
                }
            });

            let metrics_daemon = Arc::clone(&metrics);
            tauri::async_runtime::spawn(async move {
                daemon::supervise(metrics_daemon).await;
            });

            // Push the dashboard snapshot to the frontend every second.
            let handle = app.handle().clone();
            let metrics_ui = Arc::clone(&metrics);
            tauri::async_runtime::spawn(async move {
                let mut tick = tokio::time::interval(Duration::from_secs(1));
                loop {
                    tick.tick().await;
                    let _ = handle.emit("nodera://metrics", metrics_ui.snapshot());
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
