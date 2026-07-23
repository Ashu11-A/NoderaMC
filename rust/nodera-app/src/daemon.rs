//! Task 32 (Option B): supervise the bundled headless Nodera peer **worker** (`nodera-headless`, a
//! Java process built by the `application` plugin's installDist) — the always-on node that keeps a
//! player on the network with Minecraft closed and owns the control endpoint the mod probes.
//!
//! The supervisor launches the worker's `bin/nodera-headless` launcher, restarts it with backoff if
//! it dies, and keeps the shared [`MetricsHandle`] daemon-up flag in sync (the authoritative liveness
//! signal is [`crate::control::monitor`] probing the worker's control port).
//!
//! **Attach mode** (`NODERA_APP_ATTACH=1`): do NOT spawn a worker — one is already running (e.g.
//! started by `scripts/dev.sh`). The app only monitors + shows the UI. This prevents two workers
//! fighting over the control port in development.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;

use crate::logs::LogBuffer;
use crate::metrics::MetricsHandle;

/// True when the app should attach to an already-running worker instead of supervising its own.
pub fn attach_mode() -> bool {
    std::env::var("NODERA_APP_ATTACH")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false)
}

/// Locate the worker launcher: `NODERA_WORKER_BIN`, else the bundled installDist under resources.
fn worker_launcher() -> PathBuf {
    std::env::var("NODERA_WORKER_BIN")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("resources/nodera-headless/bin/nodera-headless"))
}

/// Run the supervisor loop until the process exits. Restarts the worker with a capped backoff.
/// No-op (returns) in attach mode.
pub async fn supervise(metrics: Arc<MetricsHandle>, logs: Arc<LogBuffer>) {
    if attach_mode() {
        eprintln!("nodera-app: attach mode — not supervising a worker (one runs externally)");
        // The worker's output goes to its own log file in attach mode — follow it instead.
        crate::logs::tail_attach_log(logs).await;
        return;
    }

    let launcher = worker_launcher();
    let mut backoff = Duration::from_secs(1);
    let max_backoff = Duration::from_secs(30);

    loop {
        let spawn = Command::new(&launcher)
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
                    tauri::async_runtime::spawn(async move {
                        let mut lines = BufReader::new(out).lines();
                        while let Ok(Some(line)) = lines.next_line().await {
                            sink.push(line);
                        }
                    });
                }
                if let Some(err) = child.stderr.take() {
                    let sink = Arc::clone(&logs);
                    tauri::async_runtime::spawn(async move {
                        let mut lines = BufReader::new(err).lines();
                        while let Ok(Some(line)) = lines.next_line().await {
                            sink.push(line);
                        }
                    });
                }
                let status = child.wait().await;
                logs.push(format!("nodera-app: peer worker exited: {status:?}"));
                eprintln!("nodera-app: peer worker exited: {status:?}");
            }
            Err(e) => {
                logs.push(format!("nodera-app: failed to start peer worker: {e}"));
                eprintln!("nodera-app: failed to start peer worker ({launcher:?}): {e}");
            }
        }
        metrics.set_daemon_up(false);
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(max_backoff);
    }
}
