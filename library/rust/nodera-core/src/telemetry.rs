//! App task 5: the only place a person is asked.
//!
//! The app owns the *question*; the worker owns the *record*. That split is the whole design:
//!
//! * The companion is a UI over a node that runs without it, so storing the decision here would
//!   make a headless node's consent state depend on whether a window had ever been opened.
//! * The app therefore pushes the answer to the worker and reads it back, and the UI badges what
//!   the worker **confirmed** — never what the app intended. A consent toggle reading "on" while
//!   the worker never received the decision would be the worst possible version of that bug.
//! * The app also never sends telemetry itself. It has events worth reporting (which screens are
//!   used, whether the tray is used) and it hands them to the worker like everything else.
//!
//! What is stored locally is the answer itself plus one bit — "the question has been answered" —
//! because the person answering it is entitled to be asked once, and because **the node may not be
//! running when they answer**. On a fresh Android install it usually is not: first run is exactly
//! the moment the worker is still starting, and the flow used to refuse to move on until a process
//! that had not finished booting confirmed the tap. The answer is therefore recorded here and
//! delivered to the worker as soon as one answers — by [`deliver_pending`], on the reconciliation
//! loop below.
//!
//! The split above survives that: the local copy is the *answer*, never the *state*. The UI still
//! badges what the worker confirmed, and an undelivered answer renders as "not yet recorded on this
//! node" rather than as consent.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::control::{self, PROTOCOL_VERSION};
use crate::settings::SettingsHandle;

const TELEMETRY: &str = "NODERA-TELEMETRY";

/// The consent state, as the worker reports it.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Consent {
    /// Nobody has answered. Collects nothing — and the reason the modal exists.
    #[default]
    Unanswered,
    Denied,
    Granted,
}

impl Consent {
    pub fn parse(token: &str) -> Self {
        match token.trim().to_ascii_lowercase().as_str() {
            "granted" => Consent::Granted,
            "denied" => Consent::Denied,
            // Anything else — including a value from a future worker — reads as unanswered, which
            // collects nothing. Consent fails closed.
            _ => Consent::Unanswered,
        }
    }

    // The inverse of the parser above, kept as a pair: a one-way mapping is how the two ends of
    // a consent token drift apart.
    #[allow(dead_code)]
    pub fn as_str(self) -> &'static str {
        match self {
            Consent::Unanswered => "unanswered",
            Consent::Denied => "denied",
            Consent::Granted => "granted",
        }
    }
}

/// What the Privacy screen renders.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct TelemetryStatus {
    /// The worker's answer. `unanswered` when the worker is unreachable — never a cached grant.
    pub consent: Consent,
    /// `false` when the worker did not answer, or predates the verb. The UI must then say "this
    /// worker cannot do that" rather than showing a toggle with nothing behind it.
    pub supported: bool,
    /// Where reports would go; empty when the node has no collector configured.
    pub endpoint: String,
    pub queued: u64,
    pub sent: u64,
    /// The worker's own words about its last attempt; empty when the last one worked.
    pub last_error: String,
    /// Whether this installation has ever answered the question. Local: the modal is a UI concern.
    pub asked: bool,
    /// The answer this person gave, as this installation recorded it. `None` = never answered.
    ///
    /// Not the same thing as [`Self::consent`], which is what the *node* says. They differ exactly
    /// while an answer is waiting to be delivered, and the UI is expected to show the difference
    /// rather than smooth it over.
    pub answer: Option<bool>,
    /// `true` when an answer was recorded here and no worker has accepted it yet.
    pub pending: bool,
    /// Why the last delivery attempt failed, in the transport's own words; empty when none failed.
    pub delivery_error: String,
}

/// Read the worker's telemetry status, plus whatever this installation recorded locally.
pub async fn status(control_addr: &str, settings: &SettingsHandle) -> TelemetryStatus {
    let setup = settings.snapshot().setup;
    let asked = has_been_asked() || setup.telemetry_granted.is_some();
    let mut status =
        match control::request(control_addr, format!("{TELEMETRY} {PROTOCOL_VERSION} GET")).await {
            Ok(line) => parse_status(&line),
            Err(_) => TelemetryStatus {
                // An unreachable worker is not consent, and it is not a denial either: it is an
                // unknown, and the UI shows it as one.
                consent: Consent::Unanswered,
                supported: false,
                ..TelemetryStatus::default()
            },
        };
    status.asked = asked;
    status.answer = setup.telemetry_granted;
    status.pending = setup.telemetry_granted.is_some() && !setup.telemetry_delivered;
    status
}

