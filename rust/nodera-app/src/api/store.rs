//! The one place the dashboard's current picture lives, and the only place it changes.
//!
//! Two jobs, both of which used to be spread across the app and got them wrong:
//!
//! 1. **Provenance.** Accepting a snapshot stamps it with a clock reading and a revision. Losing
//!    the link marks the picture stale *without erasing it* — the last thing we heard is still the
//!    most useful thing to show, as long as the screen says how old it is. The failure mode this
//!    replaces is a UI that keeps rendering dated numbers as though they were current.
//!
//! 2. **Rates.** Throughput is measured here, between two accepted snapshots, against a real
//!    elapsed time. Not in React (which sees renders, not samples) and not assumed from the event
//!    cadence (which is a wish, not a measurement).

use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::api::model::{Dashboard, Link, LinkStatus, Rates};
use crate::metrics::Metrics;

/// Epoch millis, saturating at zero — a clock before 1970 is not worth a panic.
fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// The previous accepted sample, kept only to difference the next one against.
#[derive(Clone, Copy)]
struct Sample {
    sent: u64,
    received: u64,
    at: Instant,
}

/// Shared, revisioned dashboard state.
pub struct DashboardStore {
    inner: Mutex<Inner>,
}

struct Inner {
    dashboard: Dashboard,
    previous: Option<Sample>,
    revision: u64,
    reconnects: u64,
}

impl Default for DashboardStore {
    fn default() -> Self {
        Self::new()
    }
}

impl DashboardStore {
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(Inner {
                dashboard: Dashboard::default(),
                previous: None,
                revision: 0,
                reconnects: 0,
            }),
        }
    }

    /// Take one snapshot from the worker and make it the current picture.
    ///
    /// `transport` says what carried it (`stream` or `poll`) so the UI can show whether it is being
    /// pushed to or is asking.
    ///
    /// Returns the dashboard as the UI should now see it.
    pub fn accept(&self, metrics: &Metrics, transport: &'static str) -> Dashboard {
        let mut inner = self.inner.lock().unwrap();
        let now = Instant::now();
        let rates = inner
            .previous
            .and_then(|previous| rates_between(previous, metrics, now))
            .unwrap_or_default();
        inner.previous = Some(Sample {
            sent: metrics.total_sent_bytes,
            received: metrics.total_received_bytes,
            at: now,
        });
        inner.revision += 1;

        let mut dashboard = Dashboard::of(metrics, rates);
        let stamp = now_ms();
        dashboard.link = Link {
            status: if transport == "stream" {
                LinkStatus::Live
            } else {
                LinkStatus::Polling
            },
            transport: transport.to_owned(),
            worker_version: metrics.worker_version.clone(),
            last_update_ms: stamp,
            age_ms: 0,
            revision: inner.revision,
            last_error: String::new(),
            reconnects: inner.reconnects,
            has_data: true,
        };
        inner.dashboard = dashboard.clone();
        dashboard
    }

    /// Mark the link down, keeping the last picture but saying so.
    ///
    /// The values are deliberately not cleared. Blanking the screen on every hiccup loses the only
    /// information available about a node that has gone quiet, and a user cannot tell a blanked
    /// dashboard from a broken one. Ageing it is honest and useful; zeroing it is neither.
    pub fn mark_offline(&self, reason: impl Into<String>) -> Dashboard {
        let mut inner = self.inner.lock().unwrap();
        inner.dashboard.link.status = LinkStatus::Offline;
        inner.dashboard.link.transport = "none".to_owned();
        inner.dashboard.link.last_error = reason.into();
        // The rate baseline is dropped: differencing across an outage would attribute a whole gap
        // to one interval and print a spike that never happened.
        inner.previous = None;
        inner.dashboard.clone()
    }

    /// Note that the link is being re-established (counted, so a flapping worker is visible).
    pub fn mark_reconnecting(&self, reason: impl Into<String>) -> Dashboard {
        let mut inner = self.inner.lock().unwrap();
        inner.reconnects += 1;
        inner.dashboard.link.reconnects = inner.reconnects;
        inner.dashboard.link.status = LinkStatus::Connecting;
        inner.dashboard.link.transport = "none".to_owned();
        inner.dashboard.link.last_error = reason.into();
        inner.previous = None;
        inner.dashboard.clone()
    }

    /// The current picture, with its age computed as of *now* rather than as of acceptance.
    pub fn snapshot(&self) -> Dashboard {
        let inner = self.inner.lock().unwrap();
        let mut dashboard = inner.dashboard.clone();
        dashboard.link.age_ms = if dashboard.link.last_update_ms == 0 {
            0
        } else {
            now_ms().saturating_sub(dashboard.link.last_update_ms)
        };
        dashboard
    }
}

