//! Starting the game.
//!
//! # Why the app does this at all
//!
//! Joining a world already worked: the worker opens a tunnel and binds a loopback port, and the
//! player was then told to go and type `127.0.0.1:25601` into Minecraft's Direct Connect box. That
//! is a launcher's job, and leaving it undone is what made this application a dashboard with a
//! network stack rather than a way to play.
//!
//! # Four ways in, in order of how good the outcome is
//!
//! There is no single way to start Minecraft, and the differences are not cosmetic — they decide
//! whether the player lands *in the world* or in a menu, and whether they can land anywhere at all.
//!
//! 1. [`Tier::Prism`] — a third-party launcher's own CLI (`prismlauncher --launch <instance>
//!    --server <addr>`). The launcher already owns the account, the Java runtime, the mod loader and
//!    the classpath, and on 1.20+ it forwards the address as `--quickPlayMultiplayer`. Best outcome,
//!    no credentials needed, and this is the one most players on a modded 1.21.1 already have.
//! 2. [`Tier::Direct`] — this app assembles the command line itself and spawns the JVM. Same
//!    outcome, and the only one that works with no other launcher installed — but it needs a
//!    Microsoft account, and see [`auth`] for why this build cannot supply one.
//! 3. [`Tier::ServersDat`] — write the address into `servers.dat` as a saved server and start the
//!    official launcher. One click from the Multiplayer list rather than none, no credentials.
//! 4. [`Tier::Address`] — hand over `127.0.0.1:<port>` and say so plainly. Always available; it is
//!    what the app did before this module existed, kept as the floor rather than as the plan.
//!
//! The tunnel is opened **before** any of them. A launch that fails after the game is running is an
//! annoyance; a game that starts and then finds nothing listening is a player staring at "connection
//! refused" with no idea which half broke.
//!
//! # Not on Android
//!
//! The whole module is compiled out there. There is no Java Minecraft client on Android and Bedrock
//! speaks a different protocol, so a Play button on a phone would be a button that cannot work. The
//! gate is structural rather than a runtime check, so a Compose screen cannot call this by accident.

pub mod auth;
pub mod command;
pub mod discover;
pub mod java;
pub mod plan;
pub mod servers_dat;
pub mod version;

use serde::{Deserialize, Serialize};

/// Validated world identity used by launcher internals.
///
/// Kept distinct from [`SessionId`] even though control protocol currently uses world genesis hash
/// as live session id. That protocol alias is crossed only by [`SessionId::for_world`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct WorldId(String);

