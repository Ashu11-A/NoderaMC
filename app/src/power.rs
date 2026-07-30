//! Battery-aware transfer gating: **the app decides, the worker obeys a boolean.**
//!
//! The worker is a Minecraft-free node that also runs on servers and in containers. Teaching the
//! JVM about batteries would be a layering violation *and* a lie — a worker on a headless box has no
//! host power state to read, and a worker in a container cannot see the laptop it is running on.
//! So the whole policy lives here, and the only thing that crosses the control socket is
//! `behavior.transfers_paused` (see [`crate::config::WorkerConfig`]).
//!
//! Two properties this module is built around:
//!
//! * **The decision is a pure function.** [`should_transfer`] takes the settings, a battery reading
//!   and whether the game is open, and returns a boolean. That makes the entire truth table
//!   testable on a machine with no battery, which is most CI.
//! * **Pushes are edge-triggered.** The sampler compares the computed value to the last one and only
//!   asks for a push when it changed. A 30 s sampler that pushed unconditionally would become a
//!   control-socket heartbeat — 2 880 pointless connections a day, each of which reconfigures a
//!   live transfer service.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use crate::api::store::DashboardStore;
use crate::config::PushSignal;
use crate::settings::{Settings, SettingsHandle};

/// How often host power state is read. Battery level moves in percent-per-minute at worst, so a
/// faster cadence would buy nothing and cost a hardware poll.
const SAMPLE_INTERVAL: Duration = Duration::from_secs(30);

/// What the app knows about host power at one instant.
///
/// `present: false` is not "0%": it means this machine has no battery at all (desktop, server, VM),
/// which is the case where every power rule must be inert rather than maximally restrictive.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BatteryState {
    pub present: bool,
    /// `true` while running on the battery (discharging), `false` while on mains.
    pub on_battery: bool,
    /// Charge level, 0–100.
    pub percent: u8,
}

impl BatteryState {
    /// A machine with no battery — the state every desktop and server reports.
    pub fn none() -> Self {
        Self::default()
    }
}

/// Decide whether this node should be moving bytes right now.
///
/// Reading order matters and encodes the product decisions:
///
/// 1. **No battery ⇒ always transfer.** A rule about battery cannot apply where there is none, and
///    silently pausing a server because a checkbox was ticked would be indefensible.
/// 2. **Game open ⇒ transfer, unless the user asked otherwise.** Pausing the swarm mid-session is
///    the one moment a player would notice, so honouring the floor during play must be opted into.
/// 3. **`only_when_charging` ⇒ pause whenever discharging**, at any level. This is the strict rule
///    and it is checked before the floor.
/// 4. **The floor only applies while discharging.** Pausing at 15 % *while plugged in* would be a
///    trap: the level is rising, so the condition that paused transfers is the one that fixes
///    itself, and the user would see the node idle on mains power for no reason they can observe.
pub fn should_transfer(settings: &Settings, battery: BatteryState, game_running: bool) -> bool {
    if !battery.present {
        return true;
    }
    if game_running && !settings.behavior.power_rules_during_game {
        return true;
    }
    if settings.behavior.only_when_charging && battery.on_battery {
        return false;
    }
    if settings.behavior.battery_control
        && battery.on_battery
        && battery.percent < settings.behavior.battery_threshold_percent
    {
        return false;
    }
    true
}

/// The single `transfers_paused` value, with the three independent reasons it can be set.
///
/// Separate flags OR-ed rather than one, because "the tray says pause", "the battery says pause"
/// and "we are on mobile data" have to be able to clear independently: plugging the laptop in must
/// not silently un-pause a node the user paused by hand, un-pausing by hand must not be undone by
/// the next battery sample, and walking onto Wi-Fi must not resume a node paused for battery.
#[derive(Default)]
pub struct PauseHandle {
    manual: AtomicBool,
    power: AtomicBool,
    network: AtomicBool,
}

impl PauseHandle {
    pub fn new() -> Self {
        Self::default()
    }

    /// The value pushed to the worker.
    pub fn paused(&self) -> bool {
        self.manual.load(Ordering::Relaxed)
            || self.power.load(Ordering::Relaxed)
            || self.network.load(Ordering::Relaxed)
    }

