//! `nodera-rendezvous` — the standalone Nodera rendezvous + relay service (Task 29).
//!
//! Two logically separate responsibilities in one binary (docs/rendezvous/REFERENCE.md):
//!
//! * **Rendezvous** — peers *register* signed candidate records under a `(network, world)`
//!   namespace and *discover* each other; cheap metadata, TTL'd, quota'd.
//! * **Relay** — a peer *reserves* an inbound slot, and when no direct path exists the service
//!   *bridges* an end-to-end-encrypted circuit between two peers, metered against the reservation.
//!
//! **It carries no authority** (Task 0 §4 rule 7). Records are self-signed and verified against the
//! same canonical bytes a discovering peer checks; circuit payloads are end-to-end-encrypted, so a
//! lying relay can hide peers or refuse to forward, never forge a record or read a payload. Losing
//! the service degrades reachability, never correctness.
//!
//! ```text
//! nodera-rendezvous --config nodera-rendezvous.toml
//! nodera-rendezvous --healthcheck 127.0.0.1:25601
//! nodera-rendezvous --version
//! ```
//!
//! The command line, the configuration layering, the identity file and the shutdown signal are
//! `nodera-service`'s — identical in all three services, and shared so they cannot drift.

mod circuit;
mod config;
mod discover;
mod punch;
mod register;
mod registry;
mod reservation;
mod service;
mod telemetry;
#[cfg(test)]
mod test_support;
mod wire;

use config::Config;
use nodera_service::cli;
use nodera_service::serve::shutdown_signal;
use reservation::ReservationKeeper;
use service::Rendezvous;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use tokio::sync::Mutex;

const NAME: &str = "nodera-rendezvous";

#[tokio::main]
async fn main() -> ExitCode {
    cli::run(
        cli::Service {
            name: NAME,
            usage: "usage: nodera-rendezvous [--config <file>] [--bind <addr>] \
                    [--healthcheck <addr>] [--print-env] [--version]",
            version: env!("NODERA_VERSION"),
            env_reference: Config::env_reference,
            extra: &[],
        },
        serve,
        |addr| async move { healthcheck(&addr).await },
    )
    .await
}

async fn serve(
    config_path: Option<PathBuf>,
    bind_override: Option<String>,
) -> Result<(), Box<dyn std::error::Error>> {
    let config: Config = nodera_service::config::configure(NAME, config_path, bind_override)?;
    let (listener, bound) = nodera_service::config::bind(NAME, config.bind_addr).await?;

    // The reservation HMAC key: the configured one keeps proofs valid across a restart; otherwise
    // an ephemeral key is minted (fine for a single process — reservations are short-lived anyway).
    let key = config
        .reservation_hmac_key()
        .unwrap_or_else(|| ephemeral_key(&bound.to_string()));
    let keeper = ReservationKeeper::new(key, bound.to_string());

    let identity = Arc::new(nodera_service::identity::ServiceIdentity::load_or_generate(
        NAME,
        &config.identity_file,
    )?);

    let lifecycle = nodera_service::lifecycle::Lifecycle::new();
    let lifecycle_config = nodera_service::lifecycle::LifecycleConfig {
        tracker_endpoints: config.tracker_endpoints.clone(),
        routes: config.routes(),
        network_id: nodera_codec::types::NetworkId::new(0, 0),
        version: env!("NODERA_VERSION").to_owned(),
        record_ttl_seconds: config.registration_ttl_seconds,
        announce_interval_seconds: u64::from(config.refresh_interval_seconds),
        update: config.update_config(),
    };
    let max_circuits = config.max_concurrent_circuits;

    // The service shares the drain state, so the reserve and connect paths refuse new work the
    // instant the decision to stop is made — before any peer has been told.
    let rendezvous = Arc::new(Mutex::new(Rendezvous::with_drain(
        config,
        keeper,
        Arc::clone(lifecycle.drain()),
    )));
    // The control-channel map is shared with the lifecycle task so a drain notice can be pushed down
    // the very sockets the reserved peers are already listening on.
    let channels = wire::control_channels();
    // Operator-configured, off by default, on its own task: a telemetry outage must never reach a
    // registration, a discovery, a punch, or a relay (`docs/rendezvous/Task.4.md`).
    let telemetry_task = tokio::spawn(telemetry::run(Arc::clone(&rendezvous)));

    let (stop_wire_tx, stop_wire_rx) = tokio::sync::oneshot::channel::<()>();
    let host = Arc::new(RendezvousHost {
        rendezvous: Arc::clone(&rendezvous),
        channels: Arc::clone(&channels),
        max_circuits,
    });
    let lifecycle_task = tokio::spawn({
        let lifecycle = Arc::clone(&lifecycle);
        let identity = Arc::clone(&identity);
        async move {
            let outcome = nodera_service::lifecycle::run(
                host,
                lifecycle,
                identity,
                lifecycle_config,
                Arc::new(nodera_service::update::HttpsFetcher {
                    timeout_seconds: 120,
                    ..Default::default()
                }),
                shutdown_signal(),
            )
            .await;
            // Only now does the listener close: the drain has already refused new work, told the
            // peers, and waited for the circuits.
            let _ = stop_wire_tx.send(());
            outcome
        }
    });

    wire::run(rendezvous, listener, Some(channels), async move {
        let _ = stop_wire_rx.await;
    })
    .await?;
    telemetry_task.abort();
    let outcome = lifecycle_task
        .await
        .unwrap_or(nodera_service::lifecycle::Outcome::Stopped);
    if outcome == nodera_service::lifecycle::Outcome::Updated {
        let exe = std::env::current_exe()?;
        println!("nodera-rendezvous: restarting into the updated binary");
        return Err(nodera_service::update::restart_into(&exe).into());
    }
    println!("nodera-rendezvous: stopped");
    Ok(())
}

