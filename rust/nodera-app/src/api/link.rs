//! The live connection to the headless Java peer.
//!
//! # Push first, poll only as a fallback
//!
//! The worker's control endpoint answers one request per connection — except for
//! `NODERA-WATCH`, which it *holds open and writes into*. This module takes that stream, so a
//! change on the node reaches the screen because the node said so, not because the app happened to
//! ask at the right moment.
//!
//! Polling is kept, and only for the case it is actually right for: a worker too old to know the
//! verb. It answers `NODERA-ERR unknown verb` on the first line, and the link drops to a poll loop
//! rather than sitting on a stream that will never speak. Everything downstream is told which of
//! the two it is getting, because "current to the millisecond" and "current to within a second"
//! are different promises.
//!
//! # A dropped link is an event, not a silence
//!
//! Every transition — connected, first snapshot, stream ended, unreachable — is written to the
//! store and emitted. A dashboard that goes quiet must be able to say whether the node went quiet
//! or the link did, and that is only possible if the link reports itself.

use std::sync::Arc;
use std::time::Duration;

use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;
use tokio::time::timeout;

use crate::api::store::DashboardStore;
use crate::control::PROTOCOL_VERSION;
use crate::metrics::Metrics;

/// The event every page listens on. One channel, one shape, one source of truth.
pub const DASHBOARD_EVENT: &str = "nodera://dashboard";

/// Raised when this node's tracker reachability **changes**.
///
/// # Why a second event when the dashboard already carries this
///
/// The dashboard says what is *true*; this says what just *changed*. A page that only has the
/// former has to diff two snapshots to notice a transition, and every page that cares would have to
/// do it separately and agree — which is how one screen ends up celebrating a connection another
/// has not noticed. It is the same split the app already makes between the dashboard and
/// `nodera://event`.
///
/// It is also the cheap answer for anything **not** built on the dashboard: the settings screens
/// and the peer self-test read tracker state through their own commands, and had no way to know a
/// tracker had come back short of polling for it.
pub const DISCOVERY_EVENT: &str = "nodera://discovery";

/// How often the worker may push. Matches `ControlServer.DEFAULT_WATCH_INTERVAL_MILLIS`.
const WATCH_INTERVAL_MILLIS: u64 = 250;

/// How long to wait for the worker to accept the watch connection.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(2);

/// How long a stream may be silent before it is treated as dead.
///
/// The worker re-sends the current state every 10 s precisely so this can exist: without a
/// keepalive, a half-open TCP connection is indistinguishable from a quiet node for minutes, and
/// the dashboard would keep claiming "live" over numbers nobody is still sending.
const SILENCE_TIMEOUT: Duration = Duration::from_secs(25);

/// Backoff bounds for re-establishing the link.
const MIN_BACKOFF: Duration = Duration::from_millis(500);
const MAX_BACKOFF: Duration = Duration::from_secs(10);

/// Poll cadence when the worker cannot stream.
const POLL_INTERVAL: Duration = Duration::from_secs(1);

/// Where an accepted snapshot goes.
///
/// A trait object rather than an `AppHandle` so the pump below can be driven by a test with a real
/// socket on one end and a recorder on the other. The link's behaviour — what it does with an error
/// line, a malformed payload, a silent socket — is the part worth testing, and none of it should
/// need a window to exist.
pub trait Sink: Send + Sync {
    fn publish(&self, dashboard: crate::api::model::Dashboard);
}

/// What changed about this node's ability to be found.
///
/// `connected` is the question every screen actually asks — "can anyone find me" — and it is
/// deliberately *not* "all trackers are up": one answering tracker is enough to be discoverable,
/// and a node with three configured and one alive is working, not degraded.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct DiscoveryChange {
    /// At least one tracker answered.
    pub connected: bool,
    pub reachable: usize,
    pub total: usize,
    /// The endpoints that answered, `host:port`, so a screen can name them rather than count them.
    pub endpoints: Vec<String>,
}

