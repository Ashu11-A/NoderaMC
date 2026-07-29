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
//! What is stored locally is exactly one bit — "the question has been answered" — because the modal
//! must not reappear, and that is a property of this installation's UI, not of the node.

use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

use crate::control::{self, PROTOCOL_VERSION};

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
}

/// Read the worker's telemetry status.
pub async fn status(control_addr: &str) -> TelemetryStatus {
    let asked = has_been_asked();
    match control::request(control_addr, format!("{TELEMETRY} {PROTOCOL_VERSION} GET")).await {
        Ok(line) => {
            let mut status = parse_status(&line);
            status.asked = asked;
            status
        }
        Err(_) => TelemetryStatus {
            // An unreachable worker is not consent, and it is not a denial either: it is an
            // unknown, and the UI shows it as one.
            consent: Consent::Unanswered,
            supported: false,
            asked,
            ..TelemetryStatus::default()
        },
    }
}

/// Push a decision to the worker and read back what it recorded.
///
/// The local "asked" flag is set **whichever way the question was answered**, and it is set even if
/// the push failed: the person answered, and re-asking them because their node was busy would be
/// the app nagging.
pub async fn set(control_addr: &str, granted: bool) -> Result<TelemetryStatus, String> {
    mark_asked();
    let decision = if granted { "granted" } else { "denied" };
    let reply = control::request(
        control_addr,
        format!("{TELEMETRY} {PROTOCOL_VERSION} SET {decision}"),
    )
    .await;
    match reply {
        Ok(_) => Ok(status(control_addr).await),
        Err(reason) => Err(reason),
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

/// Parse the worker's status line.
fn parse_status(line: &str) -> TelemetryStatus {
    if line.starts_with("NODERA-ERR") {
        // The worker answered, and what it said was "I cannot". Distinct from unreachable, and the
        // UI renders it differently: one is a worker to upgrade, the other is a worker to start.
        return TelemetryStatus {
            supported: false,
            ..TelemetryStatus::default()
        };
    }
    TelemetryStatus {
        consent: Consent::parse(&text(line, "consent")),
        supported: true,
        endpoint: text(line, "endpoint"),
        queued: number(line, "queued"),
        sent: number(line, "sent"),
        last_error: text(line, "last_error"),
        asked: false,
    }
}

fn text(json: &str, key: &str) -> String {
    let needle = format!("\"{key}\":\"");
    match json.find(&needle) {
        None => String::new(),
        Some(at) => {
            let start = at + needle.len();
            match json[start..].find('"') {
                None => String::new(),
                Some(end) => json[start..start + end].to_owned(),
            }
        }
    }
}

fn number(json: &str, key: &str) -> u64 {
    let needle = format!("\"{key}\":");
    match json.find(&needle) {
        None => 0,
        Some(at) => {
            let rest = &json[at + needle.len()..];
            let digits: String = rest.chars().take_while(char::is_ascii_digit).collect();
            digits.parse().unwrap_or(0)
        }
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
