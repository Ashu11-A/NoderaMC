//! `nodera-rendezvous` — the standalone Nodera rendezvous + relay service (Task 29).
//!
//! Two logically separate responsibilities in one binary (rendezvous.md §1):
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
    "usage: nodera-rendezvous [--config <file>] [--bind <addr>] [--healthcheck <addr>] [--version]"
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
            println!("nodera-rendezvous {}", env!("CARGO_PKG_VERSION"));
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

    let rendezvous = Arc::new(Mutex::new(Rendezvous::new(config, keeper)));
    wire::run(rendezvous, listener, shutdown_signal()).await?;
    println!("nodera-rendezvous: stopped");
    Ok(())
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
/// let live circuits run out their reservation (rendezvous.md ops notes).
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
