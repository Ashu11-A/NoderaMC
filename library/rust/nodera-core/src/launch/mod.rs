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
    /// The game is up.
    Running,
    /// The game exited. Not a failure — quitting is the normal end.
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
    pub profile: Option<String>,
    /// The Java runtime chosen, for the log and for the "wrong Java" message.
    pub java: Option<String>,
    pub pid: Option<u32>,
    pub exit_code: Option<i32>,
    /// Why it stopped, in the words a person reads. Empty unless [`Phase::Failed`].
    pub reason: String,
    pub remedy: Remedy,
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
            world_id: "abc".to_owned(),
            session_id: Some("s-1".to_owned()),
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
            session_id: Some("s-1".to_owned()),
            ..LaunchState::idle()
        };
        assert!(!closed.holds_a_tunnel());
    }
}