impl DiscoveryChange {
    fn of(dashboard: &crate::api::model::Dashboard) -> Self {
        let trackers = &dashboard.discovery.trackers;
        let endpoints: Vec<String> = trackers
            .iter()
            .filter(|t| t.reachable)
            .map(|t| format!("{}:{}", t.host, t.port))
            .collect();
        Self {
            connected: !endpoints.is_empty(),
            reachable: endpoints.len(),
            total: trackers.len(),
            endpoints,
        }
    }
}

/// The production sink: the Tauri events every page listens on.
pub struct EventSink {
    app: AppHandle,
    /// The last discovery state reported, so [`DISCOVERY_EVENT`] is edge-triggered.
    ///
    /// Held here rather than in the pump because a snapshot reaches the UI by two routes — the
    /// stream and the polling fallback — and an edge detected in only one of them would go missing
    /// exactly when the link is degraded, which is when it matters most.
    last_discovery: std::sync::Mutex<Option<DiscoveryChange>>,
    /// Whether the last emit failed, so the transition is logged once rather than on every push.
    emit_failing: std::sync::atomic::AtomicBool,
}

impl EventSink {
    pub fn new(app: AppHandle) -> Self {
        Self {
            app,
            last_discovery: std::sync::Mutex::new(None),
            emit_failing: std::sync::atomic::AtomicBool::new(false),
        }
    }

    /// Emit, and report the *transition* between working and not.
    ///
    /// This was `let _ = emit(...)`, justified by "no window is listening is normal". That is true
    /// on a desktop minimised to the tray and false everywhere else — and it meant a link that was
    /// receiving state perfectly while **nothing reached the screen** looked, from the logs, exactly
    /// like a link that was working. Discarding the one signal that distinguishes them is how a
    /// dashboard sits on `—` with a healthy node behind it.
    fn emit<P: serde::Serialize + Clone>(&self, event: &str, payload: P) {
        use std::sync::atomic::Ordering;
        match self.app.emit(event, payload) {
            Ok(()) => {
                if self.emit_failing.swap(false, Ordering::Relaxed) {
                    log::info!("ui: events are reaching the interface again");
                }
            }
            Err(e) => {
                if !self.emit_failing.swap(true, Ordering::Relaxed) {
                    log::warn!("ui: {event} could not be delivered to the interface: {e}");
                }
            }
        }
    }
}

impl Sink for EventSink {
    fn publish(&self, dashboard: crate::api::model::Dashboard) {
        let discovery = DiscoveryChange::of(&dashboard);
        // Compared and stored before the emit, so a listener that reacts by asking for state
        // cannot race a second identical edge.
        let changed = {
            let mut last = self.last_discovery.lock().unwrap();
            if last.as_ref() == Some(&discovery) {
                false
            } else {
                *last = Some(discovery.clone());
                true
            }
        };
        if changed {
            log::info!(
                "discovery: {} of {} tracker(s) answering{}",
                discovery.reachable,
                discovery.total,
                if discovery.endpoints.is_empty() {
                    String::new()
                } else {
                    format!(" ({})", discovery.endpoints.join(", "))
                }
            );
            self.emit(DISCOVERY_EVENT, discovery);
        }
        self.emit(DASHBOARD_EVENT, dashboard);
    }
}

/// Run the link until the app exits.
///
/// `on_reconnect` is notified on every offline→online edge, which is what re-pushes configuration
/// to a worker that came back without it (the worker holds configuration in memory by design).
pub async fn run(
    control_addr: String,
    store: Arc<DashboardStore>,
    app: AppHandle,
    on_reconnect: Arc<tokio::sync::Notify>,
) {
    let sink: Arc<dyn Sink> = Arc::new(EventSink::new(app));
    pump(control_addr, store, sink, on_reconnect).await;
}