    /// Why the node is paused, in the order a person would want to hear it.
    ///
    /// Returned as a reason rather than a bare boolean because "paused" on its own is the single
    /// most confusing state this app can be in: a phone that quietly stops seeding on mobile data
    /// looks exactly like a phone whose node crashed. The UI shows this string.
    pub fn reason(&self) -> Option<&'static str> {
        if self.manual.load(Ordering::Relaxed) {
            Some("paused by you")
        } else if self.network.load(Ordering::Relaxed) {
            Some("this connection is not allowed by your network setting")
        } else if self.power.load(Ordering::Relaxed) {
            Some("paused by the battery rules")
        } else {
            None
        }
    }

    pub fn manual_paused(&self) -> bool {
        self.manual.load(Ordering::Relaxed)
    }

    /// Flip the tray's manual pause. Returns the new *effective* pause state.
    pub fn toggle_manual(&self) -> bool {
        self.manual.fetch_xor(true, Ordering::Relaxed);
        self.paused()
    }

    /// Set the power-policy half. Returns `true` when the effective value **changed**, which is the
    /// sampler's edge trigger — the caller pushes only then.
    pub fn set_power_pause(&self, pause: bool) -> bool {
        let before = self.paused();
        self.power.store(pause, Ordering::Relaxed);
        before != self.paused()
    }

    /// Set the connection-policy half, with the same edge-trigger contract as
    /// [`Self::set_power_pause`].
    pub fn set_network_pause(&self, pause: bool) -> bool {
        let before = self.paused();
        self.network.store(pause, Ordering::Relaxed);
        before != self.paused()
    }
}

/// Read host power state, collapsing every failure to "no battery".
///
/// Failure means the platform did not tell us, and inventing a level from that would gate real
/// transfers on a guess. "No battery" makes the rules inert, which is the only safe default.
///
/// The manager is constructed per sample rather than held across the `await` in [`sample`]: it is
/// cheap, and nothing platform-specific then has to be `Send` for the task to compile everywhere.
/// Read the host's battery, where there is one to read.
///
/// The mobile build has no battery crate: a phone's power management is the phone's, an app that
/// second-guesses it gets killed for it, and there is no worker to pause. Reporting "no battery"
/// takes the same branch a desktop tower takes, so every rule downstream is already written for it.
#[cfg(any(target_os = "android", target_os = "ios"))]
fn read_battery() -> BatteryState {
    BatteryState::none()
}

#[cfg(not(any(target_os = "android", target_os = "ios")))]
fn read_battery() -> BatteryState {
    let Ok(manager) = starship_battery::Manager::new() else {
        return BatteryState::none();
    };
    let Ok(batteries) = manager.batteries() else {
        return BatteryState::none();
    };
    if let Some(battery) = batteries.flatten().next() {
        let percent = (battery.state_of_charge().value * 100.0).round();
        return BatteryState {
            present: true,
            on_battery: matches!(battery.state(), starship_battery::State::Discharging),
            percent: percent.clamp(0.0, 100.0) as u8,
        };
    }
    BatteryState::none()
}

/// Is a Minecraft session open on this node?
///
/// Read from the same dashboard store the UI renders, rather than from plumbing of its own: a world
/// only carries a game endpoint while its host's game is listening, and it is cleared when the game
/// closes. That makes "the game is running" an observation of the worker's own state instead of a
/// second, disagreeable source of truth — and it means the power rules and the screen can never
/// disagree about whether anyone is playing.
pub fn game_running(store: &DashboardStore) -> bool {
    store
        .snapshot()
        .worlds
        .iter()
        .any(|world| world.game_endpoint.is_some())
}

/// How often the active connection is re-read.
///
/// Faster than the power sampler because a network change is instantaneous and user-visible: a
/// phone that leaves Wi-Fi should stop seeding within seconds, not within half a minute of mobile
/// data. The read is a pair of JNI calls against an in-memory system service, so the cost is
/// negligible.
const NETWORK_INTERVAL: Duration = Duration::from_secs(10);

/// Sample the active connection and pause when the user's policy does not allow it.
///
/// Runs on every platform. On desktop [`crate::android::network::state`] reports the subject as
/// unsupported and [`crate::android::network::allows_transfers`] is inert, so this loop costs one
/// comparison every ten seconds and exists so the two platforms do not diverge — a rule that only
/// exists on one of them is a rule nobody maintains.
pub async fn sample_network(
    settings: Arc<SettingsHandle>,
    pause: Arc<PauseHandle>,
    push: Arc<PushSignal>,
    latest: Arc<Mutex<crate::android::network::NetworkState>>,
) {
    let mut tick = tokio::time::interval(NETWORK_INTERVAL);
    loop {
        tick.tick().await;
        let state = crate::android::network::state();
        let policy = settings.snapshot().network.transfer_network;
        let allowed = crate::android::network::allows_transfers(policy, &state);
        // Cached for the UI: the screen has to be able to say *which* connection the node is on and
        // why it is paused, or "paused" is indistinguishable from "crashed".
        *latest.lock().unwrap() = state;
        if pause.set_network_pause(!allowed) {
            push.request();
        }
    }
}

