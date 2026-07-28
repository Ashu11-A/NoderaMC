//! `nodera-telemetry` — the Nodera telemetry ingest service.
//!
//! It collects **consented, schema-bounded, de-identified** operational events from peers, from
//! the tracker and rendezvous services, and from Paper/Folia endpoints, and spools them as NDJSON
//! for the Big Data plane in `docker/telemetry/` (Vector → Redpanda → ClickHouse → Grafana/Spark).
//!
//! **It carries no authority whatsoever** — the same rule the tracker and rendezvous services live
//! under (`docs/README.md` §4.3 rule 7), only more so. Nothing in the network reads it, no peer
//! consults it, and losing it entirely costs the project insight and costs players nothing. That
//! is what makes the privacy decisions cheap to take: telemetry can be refused, sampled, dropped,
//! or turned off by default without any correctness argument to have.
//!
//! Three properties are enforced here rather than promised:
//!
//! * **Consent is a gate.** A batch that does not carry `consent: "granted"` writes nothing.
//! * **The registry is the collection policy.** Only events and attributes declared in
//!   [`schema`] can be stored; there is no free-text value anywhere in it.
//! * **Identifiers are replaced, addresses are discarded.** The install id becomes a rotating
//!   HMAC subject; the source address becomes a country and an ASN and is then gone.
//!
//! ```text
//! nodera-telemetry --config nodera-telemetry.toml
//! nodera-telemetry --healthcheck 127.0.0.1:25620
//! nodera-telemetry --print-schema
//! nodera-telemetry --version
//! ```

// The modules live in the library half of this crate (`lib.rs`) so the reporting services can
// share the event model with the receiver that validates it.
use nodera_telemetry::{config, geo, schema, service, sink, wire};

use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;

use config::Config;
use geo::GeoTable;
use service::Ingest;
use sink::NdjsonSink;
use tokio::net::TcpListener;
use tokio::sync::Mutex;

fn usage() -> &'static str {
    "usage: nodera-telemetry [--config <file>] [--bind <addr>] [--healthcheck <addr>] \
     [--print-schema] [--print-env] [--version]"
}

