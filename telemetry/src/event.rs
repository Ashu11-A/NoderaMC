//! Parsing, consent enforcement, and schema validation for one submitted batch.
//!
//! Everything a client sends is treated as a *claim*, never as a fact. The batch says it has
//! consent; the batch says what time it is; the batch says which events it carries. This module
//! re-decides all three against [`crate::schema`] and the operator's bounds, and what comes out
//! the far side is the only thing the sink ever sees.
//!
//! **Why validate here rather than in the emitter.** The emitters are the parts of the system a
//! privacy claim is hardest to check: three languages, several processes, and a mod anyone can
//! fork. Putting the gate at ingest means the claim "NoderaMC stores exactly the registry" is true
//! even for a client that is buggy, modified, or hostile — and it is provable by reading one
//! crate.

use std::collections::BTreeMap;

use serde_json::{Map, Value};

use crate::schema::{self, Source, SpecError, ValueKind};

/// Wire version of the batch envelope. Bumped only for a breaking envelope change; the *event*
/// registry grows without touching it.
pub const BATCH_VERSION: u64 = 1;

/// The only consent token that admits a batch.
///
/// Compared exactly, and the *absence* of the field is a refusal rather than a default: a client
/// that forgets to say whether the user agreed has, as far as this service is concerned, no
/// agreement to report.
pub const CONSENT_GRANTED: &str = "granted";

/// Operator bounds applied while validating.
#[derive(Debug, Clone, Copy)]
pub struct Bounds {
    pub max_events_per_batch: usize,
    pub max_attrs_per_event: usize,
    /// How far in the past an event timestamp may sit. Older events are dropped: a month-old
    /// backlog replayed at once is indistinguishable from a fabricated one.
    pub max_event_age_millis: u64,
    /// How far in the future a timestamp may sit before it is treated as a wrong clock.
    pub max_clock_skew_millis: u64,
}

/// One event that survived validation, with only declared attributes left on it.
#[derive(Debug, Clone, PartialEq)]
pub struct CleanEvent {
    /// The `'static` registry name, not the client's string — so nothing downstream can be handed
    /// a name that was never declared.
    pub name: &'static str,
    pub at_millis: u64,
    pub attrs: BTreeMap<String, Value>,
}

/// A validated batch: what will be written, plus an honest account of what was not.
#[derive(Debug, Clone, PartialEq)]
pub struct CleanBatch {
    pub source: Source,
    /// The client's raw install identifier. Never written — [`crate::subject`] replaces it before
    /// anything reaches the sink.
    pub install: String,
    pub agent: String,
    pub events: Vec<CleanEvent>,
    /// Per-reason counts for events that were refused, and for attributes that were dropped.
    pub rejected: BTreeMap<&'static str, u64>,
}

/// Why a whole batch was refused. A batch-level refusal writes nothing at all.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum BatchError {
    #[error("the payload is not JSON this service can read")]
    Malformed,
    #[error("unsupported envelope version")]
    UnsupportedVersion,
    #[error("unknown source")]
    UnknownSource,
    #[error("no consent recorded for this batch")]
    NoConsent,
    #[error("the install identifier is not 32 lowercase hex characters")]
    BadInstall,
    #[error("too many events in one batch")]
    TooManyEvents,
}

impl BatchError {
    /// Stable machine name, used as the reply's reason key and as a counter label.
    pub fn as_str(self) -> &'static str {
        match self {
            BatchError::Malformed => "malformed",
            BatchError::UnsupportedVersion => "unsupported_version",
            BatchError::UnknownSource => "unknown_source",
            BatchError::NoConsent => "no_consent",
            BatchError::BadInstall => "bad_install",
            BatchError::TooManyEvents => "too_many_events",
        }
    }
}

