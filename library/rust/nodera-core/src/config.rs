//! The `NODERA-CONFIG` lane: the app's settings document, translated into the subset the worker can
//! act on, pushed over the loopback control socket.
//!
//! **Why the app pushes and the worker never reads a file.** The worker already takes configuration
//! from its process environment at startup. Giving it a config file to watch would make three
//! sources of truth (env, file, verb) that must agree, and the one that loses a race wins silently.
//! Instead the worker holds runtime configuration **in memory only**, and this app re-pushes on
//! every offline→online edge (see [`crate::control::monitor`]). A worker that forgot — because it
//! crashed, was restarted, or was started by hand before the app — is indistinguishable from a
//! worker that just came up, and all of them are fixed by the same mechanism.
//!
//! **Wire contract** (mirrored by `ControlHandler.applyConfig` on the Java side):
//! ```text
//!   app → worker:  NODERA-CONFIG <protocolVersion> <configJsonBase64>   (set)
//!   app → worker:  NODERA-CONFIG <protocolVersion>                      (read back)
//!   worker → app:  {"applied":[…],"restart_required":[…],"rejected":{key:reason}}
//!   worker → app:  NODERA-ERR unknown verb        (a worker older than this app)
//! ```
//!
//! Base64 rather than trailing raw JSON because `ControlServer` splits request lines on `\s+`;
//! base64's alphabet contains no whitespace, so the payload cannot be corrupted by construction.
//!
//! **The reply is load-bearing, not decorative.** `applied` is the only evidence the app has that a
//! setting is in force, and [`crate::settings::setting_status`] refuses to badge anything "live"
//! without it — so an app talking to an older worker degrades to "this worker cannot do that"
//! automatically instead of claiming an enforcement nobody is performing.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine as _;
use serde::{Deserialize, Serialize};
use tokio::sync::Notify;

use crate::control::{self, PROTOCOL_VERSION};
use crate::power::PauseHandle;
use crate::settings::{Settings, SettingsHandle, WorkerReport};

const CONFIG: &str = "NODERA-CONFIG";

/// How long a burst of saves is coalesced before one push goes out.
///
/// Dragging a bandwidth slider emits a save per frame; without this each one would open its own TCP
/// connection to the worker — ~50 connections for one gesture, every one of them immediately stale.
/// 250 ms is below the threshold at which a settings change feels delayed and far above the
/// interval between drag events.
const DEBOUNCE: Duration = Duration::from_millis(250);

/// The configuration the worker can act on, keyed by the **same dotted names the reply uses**.
///
/// Deliberately keyed like the settings document rather than like the worker's internals: `applied`
/// and `rejected` come back keyed this way, so the app can match a reply to a UI control without a
/// translation table that could drift. Field order here is the JSON's field order — the golden test
/// pins it so a reordering that would churn every diff has to be deliberate.
///
/// Appearance and auto-start are absent on purpose: they are enforced inside this app, and the
/// worker has no business being told about them.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkerConfig {
    /// The one flag behind battery rules, the tray's Pause seeding, and manual pause.
    #[serde(rename = "behavior.transfers_paused")]
    pub transfers_paused: bool,
    #[serde(rename = "network.default_trackers")]
    pub default_trackers: Vec<String>,
    #[serde(rename = "network.unlimited_connections_only")]
    pub unlimited_connections_only: bool,
    #[serde(rename = "network.max_connections")]
    pub max_connections: u32,
    #[serde(rename = "network.max_connections_per_world")]
    pub max_connections_per_world: u32,
    #[serde(rename = "network.max_upload_slots_per_world")]
    pub max_upload_slots_per_world: u32,
    #[serde(rename = "network.max_upload_bytes_per_sec")]
    pub max_upload_bytes_per_sec: u64,
    #[serde(rename = "network.max_download_bytes_per_sec")]
    pub max_download_bytes_per_sec: u64,
    /// `random` or `start-end`. Sent even though it can only take effect at restart, so the worker
    /// gets to *say* `restart_required` rather than the app guessing on its behalf.
    #[serde(rename = "network.port_range")]
    pub port_range: String,
    /// Relay endpoints, comma-separated — the shape the worker's `rendezvous_endpoints` handler
    /// parses. Also restart-scoped, and also sent so the worker is the one that says so.
    #[serde(rename = "network.rendezvous_endpoints")]
    pub rendezvous_endpoints: String,
    /// Empty means "the worker's own default"; sent verbatim so the worker can answer honestly.
    #[serde(rename = "storage.peer_worlds_dir")]
    pub peer_worlds_dir: String,
    /// Disk this node will spend on other people's worlds. 0 = the worker's default.
    #[serde(rename = "storage.replication_budget_bytes")]
    pub replication_budget_bytes: u64,
    /// Replication sweep period in seconds. 0 = the worker's default.
    #[serde(rename = "storage.replication_sweep_seconds")]
    pub replication_sweep_seconds: u64,
}

