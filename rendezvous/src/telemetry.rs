//! Rendezvous task 4: this service's own counters, plus the NAT-pair punch statistics.
//!
//! This is simultaneously the most valuable telemetry the project collects and the most dangerous
//! to collect, and the shape of this module is entirely a consequence of the second half.
//!
//! A rendezvous service watches **both ends of every attempt to connect** — the single most
//! identifying vantage point in the system. So what leaves is counters and four coarse class
//! labels: no address, no node id, no namespace, no pair identity, no timing that could correlate
//! two peers. What leaves is *"of 4,812 attempts between two hard NATs this hour, 38 % succeeded"* —
//! a number that improves the product and describes nobody.
//!
//! That number is also what [`rendezvous 3`](../../../docs/rendezvous/Task.3.md) has been blocked
//! on: "does hole punching work across the real internet" is unanswerable from one developer's
//! connection and directly answerable from a population of attempts grouped by NAT pair.
//!
//! Off unless `telemetry_endpoint` is configured.

use std::sync::Arc;
use std::time::Duration;

use nodera_telemetry::reporter::{arch_label, install_id, os_label, Reporter, ServiceEvent};
use tokio::sync::Mutex;

use crate::service::Rendezvous;

/// Run the reporting loop until the task is aborted. No-op when telemetry is not configured.
pub async fn run(service: Arc<Mutex<Rendezvous>>) {
    let (endpoint, interval_seconds, bind) = {
        let guard = service.lock().await;
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
        "rendezvous",
        format!("nodera-rendezvous {}", env!("NODERA_VERSION")),
        install_id(&format!("rendezvous@{bind}")),
    );
    reporter.record(
        ServiceEvent::new("service.start", now_millis())
            .label("os", os_label())
            .label("arch", arch_label()),
    );

    let mut previous = Counters::default();
    let mut ticker = tokio::time::interval(Duration::from_secs(interval_seconds));
    ticker.tick().await;
    loop {
        ticker.tick().await;
        let now = now_millis();
        let (current, namespaces, outcomes) = {
            let mut guard = service.lock().await;
            let (registrations, discoveries, reservations, circuits, rejected, namespaces) =
                guard.stats();
            (
                Counters {
                    registrations,
                    discoveries,
                    reservations,
                    circuits,
                    rejected,
                },
                namespaces,
                guard.drain_punch_outcomes(),
            )
        };

        reporter.record(
            ServiceEvent::new("rendezvous.window", now)
                .number(
                    "registrations",
                    current.registrations.saturating_sub(previous.registrations),
                )
                .number(
                    "discoveries",
                    current.discoveries.saturating_sub(previous.discoveries),
                )
                .number(
                    "reservations",
                    current.reservations.saturating_sub(previous.reservations),
                )
                .number(
                    "circuits",
                    current.circuits.saturating_sub(previous.circuits),
                )
                .number(
                    "rejected",
                    current.rejected.saturating_sub(previous.rejected),
                )
                .number("namespaces", namespaces as u64)
                .number("window_seconds", interval_seconds),
        );

        // One row per NAT-pair class that saw any attempt. Classes with no attempts are omitted
        // rather than reported as zero: a zero would be indistinguishable from "this service never
        // sees that pair type", and the difference matters when comparing deployments.
        for (class, attempts, successes) in outcomes {
            reporter.record(
                ServiceEvent::new("rendezvous.punch", now)
                    .number("attempts", attempts)
                    .number("successes", successes)
                    .label("nat_pair", class.label()),
            );
        }

        // Relay volume is a cost signal and a health signal at once: a rising relay fraction means
        // punching is failing somewhere, and relay bandwidth is the one resource this service
        // actually spends.
        reporter.record(
            ServiceEvent::new("rendezvous.relay", now)
                .number(
                    "circuits",
                    current.circuits.saturating_sub(previous.circuits),
                )
                .number("denied", current.rejected.saturating_sub(previous.rejected)),
        );

        previous = current;
        reporter.flush(now).await;
    }
}

#[derive(Default, Clone, Copy)]
struct Counters {
    registrations: u64,
    discoveries: u64,
    reservations: u64,
    circuits: u64,
    rejected: u64,
}

fn now_millis() -> u64 {
    crate::wire::now_millis()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::reservation::ReservationKeeper;

    fn service() -> Arc<Mutex<Rendezvous>> {
        let keeper = ReservationKeeper::new(b"a-test-secret".to_vec(), "127.0.0.1:0".to_owned());
        Arc::new(Mutex::new(Rendezvous::new(Config::default(), keeper)))
    }

    /// The default: nothing is reported and the loop does not run.
    #[tokio::test]
    async fn telemetry_is_off_without_an_endpoint() {
        tokio::time::timeout(Duration::from_secs(2), run(service()))
            .await
            .expect("the reporting loop must return when telemetry is unconfigured");
    }
}