impl WorldId {
    pub(crate) fn parse(value: impl Into<String>) -> Result<Self, String> {
        let value = value.into();
        let value = value.trim();
        if !crate::api::network::is_sha256_hex(value) {
            return Err("world id must be exactly 64 hexadecimal characters".to_owned());
        }
        Ok(Self(value.to_ascii_lowercase()))
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

/// Validated identity accepted by `NODERA-CONNECT` and `NODERA-DISCONNECT`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SessionId(String);

impl SessionId {
    pub(crate) fn parse(value: impl Into<String>) -> Result<Self, String> {
        let value = value.into();
        let value = value.trim();
        if !crate::api::network::is_sha256_hex(value) {
            return Err("session id must be exactly 64 hexadecimal characters".to_owned());
        }
        Ok(Self(value.to_ascii_lowercase()))
    }

    /// Current directory contract aliases a live session to its world's genesis hash.
    pub(crate) fn for_world(world: &WorldId) -> Self {
        Self(world.0.clone())
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }
}

/// How far a launch got.
///
/// A closed set, and every one of them is a thing the interface says out loud. A spinner with no
/// word under it is the same failure as a dashboard reporting `0` for "we never asked".
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Phase {
    /// Nothing is running.
    #[default]
    Idle,
    /// Working out which installation and which launcher can start this.
    Resolving,
    /// Asking the worker for a tunnel.
    Joining,
    /// Reading version metadata, resolving Java, writing `servers.dat`.
    Preparing,
    /// The process is being started.
    Spawning,
    /// Launch command succeeded. Direct route owns running game; delegated route owns handoff only.
    Running,
    /// Explicit leave is closing a retained or in-flight tunnel.
    Closing,
    /// Launch ended. `cancelled` distinguishes explicit leave from normal game exit.
    Exited,
    /// It stopped before the game was up, and `remedy` says what to offer.
    Failed,
}

/// The one thing the interface should offer next.
///
/// An enum rather than a sentence, because the interface has to render a *button*, and a button
/// built by pattern-matching prose is a button that stops appearing when somebody rewords an error.
/// The prose lives beside it in `reason`; this is what the click does.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Remedy {
    /// Nothing to offer — the message is the whole answer.
    #[default]
    None,
    /// The Nodera mod is missing from the installation this would launch.
    InstallMod,
    /// No Minecraft installation was found, or several were and none is chosen.
    PickInstall,
    /// A Java runtime is missing, or too old for this version.
    InstallJava,
    /// A Microsoft account is needed and this build cannot ask for one.
    SignIn,
    /// The floor: show the address and let the player connect by hand.
    CopyAddress,
    /// Transient — a tunnel that did not open, a launcher that returned non-zero.
    Retry,
}

/// Which route was taken, or would be.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Tier {
    /// Prism Launcher / MultiMC, through its own CLI. Auto-connects.
    Prism,
    /// This app assembles the command line. Auto-connects; needs an account.
    Direct,
    /// `servers.dat` plus the official launcher. One click from the Multiplayer list.
    ServersDat,
    /// The address, and words.
    Address,
}

impl Tier {
    /// Does the player land in the world without touching a menu?
    pub fn auto_connects(self) -> bool {
        matches!(self, Tier::Prism | Tier::Direct)
    }
}

/// Everything a launch screen renders, in one payload.
///
/// Sent on `nodera://launch` at every transition. Fields are `Option` wherever "not yet" is a real
/// answer, for the same reason the dashboard model is: a `0` that means "we have not asked" is the
/// bug this codebase keeps having.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct LaunchState {
    /// Backend-generated id shared by every transition from one Play request.
    #[serde(default)]
    pub correlation_id: String,
    pub phase: Phase,
    pub world_id: String,
    /// The worker's session, once [`Phase::Joining`] succeeded — and what must be handed back to
    /// `leave_world` when the game exits.
    pub session_id: Option<String>,
    /// The loopback address the game connects to.
    pub address: Option<String>,
    pub tier: Option<Tier>,
    /// The installation this is launching from.
    pub install_path: Option<String>,
    /// Stable opaque selector of chosen installation.
    #[serde(default)]
    pub target_id: Option<String>,
    /// Display name retained for older callers.
    pub profile: Option<String>,
    /// The Java runtime chosen, for the log and for the "wrong Java" message.
    pub java: Option<String>,
    pub pid: Option<u32>,
    pub exit_code: Option<i32>,
    /// `true` means an external launcher accepted ownership; Nodera cannot observe game lifetime.
    #[serde(default)]
    pub handoff: bool,
    /// Explicit leave ended launch before an observed game exit.
    #[serde(default)]
    pub cancelled: bool,
    /// Why it stopped, in the words a person reads. Empty unless [`Phase::Failed`].
    pub reason: String,
    pub remedy: Remedy,
}

/// Process-local identity of one launch attempt.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct LaunchId(u64);

impl LaunchId {
    fn correlation(self) -> String {
        format!("launch-{:016x}", self.0)
    }
}

#[derive(Debug)]
struct CoordinatedLaunch {
    next: u64,
    active: Option<LaunchId>,
    current: LaunchState,
}

/// Backend-owned launch state, independent of any React component lifetime.
#[derive(Debug)]
pub(crate) struct LaunchCoordinator(std::sync::Mutex<CoordinatedLaunch>);

