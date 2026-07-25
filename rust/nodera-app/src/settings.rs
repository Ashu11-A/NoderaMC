//! Persisted user settings for the companion app.
//!
//! Settings live in one JSON file under the OS config dir (`~/.config/nodera/settings.json` on
//! Linux). Every field carries a `#[serde(default)]` so a file written by an older build still
//! loads — a settings file that fails to parse would otherwise reset a user's whole configuration,
//! which is a far worse outcome than ignoring one unknown key.
//!
//! **What is enforced where.** Persisting a preference and acting on it are different jobs, and
//! this module only does the first. Which of the two happened to any given key is answered by
//! [`setting_status`], and the answer is *computed from the worker's own reply* rather than
//! hard-coded — see the invariant documented on [`Enforcement`]. The UI renders that answer beside
//! each control, so it can never imply a limit is in force when nothing is applying it.

use std::collections::{BTreeMap, BTreeSet};
use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};

/// Which colour scheme the UI renders in.
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum Theme {
    /// Follow the OS preference.
    #[default]
    System,
    Dark,
    Light,
}

/// Appearance settings.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Appearance {
    pub theme: Theme,
    /// Desktop notifications for node events (peer joined, world recovered, worker lost).
    pub notifications: bool,
}

impl Default for Appearance {
    fn default() -> Self {
        Self {
            theme: Theme::System,
            notifications: true,
        }
    }
}

/// Behaviour + power management.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Behavior {
    /// Launch the companion at login (wired to the autostart plugin).
    pub auto_start: bool,
    /// Only move data while the device is on mains power.
    pub only_when_charging: bool,
    /// Enable the battery floor below which transfers stop.
    pub battery_control: bool,
    /// The floor itself, as a battery percentage.
    pub battery_threshold_percent: u8,
    /// Apply the power rules even while Minecraft is running.
    ///
    /// Off by default on purpose: pausing the swarm mid-session is the one moment a player would
    /// actually notice, so honouring the battery floor during play has to be asked for. The UI only
    /// reveals this once `battery_control` is on, because on its own it would mean nothing.
    pub power_rules_during_game: bool,
}

impl Default for Behavior {
    fn default() -> Self {
        Self {
            auto_start: false,
            only_when_charging: false,
            battery_control: false,
            battery_threshold_percent: 20,
            power_rules_during_game: false,
        }
    }
}

/// Connection + bandwidth limits.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Network {
    /// Discovery services this node announces to and queries. `tcp://host:port` or `udp://host:port`;
    /// a bare `host:port` means TCP.
    pub default_trackers: Vec<String>,
    /// Refuse peers that impose a connection cap of their own.
    pub unlimited_connections_only: bool,
    /// Bind an OS-assigned port instead of one from the range below.
    pub use_random_port: bool,
    pub port_range_start: u16,
    pub port_range_end: u16,
    /// 0 means unlimited, for every field below.
    pub max_connections: u32,
    pub max_connections_per_world: u32,
    pub max_upload_slots_per_world: u32,
    /// Bytes per second; 0 = unlimited.
    pub max_upload_bytes_per_sec: u64,
    pub max_download_bytes_per_sec: u64,
}

impl Default for Network {
    fn default() -> Self {
        Self {
            default_trackers: vec!["tcp://127.0.0.1:25600".to_owned()],
            unlimited_connections_only: false,
            use_random_port: true,
            port_range_start: 37000,
            port_range_end: 57010,
            max_connections: 200,
            max_connections_per_world: 50,
            max_upload_slots_per_world: 4,
            max_upload_bytes_per_sec: 0,
            max_download_bytes_per_sec: 0,
        }
    }
}

/// Where content this node holds for other people is written.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Storage {
    /// Empty = the worker's own default (`~/.nodera/archive`).
    pub peer_worlds_dir: String,
}

impl Default for Storage {
    fn default() -> Self {
        Self {
            peer_worlds_dir: String::new(),
        }
    }
}

/// The whole settings document.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct Settings {
    pub appearance: Appearance,
    pub behavior: Behavior,
    pub network: Network,
    pub storage: Storage,
}

impl Settings {
    /// Keys the UI must label as stored-but-not-yet-applied.
    ///
    /// Thin shim over [`setting_status`], kept so an older frontend build keeps working. The real
    /// API is [`setting_status`], which answers *why* and can say "this worker cannot", a
    /// distinction a flat list of names structurally cannot carry.
    pub fn unenforced(report: &WorkerReport<'_>) -> Vec<String> {
        setting_status(report)
            .into_iter()
            .filter(|status| status.state != SettingState::Live)
            .map(|status| status.key)
            .collect()
    }

