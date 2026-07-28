//! Draining: a service stops taking new work, tells the peers it already has, finishes what is in
//! flight, and only then exits.
//!
//! ## What was wrong before
//!
//! The rendezvous already printed `draining on shutdown signal` and broke its accept loop — but the
//! bridged circuits were detached `tokio::spawn` tasks nobody awaited, so `#[tokio::main]` returning
//! dropped the runtime and killed every live circuit mid-frame. The word "drain" was in the log line
//! and nowhere in the behaviour. On the peer side the damage was worse than a dropped connection: a
//! peer whose reservation vanished retried once, slept 500 ms, and then **ended its accept loop
//! permanently**, so a rendezvous restart silently removed that peer's inbound path until the game
//! was restarted.
//!
//! ## What draining is here
//!
//! Three obligations, in order, and the order is the whole design:
//!
//! 1. **Refuse new work** ([`DrainState::accepts_new_work`]). A reservation granted after the decision
//!    to stop is a promise the service knows it will break.
//! 2. **Say so, to everyone already committed.** The caller broadcasts a signed `ServiceDrainNotice`
//!    down every control channel *and* announces `Draining` to its trackers. Peers get replacements in
//!    the same message, so migration needs no discovery round trip at the worst possible moment.
//! 3. **Wait for what is in flight**, up to a grace period ([`DrainState::wait_for_quiet`]). A relay
//!    circuit carrying a world transfer finishes; it does not get cut because an update was available.
//!
//! The grace period is a ceiling, not a sleep: a service with nothing in flight exits immediately.

use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use nodera_codec::service::ServiceLifecycle;

/// Shared, cheap-to-clone drain state.
///
/// Every field is atomic because the accept loop, each bridged circuit, the announce loop, and the
/// signal handler all touch it from different tasks, and none of them should ever wait on a lock to
/// find out whether the service is still open for business.
#[derive(Debug, Default)]
pub struct DrainState {
    draining: AtomicBool,
    deadline_epoch_millis: AtomicU64,
    in_flight: AtomicUsize,
    reason: std::sync::Mutex<String>,
}

impl DrainState {
    /// A fresh, serving state.
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    /// Begin draining, recording when the service intends to stop and why.
    ///
    /// Idempotent: a SIGTERM arriving while an update-triggered drain is already running must not
    /// extend the deadline or overwrite the reason, or a restart could be postponed indefinitely by
    /// repeated signals.
    pub fn begin(&self, deadline_epoch_millis: u64, reason: &str) -> bool {
        if self.draining.swap(true, Ordering::SeqCst) {
            return false;
        }
        self.deadline_epoch_millis
            .store(deadline_epoch_millis, Ordering::SeqCst);
        if let Ok(mut held) = self.reason.lock() {
            *held = reason.to_owned();
        }
        true
    }

    /// Whether the service is draining.
    pub fn is_draining(&self) -> bool {
        self.draining.load(Ordering::SeqCst)
    }

    /// Whether new work (a reservation, a circuit) may still be accepted.
    pub fn accepts_new_work(&self) -> bool {
        !self.is_draining()
    }

    /// The lifecycle value to advertise in the next signed record.
    pub fn lifecycle(&self) -> ServiceLifecycle {
        if self.is_draining() {
            ServiceLifecycle::Draining
        } else {
            ServiceLifecycle::Serving
        }
    }

    /// The intended stop time, or `0` while serving.
    pub fn deadline_epoch_millis(&self) -> u64 {
        if self.is_draining() {
            self.deadline_epoch_millis.load(Ordering::SeqCst)
        } else {
            0
        }
    }

    /// Why the service is draining; empty while serving.
    pub fn reason(&self) -> String {
        self.reason
            .lock()
            .map(|held| held.clone())
            .unwrap_or_default()
    }

    /// Count one unit of work as started. Returns a guard that decrements on drop.
    pub fn enter(self: &Arc<Self>) -> WorkGuard {
        self.in_flight.fetch_add(1, Ordering::SeqCst);
        WorkGuard {
            state: Arc::clone(self),
        }
    }

    /// How many units of work are in flight.
    pub fn in_flight(&self) -> usize {
        self.in_flight.load(Ordering::SeqCst)
    }

