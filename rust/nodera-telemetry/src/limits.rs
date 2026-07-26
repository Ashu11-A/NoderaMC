//! Per-source quotas.
//!
//! Telemetry is deliberately **unauthenticated and unsigned** (`docs/telemetry/Task.1.md` §Design):
//! signing a report with a node's Ed25519 key would tie every measurement to the identity the rest
//! of the network knows a peer by, which is exactly the linkage this pipeline exists to avoid. The
//! price is that anyone can submit, so the only thing standing between the warehouse and a flood
//! is a bound per source address.
//!
//! Two counters, because the two abuses are different shapes: many small batches (connection and
//! parse cost) and few enormous ones (storage cost).

use std::collections::HashMap;
use std::net::IpAddr;

/// A fixed-window pair of counters keyed by source IP.
///
/// Fixed windows rather than token buckets, matching `nodera-tracker`: an operator reasons in the
/// same units the configuration is written in, and the worst case (2× across a boundary) is
/// bounded and harmless for a service whose output is aggregate statistics.
#[derive(Debug)]
pub struct IngestQuota {
    window_millis: u64,
    batch_limit: u32,
    event_limit: u32,
    windows: HashMap<IpAddr, Window>,
}

#[derive(Debug, Clone, Copy)]
struct Window {
    started_millis: u64,
    batches: u32,
    events: u32,
}

/// What a quota decision was.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    Admit,
    /// Too many batches from this source in the window.
    TooManyBatches,
    /// The batch count is fine but the source has spent its event budget.
    TooManyEvents,
}

impl IngestQuota {
    pub fn new(window_millis: u64, batch_limit: u32, event_limit: u32) -> Self {
        Self {
            window_millis: window_millis.max(1),
            batch_limit,
            event_limit,
            windows: HashMap::new(),
        }
    }

    /// Count one batch of `events` events from `source`.
    ///
    /// The event budget is charged **before** the verdict is known, so a refused batch still costs
    /// its sender: otherwise the cheapest attack is to send oversized batches forever and pay
    /// nothing for the ones that bounce.
    pub fn admit(&mut self, source: IpAddr, events: u32, now_millis: u64) -> Verdict {
        let window = self.windows.entry(source).or_insert(Window {
            started_millis: now_millis,
            batches: 0,
            events: 0,
        });
        if now_millis.saturating_sub(window.started_millis) >= self.window_millis {
            window.started_millis = now_millis;
            window.batches = 0;
            window.events = 0;
        }
        window.batches = window.batches.saturating_add(1);
        window.events = window.events.saturating_add(events);

        if self.batch_limit > 0 && window.batches > self.batch_limit {
            return Verdict::TooManyBatches;
        }
        if self.event_limit > 0 && window.events > self.event_limit {
            return Verdict::TooManyEvents;
        }
        Verdict::Admit
    }

    /// Drop counters whose window has fully elapsed, so an internet-wide scan does not leave one
    /// map entry per source behind forever.
    pub fn sweep(&mut self, now_millis: u64) {
        let window_millis = self.window_millis;
        self.windows
            .retain(|_, w| now_millis.saturating_sub(w.started_millis) < window_millis);
    }

    #[cfg(test)]
    pub fn tracked_sources(&self) -> usize {
        self.windows.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(last: u8) -> IpAddr {
        IpAddr::from([198, 51, 100, last])
    }

    #[test]
    fn batches_within_the_limit_pass_and_the_next_one_is_refused() {
        let mut quota = IngestQuota::new(60_000, 3, 0);
        for _ in 0..3 {
            assert_eq!(quota.admit(ip(1), 1, 0), Verdict::Admit);
        }
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::TooManyBatches);
    }

    #[test]
    fn the_event_budget_is_separate_from_the_batch_budget() {
        let mut quota = IngestQuota::new(60_000, 100, 10);
        assert_eq!(quota.admit(ip(1), 9, 0), Verdict::Admit);
        assert_eq!(quota.admit(ip(1), 2, 0), Verdict::TooManyEvents);
    }

    #[test]
    fn a_refused_batch_still_spends_its_budget() {
        let mut quota = IngestQuota::new(60_000, 1, 0);
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::Admit);
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::TooManyBatches);
        // Still refused at the same instant: the refusal did not reset anything.
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::TooManyBatches);
    }

    #[test]
    fn the_window_resets() {
        let mut quota = IngestQuota::new(1_000, 1, 0);
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::Admit);
        assert_eq!(quota.admit(ip(1), 1, 999), Verdict::TooManyBatches);
        assert_eq!(quota.admit(ip(1), 1, 1_000), Verdict::Admit);
    }

    #[test]
    fn sources_are_counted_independently() {
        let mut quota = IngestQuota::new(60_000, 1, 0);
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::Admit);
        assert_eq!(quota.admit(ip(2), 1, 0), Verdict::Admit);
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::TooManyBatches);
    }

    #[test]
    fn zero_disables_a_limit_rather_than_blocking_everything() {
        let mut quota = IngestQuota::new(60_000, 0, 0);
        for _ in 0..1_000 {
            assert_eq!(quota.admit(ip(1), 1_000, 0), Verdict::Admit);
        }
    }

    #[test]
    fn sweeping_drops_idle_counters() {
        let mut quota = IngestQuota::new(1_000, 1, 0);
        quota.admit(ip(1), 1, 0);
        quota.admit(ip(2), 1, 0);
        assert_eq!(quota.tracked_sources(), 2);
        quota.sweep(5_000);
        assert_eq!(quota.tracked_sources(), 0);
    }

    #[test]
    fn a_saturating_event_count_does_not_wrap_into_admission() {
        let mut quota = IngestQuota::new(60_000, 0, 10);
        assert_eq!(quota.admit(ip(1), u32::MAX, 0), Verdict::TooManyEvents);
        assert_eq!(quota.admit(ip(1), u32::MAX, 0), Verdict::TooManyEvents);
    }
}
