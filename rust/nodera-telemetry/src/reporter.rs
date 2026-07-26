//! The service-side emitter, shared by `nodera-tracker` and `nodera-rendezvous`.
//!
//! **Off unless an operator configures an endpoint.** Someone who downloads and runs a tracker has
//! agreed to run a tracker; they have not agreed to report to the project. The absence of
//! `telemetry_endpoint` is the absence of consent, and it is the default in both services' shipped
//! configuration. The project's own deployments set it.
//!
//! **Counters over a window, never per-request rows.** A per-announce event would be a log of who
//! announced what and when — a record of peers and worlds by another name. A window event is one
//! row per interval containing totals, which answers "is this service healthy, and how much does it
//! carry" without describing anybody. The type system helps here: [`ServiceEvent`] can only hold
//! numbers and declared enum labels, so an address or a node id cannot be attached to one even by
//! accident.
//!
//! **A telemetry outage is invisible.** Sends are best-effort from a bounded queue on the service's
//! own task; nothing here can slow, block, or fail an announce, a query, a punch, or a relay.

use std::collections::BTreeMap;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::event::{BATCH_VERSION, CONSENT_GRANTED};

/// Bounded queue: a service that cannot reach the collector keeps this many windows and then drops
/// the oldest. Small on purpose — a service's window events are only interesting while they are
/// recent, and an unbounded queue in a long-running daemon is a memory leak with a schedule.
const MAX_QUEUED_EVENTS: usize = 240;

/// Connect/read timeouts. Short: a stalled collector must not hold a service's task.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
const READ_TIMEOUT: Duration = Duration::from_secs(5);

/// One event a service may report. Values are numbers or declared labels; there is no string
/// variant that accepts arbitrary text.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ServiceEvent {
    name: &'static str,
    at_millis: u64,
    numbers: BTreeMap<&'static str, i64>,
    labels: BTreeMap<&'static str, &'static str>,
}

impl ServiceEvent {
    /// Start an event. `name` is a `'static` string so it can only be a constant in the calling
    /// crate — never a value derived from a request.
    pub fn new(name: &'static str, at_millis: u64) -> Self {
        Self {
            name,
            at_millis,
            numbers: BTreeMap::new(),
            labels: BTreeMap::new(),
        }
    }

    /// Attach a counter. Saturated into `i64` because a counter that wrapped would be reported as a
    /// negative rate, and the receiver would reject it — losing the whole window over one field.
    pub fn number(mut self, key: &'static str, value: u64) -> Self {
        self.numbers.insert(key, value.min(i64::MAX as u64) as i64);
        self
    }

    /// Attach a declared label (a NAT-pair class, a platform). `'static` for the same reason as the
    /// name: a label that came from a request is not a label, it is data about a peer.
    pub fn label(mut self, key: &'static str, value: &'static str) -> Self {
        self.labels.insert(key, value);
        self
    }

    fn to_json(&self) -> String {
        let mut attrs = Vec::with_capacity(self.numbers.len() + self.labels.len());
        for (key, value) in &self.numbers {
            attrs.push(format!("\"{key}\":{value}"));
        }
        for (key, value) in &self.labels {
            attrs.push(format!("\"{key}\":\"{value}\""));
        }
        format!(
            "{{\"name\":\"{}\",\"t\":{},\"attrs\":{{{}}}}}",
            self.name,
            self.at_millis,
            attrs.join(",")
        )
    }
}

/// The service-side emitter.
pub struct Reporter {
    endpoint: Option<String>,
    source: &'static str,
    agent: String,
    install: String,
    queue: Vec<ServiceEvent>,
    dropped: u64,
    sent: u64,
    last_error: String,
}

impl Reporter {
    /// Build a reporter.
    ///
    /// `endpoint` empty ⇒ disabled: [`Reporter::record`] becomes a no-op and no socket is ever
    /// opened. `install` identifies the *service instance*, not any peer — an operator's deployment,
    /// so its windows can be told apart from another operator's.
    pub fn new(endpoint: &str, source: &'static str, agent: String, install: String) -> Self {
        let endpoint = endpoint.trim();
        Self {
            endpoint: (!endpoint.is_empty()).then(|| normalise(endpoint)),
            source,
            agent,
            install,
            queue: Vec::new(),
            dropped: 0,
            sent: 0,
            last_error: String::new(),
        }
    }