impl Default for LaunchCoordinator {
    fn default() -> Self {
        Self(std::sync::Mutex::new(CoordinatedLaunch {
            next: 1,
            active: None,
            current: LaunchState::idle(),
        }))
    }
}

impl LaunchCoordinator {
    pub(crate) fn begin(&self, world: &WorldId) -> Result<(LaunchId, LaunchState), String> {
        let mut held = self.0.lock().map_err(|_| "launch state is unavailable")?;
        if held.active.is_some() || held.current.session_id.is_some() {
            return Err(
                "another launch or tunnel is still active; leave it before starting another"
                    .to_owned(),
            );
        }
        let id = LaunchId(held.next);
        held.next = held.next.saturating_add(1);
        let state = LaunchState {
            correlation_id: id.correlation(),
            world_id: world.as_str().to_owned(),
            ..LaunchState::idle()
        }
        .at(Phase::Resolving);
        held.active = Some(id);
        held.current = state.clone();
        Ok((id, state))
    }

    pub(crate) fn update(&self, id: LaunchId, state: LaunchState) -> bool {
        if let Ok(mut held) = self.0.lock() {
            if held.active == Some(id) {
                held.current = state;
                return true;
            }
        }
        false
    }

    pub(crate) fn finish(&self, id: LaunchId, state: LaunchState) -> bool {
        if let Ok(mut held) = self.0.lock() {
            if held.active == Some(id) {
                held.current = state;
                held.active = None;
                return true;
            }
        }
        false
    }

    pub(crate) fn is_active(&self, id: LaunchId) -> bool {
        self.0
            .lock()
            .map(|held| held.active == Some(id))
            .unwrap_or(false)
    }

    /// Run one non-interruptible step only if attempt is still current.
    pub(crate) fn if_active<T>(&self, id: LaunchId, action: impl FnOnce() -> T) -> Option<T> {
        let held = self.0.lock().ok()?;
        if held.active != Some(id) {
            return None;
        }
        Some(action())
    }

    pub(crate) fn current(&self) -> LaunchState {
        self.0
            .lock()
            .map(|held| held.current.clone())
            .unwrap_or_else(|_| LaunchState::idle())
    }

    pub(crate) fn owned_session(&self) -> Option<SessionId> {
        let held = self.0.lock().ok()?;
        if held.active.is_none() && held.current.session_id.is_none() {
            return None;
        }
        SessionId::parse(
            held.current
                .session_id
                .as_deref()
                .unwrap_or(&held.current.world_id),
        )
        .ok()
    }

    /// Clear a launch-owned tunnel after explicit leave.
    pub(crate) fn release_session(&self, session: &SessionId) -> Option<LaunchState> {
        let mut held = self.0.lock().ok()?;
        if held.current.session_id.as_deref() != Some(session.as_str()) {
            return None;
        }
        held.current.session_id = None;
        if matches!(held.current.phase, Phase::Running | Phase::Closing) {
            held.current.phase = Phase::Exited;
        }
        held.active = None;
        Some(held.current.clone())
    }

    pub(crate) fn release_if_closed(
        &self,
        session: &SessionId,
        outcome: &crate::api::network::Outcome,
    ) -> Option<LaunchState> {
        outcome
            .closed_or_absent()
            .then(|| self.release_session(session))
            .flatten()
    }

    /// Keep a retained tunnel actionable when explicit cleanup could not prove it closed.
    pub(crate) fn cleanup_failed(&self, session: &SessionId, reason: &str) -> Option<LaunchState> {
        let mut held = self.0.lock().ok()?;
        if held.current.session_id.as_deref() != Some(session.as_str()) {
            return None;
        }
        held.current.phase = Phase::Failed;
        held.current.cancelled = true;
        held.current.remedy = Remedy::Retry;
        held.current.reason = format!("connection could not be closed: {reason}");
        Some(held.current.clone())
    }