/// The link loop, independent of Tauri.
pub async fn pump(
    control_addr: String,
    store: Arc<DashboardStore>,
    sink: Arc<dyn Sink>,
    on_reconnect: Arc<tokio::sync::Notify>,
) {
    let mut backoff = MIN_BACKOFF;
    // Said once, at info: which endpoint this link is for. On a phone the worker and the app are
    // the same process and there is no terminal to read, so "the app says offline" was previously
    // impossible to tell apart from "the app is not even trying".
    log::info!("link: watching the worker on {control_addr}");
    loop {
        match stream_once(&control_addr, &store, &sink, &on_reconnect).await {
            Outcome::CannotStream(reason) => {
                // Not a failure: an older worker. Poll it until it goes away, then re-probe for the
                // stream, so upgrading the worker upgrades the link without restarting the app.
                sink.publish(store.mark_offline(reason));
                poll_until_gone(&control_addr, &store, &sink, &on_reconnect).await;
                backoff = MIN_BACKOFF;
            }
            Outcome::Ended(reason) => {
                log::info!("link: stream ended: {reason}");
                sink.publish(store.mark_offline(reason));
                backoff = MIN_BACKOFF;
            }
            Outcome::Unreachable(reason) => {
                log::warn!("link: {control_addr} unreachable: {reason}");
                sink.publish(store.mark_offline(reason));
                backoff = (backoff * 2).min(MAX_BACKOFF);
            }
        }
        tokio::time::sleep(backoff).await;
        sink.publish(store.mark_reconnecting("re-establishing the link to the worker"));
    }
}

enum Outcome {
    /// The worker does not know `NODERA-WATCH`.
    CannotStream(String),
    /// The stream was established and then finished.
    Ended(String),
    /// The worker could not be reached at all.
    Unreachable(String),
}

/// Open one watch stream and pump it into the store until it ends.
async fn stream_once(
    control_addr: &str,
    store: &Arc<DashboardStore>,
    sink: &Arc<dyn Sink>,
    on_reconnect: &Arc<tokio::sync::Notify>,
) -> Outcome {
    let stream = match timeout(CONNECT_TIMEOUT, TcpStream::connect(control_addr)).await {
        Err(_) => {
            return Outcome::Unreachable(format!(
                "the worker did not accept a connection on {control_addr} in time"
            ))
        }
        Ok(Err(e)) => {
            return Outcome::Unreachable(format!("the worker is unreachable on {control_addr}: {e}"))
        }
        Ok(Ok(stream)) => stream,
    };
    let (read, mut write) = stream.into_split();
    let request = format!("NODERA-WATCH {PROTOCOL_VERSION} {WATCH_INTERVAL_MILLIS}\n");
    if let Err(e) = write.write_all(request.as_bytes()).await {
        return Outcome::Unreachable(format!("could not ask the worker to stream: {e}"));
    }
    if let Err(e) = write.flush().await {
        return Outcome::Unreachable(format!("could not ask the worker to stream: {e}"));
    }

    let mut lines = BufReader::new(read).lines();
    let mut first = true;
    loop {
        let next = match timeout(SILENCE_TIMEOUT, lines.next_line()).await {
            Err(_) => {
                return Outcome::Ended(
                    "the worker stopped sending — no state and no keepalive".to_owned(),
                )
            }
            Ok(Err(e)) => return Outcome::Ended(format!("the stream failed: {e}")),
            Ok(Ok(None)) => return Outcome::Ended("the worker closed the stream".to_owned()),
            Ok(Ok(Some(line))) => line,
        };
        if let Some(reason) = next.strip_prefix("NODERA-ERR") {
            let reason = reason.trim();
            if first {
                return Outcome::CannotStream(format!(
                    "this worker cannot stream state ({}), falling back to polling",
                    if reason.is_empty() { "no reason given" } else { reason }
                ));
            }
            return Outcome::Ended(format!("the worker ended the stream: {reason}"));
        }
        match serde_json::from_str::<Metrics>(&next) {
            Ok(metrics) => {
                let dashboard = store.accept(&metrics, "stream");
                if first {
                    log::info!("link: receiving node state from the worker");
                    // Offline→online edge. The worker keeps configuration in memory, so anything
                    // that made it forget presents to us as exactly this.
                    on_reconnect.notify_one();
                    first = false;
                }
                sink.publish(dashboard);
            }
            Err(e) => {
                // One unparsable line is not a dead link — but it must be visible, because a silent
                // parse failure is exactly how a dashboard freezes while claiming to be live. It
                // was visible only in the UI's wording before, which on a phone meant "Offline"
                // with no way to find out why.
                log::warn!(
                    "link: unreadable state ({e}); first 120 bytes: {}",
                    next.chars().take(120).collect::<String>()
                );
                sink.publish(
                    store.mark_offline(format!("the worker sent state this app cannot read: {e}")),
                );
            }
        }
    }
}