/// Record a decision locally, then try to hand it to the worker.
///
/// **Never fails.** The person answered; whether their node was listening at that instant is not
/// their problem and must not be their dead end. The answer is persisted first, the push is
/// attempted second, and an undelivered answer comes back as [`TelemetryStatus::pending`] — which
/// the loop in [`reconcile_loop`] retries until a worker takes it.
///
/// The local "asked" marker is set whichever way the question was answered, and whether or not the
/// push worked: re-asking someone because their node was busy would be the app nagging.
pub async fn set(control_addr: &str, settings: &SettingsHandle, granted: bool) -> TelemetryStatus {
    mark_asked();
    record_answer(settings, granted);
    let delivery = deliver(control_addr, granted).await;
    match delivery {
        Ok(()) => {
            mark_delivered(settings);
            let mut status = status(control_addr, settings).await;
            status.pending = false;
            status
        }
        Err(reason) => {
            let mut status = status(control_addr, settings).await;
            status.delivery_error = reason;
            status
        }
    }
}

/// Hand a recorded-but-undelivered answer to the worker, if there is one.
///
/// Returns `true` when something was delivered, so the caller can log the edge rather than the
/// silence around it.
pub async fn deliver_pending(control_addr: &str, settings: &SettingsHandle) -> bool {
    let setup = settings.snapshot().setup;
    let Some(granted) = setup.telemetry_granted else {
        return false;
    };
    if setup.telemetry_delivered {
        return false;
    }
    match deliver(control_addr, granted).await {
        Ok(()) => {
            mark_delivered(settings);
            true
        }
        Err(_) => false,
    }
}

/// Keep trying to deliver the recorded answer until a worker accepts it.
///
/// Cheap by construction: with nothing pending it is a settings snapshot every [`RECONCILE_EVERY`]
/// and no socket at all, so a node that answered on the first attempt never opens a second
/// connection.
pub async fn reconcile_loop(control_addr: String, settings: Arc<SettingsHandle>) {
    let mut tick = tokio::time::interval(RECONCILE_EVERY);
    loop {
        tick.tick().await;
        if deliver_pending(&control_addr, &settings).await {
            log::info!("telemetry: the recorded consent answer reached the worker");
        }
    }
}

/// How often an undelivered answer is offered to the worker again.
const RECONCILE_EVERY: Duration = Duration::from_secs(20);

/// One `SET` exchange.
async fn deliver(control_addr: &str, granted: bool) -> Result<(), String> {
    let decision = if granted { "granted" } else { "denied" };
    control::request(
        control_addr,
        format!("{TELEMETRY} {PROTOCOL_VERSION} SET {decision}"),
    )
    .await
    .map(|_| ())
}

/// Persist the answer, marking it undelivered.
fn record_answer(settings: &SettingsHandle, granted: bool) {
    let mut next = settings.snapshot();
    next.setup.telemetry_granted = Some(granted);
    next.setup.telemetry_delivered = false;
    if let Err(reason) = settings.save(next) {
        // Logged rather than surfaced: the worker push below is still attempted, and a settings
        // file that cannot be written is a fault the Settings screen already reports on its own.
        log::warn!("telemetry: could not record the answer locally: {reason}");
    }
}

/// Mark the recorded answer as accepted by a worker.
fn mark_delivered(settings: &SettingsHandle) {
    let mut next = settings.snapshot();
    if next.setup.telemetry_delivered {
        return;
    }
    next.setup.telemetry_delivered = true;
    if let Err(reason) = settings.save(next) {
        log::warn!("telemetry: could not record that the answer was delivered: {reason}");
    }
}

/// Ask the ingest service what it accepts.
///
/// Read from the service the user actually reports to, rather than from prose maintained beside it:
/// a privacy notice kept separately from the enforcement drifts, and it always drifts in the
/// uncomfortable direction. When the endpoint cannot be reached the caller falls back to the
/// bundled copy and says so.
pub async fn collected_schema(endpoint: &str) -> Result<String, String> {
    if endpoint.trim().is_empty() {
        return Err("this node has no telemetry collector configured".to_owned());
    }
    let address = endpoint.trim().strip_prefix("tcp://").unwrap_or(endpoint);
    let probe = br#"{"v":1,"probe":true}"#;
    match framed_request(address, probe).await {
        Ok(reply) => Ok(reply),
        // L-78: a person asking "what is collected?" while offline deserves an answer, and the
        // honest one is the registry this build was compiled against — labelled as such. Returning
        // an error here would leave the screen blank at exactly the moment someone is deciding
        // whether to consent.
        Err(_) => Ok(bundled_schema()),
    }
}

