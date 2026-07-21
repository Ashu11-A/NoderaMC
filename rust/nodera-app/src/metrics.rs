//! Task 32: the dashboard telemetry the companion window renders and the control endpoint mirrors.
//! Exactly the four required panels: chunks/data maintained, GB sent/received, peers exchanging data,
//! and the world this node is connected to / hosting.
//!
//! In Option B the numbers originate in the bundled headless Java peer (its `TrafficMeter` /
//! `ArchiveInventory` / `TransportSelector`), streamed to the daemon over the peer's own status
//! channel; this module is the aggregation + serialization seam the React UI reads via Tauri events.

use std::sync::Mutex;

use serde::{Deserialize, Serialize};

/// One connected peer this node is exchanging data with.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct PeerRow {
    pub node_id: String,
    pub route: String,
    /// `direct` | `punched` | `relayed`.
    pub path: String,
    pub up_bytes_per_sec: u64,
    pub down_bytes_per_sec: u64,
}

/// The full dashboard snapshot pushed to the React frontend.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct Metrics {
    /// Distinct content pieces this node currently seeds/holds for the network.
    pub maintained_pieces: u64,
    /// Bytes of world data this node holds for the network.
    pub maintained_bytes: u64,
    /// Lifetime bytes sent / received (the UI renders these as GB).
    pub total_sent_bytes: u64,
    pub total_received_bytes: u64,
    /// Peers currently exchanging data with this node.
    pub peers: Vec<PeerRow>,
    /// Worlds this node is hosting / connected to (display names).
    pub connected_worlds: Vec<String>,
    /// Whether the supervised headless peer is currently up.
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
