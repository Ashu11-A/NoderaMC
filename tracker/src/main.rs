//! `nodera-tracker` — the standalone Nodera tracker service (Task 28).
//!
//! Always-on discovery infrastructure: peers **announce** to it and **query** it, so a world's
//! list survives its host peer going offline. It answers the frozen Task 20 wire family, which is
//! why the Java read path (`TrackerDataSource` → the Task 26 multiplayer GUI) needed no changes
//! when the role moved out of process.
//!
//! **It carries no authority** (Task 0 §4 rule 7). A lying tracker can hide peers or invent
//! unreachable ones; it cannot forge world state (hash-verified) or identities (Ed25519-signed
//! records, verified here before anything is recorded). Losing the tracker degrades discovery,
//! never correctness.
//!
//! ```text
//! nodera-tracker --config nodera-tracker.toml
//! nodera-tracker --healthcheck 127.0.0.1:25600
//! nodera-tracker --version
//! ```
//!
//! The command line, the configuration layering, the identity file and the shutdown signal are
//! `nodera-service`'s — identical in all three services, and shared so they cannot drift.

mod announce;
mod config;
mod deletion;
mod health;
mod query;
mod registry;
mod service;
mod services;
mod telemetry;
#[cfg(test)]
mod test_support;
mod wire;

use config::Config;
use nodera_service::cli;
use nodera_service::serve::shutdown_signal;
use service::Tracker;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use tokio::net::UdpSocket;
use tokio::sync::Mutex;

const NAME: &str = "nodera-tracker";

#[tokio::main]
async fn main() -> ExitCode {
    cli::run(
        cli::Service {
            name: NAME,
            usage: "usage: nodera-tracker [--config <file>] [--bind <addr>] \
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

    // The UDP surface binds the port the TCP listener actually got, so `--bind 127.0.0.1:0` gives
    // both surfaces the same port and a peer can reach either at one address.
    let udp = if config.udp_enabled {
        match UdpSocket::bind(bound).await {
            Ok(socket) => {
                println!("nodera-tracker: udp listening on {bound}");
                Some(socket)
            }
            Err(e) => {
                // A refused UDP bind must not take the service down: TCP is the complete surface,
                // UDP is the cheap one. Say so loudly rather than appearing to serve both.
                eprintln!("nodera-tracker: udp bind on {bound} failed ({e}); serving TCP only");
                None
            }
        }
    } else {
        None
    };

    let lifecycle_config = nodera_service::lifecycle::LifecycleConfig {
        tracker_endpoints: config.peer_tracker_endpoints.clone(),
        routes: config.routes(),
        network_id: nodera_codec::types::NetworkId::new(0, 0),
        version: env!("NODERA_VERSION").to_owned(),
        record_ttl_seconds: config.peer_ttl_seconds,
        announce_interval_seconds: u64::from(config.announce_interval_seconds),
        update: config.update_config(),
    };
    let identity = Arc::new(nodera_service::identity::ServiceIdentity::load_or_generate(
        NAME,
        &config.identity_file,
    )?);

    let tracker = Arc::new(Mutex::new(Tracker::new(config)));
    let lifecycle = nodera_service::lifecycle::Lifecycle::new();
    // Operator-configured, off by default, and on its own task: a telemetry outage must never
    // reach an announce or a query (`docs/tracker/Task.4.md`).
    let telemetry_task = tokio::spawn(telemetry::run(Arc::clone(&tracker)));
    let udp_task = udp.map(|socket| {
        let tracker = Arc::clone(&tracker);
        tokio::spawn(async move { wire::serve_udp(tracker, socket).await })
    });

    // The lifecycle task owns the announce cadence, the drain sequence and the update check; the
    // wire loop keeps owning sockets. Shutdown flows from the lifecycle to the wire loop so the
    // drain finishes *before* the listener closes, rather than racing it.
    let (stop_wire_tx, stop_wire_rx) = tokio::sync::oneshot::channel::<()>();
    let host = Arc::new(TrackerHost {
        tracker: Arc::clone(&tracker),
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
            let _ = stop_wire_tx.send(());
            outcome
        }
    });

    wire::run(Arc::clone(&tracker), listener, async move {
        let _ = stop_wire_rx.await;
    })
    .await?;
    if let Some(task) = udp_task {
        task.abort();
    }
    telemetry_task.abort();
    let outcome = lifecycle_task
        .await
        .unwrap_or(nodera_service::lifecycle::Outcome::Stopped);
    if outcome == nodera_service::lifecycle::Outcome::Updated {
        let exe = std::env::current_exe()?;
        println!("nodera-tracker: restarting into the updated binary");
        // Only returns on failure: success replaces this process image, keeping the pid and the
        // supervisor relationship intact.
        return Err(nodera_service::update::restart_into(&exe).into());
    }
    println!("nodera-tracker: stopped");
    Ok(())
}

/// The tracker's answers to the shared lifecycle's questions about itself.
struct TrackerHost {
    tracker: Arc<Mutex<Tracker>>,
}

impl nodera_service::lifecycle::ServiceHost for TrackerHost {
    fn kind(&self) -> nodera_codec::service::ServiceKind {
        nodera_codec::service::ServiceKind::Tracker
    }

    fn capacity(&self) -> nodera_service::lifecycle::CapacitySnapshot {
        // A blocking lock inside an async caller would be a deadlock risk; `try_lock` means a busy
        // tracker reports last-known-nothing rather than stalling its own announce.
        let Ok(tracker) = self.tracker.try_lock() else {
            return nodera_service::lifecycle::CapacitySnapshot::default();
        };
        let (_, rejected, _, worlds) = tracker.stats();
        nodera_service::lifecycle::CapacitySnapshot {
            active_sessions: worlds.try_into().unwrap_or(u32::MAX),
            max_sessions: tracker.config().max_worlds.try_into().unwrap_or(u32::MAX),
            active_circuits: 0,
            max_circuits: 0,
            rejected_last_window: rejected.try_into().unwrap_or(u32::MAX),
        }
    }

    fn notify_peers(&self, _notice: &nodera_codec::service::ServiceDrainNotice) -> usize {
        // A tracker holds no long-lived peer channels: every request is its own short connection. Its
        // drain reaches peers through the directory answer, which is why a tracker restart is cheap
        // and a relay restart is not (`docs/rendezvous/REFERENCE.md` §15).
        0
    }
}

/// Probe a running tracker: a query for the all-zero world proves the listener decodes canonical
/// frames and answers, without needing to know any real world's genesis hash.
async fn healthcheck(addr: &str) -> Result<(), Box<dyn std::error::Error>> {
    use nodera_codec::messages::{DiscoveryMessage, TrackerQuery};
    use nodera_service::frame::{read_frame, write_frame};
    let mut stream =
        tokio::net::TcpStream::connect(nodera_service::endpoint::socket_target(addr)).await?;
    let probe = DiscoveryMessage::TrackerQuery(TrackerQuery {
        genesis_hash: vec![0u8; 32],
    })
    .encode();
    write_frame(&mut stream, &probe).await?;
    let reply = read_frame(&mut stream, 1 << 20)
        .await?
        .ok_or("tracker closed the connection without answering")?;
    match DiscoveryMessage::decode(&reply)? {
        DiscoveryMessage::TrackerResponse(_) => Ok(()),
        other => Err(format!("unexpected reply tag {}", other.tag()).into()),
    }
}