    /// Every settings key, derived from the document itself rather than written out by hand.
    ///
    /// Derived so that adding a field to any section breaks the coverage test in this module until
    /// [`ENFORCEMENT`] gains a row for it — a new control that silently claims nothing about
    /// whether it works is exactly the failure this whole mechanism exists to prevent.
    ///
    /// The one collapse: `port_range_start` + `port_range_end` are a single control in the UI and a
    /// single value on the wire, so they report as one key, `network.port_range`.
    // Only the drift test consults this today; it is public API all the same, because the answer to
    // "what are the settings keys" must have exactly one implementation.
    #[allow(dead_code)]
    pub fn keys() -> BTreeSet<String> {
        let document = serde_json::to_value(Settings::default())
            .expect("the settings document is always serialisable");
        let mut keys = BTreeSet::new();
        for (section, fields) in document.as_object().expect("sections") {
            for field in fields.as_object().expect("fields").keys() {
                let field = if field.starts_with("port_range_") {
                    "port_range"
                } else {
                    field.as_str()
                };
                keys.insert(format!("{section}.{field}"));
            }
        }
        keys
    }

    /// Reject values that would produce a broken configuration rather than storing them.
    pub fn validate(&self) -> Result<(), String> {
        if !self.network.use_random_port {
            if self.network.port_range_start == 0 || self.network.port_range_end == 0 {
                return Err("port range bounds must be above 0".to_owned());
            }
            if self.network.port_range_start > self.network.port_range_end {
                return Err("port range start must not exceed its end".to_owned());
            }
        }
        if self.behavior.battery_threshold_percent > 100 {
            return Err("battery threshold must be a percentage (0–100)".to_owned());
        }
        for tracker in &self.network.default_trackers {
            let route = tracker
                .split_once("://")
                .map(|(scheme, rest)| {
                    if scheme != "tcp" && scheme != "udp" {
                        return Err(format!("unknown tracker scheme '{scheme}' in {tracker}"));
                    }
                    Ok(rest)
                })
                .unwrap_or(Ok(tracker.as_str()))?;
            let Some((host, port)) = route.rsplit_once(':') else {
                return Err(format!("tracker '{tracker}' is not host:port"));
            };
            if host.is_empty() || port.parse::<u16>().map(|p| p == 0).unwrap_or(true) {
                return Err(format!("tracker '{tracker}' is not host:port"));
            }
        }
        Ok(())
    }
}

/* ------------------------------------------------------------- what is actually enforced, and by whom */

