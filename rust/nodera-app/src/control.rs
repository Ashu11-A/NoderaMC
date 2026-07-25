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
use tokio::sync::Notify;
use tokio::time::timeout;

use crate::metrics::MetricsHandle;

/// Control-protocol version. MUST match `ControlProtocol.PROTOCOL_VERSION` (Java) + the mod.
pub const PROTOCOL_VERSION: u32 = 2;

const PROBE: &str = "NODERA-PROBE";
const OK: &str = "NODERA-OK";
const STATE: &str = "NODERA-STATE";
const PIECES: &str = "NODERA-PIECES";
const ERR: &str = "NODERA-ERR";

/// How long to wait for the worker to accept a loopback connection.
///
/// Loopback either connects immediately or is not listening, so two seconds is already generous;
/// the value exists to bound the pathological case (a listening socket whose accept loop is
/// wedged), not to tolerate slow networks — there is no network here.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);

/// How long to wait for the reply line once the request is on the wire.
///
/// Longer than the connect budget because some verbs do real work before answering (`NODERA-SEED`
/// splits an archive). Without this the poll loop below would block **forever** on a worker that
/// accepted the connection and then stalled — the dashboard would freeze on stale numbers while
/// still claiming the node is up, which is worse than reporting it offline.
const READ_TIMEOUT: Duration = Duration::from_secs(5);

/// Send one control request line and return the worker's single reply line.
///
/// Every verb is one short request/response on its own connection, matching the mod's client — so
/// a slow or missing worker fails fast instead of holding a shared socket.
///
/// Returns `Err` with a human-readable reason for **every** failure mode, including the worker's own
/// `NODERA-ERR <reason>` text. That text used to be thrown away here, which meant a rejected verb
/// and an absent worker were indistinguishable to every caller — fine for the poll loop, which only
/// wants "did it answer", but fatal for configuration pushes, where the whole point is to show the
/// user *why* the worker declined. Callers that genuinely do not care map `Err` to `None`.
pub(crate) async fn request(control_addr: &str, line: String) -> Result<String, String> {
    let stream = timeout(CONNECT_TIMEOUT, TcpStream::connect(control_addr))
        .await
        .map_err(|_| format!("the worker did not accept a connection on {control_addr} in time"))?
        .map_err(|e| format!("the worker is unreachable on {control_addr}: {e}"))?;
    let (read, mut write) = stream.into_split();
    let exchange = async {
        write
            .write_all(format!("{line}\n").as_bytes())
            .await
            .map_err(|e| format!("could not send the request: {e}"))?;
        write
            .flush()
            .await
            .map_err(|e| format!("could not send the request: {e}"))?;
        let mut lines = BufReader::new(read).lines();
        lines
            .next_line()
            .await
            .map_err(|e| format!("could not read the reply: {e}"))?
            .ok_or_else(|| "the worker closed the connection without replying".to_owned())
    };
    let reply = timeout(READ_TIMEOUT, exchange)
        .await
        .map_err(|_| "the worker accepted the request but never replied".to_owned())??;
    if let Some(reason) = reply.strip_prefix(ERR) {
        let reason = reason.trim();
        return Err(if reason.is_empty() {
            "the worker refused the request without giving a reason".to_owned()
        } else {
            reason.to_owned()
        });
    }
    Ok(reply)
}

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

/// Fetch the worker's live metrics snapshot (`NODERA-STATE`), parsing the JSON reply into
/// [`crate::metrics::Metrics`]. Returns `None` if the worker is unreachable or replies with an error.
pub async fn fetch_state(control_addr: &str) -> Option<crate::metrics::Metrics> {
    // `.ok()?` on purpose: the poll loop's only question is "did the worker answer with state",
    // and every failure reason answers it the same way. The reason is not discarded upstream —
    // `request` still returns it for the callers that show it.
    let line = request(control_addr, format!("{STATE} {PROTOCOL_VERSION}"))
        .await
        .ok()?;
    serde_json::from_str::<crate::metrics::Metrics>(&line).ok()
}