    /// Whether anything will ever be sent.
    pub fn enabled(&self) -> bool {
        self.endpoint.is_some()
    }

    /// Queue one event. No-op when disabled; drops the oldest when the queue is full.
    pub fn record(&mut self, event: ServiceEvent) {
        if self.endpoint.is_none() {
            return;
        }
        if self.queue.len() >= MAX_QUEUED_EVENTS {
            self.queue.remove(0);
            self.dropped += 1;
        }
        self.queue.push(event);
    }

    /// How many events are waiting, for the operator log line.
    pub fn queued(&self) -> usize {
        self.queue.len()
    }

    pub fn dropped(&self) -> u64 {
        self.dropped
    }

    pub fn sent(&self) -> u64 {
        self.sent
    }

    pub fn last_error(&self) -> &str {
        &self.last_error
    }

    /// The batch envelope for the queued events.
    fn envelope(&self, now_millis: u64) -> String {
        let events: Vec<String> = self.queue.iter().map(ServiceEvent::to_json).collect();
        format!(
            "{{\"v\":{BATCH_VERSION},\"src\":\"{}\",\"consent\":\"{CONSENT_GRANTED}\",\
             \"install\":\"{}\",\"agent\":\"{}\",\"sent_at\":{now_millis},\"events\":[{}]}}",
            self.source,
            self.install,
            self.agent,
            events.join(",")
        )
    }

    /// Send everything queued.
    ///
    /// A delivery failure keeps the events for the next attempt; a *refusal* (the service answered
    /// and declined) discards them, because retrying a batch the collector will refuse again spends
    /// bandwidth on being wrong faster. Either way this returns `()` — a service must not be able to
    /// fail because a dashboard is unreachable.
    pub async fn flush(&mut self, now_millis: u64) {
        let Some(endpoint) = self.endpoint.clone() else {
            return;
        };
        if self.queue.is_empty() {
            return;
        }
        let body = self.envelope(now_millis);
        match submit(&endpoint, &body).await {
            Ok(reply) => {
                self.sent += self.queue.len() as u64;
                self.queue.clear();
                self.last_error = reply_error(&reply);
            }
            Err(reason) => {
                self.last_error = reason;
            }
        }
    }
}

/// Accept `host:port` as well as `tcp://host:port`, matching every other endpoint in the project.
fn normalise(endpoint: &str) -> String {
    endpoint
        .strip_prefix("tcp://")
        .unwrap_or(endpoint)
        .to_owned()
}

async fn submit(endpoint: &str, body: &str) -> Result<String, String> {
    let connect = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(endpoint));
    let mut stream = match connect.await {
        Ok(Ok(stream)) => stream,
        Ok(Err(e)) => return Err(format!("connect: {e}")),
        Err(_) => return Err("connect: timed out".to_owned()),
    };

    let framed =
        nodera_codec::framing::frame(body.as_bytes()).map_err(|e| format!("frame: {e}"))?;
    stream
        .write_all(&framed)
        .await
        .map_err(|e| format!("write: {e}"))?;

    let read = tokio::time::timeout(READ_TIMEOUT, read_reply(&mut stream));
    match read.await {
        Ok(Ok(reply)) => Ok(reply),
        Ok(Err(e)) => Err(format!("read: {e}")),
        Err(_) => Err("read: timed out".to_owned()),
    }
}

async fn read_reply(stream: &mut TcpStream) -> std::io::Result<String> {
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    let len = nodera_codec::framing::decode_length(header)
        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()))?;
    if len > 64 * 1024 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "reply is implausibly large",
        ));
    }
    let mut body = vec![0u8; len];
    stream.read_exact(&mut body).await?;
    Ok(String::from_utf8_lossy(&body).into_owned())
}

/// Pull `"error":"…"` out of a reply, or empty when the batch was accepted.
fn reply_error(reply: &str) -> String {
    let key = "\"error\":\"";
    match reply.find(key) {
        None => String::new(),
        Some(at) => {
            let start = at + key.len();
            match reply[start..].find('"') {
                None => String::new(),
                Some(end) => reply[start..start + end].to_owned(),
            }
        }
    }
}

