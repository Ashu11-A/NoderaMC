//! Per-source fixed-window quotas.
//!
//! Every Nodera service is pre-auth by construction — a signature proves who signed, not that they
//! are entitled to a share of the service — so the only thing between an unauthenticated socket and
//! the registry is a bound per source address. All three services had written that bound out
//! themselves: the tracker's `AnnounceQuota` and the rendezvous' `RequestQuota` were identical down
//! to their tests, and telemetry's `IngestQuota` is the same window with a second counter on it.
//!
//! **Fixed windows, not token buckets.** An operator reasons about "N per interval" in the same
//! units the interval is configured in, and the worst case (2N across a window boundary) is bounded
//! and harmless for services whose output is a directory answer or an aggregate statistic.
//!
//! **A limit of `0` disables that limit** rather than blocking every peer. That reading is
//! deliberate and it is load-bearing: the opposite one turns an unset value into a total outage.

use std::collections::HashMap;
use std::net::IpAddr;

/// One source's counters within the current window.
#[derive(Debug, Clone, Copy, Default)]
struct Window {
    started_millis: u64,
    primary: u32,
    secondary: u32,
}

impl Window {
    fn roll(&mut self, window_millis: u64, now_millis: u64) {
        if now_millis.saturating_sub(self.started_millis) >= window_millis {
            self.started_millis = now_millis;
            self.primary = 0;
            self.secondary = 0;
        }
    }
}

/// A fixed-window counter keyed by source IP.
///
/// Used for announces (tracker) and register/reserve requests (rendezvous): one event, one budget.
#[derive(Debug)]
pub struct Quota {
    window_millis: u64,
    limit: u32,
    windows: HashMap<IpAddr, Window>,
}

impl Quota {
    /// A quota of `limit` events per `window_millis`. A `limit` of `0` disables it.
    pub fn new(window_millis: u64, limit: u32) -> Self {
        Self {
            window_millis: window_millis.max(1),
            limit,
            windows: HashMap::new(),
        }
    }

    /// Count one event from `source`; returns whether it is within the quota.
    pub fn admit(&mut self, source: IpAddr, now_millis: u64) -> bool {
        if self.limit == 0 {
            return true;
        }
        let window_millis = self.window_millis;
        let window = self.windows.entry(source).or_insert(Window {
            started_millis: now_millis,
            ..Window::default()
        });
        window.roll(window_millis, now_millis);
        window.primary += 1;
        window.primary <= self.limit
    }

    /// Drop counters whose window has fully elapsed, so a scan of the internet does not leave one
    /// map entry per source address behind forever.
    pub fn sweep(&mut self, now_millis: u64) {
        sweep_windows(&mut self.windows, self.window_millis, now_millis);
    }

    /// How many sources are currently tracked.
    pub fn tracked_sources(&self) -> usize {
        self.windows.len()
    }
}

/// What a two-counter quota decided.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// Within both budgets.
    Admit,
    /// Too many batches from this source in the window.
    TooManyBatches,
    /// The batch count is fine but the source has spent its item budget.
    TooManyItems,
}

/// A fixed-window quota with two budgets: how many batches, and how many items inside them.
///
/// Telemetry ingest needs both because the two abuses are different shapes — many small batches
/// (connection and parse cost) and few enormous ones (storage cost) — and a single counter cannot
/// see one of them.
#[derive(Debug)]
pub struct PairQuota {
    window_millis: u64,
    batch_limit: u32,
    item_limit: u32,
    windows: HashMap<IpAddr, Window>,
}

impl PairQuota {
    /// A quota of `batch_limit` batches and `item_limit` items per `window_millis`. Either `0`
    /// disables that budget alone.
    pub fn new(window_millis: u64, batch_limit: u32, item_limit: u32) -> Self {
        Self {
            window_millis: window_millis.max(1),
            batch_limit,
            item_limit,
            windows: HashMap::new(),
        }
    }

    /// Count one batch of `items` items from `source`.
    ///
    /// The item budget is charged **before** the verdict is known, so a refused batch still costs
    /// its sender: otherwise the cheapest attack is to send oversized batches forever and pay
    /// nothing for the ones that bounce.
    pub fn admit(&mut self, source: IpAddr, items: u32, now_millis: u64) -> Verdict {
        let window_millis = self.window_millis;
        let window = self.windows.entry(source).or_insert(Window {
            started_millis: now_millis,
            ..Window::default()
        });
        window.roll(window_millis, now_millis);
        window.primary = window.primary.saturating_add(1);
        window.secondary = window.secondary.saturating_add(items);

        if self.batch_limit > 0 && window.primary > self.batch_limit {
            return Verdict::TooManyBatches;
        }
        if self.item_limit > 0 && window.secondary > self.item_limit {
            return Verdict::TooManyItems;
        }
        Verdict::Admit
    }