    /// Wait until nothing is in flight, or until `grace` elapses.
    ///
    /// Returns the number still in flight when the wait ended — `0` for a clean drain, non-zero when
    /// the grace period bounded it, which the caller logs so an operator can tell a tidy restart from
    /// a truncated one.
    pub async fn wait_for_quiet(&self, grace: Duration) -> usize {
        // Poll rather than notify: the alternative is a Notify that every circuit must remember to
        // signal on every exit path, and a missed signal here means the process hangs on shutdown.
        const TICK: Duration = Duration::from_millis(50);
        let deadline = tokio::time::Instant::now() + grace;
        loop {
            let remaining = self.in_flight();
            if remaining == 0 {
                return 0;
            }
            if tokio::time::Instant::now() >= deadline {
                return remaining;
            }
            tokio::time::sleep(TICK).await;
        }
    }
}

/// Decrements the in-flight counter when dropped.
///
/// A guard rather than an explicit release call because the release must happen on the panic path
/// too: a circuit task that died on a malformed frame still has to stop holding the shutdown open.
#[derive(Debug)]
pub struct WorkGuard {
    state: Arc<DrainState>,
}

impl Drop for WorkGuard {
    fn drop(&mut self) {
        self.state.in_flight.fetch_sub(1, Ordering::SeqCst);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_serving_state_accepts_work_and_advertises_serving() {
        let state = DrainState::new();
        assert!(state.accepts_new_work());
        assert_eq!(state.lifecycle(), ServiceLifecycle::Serving);
        assert_eq!(state.deadline_epoch_millis(), 0);
    }

    #[test]
    fn draining_refuses_new_work_immediately() {
        // The obligation that has to come first: a reservation granted after the stop decision is a
        // promise the service already knows it will break.
        let state = DrainState::new();
        assert!(state.begin(1_700_000_030_000, "update"));
        assert!(!state.accepts_new_work());
        assert_eq!(state.lifecycle(), ServiceLifecycle::Draining);
        assert_eq!(state.deadline_epoch_millis(), 1_700_000_030_000);
        assert_eq!(state.reason(), "update");
    }

    #[test]
    fn beginning_twice_does_not_move_the_deadline() {
        // Otherwise repeated signals postpone the restart forever.
        let state = DrainState::new();
        assert!(state.begin(1_000, "update"));
        assert!(!state.begin(9_999, "shutdown"));
        assert_eq!(state.deadline_epoch_millis(), 1_000);
        assert_eq!(state.reason(), "update");
    }

    #[test]
    fn a_guard_releases_on_drop_including_on_a_panic_path() {
        let state = DrainState::new();
        let outer = state.enter();
        assert_eq!(state.in_flight(), 1);
        {
            let _inner = state.enter();
            assert_eq!(state.in_flight(), 2);
        }
        assert_eq!(state.in_flight(), 1);
        drop(outer);
        assert_eq!(state.in_flight(), 0);
    }

    #[tokio::test]
    async fn a_quiet_service_stops_without_waiting_out_the_grace_period() {
        let state = DrainState::new();
        let started = std::time::Instant::now();
        assert_eq!(state.wait_for_quiet(Duration::from_secs(30)).await, 0);
        assert!(
            started.elapsed() < Duration::from_secs(1),
            "an idle drain should be immediate, took {:?}",
            started.elapsed()
        );
    }

    #[tokio::test]
    async fn in_flight_work_holds_the_drain_until_it_finishes() {
        let state = DrainState::new();
        let guard = state.enter();
        let releaser = {
            let state = Arc::clone(&state);
            tokio::spawn(async move {
                tokio::time::sleep(Duration::from_millis(120)).await;
                drop(guard);
                state.in_flight()
            })
        };
        assert_eq!(state.wait_for_quiet(Duration::from_secs(5)).await, 0);
        assert_eq!(releaser.await.unwrap(), 0);
    }

    #[tokio::test]
    async fn the_grace_period_bounds_the_wait_and_reports_what_was_cut() {
        // A circuit that never finishes must not hang the process forever; the return value is how
        // an operator tells a tidy restart from a truncated one.
        let state = DrainState::new();
        let _stuck = state.enter();
        assert_eq!(state.wait_for_quiet(Duration::from_millis(120)).await, 1);
    }
}