    /// Obsolete active task before tunnel cleanup begins.
    pub(crate) fn cancel_for(&self, session: &SessionId) -> Option<(LaunchId, LaunchState)> {
        let mut held = self.0.lock().ok()?;
        let id = held.active?;
        if held.current.world_id != session.as_str()
            && held.current.session_id.as_deref() != Some(session.as_str())
        {
            return None;
        }
        held.active = None;
        held.current.phase = Phase::Closing;
        held.current.cancelled = true;
        held.current.reason = "launch cancelled; closing its tunnel".to_owned();
        held.current.remedy = Remedy::None;
        // Blocks a replacement launch until cleanup proves tunnel absent.
        held.current.session_id = Some(session.as_str().to_owned());
        Some((id, held.current.clone()))
    }

    /// Record cancellation cleanup. Failed cleanup remains active because tunnel lifetime is unknown.
    pub(crate) fn cancelled(
        &self,
        id: LaunchId,
        _session: &SessionId,
        cleanup: crate::api::network::Outcome,
    ) {
        let Ok(mut held) = self.0.lock() else { return };
        if held.active != Some(id) {
            return;
        }
        held.current.phase = Phase::Failed;
        held.current.cancelled = true;
        held.current.remedy = Remedy::Retry;
        if cleanup.closed_or_absent() {
            held.current.session_id = None;
            held.current.reason = "launch was cancelled; its tunnel was closed".to_owned();
            held.active = None;
        } else {
            held.current.reason = format!(
                "launch was cancelled, but its tunnel could not be closed: {}",
                cleanup.error
            );
        }
    }
}

const CLEANUP_DELAYS: [std::time::Duration; 4] = [
    std::time::Duration::from_millis(100),
    std::time::Duration::from_millis(400),
    std::time::Duration::from_millis(1_500),
    std::time::Duration::from_millis(4_000),
];