/// Bytes per second between two samples, or `None` when the interval is too short to divide by.
///
/// Counters only climb, so a decrease means the worker restarted and reset them; that is reported
/// as zero rather than as a negative or a wrap-around.
fn rates_between(previous: Sample, metrics: &Metrics, now: Instant) -> Option<Rates> {
    let elapsed = now.saturating_duration_since(previous.at);
    if elapsed < Duration::from_millis(20) {
        return None;
    }
    let seconds = elapsed.as_secs_f64();
    let up = metrics.total_sent_bytes.saturating_sub(previous.sent);
    let down = metrics.total_received_bytes.saturating_sub(previous.received);
    Some(Rates {
        up: Some((up as f64 / seconds).round() as u64),
        down: Some((down as f64 / seconds).round() as u64),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn metrics(sent: u64, received: u64) -> Metrics {
        Metrics {
            node_id: "n".into(),
            worker_version: "1.2.3".into(),
            total_sent_bytes: sent,
            total_received_bytes: received,
            ..Metrics::default()
        }
    }

    #[test]
    fn a_store_that_has_heard_nothing_says_so() {
        let store = DashboardStore::new();
        let dash = store.snapshot();

        // The distinction the whole module exists for: this is not "the worker reported zero".
        assert!(!dash.link.has_data);
        assert_eq!(dash.link.status, LinkStatus::Connecting);
        assert_eq!(dash.link.revision, 0);
    }

    #[test]
    fn the_first_snapshot_has_no_rate_because_a_rate_needs_two() {
        let store = DashboardStore::new();
        let dash = store.accept(&metrics(3_400_000_000, 1_000), "stream");

        // The bug this pins: differencing a lifetime counter against a zeroed placeholder once
        // rendered 3393.9 MB/s on a node's first frame.
        assert_eq!(dash.traffic.up_bytes_per_sec, None);
        assert!(dash.link.has_data);
        assert_eq!(dash.link.status, LinkStatus::Live);
        assert_eq!(dash.link.worker_version, "1.2.3");
    }

    #[test]
    fn a_rate_is_measured_between_two_accepted_snapshots() {
        let store = DashboardStore::new();
        store.accept(&metrics(0, 0), "stream");
        std::thread::sleep(Duration::from_millis(60));
        let dash = store.accept(&metrics(6_000, 0), "stream");

        let up = dash.traffic.up_bytes_per_sec.expect("two samples make a rate");
        // ~6000 bytes over ~60 ms is ~100 KB/s. Loose bounds: the assertion is that it is measured
        // against elapsed time, not that the test machine has a real-time scheduler.
        assert!(up > 20_000 && up < 400_000, "unexpected rate {up}");
        assert_eq!(dash.link.revision, 2);
    }

    #[test]
    fn a_worker_restart_reads_as_zero_throughput_not_as_a_negative_spike() {
        let store = DashboardStore::new();
        store.accept(&metrics(9_000_000, 9_000_000), "stream");
        std::thread::sleep(Duration::from_millis(30));
        // Counters reset: the worker is a new process.
        let dash = store.accept(&metrics(10, 10), "stream");

        assert_eq!(dash.traffic.up_bytes_per_sec, Some(0));
        assert_eq!(dash.traffic.down_bytes_per_sec, Some(0));
    }

    #[test]
    fn going_offline_keeps_the_picture_and_dates_it() {
        let store = DashboardStore::new();
        store.accept(&metrics(1, 1), "stream");

        let dash = store.mark_offline("the worker is unreachable on 127.0.0.1:25610");

        // Still there — a stale reading with an age beats a blank screen with no explanation.
        assert!(dash.link.has_data);
        assert_eq!(dash.link.status, LinkStatus::Offline);
        assert_eq!(dash.link.transport, "none");
        assert!(dash.link.last_error.contains("unreachable"));
        assert_eq!(dash.node.node_id, "n");
    }

    #[test]
    fn no_rate_is_computed_across_an_outage() {
        let store = DashboardStore::new();
        store.accept(&metrics(0, 0), "stream");
        store.mark_offline("gone");
        std::thread::sleep(Duration::from_millis(30));
        let dash = store.accept(&metrics(50_000_000, 0), "poll");

        // Differencing across the gap would attribute the whole outage's traffic to one interval.
        assert_eq!(dash.traffic.up_bytes_per_sec, None);
        assert_eq!(dash.link.status, LinkStatus::Polling);
    }

    #[test]
    fn reconnects_are_counted_so_a_flapping_worker_is_visible() {
        let store = DashboardStore::new();
        store.accept(&metrics(1, 1), "stream");
        store.mark_reconnecting("stream ended");
        let dash = store.mark_reconnecting("stream ended");

        assert_eq!(dash.link.reconnects, 2);
        assert_eq!(dash.link.status, LinkStatus::Connecting);
    }

    #[test]
    fn age_is_computed_when_read_not_when_accepted() {
        let store = DashboardStore::new();
        let accepted = store.accept(&metrics(1, 1), "stream");
        assert_eq!(accepted.link.age_ms, 0);

        std::thread::sleep(Duration::from_millis(40));
        let read = store.snapshot();

        // A UI polling the store must see the picture get older, or "live" means nothing.
        assert!(read.link.age_ms >= 30, "age was {}", read.link.age_ms);
    }

    #[test]
    fn revision_climbs_even_when_every_value_is_unchanged() {
        let store = DashboardStore::new();
        store.accept(&metrics(7, 7), "stream");
        let second = store.accept(&metrics(7, 7), "stream");

        // How a UI tells "nothing is happening" from "nothing is arriving".
        assert_eq!(second.link.revision, 2);
    }
}