impl WorkerConfig {
    /// Project the user's settings plus the live pause state onto the worker-facing subset.
    ///
    /// `transfers_paused` is not a settings field: it is computed from battery state and the tray
    /// toggle, both of which live in this process. See [`crate::power`] for why the app decides and
    /// the worker merely obeys.
    pub fn of(settings: &Settings, transfers_paused: bool) -> Self {
        Self {
            transfers_paused,
            // Merged with the stores', so adding a store takes effect on a running worker rather
            // than at the next restart. `merged` puts the user's own first and deduplicates.
            default_trackers: crate::stores::merged(
                &settings.network.default_trackers,
                &settings.network.tracker_stores,
                crate::stores::ServiceKind::Tracker,
            ),
            unlimited_connections_only: settings.network.unlimited_connections_only,
            max_connections: settings.network.max_connections,
            max_connections_per_world: settings.network.max_connections_per_world,
            max_upload_slots_per_world: settings.network.max_upload_slots_per_world,
            max_upload_bytes_per_sec: settings.network.max_upload_bytes_per_sec,
            max_download_bytes_per_sec: settings.network.max_download_bytes_per_sec,
            port_range: if settings.network.use_random_port {
                "random".to_owned()
            } else {
                format!(
                    "{}-{}",
                    settings.network.port_range_start, settings.network.port_range_end
                )
            },
            rendezvous_endpoints: settings.network.rendezvous_endpoints.join(","),
            peer_worlds_dir: settings.storage.peer_worlds_dir.clone(),
            replication_budget_bytes: settings.storage.replication_budget_bytes,
            replication_sweep_seconds: settings.storage.replication_sweep_seconds,
        }
    }

    /// The request line for this configuration.
    pub fn request_line(&self) -> String {
        let json = serde_json::to_string(self).expect("WorkerConfig is always serialisable");
        let payload = base64::engine::general_purpose::STANDARD.encode(json);
        format!("{CONFIG} {PROTOCOL_VERSION} {payload}")
    }
}

/// The worker's verdict, per key.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConfigReply {
    /// Keys now in force. **The only evidence the app accepts for a "live" badge.**
    #[serde(default)]
    pub applied: Vec<String>,
    /// Keys stored but needing a worker restart to take effect.
    #[serde(default)]
    pub restart_required: Vec<String>,
    /// Keys the worker refuses, with its own reason. A `BTreeMap` so the UI order is stable.
    #[serde(default)]
    pub rejected: BTreeMap<String, String>,
}

/// The last push's outcome, as the UI renders it.
///
/// Cached rather than recomputed on demand because a push is a network round-trip: the Settings
/// screen asks for this on every render, and re-pushing to answer would turn a paint into traffic.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct ConfigStatus {
    /// `true` when the worker **answered** the last push — including when it answered by refusing.
    /// It is therefore the app's liveness evidence, not its success flag; success is `error` empty.
    pub pushed: bool,
    /// `false` when the worker answered `NODERA-ERR unknown verb` — it predates this app.
    pub supported: bool,
    pub applied: Vec<String>,
    pub restart_required: Vec<String>,
    pub rejected: BTreeMap<String, String>,
    /// Empty when the last push succeeded; otherwise the worker's own words, verbatim.
    ///
    /// Surfaced as *soft* status, never as a save failure: the settings are already on disk by the
    /// time a push is attempted, so a worker being offline costs the user nothing but a delay.
    pub error: String,
}

