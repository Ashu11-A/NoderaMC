//! Task 32 (Option B): supervise the bundled **headless Java Nodera peer** — the always-on node that
//! registers with rendezvous, announces cached/hosted worlds to the tracker, seeds the distribution
//! data plane, and validates assigned regions, all without Minecraft. This is what keeps a hosted
//! world available after the host closes their game (the request-#3 fix) and turns every install into
//! a persistent network node.
//!
//! The supervisor spawns `java -jar nodera-headless.jar`, restarts it with backoff if it dies, and
//! keeps the shared [`MetricsHandle`] daemon-up flag in sync. The peer streams its telemetry back
//! (chunks/bytes/peers/worlds) over its own status channel; wiring that pump is the live lane.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tokio::process::Command;

use crate::metrics::MetricsHandle;

/// Locate the bundled headless-peer jar next to the app resources.
fn headless_jar() -> PathBuf {
    // Resolved from the Tauri resource dir at runtime; this default aids `cargo tauri dev`.
    std::env::var("NODERA_HEADLESS_JAR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("resources/nodera-headless.jar"))
}

/// Locate a Java 21 runtime to run the headless peer (bundled runtime preferred; PATH fallback).
fn java_bin() -> String {
    std::env::var("NODERA_JAVA").unwrap_or_else(|_| "java".to_string())
}

/// Run the supervisor loop until the process exits. Restarts the peer with a capped backoff.
pub async fn supervise(metrics: Arc<MetricsHandle>) {
    let jar = headless_jar();
    let java = java_bin();
    let mut backoff = Duration::from_secs(1);
    let max_backoff = Duration::from_secs(30);

    loop {
        metrics.set_daemon_up(false);
        let spawn = Command::new(&java)
            .arg("-jar")
            .arg(&jar)
            .arg("--headless-peer")
            .kill_on_drop(true)
            .spawn();

        match spawn {
            Ok(mut child) => {
                metrics.set_daemon_up(true);
                backoff = Duration::from_secs(1); // healthy start resets backoff
                let status = child.wait().await;
                eprintln!("nodera-app: headless peer exited: {status:?}");
            }
            Err(e) => {
                eprintln!("nodera-app: failed to start headless peer ({java} -jar {jar:?}): {e}");
            }
        }

        metrics.set_daemon_up(false);
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(max_backoff);
    }
}