/// The rendezvous' answers to the shared lifecycle's questions about itself.
struct RendezvousHost {
    rendezvous: Arc<Mutex<Rendezvous>>,
    channels: wire::ControlChannels,
    max_circuits: u32,
}

impl nodera_service::lifecycle::ServiceHost for RendezvousHost {
    fn kind(&self) -> nodera_codec::service::ServiceKind {
        nodera_codec::service::ServiceKind::Rendezvous
    }

    fn capacity(&self) -> nodera_service::lifecycle::CapacitySnapshot {
        // `try_lock`: a busy relay reports nothing rather than stalling its own announce behind the
        // request path it is trying to describe.
        let Ok(service) = self.rendezvous.try_lock() else {
            return nodera_service::lifecycle::CapacitySnapshot::default();
        };
        let (_, _, _, _, rejected, _) = service.stats();
        nodera_service::lifecycle::CapacitySnapshot {
            active_sessions: service.registration_count().try_into().unwrap_or(u32::MAX),
            max_sessions: service
                .config()
                .max_records_per_namespace
                .saturating_mul(service.config().max_namespaces)
                .try_into()
                .unwrap_or(u32::MAX),
            active_circuits: service.drain().in_flight().try_into().unwrap_or(u32::MAX),
            max_circuits: self.max_circuits,
            rejected_last_window: rejected.try_into().unwrap_or(u32::MAX),
        }
    }

    fn notify_peers(&self, notice: &nodera_codec::service::ServiceDrainNotice) -> usize {
        // This is the braces to the tracker's belt. A peer holding a reservation here has an open
        // socket to this process — the fastest and most certain way to reach exactly the peers whose
        // inbound path is about to vanish, and the ones for whom rediscovery is slowest.
        let frame = nodera_codec::service::ServiceMessage::DrainNotice(notice.clone()).encode();
        wire::broadcast_frame(&self.channels, &frame)
    }
}

/// Derive an unpredictable-enough ephemeral HMAC key from the boot instant and bound address.
///
/// Reservations live minutes; the key only needs to be unguessable for that window and unique to
/// this process. An operator who needs proofs to survive a restart sets `reservation_hmac_key_hex`.
fn ephemeral_key(bound: &str) -> Vec<u8> {
    use sha2::{Digest, Sha256};
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let mut hasher = Sha256::new();
    hasher.update(nanos.to_be_bytes());
    hasher.update(std::process::id().to_be_bytes());
    hasher.update(bound.as_bytes());
    hasher.finalize().to_vec()
}

/// Probe a running service: a discovery for the all-zero namespace proves the listener decodes
/// canonical frames and answers, without needing any real namespace.
async fn healthcheck(addr: &str) -> Result<(), Box<dyn std::error::Error>> {
    use nodera_codec::rendezvous::{RendezvousDiscover, RendezvousMessage};
    use nodera_codec::types::NetworkId;
    use nodera_service::frame::{read_frame, write_frame};
    let mut stream =
        tokio::net::TcpStream::connect(nodera_service::endpoint::socket_target(addr)).await?;
    let probe = RendezvousMessage::Discover(RendezvousDiscover {
        network_id: NetworkId::new(0, 0),
        genesis_hash: vec![0u8; 32],
        cursor: 0,
        limit: 1,
    })
    .encode();
    write_frame(&mut stream, &probe).await?;
    let reply = read_frame(&mut stream, 1 << 20)
        .await?
        .ok_or("service closed the connection without answering")?;
    match RendezvousMessage::decode(&reply)? {
        RendezvousMessage::Peers(_) => Ok(()),
        other => Err(format!("unexpected reply tag {}", other.tag()).into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_ephemeral_key_is_32_bytes() {
        assert_eq!(ephemeral_key("127.0.0.1:1").len(), 32);
    }
}
