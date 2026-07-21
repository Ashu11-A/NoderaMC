//! Task 32/33: the dashboard telemetry the companion window renders and the control endpoint mirrors.
//!
//! Every field here maps 1:1 to the worker's `NODERA-STATE` JSON
//! (`java/nodera-headless/.../WorkerControlHandler.stateJson`) — the worker is the source of truth;
//! this module is the deserialization + aggregation seam the React UI reads via Tauri events. Keep
//! the two in lockstep: a field added on one side must be added on the other.

use std::sync::Mutex;

use serde::{Deserialize, Serialize};

/// One connected peer this node is exchanging data with.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct PeerRow {
    pub node_id: String,
    #[serde(default)]
    pub route: String,
    /// `direct` | `punched` | `relayed`.
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub up_bytes_per_sec: u64,
    #[serde(default)]
    pub down_bytes_per_sec: u64,
}

/// One world this node is keeping discoverable on the network.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct WorldRow {
    pub world_id: String,
    pub name: String,
    #[serde(default)]
    pub players: u32,
}

/// A configured discovery service (tracker / rendezvous) plus its last-probed reachability — the
/// "VPN server list" health rows.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct EndpointHealth {
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub reachable: bool,
}

/// The full dashboard snapshot pushed to the React frontend.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct Metrics {
    /// This worker's persistent node identity (stable across restarts).
    #[serde(default)]
    pub node_id: String,
    #[serde(default)]
    pub worker_version: String,
    /// Seconds the worker has been online this run.
    #[serde(default)]
    pub uptime_seconds: u64,
    /// Whether this node currently holds the session-gateway role.
    #[serde(default)]
    pub is_gateway: bool,
    /// The P2P route this node advertises to the mesh.
    #[serde(default)]
    pub self_route: String,
    /// The node's declared peer roles (BOOTSTRAP / FULL_ARCHIVE / REGION_VALIDATOR …).
    #[serde(default)]
    pub roles: Vec<String>,

    /// Distinct content pieces this node currently seeds/holds for the network.
    #[serde(default)]
    pub maintained_pieces: u64,
    /// Bytes of world data this node holds for the network.
    #[serde(default)]
    pub maintained_bytes: u64,
    /// Lifetime bytes sent / received (the UI renders these as GB).
    #[serde(default)]
    pub total_sent_bytes: u64,
    #[serde(default)]
    pub total_received_bytes: u64,

    /// Peers currently exchanging data with this node.
    #[serde(default)]
    pub peers: Vec<PeerRow>,
    /// Worlds this node is hosting / connected to.
    #[serde(default)]
    pub connected_worlds: Vec<WorldRow>,
    /// Configured trackers + reachability.
    #[serde(default)]
    pub trackers: Vec<EndpointHealth>,
    /// Configured rendezvous services + reachability.
    #[serde(default)]
    pub rendezvous: Vec<EndpointHealth>,

    /// Whether the supervised headless peer is currently up.
    #[serde(default)]
    pub daemon_up: bool,
}

/// Thread-safe holder the supervisor updates and the UI/control read.
#[derive(Default)]
pub struct MetricsHandle {
    inner: Mutex<Metrics>,
}

impl MetricsHandle {
    pub fn new() -> Self {
        Self::default()
    }

    /// Replace the snapshot (called by the peer status pump).
    pub fn set(&self, metrics: Metrics) {
        *self.inner.lock().unwrap() = metrics;
    }

    /// Read a clone of the current snapshot (called by the UI cadence + control verbs).
    pub fn snapshot(&self) -> Metrics {
        self.inner.lock().unwrap().clone()
    }

    /// Flip just the daemon-up flag (supervisor lifecycle transitions).
    pub fn set_daemon_up(&self, up: bool) {
        self.inner.lock().unwrap().daemon_up = up;
    }
}
