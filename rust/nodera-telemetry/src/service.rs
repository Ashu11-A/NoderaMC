//! The ingest decision: one submitted frame in, one reply out, zero or more NDJSON rows written.
//!
//! The order of operations is the design, and it is deliberately "cheapest refusal first":
//!
//! ```text
//!   probe? ─► quota ─► parse+consent ─► schema validate ─► pseudonymise ─► geolocate ─► write
//! ```
//!
//! Consent is checked before anything is stored and the source address is resolved to a country
//! *after* the batch is already admitted — so the two expensive privacy operations only ever run
//! on data that is going to be kept.
//!
//! The reply is honest about refusals. A client that is being dropped for a bad clock, an
//! unsupported build, or an exhausted quota is told which, because a silently discarded report is
//! indistinguishable from a working one and produces a fleet that believes it is reporting.

use std::collections::BTreeMap;
use std::net::IpAddr;

use serde_json::{Map, Value};

use crate::config::Config;
use crate::event::{self, CleanBatch};
use crate::geo::{Geo, GeoTable};
use crate::limits::{IngestQuota, Verdict};
use crate::sink::EventSink;
use crate::subject::Pseudonymiser;

/// Row-format version, written on every line. Bumped when the *row* shape changes, independently
/// of the batch envelope version, because the warehouse and the clients version separately.
pub const ROW_SCHEMA: u64 = 1;

/// What the connection layer should do with the result.
pub enum Handled {
    /// Send this JSON reply frame.
    Reply(Vec<u8>),
}

/// Lifetime counters for the operator log line.
#[derive(Debug, Default, Clone)]
pub struct Counters {
    pub batches_accepted: u64,
    pub batches_refused: u64,
    pub events_written: u64,
    pub events_refused: u64,
    pub bytes_in: u64,
    pub write_errors: u64,
    /// Connections refused before a byte was read, because the deployment declares itself public
    /// and the source is not the TLS front (telemetry L-73).
    pub connections_refused: u64,
    /// Per-reason refusal counts, batch-level and event-level in one map.
    pub reasons: BTreeMap<String, u64>,
}

/// The ingest service.
pub struct Ingest {
    config: Config,
    quota: IngestQuota,
    geo: GeoTable,
    pseudonymiser: Pseudonymiser,
    sink: Box<dyn EventSink>,
    counters: Counters,
    admission: crate::config::Admission,
}