/// Poll a worker that cannot stream, until it stops answering.
///
/// Returning on the first failure is deliberate: the caller then re-tries the *stream*, so a worker
/// that is restarted into a newer build is picked up as a streaming one without an app restart.
async fn poll_until_gone(
    control_addr: &str,
    store: &Arc<DashboardStore>,
    sink: &Arc<dyn Sink>,
    on_reconnect: &Arc<tokio::sync::Notify>,
) {
    let mut first = true;
    let mut tick = tokio::time::interval(POLL_INTERVAL);
    loop {
        tick.tick().await;
        match crate::control::fetch_state(control_addr).await {
            Some(metrics) => {
                let dashboard = store.accept(&metrics, "poll");
                if first {
                    on_reconnect.notify_one();
                    first = false;
                }
                sink.publish(dashboard);
            }
            None => {
                sink.publish(store.mark_offline("the worker stopped answering".to_owned()));
                return;
            }
        }
    }
}

#[cfg(test)]
mod discovery_tests {
    use super::*;
    use crate::api::model::{Dashboard, Endpoint};

    fn dashboard_with(trackers: Vec<(&str, u16, bool)>) -> Dashboard {
        let mut dashboard = Dashboard::default();
        dashboard.discovery.trackers = trackers
            .into_iter()
            .map(|(host, port, reachable)| Endpoint {
                host: host.to_owned(),
                port,
                reachable,
                ..Endpoint::default()
            })
            .collect();
        dashboard
    }

    #[test]
    fn one_answering_tracker_is_connected_however_many_are_configured() {
        let change = DiscoveryChange::of(&dashboard_with(vec![
            ("a", 25600, false),
            ("b", 25600, true),
            ("c", 25600, false),
        ]));
        // A node with three configured and one alive is discoverable, not degraded.
        assert!(change.connected);
        assert_eq!(change.reachable, 1);
        assert_eq!(change.total, 3);
        assert_eq!(change.endpoints, vec!["b:25600"]);
    }

    #[test]
    fn no_answering_tracker_is_not_connected() {
        let change = DiscoveryChange::of(&dashboard_with(vec![("a", 25600, false)]));
        assert!(!change.connected);
        assert_eq!(change.reachable, 0);
        assert_eq!(change.total, 1);
    }

    /// A node with nothing configured is not "connected", and must not be confused with one whose
    /// trackers are all down — the fix for each is different.
    #[test]
    fn no_configured_tracker_reports_zero_of_zero() {
        let change = DiscoveryChange::of(&dashboard_with(vec![]));
        assert!(!change.connected);
        assert_eq!((change.reachable, change.total), (0, 0));
    }