/// The registry compiled into this build, marked as the fallback and carrying the version it was
/// built from.
///
/// The version is what makes the fallback safe to show: a copy that cannot say how old it is looks
/// exactly like a current one, and a privacy disclosure that is quietly out of date is worse than
/// no disclosure at all. The screen can compare `registry_version` against the app's own and say
/// "this is what this build knows about" rather than implying it is what the collector accepts.
pub fn bundled_schema() -> String {
    nodera_telemetry::schema::schema_json("bundled")
}

/// One framed request/response against the ingest service.
async fn framed_request(address: &str, body: &[u8]) -> Result<String, String> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let connect = tokio::time::timeout(
        std::time::Duration::from_secs(3),
        tokio::net::TcpStream::connect(address),
    );
    let mut stream = match connect.await {
        Ok(Ok(stream)) => stream,
        Ok(Err(e)) => return Err(format!("could not reach {address}: {e}")),
        Err(_) => return Err(format!("could not reach {address}: timed out")),
    };
    let mut framed = (body.len() as u32).to_be_bytes().to_vec();
    framed.extend_from_slice(body);
    stream
        .write_all(&framed)
        .await
        .map_err(|e| format!("write failed: {e}"))?;

    let mut header = [0u8; 4];
    stream
        .read_exact(&mut header)
        .await
        .map_err(|e| format!("read failed: {e}"))?;
    let length = u32::from_be_bytes(header) as usize;
    if length > 1 << 20 {
        return Err("the collector answered with an implausibly large reply".to_owned());
    }
    let mut reply = vec![0u8; length];
    stream
        .read_exact(&mut reply)
        .await
        .map_err(|e| format!("read failed: {e}"))?;
    Ok(String::from_utf8_lossy(&reply).into_owned())
}

/// The worker's `NODERA-TELEMETRY … GET` reply, exactly as `WorkerTelemetryService.stateJson`
/// writes it. Every field defaults, so a worker that adds one — or predates one — still parses.
#[derive(Debug, Default, Deserialize)]
#[serde(default)]
struct WorkerStatus {
    consent: String,
    endpoint: String,
    queued: u64,
    sent: u64,
    last_error: String,
}

/// Parse the worker's status line.
///
/// A real JSON parse, and it has to be. The three functions this replaced searched the raw line for
/// `"key":` and took whatever followed — so `number(line, "sent")` returned the first digits after
/// the first occurrence *anywhere in the line*, and `last_error` carries network-sourced text. A
/// worker whose last error contained the literal `"sent":9999` made this app report a fabricated
/// event count on the privacy screen, which is the one screen that must not be able to lie.
///
/// An unparseable reply reads as unsupported rather than as a zeroed status: "I could not
/// understand this worker" and "this worker has sent nothing" are different answers, and the UI
/// renders them differently.
fn parse_status(line: &str) -> TelemetryStatus {
    // The worker answered, and what it said was "I cannot". Distinct from unreachable, and the UI
    // renders it differently: one is a worker to upgrade, the other is a worker to start.
    let Ok(status) = serde_json::from_str::<WorkerStatus>(line) else {
        return TelemetryStatus {
            supported: false,
            ..TelemetryStatus::default()
        };
    };
    TelemetryStatus {
        consent: Consent::parse(&status.consent),
        supported: true,
        endpoint: status.endpoint,
        queued: status.queued,
        sent: status.sent,
        last_error: status.last_error,
        ..TelemetryStatus::default()
    }
}

// ---- the "has been asked" flag -------------------------------------------------------------------

static ASKED: Mutex<Option<bool>> = Mutex::new(None);

fn asked_marker_path() -> PathBuf {
    crate::settings::config_dir().join("telemetry-asked")
}

/// Whether this installation has ever answered the question.
pub fn has_been_asked() -> bool {
    let mut cached = ASKED.lock().unwrap();
    if let Some(known) = *cached {
        return known;
    }
    let known = asked_marker_path().is_file();
    *cached = Some(known);
    known
}