impl Ingest {
    pub fn new(config: Config, geo: GeoTable, sink: Box<dyn EventSink>) -> Self {
        let quota = IngestQuota::new(
            config.quota_window_seconds.saturating_mul(1_000).max(1),
            config.per_ip_batch_quota,
            config.per_ip_event_quota,
        );
        let pseudonymiser =
            Pseudonymiser::new(&config.subject_secret, config.subject_rotation_days);
        // `validate()` has already refused an unparseable CIDR, and `serve` refuses to start on a
        // config that does not validate — so reaching this with a bad range is a programming error
        // and must be loud rather than silently open.
        let admission = crate::config::Admission::from_config(&config)
            .expect("configuration was validated before the service was constructed");
        Self {
            admission,
            config,
            quota,
            geo,
            pseudonymiser,
            sink,
            counters: Counters::default(),
        }
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    pub fn counters(&self) -> &Counters {
        &self.counters
    }

    /// May a connection from `source` be served at all?
    ///
    /// This is the plaintext-listener gate (telemetry L-73). A private deployment admits
    /// everything; a deployment that has declared itself public admits only loopback and the
    /// declared TLS terminator, so a client dialling the public endpoint in the clear is refused
    /// rather than served.
    pub fn admits_connection(&mut self, source: IpAddr) -> bool {
        if self.admission.admits(source) {
            return true;
        }
        self.counters.connections_refused += 1;
        *self
            .counters
            .reasons
            .entry("plaintext_not_via_tls_front".to_owned())
            .or_insert(0) += 1;
        false
    }

    /// Expire idle quota counters.
    pub fn sweep(&mut self, now_millis: u64) {
        self.quota.sweep(now_millis);
    }

    /// Handle one frame.
    pub fn handle_frame(
        &mut self,
        frame: &[u8],
        source_ip: Option<IpAddr>,
        now_millis: u64,
    ) -> Handled {
        self.counters.bytes_in += frame.len() as u64;

        if is_probe(frame) {
            return Handled::Reply(probe_reply());
        }

        // The event count is needed for the quota *before* the batch is trusted, so it is read
        // from the raw JSON as a claim. A liar over-charges its own budget, which is fine.
        let claimed_events = claimed_event_count(frame);
        if let Some(ip) = source_ip {
            match self.quota.admit(ip, claimed_events, now_millis) {
                Verdict::Admit => {}
                Verdict::TooManyBatches => return self.refuse("quota_batches", claimed_events),
                Verdict::TooManyEvents => return self.refuse("quota_events", claimed_events),
            }
        }

        let batch = match event::validate(frame, &self.config.bounds(), now_millis) {
            Ok(batch) => batch,
            Err(e) => return self.refuse(e.as_str(), claimed_events),
        };

        let geo = source_ip
            .map(|ip| self.geo.lookup(ip))
            .unwrap_or(Geo::UNKNOWN);
        self.write(batch, geo, now_millis)
    }

    fn refuse(&mut self, reason: &str, events: u32) -> Handled {
        self.counters.batches_refused += 1;
        self.counters.events_refused += events as u64;
        *self.counters.reasons.entry(reason.to_owned()).or_insert(0) += 1;

        let mut reply = Map::new();
        reply.insert("accepted".into(), Value::from(0));
        reply.insert("rejected".into(), Value::from(events));
        reply.insert("error".into(), Value::from(reason));
        Handled::Reply(finish(reply))
    }

    fn write(&mut self, batch: CleanBatch, geo: Geo, now_millis: u64) -> Handled {
        let subject = self
            .pseudonymiser
            .subject(batch.source, &batch.install, now_millis);

        let mut written = 0u64;
        let mut failed = 0u64;
        for clean in &batch.events {
            let line = row(&batch, clean, &subject, geo, now_millis);
            match self.sink.write_line(&line, now_millis) {
                Ok(()) => written += 1,
                Err(e) => {
                    failed += 1;
                    self.counters.write_errors += 1;
                    // Logged per batch, not per row: a full disk would otherwise turn one bad
                    // minute into a second outage in the log pipeline.
                    if failed == 1 {
                        eprintln!("nodera-telemetry: spool write failed: {e}");
                    }
                }
            }
        }
        if let Err(e) = self.sink.flush() {
            self.counters.write_errors += 1;
            eprintln!("nodera-telemetry: spool flush failed: {e}");
        }

        self.counters.batches_accepted += 1;
        self.counters.events_written += written;
        let mut rejected_total = 0u64;
        for (reason, count) in &batch.rejected {
            rejected_total += count;
            *self
                .counters
                .reasons
                .entry((*reason).to_owned())
                .or_insert(0) += count;
        }
        self.counters.events_refused += rejected_total;

        let mut reasons = Map::new();
        for (reason, count) in &batch.rejected {
            reasons.insert((*reason).to_owned(), Value::from(*count));
        }
        let mut reply = Map::new();
        reply.insert("accepted".into(), Value::from(written));
        reply.insert("rejected".into(), Value::from(rejected_total));
        if failed > 0 {
            reply.insert("not_stored".into(), Value::from(failed));
        }
        if !reasons.is_empty() {
            reply.insert("reasons".into(), Value::Object(reasons));
        }
        Handled::Reply(finish(reply))
    }
}

/// One stored row.
///
/// Attributes are split by type into `num` / `str` / `flag` rather than left as one mixed object.
/// The warehouse gets three typed `Map` columns instead of a JSON blob it must re-parse per query,
/// and a value that changed type between two client builds shows up as a column mismatch rather
/// than as a silently coerced number.
fn row(
    batch: &CleanBatch,
    clean: &crate::event::CleanEvent,
    subject: &str,
    geo: Geo,
    received_at: u64,
) -> String {
    let mut num = Map::new();
    let mut text = Map::new();
    let mut flag = Map::new();
    for (key, value) in &clean.attrs {
        match value {
            Value::Bool(b) => {
                flag.insert(key.clone(), Value::from(u8::from(*b)));
            }
            Value::Number(n) => {
                num.insert(key.clone(), Value::Number(n.clone()));
            }
            other => {
                text.insert(key.clone(), other.clone());
            }
        }
    }

    let mut object = Map::new();
    object.insert("schema".into(), Value::from(ROW_SCHEMA));
    object.insert("received_at".into(), Value::from(received_at));
    object.insert("at".into(), Value::from(clean.at_millis));
    object.insert("source".into(), Value::from(batch.source.as_str()));
    object.insert("subject".into(), Value::from(subject));
    object.insert("agent".into(), Value::from(batch.agent.clone()));
    object.insert("country".into(), Value::from(geo.country_str()));
    object.insert("asn".into(), Value::from(geo.asn));
    object.insert("event".into(), Value::from(clean.name));
    object.insert("num".into(), Value::Object(num));
    object.insert("str".into(), Value::Object(text));
    object.insert("flag".into(), Value::Object(flag));
    Value::Object(object).to_string()
}

fn finish(reply: Map<String, Value>) -> Vec<u8> {
    Value::Object(reply).to_string().into_bytes()
}

/// `{"v":1,"probe":true}` — the liveness frame `--healthcheck` sends.
///
/// Separate from an empty batch on purpose: a probe must not need an install id, a consent token,
/// or a quota slot, so that a monitoring system polling every few seconds cannot exhaust the same
/// budget real clients share.
fn is_probe(frame: &[u8]) -> bool {
    serde_json::from_slice::<Value>(frame)
        .ok()
        .and_then(|value| {
            value
                .as_object()
                .and_then(|o| o.get("probe").and_then(Value::as_bool))
        })
        .unwrap_or(false)
}

fn probe_reply() -> Vec<u8> {
    let mut reply = Map::new();
    reply.insert("ok".into(), Value::from(true));
    reply.insert("service".into(), Value::from("nodera-telemetry"));
    reply.insert("version".into(), Value::from(env!("NODERA_VERSION")));
    reply.insert("row_schema".into(), Value::from(ROW_SCHEMA));
    finish(reply)
}

/// The batch's own claim about how many events it carries, read without trusting anything else.
fn claimed_event_count(frame: &[u8]) -> u32 {
    serde_json::from_slice::<Value>(frame)
        .ok()
        .and_then(|value| {
            value
                .as_object()
                .and_then(|o| o.get("events"))
                .and_then(Value::as_array)
                .map(|events| events.len().min(u32::MAX as usize) as u32)
        })
        .unwrap_or(1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::event::BatchError;
    use std::sync::{Arc, Mutex};

    const INSTALL: &str = "0123456789abcdef0123456789abcdef";
    const NOW: u64 = 1_800_000_000_000;

    fn config() -> Config {
        Config {
            subject_secret: "0123456789abcdef0123".to_owned(),
            ..Config::default()
        }
    }

    fn geo_table() -> GeoTable {
        GeoTable::parse("198.51.100.0/24,br,264644\n").0
    }

    /// The sink lives inside a boxed trait object once the service owns it, so tests keep a shared
    /// handle to the same buffer and assert on the **written text** rather than on an in-memory
    /// structure that merely agrees with it.
    #[derive(Clone, Default)]
    struct SharedSink(Arc<Mutex<Vec<String>>>);

    impl EventSink for SharedSink {
        fn write_line(&mut self, line: &str, _now: u64) -> std::io::Result<()> {
            self.0.lock().unwrap().push(line.to_owned());
            Ok(())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    fn ingest_with(config: Config, sink: SharedSink) -> Ingest {
        Ingest::new(config, geo_table(), Box::new(sink))
    }

    fn submit(ingest: &mut Ingest, body: &str, ip: &str) -> Value {
        let frame = body.replace('\'', "\"");
        let Handled::Reply(reply) =
            ingest.handle_frame(frame.as_bytes(), Some(ip.parse().unwrap()), NOW);
        serde_json::from_slice(&reply).unwrap()
    }

    fn batch(events: &str) -> String {
        format!(
            "{{'v':1,'src':'peer','consent':'granted','install':'{INSTALL}',\
              'agent':'nodera-worker 0.1.0','events':[{events}]}}"
        )
    }

    #[test]
    fn an_accepted_batch_writes_one_row_per_event() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(config(), sink.clone());
        let reply = submit(
            &mut ingest,
            &batch(
                "{'name':'feature.use','attrs':{'feature':'share_gui','count':2}},\
                 {'name':'session.end','attrs':{'minutes_bucket':30,'clean':true}}",
            ),
            "198.51.100.7",
        );
        assert_eq!(reply["accepted"], Value::from(2));
        let lines = sink.0.lock().unwrap().clone();
        assert_eq!(lines.len(), 2);
    }

    #[test]
    fn a_row_carries_the_country_and_never_the_address() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(config(), sink.clone());
        submit(
            &mut ingest,
            &batch("{'name':'feature.use','attrs':{'feature':'selftest','count':1}}"),
            "198.51.100.7",
        );
        let line = sink.0.lock().unwrap()[0].clone();
        let row: Value = serde_json::from_str(&line).unwrap();
        assert_eq!(row["country"], Value::from("BR"));
        assert_eq!(row["asn"], Value::from(264644));
        assert!(
            !line.contains("198.51.100"),
            "the source address must not survive into a row: {line}"
        );
    }

    #[test]
    fn the_install_identifier_is_replaced_by_a_rotating_subject() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(config(), sink.clone());
        submit(
            &mut ingest,
            &batch("{'name':'feature.use','attrs':{'feature':'selftest','count':1}}"),
            "198.51.100.7",
        );
        let line = sink.0.lock().unwrap()[0].clone();
        assert!(!line.contains(INSTALL), "{line}");
        let row: Value = serde_json::from_str(&line).unwrap();
        assert_eq!(row["subject"].as_str().unwrap().len(), 16);
    }

    #[test]
    fn attributes_are_split_into_typed_maps() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(config(), sink.clone());
        submit(
            &mut ingest,
            &batch("{'name':'world.join','attrs':{'ok':true,'path':'relayed','seconds_bucket':4}}"),
            "198.51.100.7",
        );
        let row: Value = serde_json::from_str(&sink.0.lock().unwrap()[0]).unwrap();
        assert_eq!(row["num"]["seconds_bucket"], Value::from(4));
        assert_eq!(row["str"]["path"], Value::from("relayed"));
        assert_eq!(row["flag"]["ok"], Value::from(1));
    }

