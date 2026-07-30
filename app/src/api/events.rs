//! The worker's event stream: things that *happened*, as opposed to things that are true.
//!
//! # Why this is a second connection
//!
//! [`crate::api::link`] carries the node's state, and a dashboard built on it is correct: a tile
//! shows a value, the value changes, the tile changes. A **prompt** is a different thing. "A world
//! was just opened to LAN" is a moment, and a UI that learned about moments by comparing successive
//! snapshots would have to keep its own previous copy, define what counts as a change, and have
//! been connected at the time. That last one is not a hypothetical: the player opens the world and
//! looks at the app seconds apart, in whichever order they feel like.
//!
//! So the worker says it outright, and this module carries what it said.
//!
//! # Replay is the feature
//!
//! Every event has a sequence number, and the connection asks for everything after the last one it
//! saw. A freshly-started app asks from `0` and receives the recent history — which is what makes
//! the LAN prompt appear whether the app or the world came first. Reconnecting after a dropped
//! socket resumes rather than restarts, so a blip does not swallow a prompt.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::time::timeout;

use crate::control::PROTOCOL_VERSION;

/// The Tauri event every page can listen on.
pub const WORKER_EVENT: &str = "nodera://event";

/// The worker sends a keepalive every 15 s; treat a longer silence as a dead socket.
const SILENCE_TIMEOUT: Duration = Duration::from_secs(40);

const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const MIN_BACKOFF: Duration = Duration::from_millis(500);
const MAX_BACKOFF: Duration = Duration::from_secs(10);

/// One thing that happened on the node.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct WorkerEvent {
    /// Position in the node's event sequence. Monotonic; used to resume without gaps or repeats.
    #[serde(default)]
    pub seq: u64,
    /// Dotted name — `lan.opened`, `lan.shared`, `keepalive`.
    #[serde(default)]
    pub event: String,
    /// Epoch millis the worker observed it.
    #[serde(default)]
    pub at: u64,
    /// Flat detail. Strings on the wire, because the control channel has no JSON library behind it.
    #[serde(default)]
    pub attributes: std::collections::HashMap<String, String>,
}

impl WorkerEvent {
    /// Whether this line is only proof the socket is alive.
    ///
    /// Kept out of the UI entirely: a keepalive is evidence for *this* module and noise for anyone
    /// else, and a page that had to filter it would eventually forget to.
    pub fn is_keepalive(&self) -> bool {
        self.event == "keepalive"
    }

    /// @return the attribute, or an empty string — an absent attribute is not an error to a page.
    // Page-facing accessor: exercised by this module's tests, not called from the Rust side.
    #[allow(dead_code)]
    pub fn attribute(&self, key: &str) -> &str {
        self.attributes.get(key).map(String::as_str).unwrap_or("")
    }
}

/// Where an event goes. A trait so the pump can be tested against a real socket and a recorder.
pub trait EventSink: Send + Sync {
    fn publish(&self, event: WorkerEvent);
}

/// The production sink.
pub struct TauriEventSink(pub AppHandle);

impl EventSink for TauriEventSink {
    fn publish(&self, event: WorkerEvent) {
        let _ = self.0.emit(WORKER_EVENT, event);
    }
}

/// Run the event stream until the app exits.
pub async fn run(control_addr: String, app: AppHandle) {
    let sink: Arc<dyn EventSink> = Arc::new(TauriEventSink(app));
    pump(control_addr, sink).await;
}

/// The stream loop, independent of Tauri.
pub async fn pump(control_addr: String, sink: Arc<dyn EventSink>) {
    // Held across reconnects: resuming from the last sequence is what turns a dropped socket into a
    // pause rather than a hole. Starting from 0 each time would replay the whole buffer and reopen
    // prompts the user already answered.
    let cursor = Arc::new(AtomicU64::new(0));
    let mut backoff = MIN_BACKOFF;
    loop {
        if stream_once(&control_addr, &sink, &cursor).await {
            backoff = MIN_BACKOFF; // the stream worked; a clean end is not a reason to slow down
        } else {
            backoff = (backoff * 2).min(MAX_BACKOFF);
        }
        tokio::time::sleep(backoff).await;
    }
}