/// Reason labels for per-event and per-attribute refusals.
mod reason {
    pub const UNKNOWN_EVENT: &str = "unknown_event";
    pub const WRONG_SOURCE: &str = "wrong_source";
    pub const NOT_AN_EVENT: &str = "not_an_event";
    pub const STALE: &str = "stale";
    pub const FUTURE: &str = "future";
    pub const UNDECLARED_ATTR: &str = "undeclared_attr";
    pub const BAD_VALUE: &str = "bad_value";
    pub const TOO_MANY_ATTRS: &str = "too_many_attrs";
}

/// Validate one submitted batch.
///
/// `now_millis` is the *server's* clock. Client timestamps are kept (they carry the ordering a
/// session actually had) but are only admitted inside the operator's window, so a broken or
/// deliberately skewed clock cannot place rows in an arbitrary partition.
pub fn validate(raw: &[u8], bounds: &Bounds, now_millis: u64) -> Result<CleanBatch, BatchError> {
    let value: Value = serde_json::from_slice(raw).map_err(|_| BatchError::Malformed)?;
    let object = value.as_object().ok_or(BatchError::Malformed)?;

    if object.get("v").and_then(Value::as_u64) != Some(BATCH_VERSION) {
        return Err(BatchError::UnsupportedVersion);
    }
    if object.get("consent").and_then(Value::as_str) != Some(CONSENT_GRANTED) {
        return Err(BatchError::NoConsent);
    }
    let source = object
        .get("src")
        .and_then(Value::as_str)
        .and_then(Source::parse)
        .ok_or(BatchError::UnknownSource)?;
    let install = object
        .get("install")
        .and_then(Value::as_str)
        .filter(|value| is_install_id(value))
        .ok_or(BatchError::BadInstall)?
        .to_owned();
    // The agent string is the one free-form field on the envelope, and it is not stored as sent:
    // it is bounded, stripped of anything that is not a printable identifier character, and only
    // ever used to tell "which build is reporting this" apart.
    let agent = sanitize_agent(object.get("agent").and_then(Value::as_str).unwrap_or(""));

    let events = object
        .get("events")
        .and_then(Value::as_array)
        .ok_or(BatchError::Malformed)?;
    if events.len() > bounds.max_events_per_batch {
        return Err(BatchError::TooManyEvents);
    }

    let mut clean = Vec::with_capacity(events.len());
    let mut rejected: BTreeMap<&'static str, u64> = BTreeMap::new();
    for event in events {
        match validate_event(event, source, bounds, now_millis, &mut rejected) {
            Some(event) => clean.push(event),
            None => continue,
        }
    }

    Ok(CleanBatch {
        source,
        install,
        agent,
        events: clean,
        rejected,
    })
}

fn count(rejected: &mut BTreeMap<&'static str, u64>, label: &'static str) {
    *rejected.entry(label).or_insert(0) += 1;
}

fn validate_event(
    value: &Value,
    source: Source,
    bounds: &Bounds,
    now_millis: u64,
    rejected: &mut BTreeMap<&'static str, u64>,
) -> Option<CleanEvent> {
    let Some(object) = value.as_object() else {
        count(rejected, reason::NOT_AN_EVENT);
        return None;
    };
    let Some(name) = object.get("name").and_then(Value::as_str) else {
        count(rejected, reason::NOT_AN_EVENT);
        return None;
    };
    let spec = match schema::spec_for(name, source) {
        Ok(spec) => spec,
        Err(SpecError::UnknownEvent) => {
            count(rejected, reason::UNKNOWN_EVENT);
            return None;
        }
        Err(SpecError::WrongSource) => {
            count(rejected, reason::WRONG_SOURCE);
            return None;
        }
    };

    let at_millis = object
        .get("t")
        .and_then(Value::as_u64)
        .unwrap_or(now_millis);
    if at_millis + bounds.max_event_age_millis < now_millis {
        count(rejected, reason::STALE);
        return None;
    }
    if at_millis > now_millis.saturating_add(bounds.max_clock_skew_millis) {
        count(rejected, reason::FUTURE);
        return None;
    }

    let empty = Map::new();
    let attrs = object
        .get("attrs")
        .and_then(Value::as_object)
        .unwrap_or(&empty);
    if attrs.len() > bounds.max_attrs_per_event {
        count(rejected, reason::TOO_MANY_ATTRS);
        return None;
    }

    let mut clean = BTreeMap::new();
    for (key, value) in attrs {
        let Some(kind) = spec.kind_of(key) else {
            // Dropped, not fatal: a newer emitter must keep reporting the fields this build does
            // know, and the undeclared one still never reaches the sink.
            count(rejected, reason::UNDECLARED_ATTR);
            continue;
        };
        match coerce(kind, value) {
            Some(value) => {
                clean.insert(key.clone(), value);
            }
            None => count(rejected, reason::BAD_VALUE),
        }
    }

    Some(CleanEvent {
        name: spec.name,
        at_millis,
        attrs: clean,
    })
}

