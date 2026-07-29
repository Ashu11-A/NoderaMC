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

/// Which links this node is willing to move other people's data over.
///
/// Not a bandwidth limit: those are about speed, this is about *cost*. A node seeding a world moves
/// as many bytes as it can by design, and on a metered connection those bytes are somebody's data
/// allowance being spent on strangers' worlds. The decision is made in this app — see
/// [`crate::android::network::allows_transfers`] — and reaches the worker as `transfers_paused`,
/// exactly like the battery rules.
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum NetworkPolicy {
    /// Any connection. The desktop default: a machine on a cable has no allowance to protect.
    #[default]
    Any,
    /// Only where the OS reports the link as unmetered. Follows the user's own system setting, so an
    /// unlimited SIM marked unmetered counts and a capped Wi-Fi hotspot does not.
    UnmeteredOnly,
    /// Only over Wi-Fi or Ethernet, whatever the OS says about metering. The literal reading of
    /// "never on mobile data", for people who want the rule not the nuance.
    WifiOnly,
}

/// Connection + bandwidth limits.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Network {
    /// Discovery services this node announces to and queries. `tcp://host:port` or `udp://host:port`;
    /// a bare `host:port` means TCP.
    pub default_trackers: Vec<String>,
    /// Relay services used to reach peers that cannot accept an inbound connection. Same route
    /// syntax as the trackers.
    ///
    /// The worker takes these at startup, so a change reports `restart_required` rather than
    /// pretending. Present here because the app previously had no way to set them at all: the only
    /// path was the spawn environment, which a phone — where the worker is started by the Activity
    /// — has no way to write.
    pub rendezvous_endpoints: Vec<String>,
    /// Lists of trackers and relays this user has chosen to trust, on top of the two above.
    ///
    /// See [`crate::stores`]. A store contributes endpoints; it never displaces one the user typed,
    /// and it never grants a service any authority it would not otherwise have. Deleting a store
    /// removes only what it contributed.
    pub tracker_stores: Vec<crate::stores::TrackerStore>,
    /// Which links this node will move data over. See [`NetworkPolicy`].
    pub transfer_network: NetworkPolicy,
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

/// The trackers a fresh install starts with.
///
/// Platform-dependent, because "the tracker on this machine" is not a thing a phone has. A desktop
/// may well be running one beside its worker, so loopback belongs there; on a handset `127.0.0.1`
/// is the handset, so listing it would guarantee one permanently failing endpoint on the status
/// screen and teach the user to ignore a red row.
///
/// **A phone therefore ships with no trackers at all, on purpose.** The previous version baked one
/// developer's LAN address (`10.0.0.101`) into every build, which is unreachable on every network
/// but that one — so a fresh install anywhere else showed a permanently failing endpoint and
/// reported "no trackers are answering" while looking, to the user, like a broken app. An empty list
/// is the honest state: the first-run flow asks for one, and the Node screen says so plainly.
///
/// `NODERA_DEFAULT_TRACKERS` (read **at compile time**, comma-separated) overrides this. That is
/// what `scripts/android-apk.sh` sets from the build host's own LAN address, so a development APK
/// still comes up pointed at the laptop that built it without that address being a shipped constant.
fn default_trackers() -> Vec<String> {
    if let Some(baked) = option_env!("NODERA_DEFAULT_TRACKERS") {
        let list: Vec<String> = baked
            .split(',')
            .map(str::trim)
            .filter(|entry| !entry.is_empty())
            .map(str::to_owned)
            .collect();
        if !list.is_empty() {
            return list;
        }
    }
    if cfg!(any(target_os = "android", target_os = "ios")) {
        Vec::new()
    } else {
        vec!["tcp://127.0.0.1:25600".to_owned()]
    }
}