    /// The whole point of the event: it fires on a *transition*, not on every snapshot. The worker
    /// pushes up to four times a second, and a listener that refetched on each one would turn a
    /// status line into a load generator.
    #[test]
    fn the_change_is_only_a_change_when_something_moved() {
        let down = DiscoveryChange::of(&dashboard_with(vec![("a", 25600, false)]));
        let same = DiscoveryChange::of(&dashboard_with(vec![("a", 25600, false)]));
        let up = DiscoveryChange::of(&dashboard_with(vec![("a", 25600, true)]));
        assert_eq!(down, same);
        assert_ne!(down, up);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::model::{Dashboard, LinkStatus};
    use std::sync::Mutex;
    use tokio::net::TcpListener;

    /// Records what the link published, so a test can assert on the sequence rather than on the
    /// final state — the transitions are the behaviour here.
    #[derive(Default)]
    struct Recorder(Mutex<Vec<Dashboard>>);

    impl Sink for Recorder {
        fn publish(&self, dashboard: Dashboard) {
            self.0.lock().unwrap().push(dashboard);
        }
    }

    impl Recorder {
        fn seen(&self) -> Vec<Dashboard> {
            self.0.lock().unwrap().clone()
        }
    }

    /// A worker that writes `lines` on the watch stream, then behaves as `then` says.
    enum Then {
        Close,
        HoldOpen,
    }

    async fn fake_worker(lines: Vec<String>, then: Then) -> String {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            // Consume the request line.
            let mut buf = [0u8; 256];
            let _ = tokio::io::AsyncReadExt::read(&mut socket, &mut buf).await;
            for line in lines {
                if tokio::io::AsyncWriteExt::write_all(&mut socket, format!("{line}\n").as_bytes())
                    .await
                    .is_err()
                {
                    return;
                }
                let _ = tokio::io::AsyncWriteExt::flush(&mut socket).await;
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            match then {
                Then::Close => drop(socket),
                Then::HoldOpen => tokio::time::sleep(Duration::from_secs(60)).await,
            }
        });
        addr
    }

    fn state(sent: u64) -> String {
        format!(
            "{{\"node_id\":\"abc\",\"worker_version\":\"9.9\",\"total_sent_bytes\":{sent}}}"
        )
    }

    /// Drive the pump briefly and stop it — the loop is infinite by design.
    async fn run_briefly(addr: String, store: Arc<DashboardStore>, sink: Arc<Recorder>, ms: u64) {
        let publisher: Arc<dyn Sink> = sink;
        let notify = Arc::new(tokio::sync::Notify::new());
        let task = tokio::spawn(pump(addr, store, publisher, notify));
        tokio::time::sleep(Duration::from_millis(ms)).await;
        task.abort();
    }

    #[tokio::test]
    async fn a_pushed_snapshot_reaches_the_store_and_the_sink() {
        let addr = fake_worker(vec![state(10), state(20)], Then::HoldOpen).await;
        let store = Arc::new(DashboardStore::new());
        let sink = Arc::new(Recorder::default());
        run_briefly(addr, Arc::clone(&store), Arc::clone(&sink), 300).await;

        let dash = store.snapshot();
        assert!(dash.link.has_data, "the worker spoke, so the picture is real");
        assert_eq!(dash.link.status, LinkStatus::Live);
        assert_eq!(dash.link.transport, "stream");
        assert_eq!(dash.node.node_id, "abc");
        assert_eq!(dash.traffic.total_sent_bytes, 20);
        assert_eq!(dash.link.revision, 2, "one revision per accepted line");
        assert!(sink.seen().len() >= 2, "each accepted line is published");
    }

    #[tokio::test]
    async fn the_offline_to_online_edge_is_signalled_once_per_connection() {
        let addr = fake_worker(vec![state(1), state(2), state(3)], Then::HoldOpen).await;
        let store = Arc::new(DashboardStore::new());
        let sink: Arc<dyn Sink> = Arc::new(Recorder::default());
        let notify = Arc::new(tokio::sync::Notify::new());
        let waiter = Arc::clone(&notify);
        let task = tokio::spawn(pump(addr, store, sink, notify));

        // The configuration push depends on this edge; firing it per snapshot would re-push
        // settings several times a second.
        timeout(Duration::from_secs(2), waiter.notified())
            .await
            .expect("the first snapshot must signal the edge");
        assert!(
            timeout(Duration::from_millis(300), waiter.notified())
                .await
                .is_err(),
            "later snapshots on the same connection are not new edges"
        );
        task.abort();
    }