/// Re-type one attribute value against its declared kind, or reject it.
///
/// Rejection rather than coercion is the point: a value outside its declared range is evidence of
/// a bug or of tampering, and storing a clamped version of it would launder both into a plausible
/// looking statistic.
fn coerce(kind: ValueKind, value: &Value) -> Option<Value> {
    match kind {
        ValueKind::Int { min, max } => {
            let number = value.as_i64()?;
            (number >= min && number <= max).then(|| Value::from(number))
        }
        ValueKind::Bool => value.as_bool().map(Value::from),
        ValueKind::Enum(values) => {
            let text = value.as_str()?;
            values.contains(&text).then(|| Value::from(text))
        }
        ValueKind::Hex { len } => {
            let text = value.as_str()?;
            (text.len() == len
                && text
                    .chars()
                    .all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c)))
            .then(|| Value::from(text))
        }
        ValueKind::Version => {
            let text = value.as_str()?;
            (!text.is_empty()
                && text.len() <= 24
                && text
                    .chars()
                    .all(|c| c.is_ascii_digit() || c == '.' || c == '-'))
            .then(|| Value::from(text))
        }
    }
}

/// A 128-bit install identifier, lowercase hex.
///
/// Checked rather than trusted because the value is a *shape* the pseudonymiser depends on: an
/// arbitrary-length string here would let a client smuggle text into a column that is documented
/// as an opaque identifier.
fn is_install_id(value: &str) -> bool {
    value.len() == 32
        && value
            .chars()
            .all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c))
}

/// Reduce the agent string to a bounded identifier — letters, digits, dot, dash, underscore,
/// space — so "which build" stays answerable and nothing else can ride along in it.
///
/// The separator characters of a filesystem path (`/`, `\`, `:`) are deliberately **not** in the
/// set. A user name embedded in a home directory is the classic accidental identifier, and an
/// agent string is exactly where one arrives.
fn sanitize_agent(raw: &str) -> String {
    raw.chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | ' '))
        .take(48)
        .collect::<String>()
        .trim()
        .to_owned()
}

