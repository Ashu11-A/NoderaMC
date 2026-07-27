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
//! ```

use nodera_codec::framing;
use nodera_codec::messages::{DiscoveryMessage, TrackerQuery};
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
    let world = match args.next().as_deref() {
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

fn query(endpoint: &str, world: &[u8]) -> Result<nodera_codec::messages::TrackerResponse, String> {
    let mut stream = TcpStream::connect(endpoint)
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