/// One connection. Returns whether it was established at all.
async fn stream_once(control_addr: &str, sink: &Arc<dyn EventSink>, cursor: &AtomicU64) -> bool {
    let stream = match timeout(CONNECT_TIMEOUT, TcpStream::connect(control_addr)).await {
        Ok(Ok(stream)) => stream,
        _ => return false,
    };
    let (read, mut write) = stream.into_split();
    let since = cursor.load(Ordering::Relaxed);
    let request = format!("NODERA-EVENTS {PROTOCOL_VERSION} {since}\n");
    if write.write_all(request.as_bytes()).await.is_err() || write.flush().await.is_err() {
        return false;
    }

    let mut lines = BufReader::new(read).lines();
    let mut established = false;
    loop {
        let next = match timeout(SILENCE_TIMEOUT, lines.next_line()).await {
            Ok(Ok(Some(line))) => line,
            // A silent socket, a read error, or a clean close all end this connection. The loop
            // above reconnects; the cursor means nothing is lost in between.
            _ => return established,
        };
        if next.starts_with("NODERA-ERR") {
            // A worker too old to announce events. Not a failure worth retrying quickly — the
            // dashboard's own state lane still works, and the UI falls back to reading it.
            return false;
        }
        let event: WorkerEvent = match serde_json::from_str(&next) {
            Ok(event) => event,
            Err(_) => continue, // one unreadable line is not a reason to drop the stream
        };
        established = true;
        // The cursor advances on keepalives too: they carry the worker's current sequence, so a
        // long-idle connection resumes from where the node actually is rather than from the last
        // interesting thing that happened.
        if event.seq > cursor.load(Ordering::Relaxed) {
            cursor.store(event.seq, Ordering::Relaxed);
        }
        if !event.is_keepalive() {
            sink.publish(event);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use tokio::net::TcpListener;

    #[derive(Default)]
    struct Recorder(Mutex<Vec<WorkerEvent>>);

    impl EventSink for Recorder {
        fn publish(&self, event: WorkerEvent) {
            self.0.lock().unwrap().push(event);
        }
    }

    impl Recorder {
        fn seen(&self) -> Vec<WorkerEvent> {
            self.0.lock().unwrap().clone()
        }
    }

    /// A worker that records the request line it was sent, then writes `lines`.
    async fn fake_worker(lines: Vec<String>) -> (String, Arc<Mutex<Vec<String>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = Arc::clone(&requests);
        tokio::spawn(async move {
            loop {
                let (mut socket, _) = match listener.accept().await {
                    Ok(pair) => pair,
                    Err(_) => return,
                };
                let mut buffer = [0u8; 256];
                let read = tokio::io::AsyncReadExt::read(&mut socket, &mut buffer)
                    .await
                    .unwrap_or(0);
                seen.lock()
                    .unwrap()
                    .push(String::from_utf8_lossy(&buffer[..read]).trim().to_owned());
                for line in &lines {
                    if socket
                        .write_all(format!("{line}\n").as_bytes())
                        .await
                        .is_err()
                    {
                        break;
                    }
                    let _ = socket.flush().await;
                }
                // Hold the connection so the reader sees the lines rather than an EOF race.
                tokio::time::sleep(Duration::from_millis(300)).await;
            }
        });
        (addr, requests)
    }

    fn lan_opened(seq: u64, port: &str) -> String {
        format!(
            r#"{{"seq":{seq},"event":"lan.opened","at":1,"attributes":{{"port":"{port}","world":"W"}}}}"#
        )
    }

    async fn run_briefly(addr: String, sink: Arc<Recorder>, ms: u64) {
        let publisher: Arc<dyn EventSink> = sink;
        let task = tokio::spawn(pump(addr, publisher));
        tokio::time::sleep(Duration::from_millis(ms)).await;
        task.abort();
    }

    #[tokio::test]
    async fn an_event_reaches_the_sink_with_its_attributes() {
        let (addr, _) = fake_worker(vec![lan_opened(1, "54321")]).await;
        let sink = Arc::new(Recorder::default());
        run_briefly(addr, Arc::clone(&sink), 250).await;

        let seen = sink.seen();
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].event, "lan.opened");
        // The prompt has to be renderable without a follow-up query, so the port and the name ride
        // the event itself.
        assert_eq!(seen[0].attribute("port"), "54321");
        assert_eq!(seen[0].attribute("world"), "W");
    }

    #[tokio::test]
    async fn a_keepalive_proves_the_socket_without_reaching_the_ui() {
        let (addr, _) = fake_worker(vec![
            r#"{"seq":4,"event":"keepalive","at":1,"attributes":{}}"#.to_owned(),
            lan_opened(5, "1"),
        ])
        .await;
        let sink = Arc::new(Recorder::default());
        run_briefly(addr, Arc::clone(&sink), 250).await;

        // A page that had to filter keepalives would eventually forget to.
        let seen = sink.seen();
        assert_eq!(seen.len(), 1);
        assert_eq!(seen[0].event, "lan.opened");
    }

    #[tokio::test]
    async fn a_reconnect_resumes_rather_than_replaying() {
        let (addr, requests) = fake_worker(vec![lan_opened(7, "1")]).await;
        let sink = Arc::new(Recorder::default());
        // Long enough for the first connection to end and a second to be made.
        run_briefly(addr, Arc::clone(&sink), 1400).await;

        let asked = requests.lock().unwrap().clone();
        assert!(asked.len() >= 2, "expected a reconnect, saw {asked:?}");
        assert!(
            asked[0].ends_with(" 0"),
            "a fresh app asks for the backlog: {}",
            asked[0]
        );
        // The whole point of the cursor: without it the second connection replays event 7 and the
        // UI reopens a prompt the user has already answered.
        assert!(
            asked[1].ends_with(" 7"),
            "a reconnect resumes: {}",
            asked[1]
        );
    }

    #[tokio::test]
    async fn an_unreadable_line_does_not_end_the_stream() {
        let (addr, _) = fake_worker(vec!["{ not json".to_owned(), lan_opened(2, "1")]).await;
        let sink = Arc::new(Recorder::default());
        run_briefly(addr, Arc::clone(&sink), 250).await;

        assert_eq!(
            sink.seen().len(),
            1,
            "the good line after a bad one must still arrive"
        );
    }

    #[tokio::test]
    async fn a_worker_that_cannot_announce_is_not_hammered() {
        let (addr, requests) = fake_worker(vec!["NODERA-ERR unknown verb".to_owned()]).await;
        let sink = Arc::new(Recorder::default());
        run_briefly(addr, Arc::clone(&sink), 900).await;

        assert!(sink.seen().is_empty());
        // Backoff doubles on a refusal, so an older worker costs a couple of connections rather
        // than a reconnect loop for as long as the app is open.
        assert!(
            requests.lock().unwrap().len() <= 3,
            "backoff should slow the retries"
        );
    }
}
