//! Per-source announce quotas.
//!
//! The tracker is pre-auth by construction — a signature proves who signed, not that they are
//! entitled to a share of the service. The quota is what keeps one host from filling the registry
//! or burning the CPU on signature checks (`docs/torrent/trackers.md` §26).

use std::collections::HashMap;
use std::net::IpAddr;

/// A fixed-window announce counter keyed by source IP.
///
/// Fixed windows, not a token bucket: an operator reasons about "N announces per interval" in the
/// same units as the announce interval itself, and the worst case (2N across a window boundary) is
/// bounded and harmless here.
#[derive(Debug)]
pub struct AnnounceQuota {
    window_millis: u64,
    limit: u32,
    windows: HashMap<IpAddr, Window>,
}

#[derive(Debug, Clone, Copy)]
struct Window {
    started_millis: u64,
    count: u32,
}

impl AnnounceQuota {
    /// A quota of `limit` announces per `window_millis`.
    pub fn new(window_millis: u64, limit: u32) -> Self {
        Self {
            window_millis: window_millis.max(1),
            limit,
            windows: HashMap::new(),
        }
    }

    /// Count one announce from `source`; returns whether it is within the quota.
    pub fn admit(&mut self, source: IpAddr, now_millis: u64) -> bool {
        if self.limit == 0 {
            return true; // 0 disables the quota rather than blocking every peer.
        }
        let window = self.windows.entry(source).or_insert(Window {
            started_millis: now_millis,
            count: 0,
        });
        if now_millis.saturating_sub(window.started_millis) >= self.window_millis {
            window.started_millis = now_millis;
            window.count = 0;
        }
        window.count += 1;
        window.count <= self.limit
    }

    /// Drop counters whose window has fully elapsed, so a scan of the internet does not leave one
    /// map entry per source address behind forever.
    pub fn sweep(&mut self, now_millis: u64) {
        let window_millis = self.window_millis;
        self.windows
            .retain(|_, w| now_millis.saturating_sub(w.started_millis) < window_millis);
    }

    /// How many sources are currently tracked.
    #[cfg(test)]
    pub fn tracked_sources(&self) -> usize {
        self.windows.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(last: u8) -> IpAddr {
        IpAddr::from([203, 0, 113, last])
    }

    #[test]
    fn announces_within_the_limit_pass_and_the_next_one_is_refused() {
        let mut quota = AnnounceQuota::new(1_000, 3);
        for _ in 0..3 {
            assert!(quota.admit(ip(1), 0));
        }
        assert!(!quota.admit(ip(1), 0));
    }

    #[test]
    fn the_window_resets() {
        let mut quota = AnnounceQuota::new(1_000, 1);
        assert!(quota.admit(ip(1), 0));
        assert!(!quota.admit(ip(1), 999));
        assert!(quota.admit(ip(1), 1_000));
    }

    #[test]
    fn sources_are_counted_independently() {
        let mut quota = AnnounceQuota::new(1_000, 1);
        assert!(quota.admit(ip(1), 0));
        assert!(quota.admit(ip(2), 0));
        assert!(!quota.admit(ip(1), 0));
    }

    #[test]
    fn sweeping_drops_idle_counters() {
        let mut quota = AnnounceQuota::new(1_000, 1);
        quota.admit(ip(1), 0);
        quota.admit(ip(2), 0);
        assert_eq!(quota.tracked_sources(), 2);
        quota.sweep(5_000);
        assert_eq!(quota.tracked_sources(), 0);
    }

    #[test]
    fn a_zero_limit_disables_the_quota() {
        let mut quota = AnnounceQuota::new(1_000, 0);
        for _ in 0..1_000 {
            assert!(quota.admit(ip(1), 0));
        }
    }
}