/// What a fresh install is willing to spend data on.
///
/// A phone defaults to Wi-Fi only. The alternative — defaulting to "any" and letting people
/// discover the setting after the fact — spends an allowance the user never agreed to spend, and the
/// bill arrives long after the evidence is gone. A desktop defaults to "any" because a machine on a
/// cable has no allowance to protect and a rule that pauses it would be an unexplained idle node.
fn default_transfer_network() -> NetworkPolicy {
    if cfg!(any(target_os = "android", target_os = "ios")) {
        NetworkPolicy::WifiOnly
    } else {
        NetworkPolicy::Any
    }
}

impl Default for Network {
    fn default() -> Self {
        Self {
            default_trackers: default_trackers(),
            rendezvous_endpoints: Vec::new(),
            // A fresh install starts with the store the app shipped with. This is the first honest
            // default a handset has had: `default_trackers()` is empty there on purpose, because
            // loopback is the handset and a baked LAN address is unreachable everywhere but one
            // network — both produce a permanently failing row that teaches the user to ignore red.
            // A publicly operated tracker, in a list they can inspect and delete, is neither.
            tracker_stores: crate::stores::built_in_store().into_iter().collect(),
            transfer_network: default_transfer_network(),
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
#[derive(Default)]
pub struct Storage {
    /// Empty = the worker's own default (`~/.nodera/archive`).
    pub peer_worlds_dir: String,
    /// How many bytes this node will spend holding **other people's** worlds. 0 = the worker's
    /// default.
    ///
    /// The worker has honoured this key since the replication lane landed and nothing has ever sent
    /// it: a node's disk budget was, until now, whatever the worker decided. On a phone that is not
    /// a detail — an unbounded archive is the difference between a helpful node and a full device.
    pub replication_budget_bytes: u64,
    /// How often the replication sweep runs, in seconds. 0 = the worker's default; the worker
    /// clamps anything below its own 30 s floor and says so.
    pub replication_sweep_seconds: u64,
}

/// What the first-run setup has settled.
///
/// A record of decisions, not of screens seen. `completed` is what suppresses the setup flow, and it
/// is only set once the user has actually answered both questions — so an interrupted setup resumes
/// rather than being skipped.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct SetupState {
    /// Whether the user has finished the first-run questions.
    pub completed: bool,
}

/// The whole settings document.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct Settings {
    pub appearance: Appearance,
    pub behavior: Behavior,
    pub network: Network,
    pub storage: Storage,
    /// Not pushed to the worker — it describes this installation's onboarding, not the node.
    #[serde(default)]
    pub setup: SetupState,
}

impl Settings {
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
            // `setup` records what the user answered during onboarding — where to store data and
            // whether to allow telemetry. The answers themselves live in `storage` and in the
            // worker's own consent record; this section is only "has the flow been completed", which
            // is about this installation and is never pushed to a node. Excluded here rather than
            // given an ENFORCEMENT row, because a row would claim it is a control that reaches
            // something.
            if section == "setup" {
                continue;
            }
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
            validate_route("tracker", tracker)?;
        }
        for endpoint in &self.network.rendezvous_endpoints {
            validate_route("rendezvous", endpoint)?;
        }
        Ok(())
    }
}

