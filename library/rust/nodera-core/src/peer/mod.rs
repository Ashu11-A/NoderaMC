//! Tracker-wire helpers retained by the app for verifying the worker's presence.
//!
//! # Why this exists
//!
//! Desktop supervises `nodera-headless`; Android loads the same worker bytecode into ART. In both
//! cases that worker owns peer identity and networking. App-side tracker code may query that
//! identity for a self-test, but opening a screen must never create or announce a second peer.
//!
//! Legacy signing helpers remain covered because canonical Rust/Java tracker compatibility is still
//! useful evidence. Runtime calls use [`tracker::verify_presence`], which only queries the commons
//! namespace for the worker id received over `NODERA-STATE`.
//!
//! # It is a real peer, not a mock
//!
//! Its identity is a real Ed25519 key pair, persisted, and every announce is signed over the same
//! canonical bytes the Java peer signs — the tracker verifies it with exactly the code path it uses
//! for any other node, and rejects this one just as readily if the signature is wrong. The wire
//! types come from `nodera-codec`, which is the same frozen contract the Java side encodes.

pub mod identity;
pub mod round;
pub mod tracker;

pub use round::announce_round;

use serde::{Deserialize, Serialize};

/// Whether this is a mobile shell. Capability checks use this; peer ownership does not.
pub const fn is_mobile() -> bool {
    cfg!(target_os = "android") || cfg!(target_os = "ios")
}

/// What the mobile peer is doing, as the UI renders it.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct PeerStatus {
    /// This device's node id, derived from its own key. Empty before the identity is created.
    pub node_id: String,
    /// Base64 of the public key it announces under.
    pub public_key: String,
    /// Trackers configured for this device, with the result of the last exchange with each.
    pub trackers: Vec<TrackerStatus>,
    /// Whether at least one tracker accepted the most recent announce.
    pub announced: bool,
    /// Epoch millis of the last successful announce, or 0.
    pub last_announce_ms: u64,
    /// Peers the trackers reported for the world this device announced.
    pub known_peers: u64,
}

/// One tracker and how the last exchange with it went.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct TrackerStatus {
    pub endpoint: String,
    pub reachable: bool,
    /// The tracker's own acceptance flag from `TrackerAnnounceAck`.
    pub accepted: bool,
    /// How long the tracker asked this peer to wait before announcing again.
    pub next_announce_seconds: u32,
    /// Round trip for the announce exchange, or `None` when it did not complete.
    pub latency_ms: Option<u64>,
    /// The tracker's rejection code, or the local failure. Empty on success.
    pub error: String,
}