impl ConfigStatus {
    /// The evidence [`crate::settings::setting_status`] is allowed to reason from.
    pub fn report(&self) -> WorkerReport<'_> {
        WorkerReport {
            reachable: self.pushed,
            supports_config: self.pushed && self.supported,
            applied: &self.applied,
            restart_required: &self.restart_required,
            rejected: &self.rejected,
        }
    }
}

/// Thread-safe holder for [`ConfigStatus`], sitting beside `SettingsHandle`.
#[derive(Default)]
pub struct ConfigStatusHandle {
    inner: Mutex<ConfigStatus>,
}

impl ConfigStatusHandle {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn snapshot(&self) -> ConfigStatus {
        self.inner.lock().unwrap().clone()
    }

    fn set(&self, status: ConfigStatus) {
        *self.inner.lock().unwrap() = status;
    }
}

/// Signal that asks for a configuration push. Newtyped for the same reason as
/// [`crate::daemon::RestartSignal`]: Tauri keys managed state by type, so two bare `Arc<Notify>`
/// states would silently collapse into one.
///
/// Holds an `Arc<Notify>` rather than a `Notify` so [`crate::control::monitor`] can be handed the
/// bare notify — the poll loop has no business knowing what configuration is.
#[derive(Default)]
pub struct PushSignal(pub Arc<Notify>);

impl PushSignal {
    /// Ask for a push. Cheap, synchronous, and safe to call from a Tauri command: `notify_one`
    /// leaves a permit when nobody is waiting, so a request raised while a push is in flight is
    /// honoured by the next loop iteration rather than lost.
    pub fn request(&self) {
        self.0.notify_one();
    }
}

/// Send one configuration to the worker and interpret its reply.
///
/// The `unknown verb` case is separated out because it is the one failure the user can act on: it
/// does not mean the push failed, it means the worker is older than the app.
pub async fn push_once(control_addr: &str, config: &WorkerConfig) -> ConfigStatus {
    match control::request(control_addr, config.request_line()).await {
        Ok(line) => match serde_json::from_str::<ConfigReply>(&line) {
            Ok(reply) => ConfigStatus {
                pushed: true,
                supported: true,
                applied: reply.applied,
                restart_required: reply.restart_required,
                rejected: reply.rejected,
                error: String::new(),
            },
            Err(e) => ConfigStatus {
                pushed: true,
                supported: false,
                error: format!("the worker replied with something this app cannot read: {e}"),
                ..ConfigStatus::default()
            },
        },
        Err(reason) if is_unknown_verb(&reason) => ConfigStatus {
            pushed: true,
            supported: false,
            error: "this worker is older than the app, so it cannot be configured from here \
                    — restart it to pick up the bundled version"
                .to_owned(),
            ..ConfigStatus::default()
        },
        Err(reason) => ConfigStatus {
            pushed: false,
            supported: false,
            error: reason,
            ..ConfigStatus::default()
        },
    }
}

/// Recognise the "your worker predates this verb" refusal.
///
/// Matched on substrings rather than an exact string because the wording belongs to the Java side
/// and is not part of the wire contract; only the *shape* (`NODERA-ERR …unknown…verb…`) is.
fn is_unknown_verb(reason: &str) -> bool {
    let lower = reason.to_ascii_lowercase();
    // Both halves of the shape are required. A bare `unsupported` used to qualify on its own, so a
    // *value*-level refusal — "unsupported tracker scheme" — was reported to the user as "this
    // worker is older than the app", pointing them at an upgrade that would fix nothing.
    (lower.contains("unknown") || lower.contains("unsupported"))
        && (lower.contains("verb") || lower.contains("command"))
}

/// Everything one push needs, so the debounce loop below stays a loop and nothing else.
pub struct ConfigPusher {
    pub control_addr: String,
    pub settings: Arc<SettingsHandle>,
    pub pause: Arc<PauseHandle>,
    pub status: Arc<ConfigStatusHandle>,
}

