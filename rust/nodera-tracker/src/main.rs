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

mod announce;
mod config;
mod deletion;
mod health;
mod limits;
mod query;
mod registry;
mod service;
mod services;
mod telemetry;
#[cfg(test)]
mod test_support;
mod wire;

use config::Config;
use service::Tracker;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::Mutex;

fn usage() -> &'static str {
    "usage: nodera-tracker [--config <file>] [--bind <addr>] [--healthcheck <addr>] [--version]"
}

enum Command {
    Serve {
        config_path: Option<PathBuf>,
        bind_override: Option<String>,
    },
    Healthcheck(String),
    Version,
    Usage,
}

fn parse_args(args: &[String]) -> Command {
    let mut config_path = None;
    let mut bind_override = None;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--version" | "-V" => return Command::Version,
            "--help" | "-h" => return Command::Usage,
            "--healthcheck" => {
                return args
                    .get(index + 1)
                    .map(|addr| Command::Healthcheck(addr.clone()))
                    .unwrap_or(Command::Usage);
            }
            "--config" => {
                let Some(value) = args.get(index + 1) else {
                    return Command::Usage;
                };
                config_path = Some(PathBuf::from(value));
                index += 1;
            }
            "--bind" => {
                let Some(value) = args.get(index + 1) else {
                    return Command::Usage;
                };
                bind_override = Some(value.clone());
                index += 1;
            }
            _ => return Command::Usage,
        }
        index += 1;
    }
    Command::Serve {
        config_path,
        bind_override,
    }
}

#[tokio::main]
async fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    match parse_args(&args) {
        Command::Version => {
            println!("nodera-tracker {}", env!("NODERA_VERSION"));
            ExitCode::SUCCESS
        }
        Command::Usage => {
            eprintln!("{}", usage());
            ExitCode::FAILURE
        }
        Command::Healthcheck(addr) => match healthcheck(&addr).await {
            Ok(()) => {
                println!("nodera-tracker: {addr} healthy");
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("nodera-tracker: {addr} unhealthy: {e}");
                ExitCode::FAILURE
            }
        },
        Command::Serve {
            config_path,
            bind_override,
        } => match serve(config_path, bind_override).await {
            Ok(()) => ExitCode::SUCCESS,
            Err(e) => {
                eprintln!("nodera-tracker: {e}");
                ExitCode::FAILURE
            }
        },
    }
}

