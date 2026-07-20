//! Task 32: the companion app's client to the peer worker's control endpoint.
//!
//! The **worker** (`nodera-headless`, a Java process) owns the loopback control endpoint that the
//! Minecraft mod probes — it is the single source of truth for "is the node up". This app does NOT
//! bind that port; it *connects* to it to (a) confirm the worker is alive for the tray/dashboard and
//! (b) later pull state. Line-oriented ASCII, matching `dev.nodera.peer.control.ControlProtocol`:
//!
//! * app → worker:  `NODERA-PROBE <protocolVersion>\n`
//! * worker → app:  `NODERA-OK <protocolVersion> <workerVersion>\n`

use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;

use crate::metrics::MetricsHandle;

/// Control-protocol version. MUST match `ControlProtocol.PROTOCOL_VERSION` (Java) + the mod.
pub const PROTOCOL_VERSION: u32 = 1;

const PROBE: &str = "NODERA-PROBE";
const OK: &str = "NODERA-OK";

/// Probe the worker once; returns its reported version on success.
pub async fn probe(control_addr: &str) -> Option<String> {
    let stream = TcpStream::connect(control_addr).await.ok()?;
    let (read, mut write) = stream.into_split();
    write
        .write_all(format!("{PROBE} {PROTOCOL_VERSION}\n").as_bytes())
        .await
        .ok()?;
    write.flush().await.ok()?;
    let mut lines = BufReader::new(read).lines();
    let line = lines.next_line().await.ok()??;
    let mut parts = line.split_whitespace();
    if parts.next()? != OK {
        return None;
    }
    let _proto = parts.next();
    Some(parts.next().unwrap_or("unknown").to_string())
}

/// Poll the worker's control endpoint on a cadence, keeping `metrics.daemon_up` in sync so the tray
/// icon + dashboard reflect the real node liveness.
pub async fn monitor(control_addr: String, metrics: Arc<MetricsHandle>) {
    let mut tick = tokio::time::interval(Duration::from_secs(2));
    loop {
        tick.tick().await;
        let up = probe(&control_addr).await.is_some();
        metrics.set_daemon_up(up);
    }
}