/// Sample host power on a cadence and push only when the verdict changes.
pub async fn sample(
    settings: Arc<SettingsHandle>,
    store: Arc<DashboardStore>,
    pause: Arc<PauseHandle>,
    push: Arc<PushSignal>,
) {
    let mut tick = tokio::time::interval(SAMPLE_INTERVAL);
    loop {
        tick.tick().await;
        let battery = read_battery();
        let transfer = should_transfer(&settings.snapshot(), battery, game_running(&store));
        if pause.set_power_pause(!transfer) {
            push.request();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn on_battery(percent: u8) -> BatteryState {
        BatteryState {
            present: true,
            on_battery: true,
            percent,
        }
    }

    fn charging(percent: u8) -> BatteryState {
        BatteryState {
            present: true,
            on_battery: false,
            percent,
        }
    }

    #[test]
    fn a_machine_without_a_battery_always_transfers_however_the_rules_are_set() {
        let mut settings = Settings::default();
        settings.behavior.only_when_charging = true;
        settings.behavior.battery_control = true;
        settings.behavior.battery_threshold_percent = 100;
        assert!(should_transfer(&settings, BatteryState::none(), false));
        assert!(should_transfer(&settings, BatteryState::none(), true));
    }

    #[test]
    fn only_when_charging_pauses_the_moment_the_cable_is_pulled() {
        let mut settings = Settings::default();
        settings.behavior.only_when_charging = true;
        assert!(!should_transfer(&settings, on_battery(100), false));
        assert!(should_transfer(&settings, charging(5), false));
    }

    #[test]
    fn the_floor_pauses_below_the_threshold_and_not_at_or_above_it() {
        let mut settings = Settings::default();
        settings.behavior.battery_control = true;
        settings.behavior.battery_threshold_percent = 20;
        assert!(!should_transfer(&settings, on_battery(15), false));
        assert!(should_transfer(&settings, on_battery(20), false));
        assert!(should_transfer(&settings, on_battery(21), false));
    }

    #[test]
    fn the_floor_is_inert_while_charging_so_the_node_cannot_idle_on_mains_power() {
        let mut settings = Settings::default();
        settings.behavior.battery_control = true;
        settings.behavior.battery_threshold_percent = 20;
        assert!(should_transfer(&settings, charging(15), false));
    }

    /// The case the plan calls out: rules that would pause are overridden while the game is open,
    /// because `power_rules_during_game` is off by default.
    #[test]
    fn an_open_game_overrides_the_rules_unless_the_user_opted_in() {
        let mut settings = Settings::default();
        settings.behavior.battery_control = true;
        settings.behavior.battery_threshold_percent = 20;
        settings.behavior.only_when_charging = true;

        settings.behavior.power_rules_during_game = false;
        assert!(should_transfer(&settings, on_battery(15), true));

        settings.behavior.power_rules_during_game = true;
        assert!(!should_transfer(&settings, on_battery(15), true));
    }

    #[test]
    fn the_rules_are_inert_when_nothing_is_switched_on() {
        assert!(should_transfer(&Settings::default(), on_battery(1), false));
    }

    #[test]
    fn a_zero_threshold_never_pauses_because_no_level_is_below_zero() {
        let mut settings = Settings::default();
        settings.behavior.battery_control = true;
        settings.behavior.battery_threshold_percent = 0;
        assert!(should_transfer(&settings, on_battery(0), false));
    }

    #[test]
    fn the_tray_and_the_battery_pause_independently() {
        let pause = PauseHandle::new();
        assert!(!pause.paused());

        assert!(pause.toggle_manual());
        assert!(pause.paused());

        // The battery agreeing changes nothing observable, so there is no edge to push.
        assert!(!pause.set_power_pause(true));
        // ...and the battery disagreeing must not undo a hand-set pause.
        assert!(!pause.set_power_pause(false));
        assert!(pause.paused());

        assert!(!pause.toggle_manual());
        assert!(!pause.paused());
    }

    #[test]
    fn the_power_half_reports_an_edge_only_when_the_effective_value_moves() {
        let pause = PauseHandle::new();
        assert!(pause.set_power_pause(true), "false → true is an edge");
        assert!(!pause.set_power_pause(true), "a repeat is not an edge");
        assert!(pause.set_power_pause(false), "true → false is an edge");
    }

    /// The game-open probe reads the same field the dashboard renders, so the two cannot disagree.
    #[test]
    fn a_world_with_an_open_mc_route_means_the_game_is_running() {
        use crate::metrics::{Metrics, WorldRow};
        let store = DashboardStore::new();
        assert!(
            !game_running(&store),
            "a store that has heard nothing must not claim a game is open"
        );

        store.accept(
            &Metrics {
                connected_worlds: vec![WorldRow {
                    mc_route: String::new(),
                    ..WorldRow::default()
                }],
                ..Metrics::default()
            },
            "stream",
        );
        assert!(!game_running(&store), "no endpoint is a closed game");

        store.accept(
            &Metrics {
                connected_worlds: vec![WorldRow {
                    mc_route: "127.0.0.1:25565".to_owned(),
                    ..WorldRow::default()
                }],
                ..Metrics::default()
            },
            "stream",
        );
        assert!(game_running(&store));
    }
}