/// Repeatedly close a possibly in-flight tunnel, with finite attempts spanning CONNECT timeout.
pub(crate) async fn cleanup_tunnel(
    control_addr: &str,
    session: &SessionId,
) -> crate::api::network::Outcome {
    let mut outcome = crate::api::network::leave_session(control_addr, session.as_str()).await;
    if outcome.ok {
        return outcome;
    }
    for delay in CLEANUP_DELAYS {
        tokio::time::sleep(delay).await;
        outcome = crate::api::network::leave_session(control_addr, session.as_str()).await;
        if outcome.ok {
            return outcome;
        }
    }
    outcome
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum DelegatedObservation {
    Pending,
    Handoff,
    Accepted,
    Failed,
}

/// Classify launcher process state without treating launcher lifetime as game lifetime.
pub(crate) fn delegated_observation(
    exit: Option<(bool, Option<i32>)>,
    grace_elapsed: bool,
) -> DelegatedObservation {
    match exit {
        Some((true, _)) => DelegatedObservation::Accepted,
        Some((false, _)) => DelegatedObservation::Failed,
        None if grace_elapsed => DelegatedObservation::Handoff,
        None => DelegatedObservation::Pending,
    }
}

/// Cancellation-safe ownership of a tunnel opened for a launch.
pub(crate) struct TunnelLease {
    control_addr: String,
    session: SessionId,
    launch_id: LaunchId,
    coordinator: std::sync::Arc<LaunchCoordinator>,
    cleanup_on_drop: bool,
}

impl TunnelLease {
    pub(crate) fn new(
        control_addr: String,
        session: SessionId,
        launch_id: LaunchId,
        coordinator: std::sync::Arc<LaunchCoordinator>,
    ) -> Self {
        Self {
            control_addr,
            session,
            launch_id,
            coordinator,
            cleanup_on_drop: true,
        }
    }

    pub(crate) async fn close(mut self) -> crate::api::network::Outcome {
        let outcome = cleanup_tunnel(&self.control_addr, &self.session).await;
        // Set only after await: cancellation while DISCONNECT is in flight drops an armed lease and
        // schedules another idempotent cleanup attempt.
        self.cleanup_on_drop = false;
        outcome
    }

    /// Another owner is already cleaning this tunnel.
    pub(crate) fn dismiss(mut self) {
        self.cleanup_on_drop = false;
    }

    /// Transfer tunnel lifetime to backend state for manual-address or delegated-launch handoff.
    pub(crate) fn preserve(mut self) {
        self.cleanup_on_drop = false;
    }
}

impl Drop for TunnelLease {
    fn drop(&mut self) {
        if !self.cleanup_on_drop {
            return;
        }
        let Ok(runtime) = tokio::runtime::Handle::try_current() else {
            return;
        };
        let control_addr = self.control_addr.clone();
        let session = self.session.clone();
        let launch_id = self.launch_id;
        let coordinator = std::sync::Arc::clone(&self.coordinator);
        runtime.spawn(async move {
            let cleanup = cleanup_tunnel(&control_addr, &session).await;
            coordinator.cancelled(launch_id, &session, cleanup);
        });
    }
}

impl LaunchState {
    pub fn idle() -> Self {
        Self::default()
    }

    /// A failure at the phase it happened in, with what to offer next.
    pub fn failed(mut self, reason: impl Into<String>, remedy: Remedy) -> Self {
        self.phase = Phase::Failed;
        self.reason = reason.into();
        self.remedy = remedy;
        self
    }

    pub fn at(mut self, phase: Phase) -> Self {
        self.phase = phase;
        self
    }

    /// Whether a tunnel is open that somebody still has to close.
    ///
    /// The reason this is a question at all: the previous Join screen opened a tunnel and never
    /// closed it, so a session outlived the game and the player's node kept a door open to a host
    /// they had finished playing with.
    pub fn holds_a_tunnel(&self) -> bool {
        self.session_id.is_some() && !matches!(self.phase, Phase::Idle)
    }
}

/// Where a launch goes.
///
/// A trait for the same reason the link has one: the interesting behaviour is the ladder — which
/// tier is picked, what happens when the launcher exits non-zero, whether the tunnel is closed —
/// and none of it should need a window, a game, or a network to test.
pub trait LaunchSink: Send + Sync {
    fn publish(&self, state: LaunchState);
}

#[cfg(test)]
mod tests {
    use super::*;

    const ID_A: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const ID_B: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    async fn disconnect_worker() -> (String, tokio::sync::mpsc::UnboundedReceiver<String>) {
        disconnect_worker_with(vec!["NODERA-OK"]).await
    }

    async fn disconnect_worker_with(
        replies: Vec<&'static str>,
    ) -> (String, tokio::sync::mpsc::UnboundedReceiver<String>) {
        use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap().to_string();
        let (sent, received) = tokio::sync::mpsc::unbounded_channel();
        tokio::spawn(async move {
            for reply in replies {
                let (socket, _) = listener.accept().await.unwrap();
                let (read, mut write) = socket.into_split();
                let line = BufReader::new(read)
                    .lines()
                    .next_line()
                    .await
                    .unwrap()
                    .unwrap();
                sent.send(line).unwrap();
                write
                    .write_all(format!("{reply}\n").as_bytes())
                    .await
                    .unwrap();
            }
        });
        (address, received)
    }

    fn launch_with_tunnel(
        coordinator: &std::sync::Arc<LaunchCoordinator>,
        session: &SessionId,
    ) -> LaunchId {
        let world = WorldId::parse(ID_A).unwrap();
        let (id, mut state) = coordinator.begin(&world).unwrap();
        state.phase = Phase::Preparing;
        state.session_id = Some(session.as_str().to_owned());
        state.address = Some("127.0.0.1:25565".to_owned());
        coordinator.update(id, state);
        id
    }

    #[test]
    fn only_the_two_quick_play_tiers_claim_to_auto_connect() {
        // The distinction is the whole point of the ladder, and it is what the screen promises the
        // player before they press the button. `servers.dat` puts the world one click away in the
        // Multiplayer list; it does not open it.
        assert!(Tier::Prism.auto_connects());
        assert!(Tier::Direct.auto_connects());
        assert!(!Tier::ServersDat.auto_connects());
        assert!(!Tier::Address.auto_connects());
    }

    #[test]
    fn a_failure_keeps_the_facts_it_had_and_adds_a_remedy() {
        let state = LaunchState {
            correlation_id: "launch-1".to_owned(),
            world_id: ID_A.to_owned(),
            session_id: Some(ID_A.to_owned()),
            address: Some("127.0.0.1:25601".to_owned()),
            ..LaunchState::idle()
        }
        .at(Phase::Preparing)
        .failed("no Java 21 on this machine", Remedy::InstallJava);

        assert_eq!(state.phase, Phase::Failed);
        assert_eq!(state.remedy, Remedy::InstallJava);
        // The address survives the failure: the floor tier is still available to the player, and a
        // screen that forgot the address would have nothing left to offer them.
        assert_eq!(state.address.as_deref(), Some("127.0.0.1:25601"));
        assert!(
            state.holds_a_tunnel(),
            "a failed prepare still leaves a door open"
        );
    }

    #[test]
    fn an_idle_launch_holds_nothing_to_close() {
        assert!(!LaunchState::idle().holds_a_tunnel());
        // Even with a session id: `Idle` is what the state is reset to *after* the tunnel is closed,
        // so treating it as open would make the caller close it twice.
        let closed = LaunchState {
            session_id: Some(ID_A.to_owned()),
            ..LaunchState::idle()
        };
        assert!(!closed.holds_a_tunnel());
    }

    #[test]
    fn concurrent_launches_are_rejected_until_active_launch_finishes() {
        let coordinator = LaunchCoordinator::default();
        let first_world = WorldId::parse(ID_A).unwrap();
        let second_world = WorldId::parse(ID_B).unwrap();
        let (first, state) = coordinator.begin(&first_world).unwrap();
        let first_correlation = state.correlation_id.clone();

        let refused = coordinator.begin(&second_world).unwrap_err();
        assert!(refused.contains("another launch"), "{refused}");

        coordinator.finish(first, state.at(Phase::Exited));
        let (_, second) = coordinator.begin(&second_world).unwrap();
        assert_eq!(second.world_id, ID_B);
        assert_ne!(second.correlation_id, first_correlation);
    }

    #[test]
    fn world_and_session_ids_are_validated_and_crossed_explicitly() {
        let world = WorldId::parse(format!(" {ID_A} ")).unwrap();
        let session = SessionId::for_world(&world);
        assert_eq!(world.as_str(), ID_A);
        assert_eq!(session.as_str(), ID_A);
        assert!(WorldId::parse("aabb").is_err());
        assert!(SessionId::parse(&ID_A[..63]).is_err());
        assert!(WorldId::parse("not-hex").is_err());
        assert!(SessionId::parse("world id").is_err());
    }

    #[tokio::test]
    async fn cancelling_launch_drops_lease_and_closes_tunnel() {
        let (control_addr, mut requests) = disconnect_worker().await;
        let coordinator = std::sync::Arc::new(LaunchCoordinator::default());
        let session = SessionId::parse(ID_A).unwrap();
        let launch_id = launch_with_tunnel(&coordinator, &session);
        let mut joining = coordinator.current();
        joining.phase = Phase::Joining;
        joining.session_id = None;
        coordinator.update(launch_id, joining);

        drop(TunnelLease::new(
            control_addr,
            session.clone(),
            launch_id,
            std::sync::Arc::clone(&coordinator),
        ));

        let request = tokio::time::timeout(std::time::Duration::from_secs(1), requests.recv())
            .await
            .expect("cancellation cleanup request")
            .unwrap();
        assert_eq!(request, format!("NODERA-DISCONNECT 2 {ID_A}"));
        for _ in 0..20 {
            if coordinator.current().reason.contains("cancelled") {
                break;
            }
            tokio::task::yield_now().await;
        }
        let cancelled = coordinator.current();
        assert_eq!(cancelled.session_id, None);
        assert!(
            cancelled.reason.contains("cancelled"),
            "{}",
            cancelled.reason
        );
        assert!(
            coordinator.begin(&WorldId::parse(ID_B).unwrap()).is_ok(),
            "successful cancellation cleanup must release launch coordinator"
        );
    }

    #[tokio::test]
    async fn manual_address_fallback_keeps_tunnel_until_explicit_leave() {
        let (control_addr, mut requests) = disconnect_worker().await;
        let coordinator = std::sync::Arc::new(LaunchCoordinator::default());
        let session = SessionId::parse(ID_A).unwrap();
        let launch_id = launch_with_tunnel(&coordinator, &session);
        let mut state = coordinator.current();
        state = state.failed("connect manually", Remedy::CopyAddress);
        coordinator.update(launch_id, state);

        TunnelLease::new(
            control_addr.clone(),
            session.clone(),
            launch_id,
            std::sync::Arc::clone(&coordinator),
        )
        .preserve();
        tokio::task::yield_now().await;
        assert!(requests.try_recv().is_err(), "fallback closed tunnel early");
        assert!(coordinator.current().holds_a_tunnel());

        let outcome = crate::api::network::leave_session(&control_addr, session.as_str()).await;
        assert!(outcome.ok, "{}", outcome.error);
        coordinator.release_session(&session);
        assert_eq!(
            requests.recv().await.as_deref(),
            Some(format!("NODERA-DISCONNECT 2 {ID_A}").as_str())
        );
        assert!(!coordinator.current().holds_a_tunnel());
    }

    #[tokio::test(start_paused = true)]
    async fn cancellation_retries_when_disconnect_overtakes_in_flight_connect() {
        let (control_addr, mut requests) =
            disconnect_worker_with(vec!["NODERA-ERR not connected to that world", "NODERA-OK"])
                .await;
        let coordinator = std::sync::Arc::new(LaunchCoordinator::default());
        let session = SessionId::parse(ID_A).unwrap();
        let world = WorldId::parse(ID_A).unwrap();
        let (launch_id, _) = coordinator.begin(&world).unwrap();

        drop(TunnelLease::new(
            control_addr,
            session,
            launch_id,
            std::sync::Arc::clone(&coordinator),
        ));

        assert_eq!(
            requests.recv().await.as_deref(),
            Some(format!("NODERA-DISCONNECT 2 {ID_A}").as_str())
        );
        assert_eq!(
            requests.recv().await.as_deref(),
            Some(format!("NODERA-DISCONNECT 2 {ID_A}").as_str())
        );
        for _ in 0..20 {
            if coordinator.current().reason.contains("cancelled") {
                break;
            }
            tokio::task::yield_now().await;
        }
        assert!(coordinator.current().reason.contains("cancelled"));
    }

    #[test]
    fn leave_obsoletes_resolving_preparing_and_spawning_attempts() {
        for phase in [Phase::Resolving, Phase::Preparing, Phase::Spawning] {
            let coordinator = LaunchCoordinator::default();
            let world = WorldId::parse(ID_A).unwrap();
            let session = SessionId::for_world(&world);
            let (id, mut state) = coordinator.begin(&world).unwrap();
            state.phase = phase;
            assert!(coordinator.update(id, state.clone()));

            coordinator.cancel_for(&session).expect("active launch");
            let mut stale = state.at(Phase::Running);
            stale.pid = Some(99);
            assert!(!coordinator.update(id, stale));
            let mut spawned = false;
            assert!(coordinator.if_active(id, || spawned = true).is_none());
            assert!(!spawned, "{phase:?} task spawned after leave");
            assert_eq!(coordinator.current().phase, Phase::Closing);
            assert!(coordinator.current().cancelled);

            coordinator.release_session(&session);
            assert!(coordinator.begin(&WorldId::parse(ID_B).unwrap()).is_ok());
        }
    }

    #[test]
    fn idempotent_absent_disconnect_releases_coordinator() {
        let coordinator = LaunchCoordinator::default();
        let world = WorldId::parse(ID_A).unwrap();
        let session = SessionId::for_world(&world);
        coordinator.begin(&world).unwrap();
        coordinator.cancel_for(&session).unwrap();

        let absent = crate::api::network::Outcome::failed("not connected to that world");
        assert!(coordinator.release_if_closed(&session, &absent).is_some());
        assert!(coordinator.begin(&WorldId::parse(ID_B).unwrap()).is_ok());
    }

    #[test]
    fn failed_disconnect_stays_actionable_and_blocks_relaunch() {
        let coordinator = LaunchCoordinator::default();
        let world = WorldId::parse(ID_A).unwrap();
        let session = SessionId::for_world(&world);
        let (id, mut state) = coordinator.begin(&world).unwrap();
        state.session_id = Some(ID_A.to_owned());
        state.phase = Phase::Running;
        assert!(coordinator.update(id, state));
        coordinator.cancel_for(&session).unwrap();

        let retained = coordinator
            .cleanup_failed(&session, "worker timed out")
            .expect("retained session");
        assert_eq!(retained.phase, Phase::Failed);
        assert_eq!(retained.remedy, Remedy::Retry);
        assert_eq!(retained.session_id.as_deref(), Some(ID_A));
        assert!(coordinator.begin(&WorldId::parse(ID_B).unwrap()).is_err());

        coordinator.release_session(&session);
        assert!(coordinator.begin(&WorldId::parse(ID_B).unwrap()).is_ok());
    }

    #[test]
    fn shutdown_owns_only_active_or_retained_tunnel() {
        let coordinator = LaunchCoordinator::default();
        assert!(coordinator.owned_session().is_none());
        let world = WorldId::parse(ID_A).unwrap();
        let (id, state) = coordinator.begin(&world).unwrap();
        assert_eq!(coordinator.owned_session().unwrap().as_str(), ID_A);
        coordinator.finish(id, state.at(Phase::Exited));
        assert!(coordinator.owned_session().is_none());
    }

    #[tokio::test(start_paused = true)]
    async fn cleanup_is_bounded_and_final_absent_is_successful_absence() {
        let replies = vec!["NODERA-ERR not connected to that world"; CLEANUP_DELAYS.len() + 1];
        let (control_addr, mut requests) = disconnect_worker_with(replies).await;
        let session = SessionId::parse(ID_A).unwrap();

        let outcome = cleanup_tunnel(&control_addr, &session).await;
        assert!(outcome.closed_or_absent());
        for _ in 0..=CLEANUP_DELAYS.len() {
            assert_eq!(
                requests.recv().await.as_deref(),
                Some(format!("NODERA-DISCONNECT 2 {ID_A}").as_str())
            );
        }
        assert!(
            requests.try_recv().is_err(),
            "cleanup exceeded attempt budget"
        );
    }

    #[test]
    fn delegated_process_is_running_only_after_handoff_or_success() {
        assert_eq!(
            delegated_observation(None, false),
            DelegatedObservation::Pending
        );
        assert_eq!(
            delegated_observation(None, true),
            DelegatedObservation::Handoff
        );
        assert_eq!(
            delegated_observation(Some((true, Some(0))), false),
            DelegatedObservation::Accepted
        );
        assert_eq!(
            delegated_observation(Some((false, Some(7))), false),
            DelegatedObservation::Failed
        );
    }
}
