//! The app's **own** peer — what runs on a phone, where there is no JVM.
//!
//! # Why this exists
//!
//! On the desktop this app supervises `nodera-headless`, a Java process, and does no networking of
//! its own beyond the loopback control socket. Android has no JVM to supervise: it runs ART, whose
//! class library is not Java's, and the worker's own bytecode does not load there (see
//! `docs/app/Task.9.md` for the measured reason). So on mobile the app *is* the peer.
//!
//! That is a smaller peer, deliberately. It speaks the **discovery plane** — announce yourself to
//! the trackers, ask them who else is there, exchange a payload to prove the path works — and
//! nothing else. No world simulation, no region committees, no content store. A phone in your
//! pocket is a node that helps other people find each other and keeps a swarm's peer set alive;
//! it is not a place to run a Minecraft world.
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

/// Whether this build should run its own peer rather than supervise a worker.
///
/// Compiled in rather than configured: an Android build has no worker to supervise and a desktop
/// build has one, and making that a runtime switch would let a desktop install silently stop
/// supervising the process the mod requires.
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