/// How a settings key is meant to reach reality.
///
/// This table states *intent*. It is deliberately not the answer the UI renders — [`setting_status`]
/// combines it with what the worker actually reported, under one invariant:
///
/// > **A key is reported `live` only if the currently-connected worker named it in `applied`.**
///
/// That is what makes an old worker behind a new app degrade automatically: the app has no path by
/// which it can claim an enforcement the worker did not confirm performing. The exception is
/// [`Enforcement::Local`], where there is no worker in the loop at all and asking one for
/// confirmation would be theatre.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Enforcement {
    /// Enforced inside this app (theme, notifications, the login item). No worker involved.
    Local {
        /// How it is applied — shown so the badge is informative rather than merely green.
        how: &'static str,
    },
    /// Pushed over `NODERA-CONFIG` and live once the worker confirms the named key.
    ///
    /// `confirmed_by` is usually the key itself. It differs where several user-facing controls feed
    /// one worker-visible value: the four battery controls are all decided *here* and reach the
    /// worker only as `behavior.transfers_paused`, so that is the key whose confirmation proves
    /// the whole rule is being honoured.
    Live { confirmed_by: &'static str },
    /// Stored now, in force after the worker restarts — the `final`-field and bind-time values.
    AtRestart,
    /// Cannot be honoured, and not "yet": the reason is structural, and is shown to the user.
    Never { reason: &'static str },
}

/// Every settings key, and how it is meant to be enforced.
///
/// The two `Never` rows are a recorded product decision: both controls stay in the UI, permanently
/// badged with the reason, rather than being deleted — removing them would silently drop settings
/// users have already saved, and would hide the fact that the limitation is known.
pub static ENFORCEMENT: &[(&str, Enforcement)] = &[
    (
        "appearance.theme",
        Enforcement::Local {
            how: "applied by this window as it renders",
        },
    ),
    (
        "appearance.notifications",
        Enforcement::Local {
            how: "applied by this app when it raises a notification",
        },
    ),
    (
        "behavior.auto_start",
        Enforcement::Local {
            how: "registered with the OS as a login item",
        },
    ),
    (
        "behavior.only_when_charging",
        Enforcement::Live {
            confirmed_by: "behavior.transfers_paused",
        },
    ),
    (
        "behavior.battery_control",
        Enforcement::Live {
            confirmed_by: "behavior.transfers_paused",
        },
    ),
    (
        "behavior.battery_threshold_percent",
        Enforcement::Live {
            confirmed_by: "behavior.transfers_paused",
        },
    ),
    (
        "behavior.power_rules_during_game",
        Enforcement::Live {
            confirmed_by: "behavior.transfers_paused",
        },
    ),
    (
        "network.default_trackers",
        Enforcement::Live {
            confirmed_by: "network.default_trackers",
        },
    ),
    (
        "network.unlimited_connections_only",
        Enforcement::Never {
            reason: "no peer advertises a connection cap on the wire, so there is nothing to filter on",
        },
    ),
    // Bind-time: the listening socket is opened once, at startup.
    ("network.use_random_port", Enforcement::AtRestart),
    ("network.port_range", Enforcement::AtRestart),
    (
        "network.max_connections",
        Enforcement::Live {
            confirmed_by: "network.max_connections",
        },
    ),
    (
        "network.max_connections_per_world",
        Enforcement::Never {
            reason: "the transport has no world dimension; a socket is not owned by a world",
        },
    ),
    (
        "network.max_upload_slots_per_world",
        Enforcement::Live {
            confirmed_by: "network.max_upload_slots_per_world",
        },
    ),
    (
        "network.max_upload_bytes_per_sec",
        Enforcement::Live {
            confirmed_by: "network.max_upload_bytes_per_sec",
        },
    ),
    (
        "network.max_download_bytes_per_sec",
        Enforcement::Live {
            confirmed_by: "network.max_download_bytes_per_sec",
        },
    ),
    // The archive directory is opened once at startup and holds live file handles.
    ("storage.peer_worlds_dir", Enforcement::AtRestart),
];

/// What the UI renders beside a control.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SettingState {
    /// In force right now.
    Live,
    /// Saved; takes effect when the worker restarts.
    RestartRequired,
    /// The worker on the other end of the socket does not do this — usually because it is older
    /// than the app. Distinct from [`Self::Unenforced`]: this one is fixed by updating the worker.
    UnsupportedByWorker,
    /// Nothing is applying this. When `reason` is non-empty the limitation is **permanent**, which
    /// is what lets the UI badge it differently from "not implemented yet".
    Unenforced,
}

/// One control's honest status.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SettingStatus {
    pub key: String,
    pub state: SettingState,
    /// Empty when there is nothing to explain; otherwise shown verbatim in the tooltip.
    pub reason: String,
}

/// What the currently-connected worker told us, which is the only evidence [`setting_status`] may
/// reason from.
#[derive(Clone, Copy, Debug)]
pub struct WorkerReport<'a> {
    /// The worker answered a configuration push at all — even by refusing it.
    pub reachable: bool,
    /// It understood `NODERA-CONFIG`.
    pub supports_config: bool,
    pub applied: &'a [String],
    pub restart_required: &'a [String],
    pub rejected: &'a BTreeMap<String, String>,
}

/// Resolve every settings key against the worker's reply.
///
/// Precedence, and why:
/// 1. `Never` first — a structural impossibility is not something a worker gets a vote on, and a
///    worker that happened to answer `applied` for such a key would be wrong, not authoritative.
/// 2. The worker's own `rejected` reason next, verbatim: it knows why better than this table does.
/// 3. `applied` ⇒ live. Only here, and only for keys whose `confirmed_by` the worker named.
/// 4. `restart_required` from either the worker or the table.
/// 5. Everything else ⇒ nothing is enforcing it, with a reason that says which case it is.
pub fn setting_status(report: &WorkerReport<'_>) -> Vec<SettingStatus> {
    ENFORCEMENT
        .iter()
        .map(|(key, enforcement)| {
            let (state, reason) = resolve(key, *enforcement, report);
            SettingStatus {
                key: (*key).to_owned(),
                state,
                reason,
            }
        })
        .collect()
}