    /// Drop counters whose window has fully elapsed.
    pub fn sweep(&mut self, now_millis: u64) {
        sweep_windows(&mut self.windows, self.window_millis, now_millis);
    }

    /// How many sources are currently tracked.
    pub fn tracked_sources(&self) -> usize {
        self.windows.len()
    }
}

fn sweep_windows(windows: &mut HashMap<IpAddr, Window>, window_millis: u64, now_millis: u64) {
    windows.retain(|_, w| now_millis.saturating_sub(w.started_millis) < window_millis);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(last: u8) -> IpAddr {
        IpAddr::from([203, 0, 113, last])
    }

    #[test]
    fn events_within_the_limit_pass_and_the_next_one_is_refused() {
        let mut quota = Quota::new(1_000, 3);
        for _ in 0..3 {
            assert!(quota.admit(ip(1), 0));
        }
        assert!(!quota.admit(ip(1), 0));
    }

    #[test]
    fn the_window_resets() {
        let mut quota = Quota::new(1_000, 1);
        assert!(quota.admit(ip(1), 0));
        assert!(!quota.admit(ip(1), 999));
        assert!(quota.admit(ip(1), 1_000));
    }

    #[test]
    fn sources_are_counted_independently() {
        let mut quota = Quota::new(1_000, 1);
        assert!(quota.admit(ip(1), 0));
        assert!(quota.admit(ip(2), 0));
        assert!(!quota.admit(ip(1), 0));
    }

    #[test]
    fn sweeping_drops_idle_counters() {
        let mut quota = Quota::new(1_000, 1);
        quota.admit(ip(1), 0);
        quota.admit(ip(2), 0);
        assert_eq!(quota.tracked_sources(), 2);
        quota.sweep(5_000);
        assert_eq!(quota.tracked_sources(), 0);
    }

    #[test]
    fn a_zero_limit_disables_the_quota_rather_than_blocking_every_peer() {
        let mut quota = Quota::new(1_000, 0);
        for _ in 0..1_000 {
            assert!(quota.admit(ip(1), 0));
        }
    }

    #[test]
    fn batches_within_the_limit_pass_and_the_next_one_is_refused() {
        let mut quota = PairQuota::new(60_000, 3, 0);
        for _ in 0..3 {
            assert_eq!(quota.admit(ip(1), 1, 0), Verdict::Admit);
        }
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::TooManyBatches);
    }

    #[test]
    fn the_item_budget_is_separate_from_the_batch_budget() {
        let mut quota = PairQuota::new(60_000, 100, 10);
        assert_eq!(quota.admit(ip(1), 9, 0), Verdict::Admit);
        assert_eq!(quota.admit(ip(1), 2, 0), Verdict::TooManyItems);
    }

    #[test]
    fn a_refused_batch_still_spends_its_budget() {
        let mut quota = PairQuota::new(60_000, 1, 0);
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::Admit);
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::TooManyBatches);
        // Still refused at the same instant: the refusal did not reset anything.
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::TooManyBatches);
    }

    #[test]
    fn the_pair_window_resets_and_counts_sources_independently() {
        let mut quota = PairQuota::new(1_000, 1, 0);
        assert_eq!(quota.admit(ip(1), 1, 0), Verdict::Admit);
        assert_eq!(quota.admit(ip(1), 1, 999), Verdict::TooManyBatches);
        assert_eq!(quota.admit(ip(1), 1, 1_000), Verdict::Admit);
        assert_eq!(quota.admit(ip(2), 1, 1_000), Verdict::Admit);
    }

    #[test]
    fn zero_disables_a_limit_rather_than_blocking_everything() {
        let mut quota = PairQuota::new(60_000, 0, 0);
        for _ in 0..1_000 {
            assert_eq!(quota.admit(ip(1), 1_000, 0), Verdict::Admit);
        }
    }

    #[test]
    fn sweeping_drops_idle_pair_counters() {
        let mut quota = PairQuota::new(1_000, 1, 0);
        quota.admit(ip(1), 1, 0);
        quota.admit(ip(2), 1, 0);
        assert_eq!(quota.tracked_sources(), 2);
        quota.sweep(5_000);
        assert_eq!(quota.tracked_sources(), 0);
    }

    #[test]
    fn a_saturating_item_count_does_not_wrap_into_admission() {
        let mut quota = PairQuota::new(60_000, 0, 10);
        assert_eq!(quota.admit(ip(1), u32::MAX, 0), Verdict::TooManyItems);
        assert_eq!(quota.admit(ip(1), u32::MAX, 0), Verdict::TooManyItems);
    }
}
