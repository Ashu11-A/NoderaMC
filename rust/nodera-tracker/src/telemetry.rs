//! Tracker task 4: this service's own counters, reported on a window.
//!
//! What leaves: how much traffic the service carried, how much it refused, how many worlds it
//! tracks and how healthy they are. What never leaves: who announced, what they announced, and
//! where from. The tracker sees source addresses, node ids, and genesis hashes on every request,
//! and none of them can be attached to a `ServiceEvent` — it holds numbers and `'static` labels
//! only, so a value derived from a request is not even representable.
//!
//! Off unless `telemetry_endpoint` is configured.

use std::sync::Arc;
use std::time::Duration;

use nodera_telemetry::reporter::{install_id, Reporter, ServiceEvent};
use tokio::sync::Mutex;

use crate::service::Tracker;

/// Run the reporting loop until the task is aborted. No-op when telemetry is not configured.
pub async fn run(tracker: Arc<Mutex<Tracker>>) {
    let (endpoint, interval_seconds, bind) = {
        let guard = tracker.lock().await;
        let config = guard.config();
        (
            config.telemetry_endpoint.clone(),
            config.telemetry_interval_seconds.max(30),
            config.bind_addr.to_string(),
        )
    };
    if endpoint.trim().is_empty() {
        return;
    }

    let mut reporter = Reporter::new(
        &endpoint,
        "tracker",
        format!("nodera-tracker {}", env!("NODERA_VERSION")),
        // Identifies this deployment, not any peer, and survives a restart without state on disk.
        install_id(&format!("tracker@{bind}")),
    );

    // The service announces itself once, so a dashboard can tell "no traffic" from "not running".
    reporter.record(
        ServiceEvent::new("service.start", now_millis())
            .label("os", os_label())
            .label("arch", arch_label()),
    );

    // Window deltas: the counters are cumulative, and a dashboard wants rates.
    let mut previous = Counters::default();
    let mut ticker = tokio::time::interval(Duration::from_secs(interval_seconds));
    ticker.tick().await; // the first tick fires immediately
    loop {
        ticker.tick().await;
        let now = now_millis();
        let (current, worlds, health) = {
            let guard = tracker.lock().await;
            let (accepted, rejected, queries, worlds) = guard.stats();
            (
                Counters {
                    accepted,
                    rejected,
                    queries,
                },
                worlds,
                guard.world_health_counts(now),
            )
        };

        reporter.record(
            ServiceEvent::new("tracker.window", now)
                .number(
                    "announces",
                    current.accepted.saturating_sub(previous.accepted),
                )
                .number(
                    "announces_rejected",
                    current.rejected.saturating_sub(previous.rejected),
                )
                .number("queries", current.queries.saturating_sub(previous.queries))
                .number("worlds", worlds as u64)
                .number("window_seconds", interval_seconds),
        );
        reporter.record(
            ServiceEvent::new("tracker.world_health", now)
                .number("healthy", health.0)
                .number("degraded", health.1)
                .number("dead", health.2),
        );
        previous = current;

        reporter.flush(now).await;
    }
}

#[derive(Default, Clone, Copy)]
struct Counters {
    accepted: u64,
    rejected: u64,
    queries: u64,
}

fn now_millis() -> u64 {
    crate::wire::now_millis()
}

fn os_label() -> &'static str {
    match std::env::consts::OS {
        "linux" => "linux",
        "macos" => "macos",
        "windows" => "windows",
        _ => "other",
    }
}

fn arch_label() -> &'static str {
    match std::env::consts::ARCH {
        "x86_64" => "x86_64",
        "aarch64" => "aarch64",
        _ => "other",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;

    /// The default configuration reports nothing at all: the loop returns before it builds a
    /// reporter, so no socket is opened and no task keeps running.
    #[tokio::test]
    async fn telemetry_is_off_without_an_endpoint() {
        let tracker = Arc::new(Mutex::new(Tracker::new(Config::default())));
        // Returns immediately rather than looping — if it did not, this test would hang.
        tokio::time::timeout(Duration::from_secs(2), run(tracker))
            .await
            .expect("the reporting loop must return when telemetry is unconfigured");
    }

    #[test]
    fn platform_labels_are_members_of_the_declared_enums() {
        assert!(["linux", "macos", "windows", "other"].contains(&os_label()));
        assert!(["x86_64", "aarch64", "other"].contains(&arch_label()));
    }
}