/// A stable pseudonymous identifier for one *service instance*.
///
/// Derived from the operator-supplied endpoint and bind address rather than randomly generated, so
/// a restarted service keeps reporting as itself without any state on disk. It identifies a
/// deployment, never a peer.
pub fn install_id(seed: &str) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(seed.as_bytes());
    digest[..16].iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reporter(endpoint: &str) -> Reporter {
        Reporter::new(
            endpoint,
            "tracker",
            "nodera-tracker 0.1.0".to_owned(),
            install_id("test"),
        )
    }

    /// The default: no endpoint, no queue, no socket, nothing.
    #[test]
    fn a_reporter_without_an_endpoint_is_completely_inert() {
        let mut reporter = reporter("");
        assert!(!reporter.enabled());
        for _ in 0..1_000 {
            reporter.record(ServiceEvent::new("tracker.window", 1).number("queries", 5));
        }
        assert_eq!(reporter.queued(), 0);
        assert_eq!(reporter.dropped(), 0);
    }

    #[test]
    fn an_endpoint_may_be_written_with_or_without_the_scheme() {
        assert!(reporter("tcp://127.0.0.1:25620").enabled());
        assert!(reporter("127.0.0.1:25620").enabled());
        assert!(!reporter("   ").enabled());
    }

    #[test]
    fn the_queue_is_bounded_and_drops_the_oldest() {
        let mut reporter = reporter("127.0.0.1:25620");
        for i in 0..(MAX_QUEUED_EVENTS + 10) {
            reporter.record(ServiceEvent::new("tracker.window", i as u64));
        }
        assert_eq!(reporter.queued(), MAX_QUEUED_EVENTS);
        assert_eq!(reporter.dropped(), 10);
        // The survivors are the newest windows: an old window is not worth reporting.
        assert_eq!(reporter.queue[0].at_millis, 10);
    }

    /// The privacy property, asserted on the rendered bytes: a window event is counters only.
    #[test]
    fn a_window_event_renders_counters_and_labels_only() {
        let event = ServiceEvent::new("tracker.window", 1_700_000_000_000)
            .number("announces", 4_812)
            .number("queries", 91_004)
            .label("nat_pair", "hard_hard");
        let json = event.to_json();
        assert_eq!(
            json,
            "{\"name\":\"tracker.window\",\"t\":1700000000000,\
             \"attrs\":{\"announces\":4812,\"queries\":91004,\"nat_pair\":\"hard_hard\"}}"
        );
    }

    #[test]
    fn the_envelope_declares_the_service_source_and_consent() {
        let mut reporter = reporter("127.0.0.1:25620");
        reporter.record(ServiceEvent::new("tracker.window", 5).number("worlds", 3));
        let envelope = reporter.envelope(1_000);
        assert!(envelope.contains("\"src\":\"tracker\""), "{envelope}");
        assert!(envelope.contains("\"consent\":\"granted\""), "{envelope}");
        assert!(envelope.contains("\"v\":1"), "{envelope}");
        assert!(envelope.contains("\"worlds\":3"), "{envelope}");
    }

    #[test]
    fn a_saturating_counter_does_not_become_a_negative_rate() {
        let event = ServiceEvent::new("tracker.window", 1).number("announces", u64::MAX);
        assert!(event.to_json().contains(&format!("{}", i64::MAX)));
    }

    /// An unreachable collector leaves the events queued and reports why — it never panics and
    /// never blocks the service.
    #[tokio::test]
    async fn an_unreachable_collector_keeps_the_events() {
        // Port 1 on loopback: reserved, never listening in a test environment.
        let mut reporter = reporter("127.0.0.1:1");
        reporter.record(ServiceEvent::new("tracker.window", 1).number("queries", 1));
        reporter.flush(1_000).await;
        assert_eq!(reporter.queued(), 1);
        assert!(
            reporter.last_error().starts_with("connect"),
            "{}",
            reporter.last_error()
        );
        assert_eq!(reporter.sent(), 0);
    }

    #[test]
    fn an_install_identifier_is_stable_for_a_deployment_and_differs_between_them() {
        assert_eq!(
            install_id("tracker.example:25600"),
            install_id("tracker.example:25600")
        );
        assert_ne!(
            install_id("tracker.example:25600"),
            install_id("other.example:25600")
        );
        assert_eq!(install_id("x").len(), 32);
    }

    #[test]
    fn a_refusal_is_read_out_of_the_reply() {
        assert_eq!(reply_error("{\"accepted\":3,\"rejected\":0}"), "");
        assert_eq!(
            reply_error("{\"accepted\":0,\"rejected\":1,\"error\":\"unsupported_version\"}"),
            "unsupported_version"
        );
    }
}