#[cfg(test)]
pub(crate) fn test_bounds() -> Bounds {
    Bounds {
        max_events_per_batch: 500,
        max_attrs_per_event: 32,
        max_event_age_millis: 7 * 24 * 3_600_000,
        max_clock_skew_millis: 300_000,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const INSTALL: &str = "0123456789abcdef0123456789abcdef";
    const NOW: u64 = 1_800_000_000_000;

    fn batch(body: &str) -> Vec<u8> {
        body.replace('\'', "\"").into_bytes()
    }

    fn one_event(name: &str, attrs: &str) -> Vec<u8> {
        batch(&format!(
            "{{'v':1,'src':'peer','consent':'granted','install':'{INSTALL}','agent':'nodera-worker 0.1.0',\
              'sent_at':{NOW},'events':[{{'name':'{name}','t':{NOW},'attrs':{attrs}}}]}}"
        ))
    }

    #[test]
    fn a_well_formed_batch_validates() {
        let clean = validate(
            &one_event("feature.use", "{'feature':'share_gui','count':3}"),
            &test_bounds(),
            NOW,
        )
        .unwrap();
        assert_eq!(clean.source, Source::Peer);
        assert_eq!(clean.install, INSTALL);
        assert_eq!(clean.events.len(), 1);
        assert_eq!(clean.events[0].name, "feature.use");
        assert_eq!(clean.events[0].attrs["count"], Value::from(3));
        assert!(clean.rejected.is_empty());
    }

    /// The headline privacy property: consent is a gate, not a preference the server records.
    #[test]
    fn a_batch_without_granted_consent_writes_nothing() {
        let raw = batch(&format!(
            "{{'v':1,'src':'peer','install':'{INSTALL}','sent_at':{NOW},'events':[]}}"
        ));
        assert_eq!(
            validate(&raw, &test_bounds(), NOW).unwrap_err(),
            BatchError::NoConsent
        );

        let denied = batch(&format!(
            "{{'v':1,'src':'peer','consent':'denied','install':'{INSTALL}','sent_at':{NOW},'events':[]}}"
        ));
        assert_eq!(
            validate(&denied, &test_bounds(), NOW).unwrap_err(),
            BatchError::NoConsent
        );
    }

    #[test]
    fn an_unknown_event_name_is_refused_and_counted() {
        let clean = validate(
            &one_event("world.name", "{'value':'My Secret Base'}"),
            &test_bounds(),
            NOW,
        )
        .unwrap();
        assert!(clean.events.is_empty());
        assert_eq!(clean.rejected[reason::UNKNOWN_EVENT], 1);
    }

    /// The forward-compatibility asymmetry, pinned: the event survives, the stowaway does not.
    #[test]
    fn an_undeclared_attribute_is_dropped_without_losing_the_event() {
        let clean = validate(
            &one_event(
                "feature.use",
                "{'feature':'selftest','count':1,'world_name':'Base'}",
            ),
            &test_bounds(),
            NOW,
        )
        .unwrap();
        assert_eq!(clean.events.len(), 1);
        assert!(!clean.events[0].attrs.contains_key("world_name"));
        assert_eq!(clean.rejected[reason::UNDECLARED_ATTR], 1);
    }

    #[test]
    fn an_out_of_range_integer_is_rejected_rather_than_clamped() {
        let clean = validate(
            &one_event("engine.tick", "{'tps_bucket':900,'window_seconds':60}"),
            &test_bounds(),
            NOW,
        )
        .unwrap();
        assert_eq!(clean.events.len(), 1);
        assert!(!clean.events[0].attrs.contains_key("tps_bucket"));
        assert_eq!(clean.events[0].attrs["window_seconds"], Value::from(60));
        assert_eq!(clean.rejected[reason::BAD_VALUE], 1);
    }

    #[test]
    fn an_enum_value_outside_its_declared_set_is_rejected() {
        let clean = validate(
            &one_event("world.join", "{'ok':true,'path':'carrier_pigeon'}"),
            &test_bounds(),
            NOW,
        )
        .unwrap();
        assert!(!clean.events[0].attrs.contains_key("path"));
        assert_eq!(clean.events[0].attrs["ok"], Value::from(true));
    }

    #[test]
    fn a_fingerprint_must_be_lowercase_hex_of_the_declared_length() {
        let ok = validate(
            &one_event(
                "error.report",
                "{'kind':'engine','fingerprint':'0f1e2d3c4b5a6978','fatal':false}",
            ),
            &test_bounds(),
            NOW,
        )
        .unwrap();
        assert_eq!(
            ok.events[0].attrs["fingerprint"],
            Value::from("0f1e2d3c4b5a6978")
        );

        let bad = validate(
            &one_event(
                "error.report",
                "{'kind':'engine','fingerprint':'NullPointerException at Foo'}",
            ),
            &test_bounds(),
            NOW,
        )
        .unwrap();
        assert!(!bad.events[0].attrs.contains_key("fingerprint"));
    }

    #[test]
    fn a_peer_may_not_report_service_level_events() {
        let clean = validate(
            &one_event("tracker.window", "{'announces':10}"),
            &test_bounds(),
            NOW,
        )
        .unwrap();
        assert!(clean.events.is_empty());
        assert_eq!(clean.rejected[reason::WRONG_SOURCE], 1);
    }

    #[test]
    fn stale_and_future_events_are_dropped_at_the_window_edges() {
        let bounds = test_bounds();
        let stale = batch(&format!(
            "{{'v':1,'src':'peer','consent':'granted','install':'{INSTALL}','events':\
              [{{'name':'session.end','t':1,'attrs':{{}}}}]}}"
        ));
        assert_eq!(
            validate(&stale, &bounds, NOW).unwrap().rejected[reason::STALE],
            1
        );

        let future = batch(&format!(
            "{{'v':1,'src':'peer','consent':'granted','install':'{INSTALL}','events':\
              [{{'name':'session.end','t':{},'attrs':{{}}}}]}}",
            NOW + bounds.max_clock_skew_millis + 1
        ));
        assert_eq!(
            validate(&future, &bounds, NOW).unwrap().rejected[reason::FUTURE],
            1
        );
    }

    #[test]
    fn an_install_identifier_that_is_not_128_bits_of_hex_is_refused() {
        for id in ["", "not-hex", "0123456789ABCDEF0123456789ABCDEF", "abc"] {
            let raw = batch(&format!(
                "{{'v':1,'src':'peer','consent':'granted','install':'{id}','events':[]}}"
            ));
            assert_eq!(
                validate(&raw, &test_bounds(), NOW).unwrap_err(),
                BatchError::BadInstall,
                "{id}"
            );
        }
    }

    #[test]
    fn an_oversized_batch_is_refused_whole() {
        let events = (0..10)
            .map(|_| "{'name':'session.end','attrs':{}}".to_owned())
            .collect::<Vec<_>>()
            .join(",");
        let raw = batch(&format!(
            "{{'v':1,'src':'peer','consent':'granted','install':'{INSTALL}','events':[{events}]}}"
        ));
        let mut bounds = test_bounds();
        bounds.max_events_per_batch = 5;
        assert_eq!(
            validate(&raw, &bounds, NOW).unwrap_err(),
            BatchError::TooManyEvents
        );
    }

    #[test]
    fn an_unsupported_envelope_version_is_refused_before_anything_else_is_read() {
        let raw = batch("{'v':99,'src':'peer','consent':'granted','install':'x','events':[]}");
        assert_eq!(
            validate(&raw, &test_bounds(), NOW).unwrap_err(),
            BatchError::UnsupportedVersion
        );
    }

    #[test]
    fn garbage_is_a_malformed_batch_not_a_panic() {
        assert_eq!(
            validate(b"not json at all", &test_bounds(), NOW).unwrap_err(),
            BatchError::Malformed
        );
        assert_eq!(
            validate(b"[1,2,3]", &test_bounds(), NOW).unwrap_err(),
            BatchError::Malformed
        );
    }

    #[test]
    fn the_agent_string_is_reduced_to_a_bounded_identifier() {
        assert_eq!(sanitize_agent("NoderaMC 0.1.0"), "NoderaMC 0.1.0");
        // A path, a user name, and a non-ASCII world name all fail to survive the filter.
        assert_eq!(
            sanitize_agent("nodera/0.1.0 (user=ashu; path=/home/ashu/世界)"),
            "nodera0.1.0 userashu pathhomeashu"
        );
        assert_eq!(sanitize_agent(&"a".repeat(200)).len(), 48);
    }
}