/// One `[scheme://]host:port` route, checked the way the peer will later parse it.
///
/// `tcp` is the only scheme accepted. `udp://` used to pass this check and then be **silently
/// dialled over TCP** by `peer::tracker::resolve`, which strips the scheme and opens a
/// `TcpStream` — so a user who configured a UDP tracker got a connection failure that named the
/// wrong cause. Refusing it here says the true thing at the moment it can still be fixed.
fn validate_route(kind: &str, route: &str) -> Result<(), String> {
    let address = route
        .split_once("://")
        .map(|(scheme, rest)| {
            if scheme != "tcp" {
                return Err(format!(
                    "unknown {kind} scheme '{scheme}' in {route} — this build speaks tcp only"
                ));
            }
            Ok(rest)
        })
        .unwrap_or(Ok(route))?;
    let Some((host, port)) = address.rsplit_once(':') else {
        return Err(format!("{kind} '{route}' is not host:port"));
    };
    if host.is_empty() || port.parse::<u16>().map(|p| p == 0).unwrap_or(true) {
        return Err(format!("{kind} '{route}' is not host:port"));
    }
    Ok(())
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
    // Declared unenforced rather than `Local`: this build raises no notification at all (there is
    // no `tauri-plugin-notification` dependency, capability, or read site), so reporting it as `Live`
    // would be exactly the defect this table exists to prevent — a control that claims to be in force
    // with nothing applying it. The toggle stays so a saved preference is not silently dropped, and
    // is badged "not supported" with this reason. See A-UX-2.
    (
        "appearance.notifications",
        Enforcement::Never {
            reason: "desktop notifications are not wired up in this build; the toggle is saved \
                     but no notification is raised",
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
    // The worker binds its rendezvous clients once, at startup.
    ("network.rendezvous_endpoints", Enforcement::AtRestart),
    // A store feeds both lists. The tracker half goes live the moment it is pushed, which is what
    // the badge reports; the relay half rides `network.rendezvous_endpoints` and waits for a
    // restart, which that row already says. Claiming `AtRestart` here would under-report the half
    // that does take effect immediately, and a badge that is wrong in the pessimistic direction
    // still teaches people not to read it.
    (
        "network.tracker_stores",
        Enforcement::Live {
            confirmed_by: "network.default_trackers",
        },
    ),
    // Decided here and pushed as the same one flag the battery rules use: the worker has no idea
    // what kind of link it is on, and a phone is the only thing that does.
    (
        "network.transfer_network",
        Enforcement::Live {
            confirmed_by: "behavior.transfers_paused",
        },
    ),
    (
        "network.unlimited_connections_only",
        Enforcement::Never {
            reason:
                "no peer advertises a connection cap on the wire, so there is nothing to filter on",
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
    // Applied live: the worker relocates the content store in place (`contentStore.relocateTo`)
    // rather than requiring a restart. This row said `AtRestart` until the Java side was read: the
    // badge happened to render correctly anyway, because `applied` is checked first — but a table
    // that disagrees with the worker is a table nobody can trust the next time they read it.
    (
        "storage.peer_worlds_dir",
        Enforcement::Live {
            confirmed_by: "storage.peer_worlds_dir",
        },
    ),
    (
        "storage.replication_budget_bytes",
        Enforcement::Live {
            confirmed_by: "storage.replication_budget_bytes",
        },
    ),
    (
        "storage.replication_sweep_seconds",
        Enforcement::Live {
            confirmed_by: "storage.replication_sweep_seconds",
        },
    ),
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
/// The directory this app keeps its own state in — the settings document and the "we have already
/// asked about telemetry" marker. Public so [`crate::telemetry`] can put its marker beside them
/// rather than inventing a second location.
pub fn config_dir() -> PathBuf {
    if let Ok(explicit) = std::env::var("NODERA_SETTINGS_FILE") {
        return PathBuf::from(explicit)
            .parent()
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("."));
    }
    dirs_config()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("nodera")
}

fn settings_path() -> PathBuf {
    if let Ok(explicit) = std::env::var("NODERA_SETTINGS_FILE") {
        return PathBuf::from(explicit);
    }
    let base = dirs_config()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("nodera");
    base.join("settings.json")
}

/// Where the process may write, on Android.
///
/// Set once at startup from the `AppHandle`, because only the Android runtime knows the app's
/// private directory — it contains the package name and the user id. Everything else here is a
/// pure function of the environment, so this is the one value that has to be injected.
///
/// **Why it exists at all:** without it `dirs_config()` returned `None` on Android and every path
/// fell back to `"."`, which for an Android process is `/` — unwritable. The peer identity could
/// not be created, so the device could not sign an announce, so it was not a peer. It failed
/// quietly, which is the worst way for a security-relevant file to fail.
static ANDROID_DATA_DIR: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();

/// Tell the settings layer where this Android app may write. No-op if already set.
/// Where the worker's synchronised service list lives.
///
/// Beside `settings.json`, which on Android is the app's data directory — the one place both the
/// app and the in-process worker can reach.
pub fn sync_file_path() -> PathBuf {
    config_dir().join(crate::stores::SYNC_FILE_NAME)
}

/// Write the synchronised service list from the current settings.
///
/// Called after anything that can change the resolved endpoints: a settings save, a store added or
/// removed, a scheduled sync. Failure is logged and swallowed — the desktop still has the
/// environment variables, and refusing to save a setting because a cache file could not be written
/// would be the wrong trade.
pub fn write_sync_file(settings: &Settings) {
    let trackers = crate::stores::merged(
        &settings.network.default_trackers,
        &settings.network.tracker_stores,
        crate::stores::ServiceKind::Tracker,
    );
    let rendezvous = crate::stores::merged(
        &settings.network.rendezvous_endpoints,
        &settings.network.tracker_stores,
        crate::stores::ServiceKind::Rendezvous,
    );
    let synced = settings
        .network
        .tracker_stores
        .iter()
        .map(|store| store.last_refreshed_epoch_millis)
        .max()
        .unwrap_or(0);
    let body = crate::stores::sync_file_body(&trackers, &rendezvous, &iso_millis(synced));
    if let Err(e) = crate::stores::write_sync_file(&config_dir(), &body) {
        log::warn!("could not write the synced service list: {e}");
    }
}

/// An epoch-millis stamp as an ISO-ish string, for the file's comment header only.
fn iso_millis(millis: u64) -> String {
    if millis == 0 {
        return String::new();
    }
    let secs = (millis / 1000) as i64;
    // Deliberately not a date library: this is a human-readable comment in a cache file, and the
    // worker never parses it.
    format!("epoch {secs}")
}

// Called from the Android branch of the setup hook only.
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub fn set_android_data_dir(dir: PathBuf) {
    let _ = ANDROID_DATA_DIR.set(dir);
}

/// Config dir without pulling in a directories crate: XDG on Linux, the OS conventions elsewhere.
fn dirs_config() -> Option<PathBuf> {
    // Checked first and on every platform: an explicitly injected directory is always right, and
    // on Android it is the only writable one.
    if let Some(dir) = ANDROID_DATA_DIR.get() {
        return Some(dir.clone());
    }
    #[cfg(target_os = "linux")]
    {
        if let Ok(xdg) = std::env::var("XDG_CONFIG_HOME") {
            if !xdg.is_empty() {
                return Some(PathBuf::from(xdg));
            }
        }
        return std::env::var("HOME")
            .ok()
            .map(|h| PathBuf::from(h).join(".config"));
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

/// What reading the settings file produced.
///
/// Three outcomes, not two. "There is no file" and "there is a file I cannot read" are the same
/// thing to a `.ok()` chain and completely different to a user: the first is a fresh install, the
/// second is a configuration that still exists and must not be thrown away.
enum Stored {
    /// Parsed cleanly.
    Document(Settings),
    /// No file — a fresh install.
    Absent,
    /// A file exists and could not be read or parsed. Carries what to tell the user.
    Damaged(String),
}

fn read_stored() -> Stored {
    let path = settings_path();
    let raw = match std::fs::read_to_string(&path) {
        Ok(raw) => raw,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Stored::Absent,
        Err(e) => return Stored::Damaged(format!("{} could not be read: {e}", path.display())),
    };
    match serde_json::from_str::<Settings>(&raw) {
        Ok(settings) => Stored::Document(settings),
        Err(e) => Stored::Damaged(format!("{} is not readable settings: {e}", path.display())),
    }
}

/// Thread-safe settings holder, loaded once at startup and written through on every change.
///
/// # Why this is more than a `Mutex<Settings>`
///
/// Two failure modes cost a user their entire configuration, and both were live:
///
/// * **The Android ordering race.** The handle is built before Tauri's `setup` hook runs, and on
///   Android the settings path is not known until that hook injects the app's private directory.
///   Tauri creates the webview *before* calling `setup`, so a command could land while this handle
///   still held defaults read from a path that did not exist — and the first-run flow's
///   `snapshot() → save()` would then write those defaults over the real file. [`Self::snapshot`]
///   therefore re-reads whenever the document has never been successfully loaded.
/// * **A damaged file.** A `.ok()` chain turned an unparseable file into defaults, which the next
///   save then made permanent. Now the damage is remembered, the file is preserved, and saving is
///   refused until the user is told.
pub struct SettingsHandle {
    inner: Mutex<Settings>,
    /// Whether `inner` came from a file. `false` means it is defaults, which may be defaults
    /// because there is no file *or* because the path was not yet knowable.
    loaded: std::sync::atomic::AtomicBool,
    /// Set when a file exists but could not be read. Blocks saving.
    damaged: Mutex<Option<String>>,
}

impl SettingsHandle {
    /// Load from disk. A missing file yields defaults; an unreadable one yields defaults **and** a
    /// recorded fault that stops anything overwriting it.
    pub fn load() -> Self {
        let handle = Self {
            inner: Mutex::new(Settings::default()),
            loaded: std::sync::atomic::AtomicBool::new(false),
            damaged: Mutex::new(None),
        };
        handle.absorb(read_stored());
        handle
    }

    /// Apply a read result to this handle.
    fn absorb(&self, stored: Stored) {
        match stored {
            Stored::Document(settings) => {
                *self.inner.lock().unwrap() = settings;
                *self.damaged.lock().unwrap() = None;
                self.loaded
                    .store(true, std::sync::atomic::Ordering::Release);
            }
            Stored::Absent => {
                // A fresh install is fully loaded: there is nothing else to find, and saying
                // otherwise would make every snapshot re-stat a file that is not there.
                *self.damaged.lock().unwrap() = None;
                self.loaded
                    .store(true, std::sync::atomic::Ordering::Release);
            }
            Stored::Damaged(reason) => {
                log::error!("settings: {reason}");
                *self.damaged.lock().unwrap() = Some(reason);
                self.loaded
                    .store(false, std::sync::atomic::Ordering::Release);
            }
        }
    }

    /// The current document, re-reading first if it has never been successfully loaded.
    ///
    /// The re-read is what closes the Android race: whichever command runs first — the setup hook's
    /// `reload`, or an IPC call that beat it — is the one that finds the file, and the other is a
    /// no-op. It costs one `stat` on a path that is almost always already loaded.
    pub fn snapshot(&self) -> Settings {
        if !self.loaded.load(std::sync::atomic::Ordering::Acquire) {
            self.absorb(read_stored());
        }
        self.inner.lock().unwrap().clone()
    }

    /// Why the stored settings could not be read, if they could not. Empty otherwise.
    ///
    /// Surfaced to the UI rather than only logged: a user whose settings file was damaged is about
    /// to be shown default values, and saying nothing would let them conclude the app forgot.
    pub fn fault(&self) -> String {
        self.damaged.lock().unwrap().clone().unwrap_or_default()
    }

    /// Re-read from disk, replacing what is in memory.
    ///
    /// Called from the Android setup hook once the app's private directory is known — see the type
    /// comment for why that is later than it sounds.
    #[cfg_attr(not(target_os = "android"), allow(dead_code))]
    pub fn reload(&self) {
        self.absorb(read_stored());
    }

    /// Validate, store, and persist. Returns the error message on a rejected document.
    pub fn save(&self, settings: Settings) -> Result<(), String> {
        // Refuse to write over something we failed to read. The alternative is that one unparseable
        // byte silently costs the user every setting they ever chose.
        //
        // The guard is dropped before the branch runs. Reading it inside `if let` keeps the
        // temporary alive for the whole body, and the body locks the same mutex again — a deadlock
        // in a settings save, which is about the worst place to put one.
        let damaged = self.damaged.lock().unwrap().clone();
        if let Some(reason) = damaged {
            let kept = settings_path().with_extension("json.bad");
            let _ = std::fs::rename(settings_path(), &kept);
            *self.damaged.lock().unwrap() = None;
            self.loaded
                .store(true, std::sync::atomic::Ordering::Release);
            return Err(format!(
                "{reason} — it has been kept as {} and your settings were not overwritten. \
                 Save again to start fresh.",
                kept.display()
            ));
        }
        settings.validate()?;
        let path = settings_path();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let encoded = serde_json::to_string_pretty(&settings).map_err(|e| e.to_string())?;
        // Write-then-rename: a crash mid-write leaves the previous settings intact rather than a
        // half-written file that would silently reset everything on next launch.
        //
        // The `sync_all` is what makes that true of a *power* loss too. Without it the rename is
        // ordered but the temp file's contents are not, and the documented guarantee fails to a
        // zero-length settings.json — which loads as "damaged" and is exactly the case above.
        let temp = path.with_extension("json.tmp");
        {
            use std::io::Write as _;
            let mut file = std::fs::File::create(&temp).map_err(|e| e.to_string())?;
            file.write_all(encoded.as_bytes())
                .map_err(|e| e.to_string())?;
            file.sync_all().map_err(|e| e.to_string())?;
        }
        std::fs::rename(&temp, &path).map_err(|e| e.to_string())?;
        *self.inner.lock().unwrap() = settings;
        self.loaded
            .store(true, std::sync::atomic::Ordering::Release);
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
        ];
        settings.validate().unwrap();

        for bad in ["http://x:1", "nohost", "host:", "host:0", ":25600"] {
            settings.network.default_trackers = vec![bad.to_owned()];
            assert!(settings.validate().is_err(), "{bad} should be refused");
        }
    }

    /// `udp://` parsed here and was then dialled over TCP by `peer::tracker::resolve`, which strips
    /// the scheme and opens a `TcpStream`. The user saw a connection failure blamed on the host.
    #[test]
    fn a_udp_tracker_is_refused_rather_than_silently_dialled_over_tcp() {
        let mut settings = Settings::default();
        settings.network.default_trackers = vec!["udp://tracker.example:25600".to_owned()];
        let error = settings.validate().unwrap_err();
        assert!(error.contains("tcp only"), "{error}");
    }

    #[test]
    fn rendezvous_endpoints_are_validated_the_same_way_as_trackers() {
        let mut settings = Settings::default();
        settings.network.rendezvous_endpoints = vec!["tcp://relay.example:25601".to_owned()];
        settings.validate().unwrap();

        settings.network.rendezvous_endpoints = vec!["relay.example".to_owned()];
        let error = settings.validate().unwrap_err();
        assert!(error.contains("rendezvous"), "{error}");
    }

    /// The shipped default must not name one machine's LAN address. It did — `10.0.0.101` — which
    /// on every other network is a permanently failing endpoint that reads as a broken app.
    #[test]
    fn no_private_lan_address_is_baked_into_the_defaults() {
        for tracker in Settings::default().network.default_trackers {
            let host = tracker
                .rsplit_once(':')
                .map(|(host, _)| host.trim_start_matches("tcp://").to_owned())
                .unwrap_or(tracker.clone());
            assert!(
                host == "127.0.0.1" || host == "localhost",
                "{tracker} is not something a fresh install anywhere can reach"
            );
        }
    }

    /// A phone spends somebody's data allowance; a desktop on a cable does not.
    #[test]
    fn the_transfer_policy_default_protects_a_handset_and_leaves_a_desktop_alone() {
        let policy = Settings::default().network.transfer_network;
        if cfg!(any(target_os = "android", target_os = "ios")) {
            assert_eq!(policy, NetworkPolicy::WifiOnly);
        } else {
            assert_eq!(policy, NetworkPolicy::Any);
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

    /* --------------------------------------------------------------------- the file on disk */

    /// `NODERA_SETTINGS_FILE` is process-global, so the tests that redirect it must not overlap.
    static FILE_LOCK: Mutex<()> = Mutex::new(());

    /// Run `body` against a settings file of its own.
    fn with_settings_file(contents: Option<&str>, body: impl FnOnce(&std::path::Path)) {
        let _guard = FILE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = std::env::temp_dir().join(format!(
            "nodera-settings-test-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("settings.json");
        if let Some(text) = contents {
            std::fs::write(&path, text).unwrap();
        }
        std::env::set_var("NODERA_SETTINGS_FILE", &path);
        body(&path);
        std::env::remove_var("NODERA_SETTINGS_FILE");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_missing_file_is_a_fresh_install_and_saves_normally() {
        with_settings_file(None, |path| {
            let handle = SettingsHandle::load();
            assert!(handle.fault().is_empty());
            handle.save(Settings::default()).unwrap();
            assert!(path.exists());
        });
    }

    /// The defect this replaced: `.ok()` turned an unreadable file into defaults, and the next save
    /// made that permanent. One bad byte cost the user every setting they had ever chosen.
    #[test]
    fn a_damaged_file_is_reported_and_the_first_save_is_refused_rather_than_destroying_it() {
        with_settings_file(Some("{not json at all"), |path| {
            let handle = SettingsHandle::load();
            let fault = handle.fault();
            assert!(fault.contains("not readable settings"), "{fault}");

            let error = handle.save(Settings::default()).unwrap_err();
            assert!(error.contains(".json.bad"), "{error}");

            // The original bytes still exist, under a name the user can act on.
            let kept = path.with_extension("json.bad");
            assert_eq!(std::fs::read_to_string(kept).unwrap(), "{not json at all");

            // ...and having been told once, saving now works.
            handle.save(Settings::default()).unwrap();
            assert!(handle.fault().is_empty());
        });
    }

    /// The Android ordering race: the handle is built before the settings path is knowable, so its
    /// first read finds nothing. A later `snapshot()` must find the real document — otherwise the
    /// first-run flow's `snapshot() → save()` writes defaults over it.
    #[test]
    fn a_document_that_appears_after_load_is_picked_up_by_the_next_snapshot() {
        with_settings_file(Some("{ this file is unreadable"), |path| {
            let handle = SettingsHandle::load();
            assert!(!handle.fault().is_empty());

            // Stand in for "the real directory became known": a valid document now exists.
            std::fs::write(path, r#"{"setup":{"completed":true}}"#).unwrap();

            assert!(
                handle.snapshot().setup.completed,
                "the handle kept serving defaults and would have overwritten the file"
            );
            assert!(handle.fault().is_empty());
        });
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
            "network.rendezvous_endpoints",
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
        assert!(
            per_world.reason.contains("no world dimension"),
            "{per_world:?}"
        );

        let unlimited = state_of(&statuses, "network.unlimited_connections_only");
        assert_eq!(unlimited.state, SettingState::Unenforced);
        assert!(
            unlimited.reason.contains("nothing to filter on"),
            "{unlimited:?}"
        );
    }

    /// `appearance.notifications` is declared unenforced because this build raises no notification.
    ///
    /// It used to be `Enforcement::Local`, which `resolve` reported as `Live` unconditionally — a
    /// control that claimed to be in force with no consumer, no plugin, and no capability. The exit
    /// test (A-UX-2) is "the setting either raises a notification or is declared unenforced"; this
    /// pins the second branch and keeps it from regressing to the first.
    #[test]
    fn appearance_notifications_is_declared_unenforced_with_a_reason() {
        let none = BTreeMap::new();
        // A worker that confirms everything cannot make this live: nothing reads it here.
        let applied = vec!["appearance.notifications".to_owned()];
        let statuses = setting_status(&report(true, true, &applied, &[], &none));
        let status = state_of(&statuses, "appearance.notifications");
        assert_eq!(status.state, SettingState::Unenforced);
        assert!(
            status.reason.contains("no notification is raised"),
            "{status:?}"
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
}