    #[test]
    fn a_batch_without_consent_writes_nothing_and_says_why() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(config(), sink.clone());
        let reply = submit(
            &mut ingest,
            &format!(
                "{{'v':1,'src':'peer','install':'{INSTALL}','events':\
                  [{{'name':'feature.use','attrs':{{'feature':'selftest','count':1}}}}]}}"
            ),
            "198.51.100.7",
        );
        assert_eq!(reply["error"], Value::from(BatchError::NoConsent.as_str()));
        assert!(sink.0.lock().unwrap().is_empty());
    }

    #[test]
    fn refused_events_are_reported_back_per_reason() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(config(), sink.clone());
        let reply = submit(
            &mut ingest,
            &batch(
                "{'name':'world.name','attrs':{}},\
                 {'name':'feature.use','attrs':{'feature':'selftest','count':1,'note':'hi'}}",
            ),
            "198.51.100.7",
        );
        assert_eq!(reply["accepted"], Value::from(1));
        assert_eq!(reply["rejected"], Value::from(2));
        assert_eq!(reply["reasons"]["unknown_event"], Value::from(1));
        assert_eq!(reply["reasons"]["undeclared_attr"], Value::from(1));
    }

    #[test]
    fn the_quota_refuses_a_flooding_source_without_touching_the_others() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(
            Config {
                per_ip_batch_quota: 2,
                ..config()
            },
            sink.clone(),
        );
        let body = batch("{'name':'feature.use','attrs':{'feature':'selftest','count':1}}");
        assert_eq!(
            submit(&mut ingest, &body, "198.51.100.7")["accepted"],
            Value::from(1)
        );
        assert_eq!(
            submit(&mut ingest, &body, "198.51.100.7")["accepted"],
            Value::from(1)
        );
        assert_eq!(
            submit(&mut ingest, &body, "198.51.100.7")["error"],
            Value::from("quota_batches")
        );
        assert_eq!(
            submit(&mut ingest, &body, "198.51.100.8")["accepted"],
            Value::from(1),
            "one noisy source must not silence a quiet one"
        );
    }

    #[test]
    fn a_probe_is_answered_without_consent_an_install_id_or_a_quota_slot() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(
            Config {
                per_ip_batch_quota: 1,
                ..config()
            },
            sink.clone(),
        );
        for _ in 0..5 {
            let reply = submit(&mut ingest, "{'v':1,'probe':true}", "198.51.100.7");
            assert_eq!(reply["ok"], Value::from(true));
            assert_eq!(reply["service"], Value::from("nodera-telemetry"));
        }
        assert!(sink.0.lock().unwrap().is_empty());
        assert_eq!(ingest.counters().batches_refused, 0);
    }

    #[test]
    fn counters_account_for_everything_that_arrived() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(config(), sink.clone());
        submit(
            &mut ingest,
            &batch(
                "{'name':'feature.use','attrs':{'feature':'selftest','count':1}},\
                 {'name':'world.name','attrs':{}}",
            ),
            "198.51.100.7",
        );
        submit(&mut ingest, "not json", "198.51.100.7");
        let counters = ingest.counters();
        assert_eq!(counters.batches_accepted, 1);
        assert_eq!(counters.batches_refused, 1);
        assert_eq!(counters.events_written, 1);
        assert_eq!(counters.events_refused, 2);
        assert_eq!(counters.reasons["unknown_event"], 1);
        assert_eq!(counters.reasons["malformed"], 1);
        assert!(counters.bytes_in > 0);
    }

    #[test]
    fn an_unknown_source_address_is_recorded_as_unknown_rather_than_omitted() {
        let sink = SharedSink::default();
        let mut ingest = ingest_with(config(), sink.clone());
        let frame = batch("{'name':'feature.use','attrs':{'feature':'selftest','count':1}}")
            .replace('\'', "\"");
        let Handled::Reply(_) = ingest.handle_frame(frame.as_bytes(), None, NOW);
        let row: Value = serde_json::from_str(&sink.0.lock().unwrap()[0]).unwrap();
        assert_eq!(row["country"], Value::from("ZZ"));
        assert_eq!(row["asn"], Value::from(0));
    }
}