fn resolve(
    key: &str,
    enforcement: Enforcement,
    report: &WorkerReport<'_>,
) -> (SettingState, String) {
    if let Enforcement::Never { reason } = enforcement {
        return (SettingState::Unenforced, reason.to_owned());
    }
    if let Enforcement::Local { how } = enforcement {
        return (SettingState::Live, how.to_owned());
    }
    if let Some(reason) = report.rejected.get(key) {
        return (SettingState::Unenforced, reason.clone());
    }

    let confirmed_by = match enforcement {
        Enforcement::Live { confirmed_by } => confirmed_by,
        _ => key,
    };
    let named = |list: &[String]| list.iter().any(|entry| entry == confirmed_by);

    if named(report.applied) {
        return (SettingState::Live, String::new());
    }
    if named(report.restart_required) {
        return (
            SettingState::RestartRequired,
            "saved — restart the worker to apply it".to_owned(),
        );
    }
    if enforcement == Enforcement::AtRestart {
        return (
            SettingState::RestartRequired,
            "saved — restart the worker to apply it".to_owned(),
        );
    }
    if !report.reachable {
        return (
            SettingState::Unenforced,
            "the worker is offline, so this is saved but not yet applied".to_owned(),
        );
    }
    if !report.supports_config {
        return (
            SettingState::UnsupportedByWorker,
            "this worker is older than the app and cannot be configured from here".to_owned(),
        );
    }
    (
        SettingState::UnsupportedByWorker,
        "this worker did not confirm it applies this setting".to_owned(),
    )
}

/// `~/.config/nodera/settings.json` (or the platform equivalent).
fn settings_path() -> PathBuf {
    if let Ok(explicit) = std::env::var("NODERA_SETTINGS_FILE") {
        return PathBuf::from(explicit);
    }
    let base = dirs_config()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("nodera");
    base.join("settings.json")
}

/// Config dir without pulling in a directories crate: XDG on Linux, the OS conventions elsewhere.
fn dirs_config() -> Option<PathBuf> {
    #[cfg(target_os = "linux")]
    {
        if let Ok(xdg) = std::env::var("XDG_CONFIG_HOME") {
            if !xdg.is_empty() {
                return Some(PathBuf::from(xdg));
            }
        }
        return std::env::var("HOME").ok().map(|h| PathBuf::from(h).join(".config"));
    }
    #[cfg(target_os = "macos")]
    {
        return std::env::var("HOME")
            .ok()
            .map(|h| PathBuf::from(h).join("Library/Application Support"));
    }
    #[cfg(target_os = "windows")]
    {
        return std::env::var("APPDATA").ok().map(PathBuf::from);
    }
    #[allow(unreachable_code)]
    None
}

/// Thread-safe settings holder, loaded once at startup and written through on every change.
pub struct SettingsHandle {
    inner: Mutex<Settings>,
}

impl SettingsHandle {
    /// Load from disk, falling back to defaults for a missing or unreadable file.
    pub fn load() -> Self {
        let settings = std::fs::read_to_string(settings_path())
            .ok()
            .and_then(|raw| serde_json::from_str::<Settings>(&raw).ok())
            .unwrap_or_default();
        Self {
            inner: Mutex::new(settings),
        }
    }

    pub fn snapshot(&self) -> Settings {
        self.inner.lock().unwrap().clone()
    }