    #[tokio::test]
    async fn a_worker_that_cannot_stream_is_reported_rather_than_waited_on() {
        let addr = fake_worker(vec!["NODERA-ERR unknown verb".to_owned()], Then::HoldOpen).await;
        let store = Arc::new(DashboardStore::new());
        let sink = Arc::new(Recorder::default());
        run_briefly(addr, Arc::clone(&store), Arc::clone(&sink), 300).await;

        let dash = store.snapshot();
        assert_eq!(dash.link.status, LinkStatus::Offline);
        assert!(
            dash.link.last_error.contains("cannot stream"),
            "got {}",
            dash.link.last_error
        );
        // And critically: NOT has_data. An older worker's refusal is not a snapshot.
        assert!(!dash.link.has_data);
    }

    #[tokio::test]
    async fn a_line_this_app_cannot_read_is_surfaced_instead_of_freezing_the_screen() {
        let addr = fake_worker(
            vec![state(5), "{ this is not json".to_owned()],
            Then::HoldOpen,
        )
        .await;
        let store = Arc::new(DashboardStore::new());
        let sink = Arc::new(Recorder::default());
        run_briefly(addr, Arc::clone(&store), Arc::clone(&sink), 300).await;

        let dash = store.snapshot();
        // The regression this guards: a silently dropped parse leaves a live-looking dashboard
        // frozen on old numbers with nothing saying why.
        assert_eq!(dash.link.status, LinkStatus::Offline);
        assert!(dash.link.last_error.contains("cannot read"), "got {}", dash.link.last_error);
        assert!(dash.link.has_data, "the earlier snapshot is still the best we have");
        assert_eq!(dash.traffic.total_sent_bytes, 5);
    }

    #[tokio::test]
    async fn a_closed_stream_becomes_offline_with_a_reason() {
        let addr = fake_worker(vec![state(7)], Then::Close).await;
        let store = Arc::new(DashboardStore::new());
        let sink = Arc::new(Recorder::default());
        run_briefly(addr, Arc::clone(&store), Arc::clone(&sink), 300).await;

        let dash = store.snapshot();
        assert_eq!(dash.link.status, LinkStatus::Offline);
        assert!(!dash.link.last_error.is_empty(), "offline must always say why");
    }

    #[tokio::test]
    async fn an_absent_worker_is_offline_and_never_claims_data() {
        // Port 1 on loopback: reserved, nothing listens.
        let store = Arc::new(DashboardStore::new());
        let sink = Arc::new(Recorder::default());
        run_briefly("127.0.0.1:1".to_owned(), Arc::clone(&store), Arc::clone(&sink), 200).await;

        let dash = store.snapshot();
        assert!(!dash.link.has_data);
        assert_eq!(dash.link.status, LinkStatus::Offline);
        assert!(dash.link.last_error.contains("unreachable"), "got {}", dash.link.last_error);
    }

    /// The constants are a contract with `ControlServer`, not preferences: a silence timeout below
    /// the worker's keepalive would tear down healthy links on every quiet moment.
    #[test]
    fn the_silence_timeout_leaves_room_for_the_workers_keepalive() {
        // ControlServer.WATCH_KEEPALIVE_MILLIS = 10_000.
        assert!(
            SILENCE_TIMEOUT >= Duration::from_secs(20),
            "must tolerate at least two missed keepalives"
        );
        assert!(WATCH_INTERVAL_MILLIS >= 50, "the worker clamps below 50 ms anyway");
    }

    #[test]
    fn backoff_is_bounded_so_a_missing_worker_is_retried_forever_but_cheaply() {
        let mut backoff = MIN_BACKOFF;
        for _ in 0..20 {
            backoff = (backoff * 2).min(MAX_BACKOFF);
        }
        assert_eq!(backoff, MAX_BACKOFF);
    }
}