/// Fetch one world's piece picture (`NODERA-PIECES`) for the Pieces tab.
///
/// Returns `None` when the worker is unreachable, predates the verb, or knows no manifest for the
/// world — all of which the UI renders as "no pieces yet" rather than as an error, because they are
/// indistinguishable to a player and none of them is actionable.
pub async fn fetch_pieces(control_addr: &str, world_id: &str) -> Option<crate::metrics::PieceMap> {
    let line = request(
        control_addr,
        format!("{PIECES} {PROTOCOL_VERSION} {world_id}"),
    )
    .await
    .ok()?;
    serde_json::from_str::<crate::metrics::PieceMap>(&line).ok()
}

/// Poll the worker's control endpoint on a cadence: pull the live metrics snapshot into the shared
/// handle so the dashboard + tray reflect real data + node liveness. A failed fetch marks the node
/// offline (keeping the rest of the snapshot as its last-known value).
///
/// `on_reconnect` is signalled on every offline→**online** edge. The worker holds configuration in
/// memory only, so anything that made it forget — a crash, a restart, an operator starting it by
/// hand in attach mode, or simply this app launching second — presents to us as exactly that edge.
/// Re-pushing here is therefore the *single* mechanism that keeps the worker's configuration equal
/// to the user's, and it needs no knowledge of which of those things happened.
pub async fn monitor(control_addr: String, metrics: Arc<MetricsHandle>, on_reconnect: Arc<Notify>) {
    let mut tick = tokio::time::interval(Duration::from_secs(2));
    let mut was_up = false;
    loop {
        tick.tick().await;
        match fetch_state(&control_addr).await {
            Some(mut snapshot) => {
                snapshot.daemon_up = true;
                metrics.set(snapshot);
                if !was_up {
                    on_reconnect.notify_one();
                }
                was_up = true;
            }
            None => {
                metrics.set_daemon_up(false);
                was_up = false;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    /// Serve exactly one connection with `reply`, or hold it open forever when `reply` is `None`.
    async fn fake_worker(reply: Option<&'static str>) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            match reply {
                Some(line) => {
                    use tokio::io::AsyncWriteExt as _;
                    let _ = socket.write_all(format!("{line}\n").as_bytes()).await;
                    let _ = socket.flush().await;
                    // Hold the socket so the client reads the line rather than an EOF race.
                    tokio::time::sleep(Duration::from_millis(200)).await;
                }
                // Never answer: the read timeout is the only thing that can end this exchange.
                None => tokio::time::sleep(Duration::from_secs(60)).await,
            }
        });
        addr
    }

    /// The regression this whole `Result` change exists for: a worker that *answered* with a reason
    /// must not look identical to a worker that is not there.
    #[tokio::test]
    async fn a_worker_error_surfaces_its_reason_instead_of_being_discarded() {
        let addr = fake_worker(Some("NODERA-ERR nope")).await;
        let err = request(&addr, "NODERA-CONFIG 2".to_owned())
            .await
            .expect_err("NODERA-ERR must be an error");
        assert_eq!(err, "nope");
    }

    #[test]
    fn a_reasonless_error_still_reads_as_a_refusal_not_as_success() {
        // Pinned as a unit expectation because a bare `NODERA-ERR` must never parse as an OK line.
        assert!("NODERA-ERR".strip_prefix(ERR).unwrap().trim().is_empty());
    }

    #[tokio::test]
    async fn an_ok_reply_is_returned_verbatim() {
        let addr = fake_worker(Some("{\"applied\":[]}")).await;
        assert_eq!(
            request(&addr, "NODERA-CONFIG 2".to_owned()).await.unwrap(),
            "{\"applied\":[]}"
        );
    }

    /// A worker that accepts the connection and then stalls used to hang the poll loop forever.
    #[tokio::test(start_paused = true)]
    async fn a_worker_that_never_replies_trips_the_read_timeout() {
        let addr = fake_worker(None).await;
        let err = request(&addr, "NODERA-STATE 2".to_owned())
            .await
            .expect_err("a silent worker must time out, not hang");
        assert!(err.contains("never replied"), "{err}");
    }

    #[tokio::test]
    async fn an_absent_worker_is_an_error_too_so_no_caller_can_mistake_it_for_a_refusal() {
        // Port 1 on loopback: reserved, nothing listens, connect fails immediately.
        let err = request("127.0.0.1:1", "NODERA-STATE 2".to_owned())
            .await
            .expect_err("no listener must be an error");
        assert!(err.contains("unreachable"), "{err}");
    }
}