enum Command {
    Serve {
        config_path: Option<PathBuf>,
        bind_override: Option<String>,
    },
    Healthcheck(String),
    /// Print the collection registry as JSON. The answer to "what does NoderaMC collect?" has to
    /// be obtainable from the running binary, not only from a document that could have drifted.
    PrintSchema,
    /// List every environment variable this service reads, for an operator writing a unit or a
    /// compose file. Names only — no values are read, so it is safe to run anywhere.
    PrintEnv,
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
            "--print-schema" => return Command::PrintSchema,
            "--print-env" => return Command::PrintEnv,
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
            println!("nodera-telemetry {}", env!("NODERA_VERSION"));
            ExitCode::SUCCESS
        }
        Command::Usage => {
            eprintln!("{}", usage());
            ExitCode::FAILURE
        }
        Command::PrintSchema => {
            println!("{}", schema::schema_json("service"));
            ExitCode::SUCCESS
        }
        Command::PrintEnv => {
            for key in Config::env_reference() {
                println!("{key}");
            }
            ExitCode::SUCCESS
        }
        Command::Healthcheck(addr) => match healthcheck(&addr).await {
            Ok(()) => {
                println!("nodera-telemetry: {addr} healthy");
                ExitCode::SUCCESS
            }
            Err(e) => {
                eprintln!("nodera-telemetry: {addr} unhealthy: {e}");
                ExitCode::FAILURE
            }
        },
        Command::Serve {
            config_path,
            bind_override,
        } => match serve(config_path, bind_override).await {
            Ok(()) => ExitCode::SUCCESS,
            Err(e) => {
                eprintln!("nodera-telemetry: {e}");
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
    // The environment layer sits between the file and the flag. Only the variable *names* are
    // printed: `NODERA_TELEMETRY_SUBJECT_SECRET` is this service's pseudonymisation key, and a
    // startup log outlives the process that wrote it.
    let from_env = config.apply_env(&nodera_service::env::SystemEnv)?;
    if !from_env.applied.is_empty() {
        println!(
            "nodera-telemetry: {} setting(s) from the environment: {}",
            from_env.applied.len(),
            from_env.applied.join(" ")
        );
    }
    if let Some(bind) = bind_override {
        config.bind_addr = bind.parse()?;
    }
    // Refuses an unset pseudonymisation secret. Starting without one would mean either storing raw
    // install ids or inventing a secret nobody recorded, and both are worse than not starting.
    config.validate()?;

    let geo = match &config.geo_table_path {
        Some(path) => {
            let (table, skipped) = GeoTable::load(path)?;
            if table.is_empty() {
                // Loud, because the failure mode is silent: a table that parsed to nothing looks
                // exactly like a working service whose whole population lives in country "ZZ".
                eprintln!(
                    "nodera-telemetry: geo table {} produced no usable rows ({skipped} skipped) \
                     — every row will be recorded as ZZ/0",
                    path.display()
                );
            } else {
                println!(
                    "nodera-telemetry: geo table {} rows loaded, {skipped} skipped",
                    table.len()
                );
            }
            table
        }
        None => {
            println!(
                "nodera-telemetry: no geo_table_path configured — every row is recorded as ZZ/0"
            );
            GeoTable::empty()
        }
    };

    let sink = NdjsonSink::new(
        &config.spool_dir,
        config.spool_max_bytes,
        config.spool_max_seconds,
    )?;

    let listener = TcpListener::bind(config.bind_addr).await?;
    let bound = listener.local_addr()?;
    // Printed unconditionally: integration tests (and operators binding port 0) read the real port
    // from this line rather than guessing it.
    println!("nodera-telemetry: listening on {bound}");
    println!(
        "nodera-telemetry: spooling to {} ({} declared events)",
        config.spool_dir.display(),
        schema::REGISTRY.len()
    );

    let ingest = Arc::new(Mutex::new(Ingest::new(config, geo, Box::new(sink))));
    wire::run(Arc::clone(&ingest), listener, shutdown_signal()).await?;
    println!("nodera-telemetry: stopped");
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

/// Probe a running service with the liveness frame — no consent, no install id, no quota slot.
async fn healthcheck(addr: &str) -> Result<(), Box<dyn std::error::Error>> {
    let mut stream = tokio::net::TcpStream::connect(addr).await?;
    wire::write_frame(&mut stream, br#"{"v":1,"probe":true}"#).await?;
    let reply = wire::read_frame(&mut stream, 64 * 1024)
        .await?
        .ok_or("the service closed the connection without answering")?;
    let reply: serde_json::Value = serde_json::from_slice(&reply)?;
    if reply["ok"].as_bool() == Some(true) {
        Ok(())
    } else {
        Err(format!("unexpected reply {reply}").into())
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
            parse_args(&["--collect-everything".to_owned()]),
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

    /// The privacy notice has a machine-readable form, and it comes from the same constant the
    /// validator uses — so the notice cannot drift from the enforcement.
    #[test]
    fn the_printed_schema_is_json_and_lists_every_declared_event() {
        let value: serde_json::Value =
            serde_json::from_str(&schema::schema_json("service")).unwrap();
        let events = value["events"].as_array().unwrap();
        assert_eq!(events.len(), schema::REGISTRY.len());
        assert!(events.iter().any(|event| event["name"] == "world.join"));
        assert_eq!(
            value["events"][0]["source"].as_str().unwrap(),
            schema::REGISTRY[0].source.as_str()
        );
    }

    #[test]
    fn every_declared_kind_renders_a_readable_name() {
        assert_eq!(
            schema::kind_name_for_test(schema::ValueKind::Int { min: 0, max: 20 }),
            "int[0..20]"
        );
        assert_eq!(
            schema::kind_name_for_test(schema::ValueKind::Enum(&["a", "b"])),
            "enum(a|b)"
        );
        assert_eq!(
            schema::kind_name_for_test(schema::ValueKind::Hex { len: 16 }),
            "hex[16]"
        );
    }
}