async fn serve(
    config_path: Option<PathBuf>,
    bind_override: Option<String>,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut config = match config_path {
        Some(path) => Config::load(&path)?,
        None => Config::default(),
    };
    if let Some(bind) = bind_override {
        config.bind_addr = bind.parse()?;
    }
    config.validate()?;

    let listener = TcpListener::bind(config.bind_addr).await?;
    let bound = listener.local_addr()?;
    // Printed unconditionally: integration tests (and operators binding port 0) read the real
    // port from this line rather than guessing it.
    println!("nodera-tracker: listening on {bound}");

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

    // The identity is minted on first start and preserved after that. A tracker that regenerated it
    // would look like a brand-new, unmeasured service after every restart — exactly when the
    // availability score it has earned matters most.
    let lifecycle_config = nodera_service::lifecycle::LifecycleConfig {
        tracker_endpoints: config.peer_tracker_endpoints.clone(),
        routes: config.routes(),
        network_id: nodera_codec::types::NetworkId::new(0, 0),
        version: env!("NODERA_VERSION").to_owned(),
        record_ttl_seconds: config.peer_ttl_seconds,
        announce_interval_seconds: u64::from(config.announce_interval_seconds),
        update: config.update_config(),
    };
    let identity = Arc::new(nodera_service::identity::ServiceIdentity::load_or_create(
        &config.identity_file,
        fresh_seed(),
        random_node_id(),
    )?);
    println!(
        "nodera-tracker: service identity {:016x}{:016x}",
        identity.node_id().msb,
        identity.node_id().lsb
    );

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
                Arc::new(nodera_service::update::CurlFetcher {
                    timeout_seconds: 120,
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

/// A fresh 32-byte secret for a first-run identity.
///
/// Uses the process' own entropy sources rather than adding a random crate to a service whose only
/// need for randomness is this one call: the address of a heap allocation, the wall clock, and the pid,
/// hashed. Identity generation is explicitly outside the deterministic engine path.
fn fresh_seed() -> [u8; 32] {
    use sha2::{Digest, Sha256};
    let marker = Box::new(0u8);
    let mut hasher = Sha256::new();
    hasher.update(nodera_service::lifecycle::now_millis().to_be_bytes());
    hasher.update(std::process::id().to_be_bytes());
    hasher.update((marker.as_ref() as *const u8 as usize).to_be_bytes());
    hasher.update(
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.subsec_nanos())
            .unwrap_or_default()
            .to_be_bytes(),
    );
    hasher.finalize().into()
}

/// A fresh node id for a first-run identity.
fn random_node_id() -> nodera_codec::types::NodeId {
    let seed = fresh_seed();
    nodera_codec::types::NodeId::new(
        u64::from_be_bytes(seed[..8].try_into().expect("8 bytes")),
        u64::from_be_bytes(seed[8..16].try_into().expect("8 bytes")),
    )
}

/// Resolve when the process is asked to stop (SIGTERM or Ctrl-C).
async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        let mut term = match signal(SignalKind::terminate()) {
            Ok(term) => term,
            Err(_) => return std::future::pending().await,
        };
        tokio::select! {
            _ = term.recv() => {}
            _ = tokio::signal::ctrl_c() => {}
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

/// Probe a running tracker: a query for the all-zero world proves the listener decodes canonical
/// frames and answers, without needing to know any real world's genesis hash.
async fn healthcheck(addr: &str) -> Result<(), Box<dyn std::error::Error>> {
    use nodera_codec::messages::{DiscoveryMessage, TrackerQuery};
    let mut stream = tokio::net::TcpStream::connect(addr).await?;
    let probe = DiscoveryMessage::TrackerQuery(TrackerQuery {
        genesis_hash: vec![0u8; 32],
    })
    .encode();
    wire::write_frame(&mut stream, &probe).await?;
    let reply = wire::read_frame(&mut stream, 1 << 20)
        .await?
        .ok_or("tracker closed the connection without answering")?;
    match DiscoveryMessage::decode(&reply)? {
        DiscoveryMessage::TrackerResponse(_) => Ok(()),
        other => Err(format!("unexpected reply tag {}", other.tag()).into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_and_help_are_recognised() {
        assert!(matches!(
            parse_args(&["--version".to_owned()]),
            Command::Version
        ));
        assert!(matches!(parse_args(&["-h".to_owned()]), Command::Usage));
    }

    #[test]
    fn an_unknown_flag_prints_usage_rather_than_starting_a_service() {
        assert!(matches!(
            parse_args(&["--serve-everything".to_owned()]),
            Command::Usage
        ));
    }

    #[test]
    fn a_flag_missing_its_value_is_usage_not_a_panic() {
        assert!(matches!(
            parse_args(&["--config".to_owned()]),
            Command::Usage
        ));
        assert!(matches!(parse_args(&["--bind".to_owned()]), Command::Usage));
        assert!(matches!(
            parse_args(&["--healthcheck".to_owned()]),
            Command::Usage
        ));
    }

    #[test]
    fn config_and_bind_are_parsed_together() {
        match parse_args(&[
            "--config".to_owned(),
            "t.toml".to_owned(),
            "--bind".to_owned(),
            "127.0.0.1:1".to_owned(),
        ]) {
            Command::Serve {
                config_path,
                bind_override,
            } => {
                assert_eq!(config_path, Some(PathBuf::from("t.toml")));
                assert_eq!(bind_override.as_deref(), Some("127.0.0.1:1"));
            }
            _ => panic!("expected a serve command"),
        }
    }
}