/// Record that the question was answered — whichever way.
///
/// A file rather than a settings field: it must survive a settings reset, because "we already asked
/// this person" is a fact about them, not a preference of theirs.
pub fn mark_asked() {
    let path = asked_marker_path();
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let _ = std::fs::write(&path, b"1");
    *ASKED.lock().unwrap() = Some(true);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn consent_fails_closed_for_anything_it_does_not_recognise() {
        assert_eq!(Consent::parse("granted"), Consent::Granted);
        assert_eq!(Consent::parse("GRANTED"), Consent::Granted);
        assert_eq!(Consent::parse("denied"), Consent::Denied);
        assert_eq!(Consent::parse("unanswered"), Consent::Unanswered);
        assert_eq!(Consent::parse("yes"), Consent::Unanswered);
        assert_eq!(Consent::parse(""), Consent::Unanswered);
    }

    #[test]
    fn a_worker_status_line_parses_into_the_screen_model() {
        let status = parse_status(
            r#"{"consent":"granted","endpoint":"tcp://collector:25620","queued":7,"dropped":0,"sent":42,"last_attempt":1700,"last_error":""}"#,
        );
        assert_eq!(status.consent, Consent::Granted);
        assert!(status.supported);
        assert_eq!(status.endpoint, "tcp://collector:25620");
        assert_eq!(status.queued, 7);
        assert_eq!(status.sent, 42);
        assert!(status.last_error.is_empty());
    }

    /// A worker that predates the verb is "cannot", not "off" — and the UI must be able to tell.
    #[test]
    fn an_older_worker_is_reported_as_unsupported_rather_than_denied() {
        let status = parse_status("NODERA-ERR unsupported");
        assert!(!status.supported);
        assert_eq!(status.consent, Consent::Unanswered);
    }

    #[test]
    fn a_worker_error_is_carried_verbatim_for_the_soft_status_line() {
        let status = parse_status(
            r#"{"consent":"granted","endpoint":"tcp://c:1","queued":3,"sent":0,"last_error":"connect: refused"}"#,
        );
        assert_eq!(status.last_error, "connect: refused");
        assert_eq!(status.queued, 3);
    }

    /// Network-sourced text inside the reply cannot invent a number on the privacy screen.
    ///
    /// `last_error` is whatever the collector said, relayed by the worker. The parser this replaced
    /// searched the whole line for `"sent":` and took the digits after the first hit, so an error
    /// message containing that string made the app report an event count nobody ever sent — on the
    /// one screen whose entire purpose is to tell the truth about what left this machine.
    #[test]
    fn a_number_quoted_inside_an_error_message_is_not_read_as_a_count() {
        let status = parse_status(
            r#"{"consent":"granted","endpoint":"tcp://c:1","queued":0,"sent":0,"last_error":"rejected: {\"sent\":9999} is not a batch"}"#,
        );
        assert_eq!(status.sent, 0, "the count must come from the count field");
        assert_eq!(status.queued, 0);
        assert!(status.last_error.contains("9999"));
    }

    /// A reply this build cannot read is "I could not understand this worker", never "this worker
    /// has sent nothing" — the second is a claim, and it would be made up.
    #[test]
    fn an_unparseable_reply_is_unsupported_rather_than_a_zeroed_status() {
        for reply in ["", "not json at all", r#"{"consent":"granted""#] {
            let status = parse_status(reply);
            assert!(!status.supported, "{reply:?}");
            assert_eq!(status.consent, Consent::Unanswered);
        }
    }

    /// A node with no collector configured is a different case from a collector that will not
    /// answer: there is nothing to disclose, so the screen says so rather than showing a registry
    /// for a service this node never talks to.
    #[test]
    fn an_unconfigured_collector_is_an_error_rather_than_an_empty_schema() {
        let outcome = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(collected_schema(""));
        assert!(outcome.is_err());
    }

    /// L-78's exit clause. A person asking "what is collected?" while the collector is unreachable
    /// gets the registry this build was compiled against — labelled as the fallback and carrying
    /// the version it came from. Not a blank screen at the moment they are deciding whether to
    /// consent, and not a possibly-stale list pretending to be current.
    #[test]
    fn the_disclosure_falls_back_to_the_bundled_registry_when_the_service_is_unreachable() {
        // Port 1 on loopback refuses immediately: unreachable, without spending a timeout on it.
        let answer = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(collected_schema("127.0.0.1:1"))
            .expect("an unreachable collector must not blank the disclosure");

        let value: serde_json::Value = serde_json::from_str(&answer).unwrap();
        assert_eq!(value["disclosure_source"], "bundled");
        assert!(
            value["events"].as_array().map(|e| e.len()).unwrap_or(0) > 0,
            "the bundled registry must actually list what is collected"
        );
        let version = value["registry_version"].as_str().unwrap_or_default();
        assert!(
            !version.is_empty(),
            "a fallback that cannot say how old it is looks exactly like a current one"
        );
    }

    /// The two answers must be distinguishable, or the screen cannot label one as a fallback.
    #[test]
    fn the_bundled_copy_says_it_is_bundled() {
        let bundled: serde_json::Value = serde_json::from_str(&bundled_schema()).unwrap();
        let live: serde_json::Value =
            serde_json::from_str(&nodera_telemetry::schema::schema_json("service")).unwrap();

        assert_eq!(bundled["disclosure_source"], "bundled");
        assert_eq!(live["disclosure_source"], "service");
        assert_eq!(bundled["events"], live["events"]);
        assert_eq!(bundled["registry_version"], live["registry_version"]);
    }
}
