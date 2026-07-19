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
mod health;
mod limits;
mod query;
mod registry;
mod service;
#[cfg(test)]
mod test_support;
mod wire;

use config::Config;
use service::Tracker;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;
use tokio::net::TcpListener;
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
            println!("nodera-tracker {}", env!("CARGO_PKG_VERSION"));
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

    let tracker = Arc::new(Mutex::new(Tracker::new(config)));
    wire::run(tracker, listener, shutdown_signal()).await?;
    println!("nodera-tracker: stopped");
    Ok(())
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
