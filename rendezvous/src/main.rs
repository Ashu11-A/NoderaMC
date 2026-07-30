//! `nodera-rendezvous` — the standalone Nodera rendezvous + relay service (Task 29).
//!
//! Two logically separate responsibilities in one binary (RENDEZVOUS.md §1):
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

mod circuit;
mod config;
mod discover;
mod limits;
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
use reservation::ReservationKeeper;
use service::Rendezvous;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio::sync::Mutex;

fn usage() -> &'static str {
    "usage: nodera-rendezvous [--config <file>] [--bind <addr>] [--healthcheck <addr>] [--print-env] [--version]"
}

enum Command {
    Serve {
        config_path: Option<PathBuf>,
        bind_override: Option<String>,
    },
    Healthcheck(String),
    Version,
    /// List every environment variable this service reads, for an operator writing a unit or a
    /// compose file. Names only — no values are read, so it is safe to run anywhere.
    PrintEnv,
    Usage,
}

fn parse_args(args: &[String]) -> Command {
    let mut config_path = None;
    let mut bind_override = None;
    let mut index = 0;
    while index < args.len() {
        match args[index].as_str() {
            "--version" | "-V" => return Command::Version,
            "--print-env" => return Command::PrintEnv,
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
            println!("nodera-rendezvous {}", env!("NODERA_VERSION"));
            ExitCode::SUCCESS
        }
        Command::PrintEnv => {
            for key in Config::env_reference() {
                println!("{key}");
            }
            ExitCode::SUCCESS
        }
        Command::Usage => {
            eprintln!("{}", usage());
            ExitCode::FAILURE
        }
        Command::Healthcheck(addr) => match healthcheck(&addr).await {
            Ok(()) => {
                println!("nodera-rendezvous: {addr} healthy");
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("nodera-rendezvous: {addr} unhealthy: {e}");
                ExitCode::FAILURE
            }
        },
        Command::Serve {
            config_path,
            bind_override,
        } => match serve(config_path, bind_override).await {
            Ok(()) => ExitCode::SUCCESS,
            Err(e) => {
                eprintln!("nodera-rendezvous: {e}");
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
    // Defaults, then the file, then the environment, then the flag: each layer belongs to someone
    // closer to this particular start than the last. Only the variable *names* are printed —
    // `reservation_hmac_key_hex` is a key, and a startup log outlives the process.
    let from_env = config.apply_env(&nodera_service::env::SystemEnv)?;
    if !from_env.applied.is_empty() {
        println!(
            "nodera-rendezvous: {} setting(s) from the environment: {}",
            from_env.applied.len(),
            from_env.applied.join(" ")
        );
    }
    if let Some(bind) = bind_override {
        config.bind_addr = bind.parse()?;
    }
    config.validate()?;

    let listener = TcpListener::bind(config.bind_addr).await?;
    let bound = listener.local_addr()?;
    // Printed unconditionally: integration tests (and operators binding port 0) read the real port
    // from this line rather than guessing it.
    println!("nodera-rendezvous: listening on {bound}");

    // The reservation HMAC key: the configured one keeps proofs valid across a restart; otherwise
    // an ephemeral key is minted (fine for a single process — reservations are short-lived anyway).
    let key = config
        .reservation_hmac_key()
        .unwrap_or_else(|| ephemeral_key(&bound.to_string()));
    let keeper = ReservationKeeper::new(key, bound.to_string());

    // The identity is minted on first start and preserved. A regenerated one would present this
    // relay to every peer as a brand-new service with no measured availability, which is the moment
    // its reputation matters most.
    let identity = Arc::new(nodera_service::identity::ServiceIdentity::load_or_create(
        &config.identity_file,
        fresh_seed(),
        random_node_id(),
    )?);
    println!(
        "nodera-rendezvous: service identity {:016x}{:016x}",
        identity.node_id().msb,
        identity.node_id().lsb
    );

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

/// A fresh 32-byte secret for a first-run identity.
///
/// Hashes the wall clock, the pid, and a heap address rather than adding a random-number crate for a
/// single call. Identity generation is explicitly outside the deterministic engine path.
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

/// Resolve when the process is asked to stop (SIGTERM or Ctrl-C). Graceful drain: stop accepting,
/// let live circuits run out their reservation (RENDEZVOUS.md ops notes).
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

/// Probe a running service: a discovery for the all-zero namespace proves the listener decodes
/// canonical frames and answers, without needing any real namespace.
async fn healthcheck(addr: &str) -> Result<(), Box<dyn std::error::Error>> {
    use nodera_codec::rendezvous::{RendezvousDiscover, RendezvousMessage};
    use nodera_codec::types::NetworkId;
    let mut stream = tokio::net::TcpStream::connect(addr).await?;
    let probe = RendezvousMessage::Discover(RendezvousDiscover {
        network_id: NetworkId::new(0, 0),
        genesis_hash: vec![0u8; 32],
        cursor: 0,
        limit: 1,
    })
    .encode();
    wire::write_frame(&mut stream, &probe).await?;
    let reply = wire::read_frame(&mut stream, 1 << 20)
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
    fn version_and_help_are_recognised() {
        assert!(matches!(
            parse_args(&["--version".to_owned()]),
            Command::Version
        ));
        assert!(matches!(parse_args(&["-h".to_owned()]), Command::Usage));
    }

    #[test]
    fn an_unknown_flag_prints_usage() {
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
    }

    #[test]
    fn an_ephemeral_key_is_32_bytes() {
        assert_eq!(ephemeral_key("127.0.0.1:1").len(), 32);
    }
}
