//! Task 32: the loopback-only control endpoint the Nodera mod probes at startup (the presence gate)
//! and, later, drives host/join through. Line-oriented ASCII handshake, matching the Java
//! `dev.nodera.mod.common.CompanionProtocol`:
//!
//! * mod → daemon: `NODERA-PROBE <protocolVersion>\n`
//! * daemon → mod: `NODERA-OK <protocolVersion> <daemonVersion>\n`
//!
//! It binds `127.0.0.1` only — never a routable interface — because it is a local trust boundary, not
//! a network service. Peers still verify everything the daemon serves on the *real* network (Task 0
//! rule 7): the control channel is a convenience, never an authority.

use std::sync::Arc;

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};

use crate::metrics::MetricsHandle;

/// Control-protocol version this daemon speaks. MUST match `CompanionProtocol.PROTOCOL_VERSION`.
pub const PROTOCOL_VERSION: u32 = 1;

const PROBE: &str = "NODERA-PROBE";
const OK: &str = "NODERA-OK";

/// The daemon's own build version, reported to the mod for user-facing "update the app" messages.
pub const DAEMON_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Run the control listener until the process exits. `metrics` lets future control verbs answer
/// state queries (dashboard mirror / in-game HUD) from the same source of truth.
pub async fn serve(bind: &str, metrics: Arc<MetricsHandle>) -> std::io::Result<()> {
    let listener = TcpListener::bind(bind).await?;
    loop {
        let (stream, _peer) = listener.accept().await?;
        let metrics = Arc::clone(&metrics);
        tokio::spawn(async move {
            if let Err(e) = handle(stream, metrics).await {
                eprintln!("nodera-app: control connection error: {e}");
            }
        });
    }
}

async fn handle(stream: TcpStream, _metrics: Arc<MetricsHandle>) -> std::io::Result<()> {
    let (read, mut write) = stream.into_split();
    let mut lines = BufReader::new(read).lines();
    if let Some(line) = lines.next_line().await? {
        let mut parts = line.split_whitespace();
        match parts.next() {
            Some(PROBE) => {
                // The mod's protocol version is parts.next(); we answer with ours regardless so the
                // mod-side gate classifies the skew (DAEMON_OUTDATED / MOD_OUTDATED).
                let reply = format!("{OK} {PROTOCOL_VERSION} {DAEMON_VERSION}\n");
                write.write_all(reply.as_bytes()).await?;
                write.flush().await?;
            }
            _ => {
                // Unknown verb: close quietly. Future verbs (HOST/JOIN/STATE) branch here.
            }
        }
    }
    Ok(())
}