    /// Validate, store, and persist. Returns the error message on a rejected document.
    pub fn save(&self, settings: Settings) -> Result<(), String> {
        settings.validate()?;
        let path = settings_path();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let encoded = serde_json::to_string_pretty(&settings).map_err(|e| e.to_string())?;
        // Write-then-rename: a crash mid-write leaves the previous settings intact rather than a
        // half-written file that would silently reset everything on next launch.
        let temp = path.with_extension("json.tmp");
        std::fs::write(&temp, encoded).map_err(|e| e.to_string())?;
        std::fs::rename(&temp, &path).map_err(|e| e.to_string())?;
        *self.inner.lock().unwrap() = settings;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_valid_and_match_the_documented_port_range() {
        let settings = Settings::default();
        settings.validate().unwrap();
        assert_eq!(settings.network.port_range_start, 37000);
        assert_eq!(settings.network.port_range_end, 57010);
        assert!(settings.network.use_random_port);
        assert!(!settings.behavior.power_rules_during_game);
    }

    #[test]
    fn an_inverted_port_range_is_refused_only_when_the_range_is_in_use() {
        let mut settings = Settings::default();
        settings.network.port_range_start = 60000;
        settings.network.port_range_end = 40000;
        // Random port is on, so the range is inert and must not block saving.
        settings.validate().unwrap();

        settings.network.use_random_port = false;
        assert!(settings.validate().is_err());
    }

    #[test]
    fn tracker_routes_are_validated_with_and_without_a_scheme() {
        let mut settings = Settings::default();
        settings.network.default_trackers = vec![
            "tracker.example:25600".to_owned(),
            "tcp://tracker.example:25600".to_owned(),
            "udp://tracker.example:25600".to_owned(),
        ];
        settings.validate().unwrap();

        for bad in ["http://x:1", "nohost", "host:", "host:0", ":25600"] {
            settings.network.default_trackers = vec![bad.to_owned()];
            assert!(settings.validate().is_err(), "{bad} should be refused");
        }
    }

    #[test]
    fn a_battery_threshold_above_100_is_refused() {
        let mut settings = Settings::default();
        settings.behavior.battery_threshold_percent = 101;
        assert!(settings.validate().is_err());
    }

    /// A file from an older build must load, not wipe the user's configuration.
    #[test]
    fn partial_documents_fill_in_defaults() {
        let settings: Settings =
            serde_json::from_str(r#"{"appearance":{"theme":"dark"}}"#).unwrap();
        assert_eq!(settings.appearance.theme, Theme::Dark);
        assert!(settings.appearance.notifications); // default preserved
        assert_eq!(settings.network.port_range_start, 37000);
    }

    #[test]
    fn unknown_keys_do_not_break_the_parse() {
        let settings: Settings =
            serde_json::from_str(r#"{"appearance":{"theme":"light","from_the_future":1}}"#)
                .unwrap();
        assert_eq!(settings.appearance.theme, Theme::Light);
    }

    /* ---------------------------------------------------------------------- enforcement table */

    fn report<'a>(
        reachable: bool,
        supports_config: bool,
        applied: &'a [String],
        restart_required: &'a [String],
        rejected: &'a BTreeMap<String, String>,
    ) -> WorkerReport<'a> {
        WorkerReport {
            reachable,
            supports_config,
            applied,
            restart_required,
            rejected,
        }
    }

    fn state_of(statuses: &[SettingStatus], key: &str) -> SettingStatus {
        statuses
            .iter()
            .find(|status| status.key == key)
            .unwrap_or_else(|| panic!("{key} missing from the status list"))
            .clone()
    }

    /// The drift guard: a new settings field has to be classified before it can ship.
    #[test]
    fn enforcement_covers_every_settings_key_exactly_once() {
        let mut seen = BTreeSet::new();
        for (key, _) in ENFORCEMENT {
            assert!(seen.insert((*key).to_owned()), "{key} listed twice");
            assert!(key.contains('.'), "{key} should be section.field");
        }
        assert_eq!(
            seen,
            Settings::keys(),
            "ENFORCEMENT and the settings document have drifted"
        );
    }

    /// The invariant the whole design rests on. A worker that says nothing gets nothing claimed on
    /// its behalf — every worker-enforced key degrades, and only the app-local ones stay live.
    #[test]
    fn nothing_worker_enforced_is_live_without_the_worker_naming_it() {
        let none = BTreeMap::new();
        let statuses = setting_status(&report(true, true, &[], &[], &none));
        for status in &statuses {
            let enforcement = ENFORCEMENT
                .iter()
                .find(|(key, _)| *key == status.key)
                .map(|(_, e)| *e)
                .unwrap();
            if matches!(enforcement, Enforcement::Local { .. }) {
                assert_eq!(status.state, SettingState::Live, "{}", status.key);
            } else {
                assert_ne!(
                    status.state,
                    SettingState::Live,
                    "{} claimed live with no confirmation",
                    status.key
                );
            }
        }
    }

    #[test]
    fn a_key_the_worker_applied_is_live() {
        let none = BTreeMap::new();
        let applied = vec!["network.max_upload_bytes_per_sec".to_owned()];
        let statuses = setting_status(&report(true, true, &applied, &[], &none));
        assert_eq!(
            state_of(&statuses, "network.max_upload_bytes_per_sec").state,
            SettingState::Live
        );
        // ...and only that key.
        assert_eq!(
            state_of(&statuses, "network.max_connections").state,
            SettingState::UnsupportedByWorker
        );
    }

    /// All four battery controls are decided in the app and reach the worker as one flag, so one
    /// confirmation legitimately covers them.
    #[test]
    fn the_battery_controls_go_live_on_the_transfers_paused_confirmation() {
        let none = BTreeMap::new();
        let applied = vec!["behavior.transfers_paused".to_owned()];
        let statuses = setting_status(&report(true, true, &applied, &[], &none));
        for key in [
            "behavior.only_when_charging",
            "behavior.battery_control",
            "behavior.battery_threshold_percent",
            "behavior.power_rules_during_game",
        ] {
            assert_eq!(state_of(&statuses, key).state, SettingState::Live, "{key}");
        }
    }

    #[test]
    fn an_old_worker_degrades_the_whole_screen_to_unsupported_with_a_reason_that_says_so() {
        let none = BTreeMap::new();
        let statuses = setting_status(&report(true, false, &[], &[], &none));
        let status = state_of(&statuses, "network.max_connections");
        assert_eq!(status.state, SettingState::UnsupportedByWorker);
        assert!(status.reason.contains("older than the app"), "{status:?}");
    }

    #[test]
    fn an_offline_worker_reads_as_saved_but_not_applied_rather_than_as_broken() {
        let none = BTreeMap::new();
        let statuses = setting_status(&report(false, false, &[], &[], &none));
        let status = state_of(&statuses, "network.max_connections");
        assert_eq!(status.state, SettingState::Unenforced);
        assert!(status.reason.contains("offline"), "{status:?}");
    }

    /// Bind-time values say "restart" even against a worker that never mentioned them, because
    /// that is true of the worker's structure, not of its reply.
    #[test]
    fn bind_time_keys_report_restart_required_without_any_worker_help() {
        let none = BTreeMap::new();
        let statuses = setting_status(&report(true, true, &[], &[], &none));
        for key in [
            "network.port_range",
            "network.use_random_port",
            "storage.peer_worlds_dir",
        ] {
            assert_eq!(
                state_of(&statuses, key).state,
                SettingState::RestartRequired,
                "{key}"
            );
        }
    }

    /// The two permanently-impossible settings keep their written reason whatever the worker says —
    /// including when a worker wrongly claims to have applied one.
    #[test]
    fn the_two_impossible_settings_stay_badged_even_if_a_worker_claims_otherwise() {
        let none = BTreeMap::new();
        let applied = vec![
            "network.max_connections_per_world".to_owned(),
            "network.unlimited_connections_only".to_owned(),
        ];
        let statuses = setting_status(&report(true, true, &applied, &[], &none));

        let per_world = state_of(&statuses, "network.max_connections_per_world");
        assert_eq!(per_world.state, SettingState::Unenforced);
        assert!(per_world.reason.contains("no world dimension"), "{per_world:?}");

        let unlimited = state_of(&statuses, "network.unlimited_connections_only");
        assert_eq!(unlimited.state, SettingState::Unenforced);
        assert!(
            unlimited.reason.contains("nothing to filter on"),
            "{unlimited:?}"
        );
    }

    /// A permanent limitation carries a reason; a merely-unapplied key must too, so the UI always
    /// has something to say instead of an unexplained grey badge.
    #[test]
    fn every_non_live_status_explains_itself() {
        let none = BTreeMap::new();
        for status in setting_status(&report(true, true, &[], &[], &none)) {
            if status.state != SettingState::Live {
                assert!(!status.reason.is_empty(), "{} has no reason", status.key);
            }
        }
    }

    #[test]
    fn the_worker_owns_the_reason_when_it_rejects_a_key_itself() {
        let mut rejected = BTreeMap::new();
        rejected.insert(
            "network.max_upload_slots_per_world".to_owned(),
            "this build serves whole pieces, so slots are meaningless".to_owned(),
        );
        let statuses = setting_status(&report(true, true, &[], &[], &rejected));
        let status = state_of(&statuses, "network.max_upload_slots_per_world");
        assert_eq!(status.state, SettingState::Unenforced);
        assert!(status.reason.contains("whole pieces"), "{status:?}");
    }

    /// The compatibility shim must keep returning namespaced names and must exclude anything live.
    #[test]
    fn the_unenforced_shim_lists_exactly_the_non_live_keys() {
        let none = BTreeMap::new();
        let applied = vec!["network.max_connections".to_owned()];
        let report = report(true, true, &applied, &[], &none);
        let shim = Settings::unenforced(&report);
        assert!(!shim.contains(&"network.max_connections".to_owned()));
        assert!(!shim.contains(&"appearance.theme".to_owned()));
        assert!(shim.contains(&"network.max_connections_per_world".to_owned()));
        for key in &shim {
            assert!(key.contains('.'), "{key} should be section.field");
        }
    }
}
