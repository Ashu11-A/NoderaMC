//! Exponential backoff for a loop that reconnects to, or respawns, the worker.
//!
//! Three loops in this crate each carried their own pair of constants and their own
//! `(backoff * 2).min(max)`: the dashboard link (500 ms → 10 s), the event stream (500 ms → 10 s)
//! and the worker supervisor (1 s → 30 s). The pairs differ on purpose — a reconnect is cheap and a
//! process spawn is not — so this is **not** a line saving and does not pretend to be one. It is
//! here because three copies of a retry rule is three places for one of them to lose its ceiling,
//! and a retry loop with no ceiling is a busy loop against a service that is already struggling.
//!
//! Two properties every caller depends on, asserted once here:
//!
//! * **it is bounded** — doubling stops at the ceiling, so a worker that never comes back is
//!   retried forever and cheaply, rather than never or continuously;
//! * **success resets it** — a link that worked and then ended must not inherit the delay earned by
//!   whatever was failing before it.

use std::time::Duration;

/// A bounded exponential delay.
#[derive(Debug, Clone, Copy)]
pub struct Backoff {
    initial: Duration,
    max: Duration,
    current: Duration,
}

impl Backoff {
    /// A backoff that starts at `initial` and never exceeds `max`.
    pub fn new(initial: Duration, max: Duration) -> Self {
        Self {
            initial,
            max,
            current: initial,
        }
    }

    /// The delay to wait now.
    pub fn current(&self) -> Duration {
        self.current
    }

    /// Sleep the current delay, then double it up to the ceiling.
    pub async fn wait(&mut self) {
        let delay = self.current;
        self.current = (self.current * 2).min(self.max);
        tokio::time::sleep(delay).await;
    }

    /// Something worked: the next failure starts from the beginning again.
    pub fn reset(&mut self) {
        self.current = self.initial;
    }

    /// Restart from a specific delay — for a pause that is *not* a failure.
    ///
    /// The motivating case: a user who pressed Restart must not wait thirty seconds because the
    /// worker happened to be flapping beforehand.
    pub fn restart_at(&mut self, delay: Duration) {
        self.current = delay;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn doubling_stops_at_the_ceiling_so_a_missing_worker_is_retried_forever_but_cheaply() {
        let mut backoff = Backoff::new(Duration::from_millis(500), Duration::from_secs(10));
        let mut seen = Vec::new();
        for _ in 0..10 {
            seen.push(backoff.current());
            backoff.current = (backoff.current * 2).min(backoff.max);
        }
        assert_eq!(seen[0], Duration::from_millis(500));
        assert_eq!(seen[1], Duration::from_secs(1));
        assert_eq!(*seen.last().unwrap(), Duration::from_secs(10));
    }

    #[test]
    fn success_resets_it_so_one_outage_does_not_slow_the_next_reconnect() {
        let mut backoff = Backoff::new(Duration::from_secs(1), Duration::from_secs(30));
        backoff.current = Duration::from_secs(30);
        backoff.reset();
        assert_eq!(backoff.current(), Duration::from_secs(1));
    }

    #[test]
    fn a_requested_restart_does_not_inherit_crash_backoff() {
        let mut backoff = Backoff::new(Duration::from_secs(1), Duration::from_secs(30));
        backoff.current = Duration::from_secs(30);
        backoff.restart_at(Duration::from_millis(500));
        assert_eq!(backoff.current(), Duration::from_millis(500));
    }

    #[tokio::test(start_paused = true)]
    async fn waiting_sleeps_the_current_delay_and_then_grows_it() {
        let mut backoff = Backoff::new(Duration::from_millis(500), Duration::from_secs(10));
        let start = tokio::time::Instant::now();
        backoff.wait().await;
        assert!(start.elapsed() >= Duration::from_millis(500));
        assert_eq!(backoff.current(), Duration::from_secs(1));
    }
}
