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
use nodera_service::cli;
use nodera_service::serve::shutdown_signal;
use service::Ingest;
use sink::NdjsonSink;
use tokio::sync::Mutex;

const NAME: &str = "nodera-telemetry";

#[tokio::main]
async fn main() -> ExitCode {
    cli::run(
        cli::Service {
            name: NAME,
            usage: "usage: nodera-telemetry [--config <file>] [--bind <addr>] \
                    [--healthcheck <addr>] [--print-schema] [--print-env] [--version]",
            version: env!("NODERA_VERSION"),
            env_reference: Config::env_reference,
            // The answer to "what does NoderaMC collect?" has to be obtainable from the running
            // binary, not only from a document that could have drifted.
            extra: &[("--print-schema", || schema::schema_json("service"))],
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
    // Defaults, then the file, then the environment, then the flag — then validate. There is no
    // pseudonymisation secret to refuse on: the per-period key is minted in memory from the OS
    // CSPRNG (see `subject.rs`), so the operator's configuration carries no key material and a
    // default config is a valid one.
    let config: Config = nodera_service::config::configure(NAME, config_path, bind_override)?;

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

    let (listener, _bound) = nodera_service::config::bind(NAME, config.bind_addr).await?;
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

/// Probe a running service with the liveness frame — no consent, no install id, no quota slot.
async fn healthcheck(addr: &str) -> Result<(), Box<dyn std::error::Error>> {
    let mut stream =
        tokio::net::TcpStream::connect(nodera_service::endpoint::socket_target(addr)).await?;
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
