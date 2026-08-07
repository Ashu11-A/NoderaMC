//! Ask a tracker who it knows, from a machine that is not the peer in question.
//!
//! # Why this is a separate program
//!
//! A device that claims to be on the network is not evidence. The evidence is a query issued by
//! somebody else that comes back with that device in it — which is the entire service a tracker
//! provides, so it is the only thing worth calling a pass.
//!
//! It uses `nodera-codec` rather than a hand-rolled parser on purpose. The encoding is a frozen
//! contract; a second, informal implementation written for a test script would be free to drift
//! from it, and a drifting checker reports failures that are its own.
//!
//! ```text
//!   nodera-query [tracker-host:port] [world-id-hex | --commons]
//!   nodera-query [tcp://tracker-host:port] --services
//! ```
//!
//! The endpoint takes either form. `tcp://` is what the documentation, the compose file and every
//! service config carry, so the scheme is stripped before the connect rather than handed to a
//! resolver that reads it as part of the hostname.
//!
//! `--services` asks the same question about infrastructure that the default asks about worlds:
//! which relays and trackers does this tracker actually know? It is the only way to check the
//! discovery lane from outside — a relay's own log saying it announced is the relay's claim, and a
//! service that announced to a tracker which then dropped it looks identical from the relay's side.

use nodera_codec::framing;
use nodera_codec::messages::{DiscoveryMessage, TrackerQuery};
use nodera_codec::service::{ServiceDirectoryQuery, ServiceKind, ServiceMessage};
use nodera_codec::types::NetworkId;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::Duration;

/// The world every mobile peer announces itself into — mirrors `nodera-app`'s `COMMONS_WORLD`.
///
/// A phone holds no world, so it needs a namespace to be present in for other peers to find it at
/// all. Duplicated here rather than shared through a crate dependency because the app is a Tauri
/// binary and this is a two-hundred-line diagnostic; the constant is asserted by the e2e script
/// finding the device, which is the only place its value matters.
const COMMONS_WORLD: [u8; 32] = *b"nodera:mobile-commons:v1\0\0\0\0\0\0\0\0";

fn main() -> Result<(), String> {
    let mut args = std::env::args().skip(1);
    let endpoint = args.next().unwrap_or_else(|| "127.0.0.1:25600".to_owned());
    let selector = args.next();
    if selector.as_deref() == Some("--services") {
        return services(&endpoint);
    }
    let world = match selector.as_deref() {
        None | Some("--commons") => COMMONS_WORLD.to_vec(),
        Some(hex) => decode_hex(hex)?,
    };

    let response = query(&endpoint, &world)?;
    println!(
        "world      {}",
        if response.world_name.is_empty() {
            "(unnamed)"
        } else {
            &response.world_name
        }
    );
    println!("health     {:?}", response.health);
    println!("players    {}", response.world_player_count);
    println!("peers      {}", response.peers.len());
    for peer in &response.peers {
        println!(
            "  {} route={}",
            peer.node_id.to_uuid_string(),
            if peer.route.is_empty() {
                "-"
            } else {
                &peer.route
            }
        );
    }
    Ok(())
}

/// Print the tracker's service directory.
///
/// Every field printed is the tracker's own answer, deliberately including the composite score it
/// computed. A peer would recompute that from the components rather than trust it; this is a
/// diagnostic, and what an operator needs to see is exactly what the tracker is telling peers.
fn services(endpoint: &str) -> Result<(), String> {
    let request = ServiceMessage::DirectoryQuery(ServiceDirectoryQuery {
        kind: ServiceKind::Rendezvous,
        network_id: NetworkId::new(0, 0),
        limit: 32,
    })
    .encode();
    let relays = ask_services(endpoint, &request)?;

    let request = ServiceMessage::DirectoryQuery(ServiceDirectoryQuery {
        kind: ServiceKind::Tracker,
        network_id: NetworkId::new(0, 0),
        limit: 32,
    })
    .encode();
    let trackers = ask_services(endpoint, &request)?;

    for (label, entries) in [("rendezvous", relays), ("tracker", trackers)] {
        println!("{label}   {} known", entries.len());
        for entry in entries {
            println!(
                "  {:016x}{:016x}  {:?}  v{}  routes={}  score={}  reporters={}",
                entry.record.service.msb,
                entry.record.service.lsb,
                entry.record.lifecycle,
                entry.record.version,
                entry.record.routes.join(","),
                entry.score.recomputed_composite(),
                entry.score.reporter_count,
            );
        }
    }
    Ok(())
}

fn ask_services(
    endpoint: &str,
    request: &[u8],
) -> Result<Vec<nodera_codec::service::ServiceDirectoryEntry>, String> {
    // The scheme comes off before the resolver sees it: `tcp://tracker.example.org:25600` is the
    // documented endpoint form — it is what the compose file and every config carries — and handing
    // it to `connect` verbatim fails with "Name does not resolve".
    let mut stream = TcpStream::connect(nodera_service::endpoint::socket_target(endpoint))
        .map_err(|e| format!("cannot reach the tracker at {endpoint}: {e}"))?;
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| e.to_string())?;
    let framed = framing::frame(request).map_err(|e| e.to_string())?;
    stream.write_all(&framed).map_err(|e| e.to_string())?;
    stream.flush().map_err(|e| e.to_string())?;

    let mut header = [0u8; 4];
    stream
        .read_exact(&mut header)
        .map_err(|e| format!("the tracker sent no answer: {e}"))?;
    let length = framing::decode_length(header).map_err(|e| e.to_string())?;
    let mut body = vec![0u8; length];
    stream.read_exact(&mut body).map_err(|e| e.to_string())?;

    match ServiceMessage::decode(&body).map_err(|e| e.to_string())? {
        ServiceMessage::DirectoryResponse(response) => Ok(response.entries),
        other => Err(format!("the tracker answered with {other:?}")),
    }
}

fn query(endpoint: &str, world: &[u8]) -> Result<nodera_codec::messages::TrackerResponse, String> {
    // Scheme off before the resolver sees it — see `ask_services`.
    let mut stream = TcpStream::connect(nodera_service::endpoint::socket_target(endpoint))
        .map_err(|e| format!("cannot reach the tracker at {endpoint}: {e}"))?;
    stream
        .set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| e.to_string())?;

    let request = DiscoveryMessage::TrackerQuery(TrackerQuery {
        genesis_hash: world.to_vec(),
    })
    .encode();
    let framed = framing::frame(&request).map_err(|e| e.to_string())?;
    stream.write_all(&framed).map_err(|e| e.to_string())?;
    stream.flush().map_err(|e| e.to_string())?;

    let mut header = [0u8; 4];
    stream
        .read_exact(&mut header)
        .map_err(|e| format!("the tracker sent no answer: {e}"))?;
    let length = framing::decode_length(header).map_err(|e| e.to_string())?;
    let mut body = vec![0u8; length];
    stream.read_exact(&mut body).map_err(|e| e.to_string())?;

    match DiscoveryMessage::decode(&body).map_err(|e| e.to_string())? {
        DiscoveryMessage::TrackerResponse(response) => Ok(response),
        other => Err(format!("the tracker answered with {other:?}")),
    }
}

fn decode_hex(hex: &str) -> Result<Vec<u8>, String> {
    if hex.len() % 2 != 0 {
        return Err("a world id must have an even number of hex digits".to_owned());
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).map_err(|e| e.to_string()))
        .collect()
}