impl ConfigPusher {
    /// Build the current configuration, push it, and cache the verdict.
    pub async fn push(&self) -> ConfigStatus {
        let config = WorkerConfig::of(&self.settings.snapshot(), self.pause.paused());
        let status = push_once(&self.control_addr, &config).await;
        self.status.set(status.clone());
        status
    }
}

/// Coalesce push requests and send at most one per [`DEBOUNCE`] window.
///
/// Runs forever as a background task. Written as a wait-then-settle loop rather than as a timer
/// spawned per request so that a save arriving from a *synchronous* Tauri command needs nothing but
/// [`PushSignal::request`] — no runtime handle, no task spawn on the UI's critical path.
pub async fn debounce_loop(pusher: Arc<ConfigPusher>, signal: Arc<PushSignal>) {
    loop {
        signal.0.notified().await;
        // Keep extending the window while requests keep arriving; a drag becomes one push.
        loop {
            tokio::select! {
                _ = tokio::time::sleep(DEBOUNCE) => break,
                _ = signal.0.notified() => continue,
            }
        }
        pusher.push().await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Golden JSON. This string is the wire contract with the Java `applyConfig` handler: if it
    /// changes, the two sides have to change together, and a test that has to be edited is the
    /// cheapest possible way to notice.
    ///
    /// Deliberately built with **no tracker stores**. `Settings::default()` now carries the built-in
    /// store, whose contents come from the `services` branch — a list maintained by pull request
    /// from outside the project. Pinning the golden string to that data would mean a contributor
    /// adding their own tracker breaks a Rust wire-contract test, which teaches exactly the wrong
    /// lesson about what this test is for. The merge itself is asserted separately below.
    #[test]
    fn default_settings_serialise_to_the_agreed_json() {
        let mut settings = Settings::default();
        settings.network.tracker_stores = vec![];
        let config = WorkerConfig::of(&settings, false);
        assert_eq!(
            serde_json::to_string(&config).unwrap(),
            r#"{"behavior.transfers_paused":false,"network.default_trackers":["tcp://127.0.0.1:25600"],"network.unlimited_connections_only":false,"network.max_connections":200,"network.max_connections_per_world":50,"network.max_upload_slots_per_world":4,"network.max_upload_bytes_per_sec":0,"network.max_download_bytes_per_sec":0,"network.port_range":"random","network.rendezvous_endpoints":"","storage.peer_worlds_dir":"","storage.replication_budget_bytes":0,"storage.replication_sweep_seconds":0}"#
        );
    }

    /// A store's trackers reach a *running* worker, not only the next one that starts.
    ///
    /// `network.default_trackers` is a live key — `WorkerControlHandler` applies it straight to
    /// `TrackerClient.setEndpoints`. If the push path used the raw setting while the spawn path used
    /// the merged one, adding a store would appear to do nothing until the worker was restarted, and
    /// the user would have no way to tell which of the two they were looking at.
    #[test]
    fn a_stores_trackers_are_pushed_to_a_running_worker() {
        let mut settings = Settings::default();
        settings.network.default_trackers = vec!["tcp://mine.example:25600".to_owned()];
        let config = WorkerConfig::of(&settings, false);

        assert_eq!(
            config.default_trackers.first().map(String::as_str),
            Some("tcp://mine.example:25600"),
            "the user's own entry keeps its place"
        );
        assert!(
            config.default_trackers.len() > 1,
            "the built-in store must contribute: {:?}",
            config.default_trackers
        );
    }

    /// Every key this app sends has to be one the worker's `applyConfig` switch recognises;
    /// anything else comes back `rejected: "unknown setting"` and silently badges as broken.
    /// The two replication keys were accepted by the worker and sent by nobody until now.
    #[test]
    fn every_key_sent_is_one_the_worker_handles() {
        let json = serde_json::to_value(WorkerConfig::of(&Settings::default(), false)).unwrap();
        let sent: BTreeMap<String, serde_json::Value> = serde_json::from_value(json).unwrap();
        // Mirrors WorkerControlHandler.applyConfig's accepted set (applied + restart_required +
        // permanently-rejected). A key outside it is a bug in this file, not in the worker.
        let handled = [
            "behavior.transfers_paused",
            "network.default_trackers",
            "network.max_connections",
            "network.max_connections_per_world",
            "network.max_upload_slots_per_world",
            "network.max_upload_bytes_per_sec",
            "network.max_download_bytes_per_sec",
            "network.port_range",
            "network.rendezvous_endpoints",
            "network.unlimited_connections_only",
            "storage.peer_worlds_dir",
            "storage.replication_budget_bytes",
            "storage.replication_sweep_seconds",
        ];
        for key in sent.keys() {
            assert!(handled.contains(&key.as_str()), "{key} is not a worker key");
        }
    }

    #[test]
    fn rendezvous_endpoints_go_out_comma_separated() {
        let mut settings = Settings::default();
        settings.network.rendezvous_endpoints =
            vec!["tcp://a:1".to_owned(), "tcp://b:2".to_owned()];
        assert_eq!(
            WorkerConfig::of(&settings, false).rendezvous_endpoints,
            "tcp://a:1,tcp://b:2"
        );
    }

    #[test]
    fn a_fixed_port_range_is_sent_as_start_end() {
        let mut settings = Settings::default();
        settings.network.use_random_port = false;
        settings.network.port_range_start = 37000;
        settings.network.port_range_end = 37009;
        assert_eq!(WorkerConfig::of(&settings, false).port_range, "37000-37009");
    }

    #[test]
    fn the_pause_flag_comes_from_the_app_not_from_the_settings_document() {
        assert!(WorkerConfig::of(&Settings::default(), true).transfers_paused);
        assert!(!WorkerConfig::of(&Settings::default(), false).transfers_paused);
    }

    /// The payload must survive `ControlServer`'s whitespace split, which is the entire reason it
    /// is base64 and not raw JSON.
    #[test]
    fn the_request_line_is_exactly_three_whitespace_separated_tokens() {
        let mut settings = Settings::default();
        settings.network.default_trackers =
            vec!["tcp://a:1".to_owned(), "tcp://b with spaces:2".to_owned()];
        let line = WorkerConfig::of(&settings, false).request_line();
        let tokens: Vec<&str> = line.split_whitespace().collect();
        assert_eq!(tokens.len(), 3, "{line}");
        assert_eq!(tokens[0], CONFIG);
        assert_eq!(tokens[1], PROTOCOL_VERSION.to_string());

        let decoded = base64::engine::general_purpose::STANDARD
            .decode(tokens[2])
            .unwrap();
        let round_trip: WorkerConfig = serde_json::from_slice(&decoded).unwrap();
        assert_eq!(round_trip, WorkerConfig::of(&settings, false));
    }

    #[test]
    fn a_reply_parses_applied_restart_and_rejected() {
        let reply: ConfigReply = serde_json::from_str(
            r#"{"applied":["network.max_upload_bytes_per_sec","behavior.transfers_paused"],
                "restart_required":["network.port_range"],
                "rejected":{"network.max_connections_per_world":"transport has no world dimension"}}"#,
        )
        .unwrap();
        assert_eq!(reply.applied.len(), 2);
        assert_eq!(reply.restart_required, vec!["network.port_range"]);
        assert_eq!(
            reply.rejected["network.max_connections_per_world"],
            "transport has no world dimension"
        );
    }

    /// A worker that answers only `{"applied":[]}` must not fail the parse — the reply is additive
    /// on the Java side and an older shape has to keep working.
    #[test]
    fn a_partial_reply_fills_in_empties() {
        let reply: ConfigReply = serde_json::from_str(r#"{"applied":[]}"#).unwrap();
        assert!(reply.applied.is_empty() && reply.rejected.is_empty());
    }

    #[test]
    fn an_old_worker_is_recognised_from_its_refusal_wording() {
        assert!(is_unknown_verb("unknown verb"));
        assert!(is_unknown_verb("Unknown command NODERA-CONFIG"));
        assert!(is_unknown_verb("unsupported verb"));
        assert!(!is_unknown_verb(
            "the worker is unreachable on 127.0.0.1:25610"
        ));
        // A refusal about a *value* is not evidence about the worker's age, and telling the user to
        // upgrade would send them after a fix that does not exist.
        assert!(!is_unknown_verb("unsupported tracker scheme 'udp'"));
    }
}
