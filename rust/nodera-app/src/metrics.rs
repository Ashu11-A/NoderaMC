//! Task 32/33: the dashboard telemetry the companion window renders and the control endpoint mirrors.
//!
//! Every field here maps 1:1 to the worker's `NODERA-STATE` JSON
//! (`java/nodera-headless/.../WorkerControlHandler.stateJson`) — the worker is the source of truth;
//! this module is the deserialization + aggregation seam the React UI reads via Tauri events. Keep
//! the two in lockstep: a field added on one side must be added on the other.

use serde::{Deserialize, Serialize};

/// One connected peer this node is exchanging data with — a row of the Peers tab.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct PeerRow {
    pub node_id: String,
    /// The peer's dialable route, rendered as `tcp://<host>:<port>` in the UI.
    #[serde(default)]
    pub route: String,
    /// How the peer is reached: `direct` | `relayed` | `unknown`.
    #[serde(default)]
    pub path: String,
    /// The peer's self-declared client agent, e.g. `NoderaMC 0.1.0`. Descriptive only.
    #[serde(default)]
    pub client: String,
    #[serde(default)]
    pub up_bytes_per_sec: u64,
    #[serde(default)]
    pub down_bytes_per_sec: u64,
    /// Bytes exchanged with this peer over the life of the connection.
    #[serde(default)]
    pub total_up_bytes: u64,
    #[serde(default)]
    pub total_down_bytes: u64,
}

/// The player count of a world nothing has reported on — see [`WorldRow::players`].
///
/// A named function rather than an inline default because `#[serde(default)]` would give `0`, and
/// `0` is a real answer to a different question.
fn unknown_players() -> i64 {
    -1
}

/// A world that has never been announced — see [`WorldRow::announced_to_trackers`].
///
/// Distinct from `0`, which means "announced to no trackers because none are configured".
fn never_announced() -> i64 {
    -1
}

/// One world this node is keeping discoverable on the network — a row of the Info tab.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WorldRow {
    pub world_id: String,
    pub name: String,
    /// Players in-world, or **negative for "no node in that world has reported"**.
    ///
    /// Signed, and that is the whole point. Only a node with a game in the world can count its
    /// players; every other peer has to be able to say it does not know. While this was a `u32` the
    /// two answers shared the value `0`, so a peer supporting a busy world published "0 players"
    /// with the same confidence as the host publishing the truth.
    #[serde(default = "unknown_players")]
    pub players: i64,
    /// The host's open Minecraft endpoint while its game is running; empty once it closes.
    #[serde(default)]
    pub mc_route: String,
    /// Epoch millis this world entered the Nodera network from this node ("Date added").
    #[serde(default)]
    pub added_at: u64,
    /// Epoch millis of this world's most recent content change here ("Last updated").
    #[serde(default)]
    pub updated_at: u64,
    /// Total content size in bytes.
    #[serde(default)]
    pub total_bytes: u64,
    /// The manifest root — the content checksum identifying these exact bytes.
    #[serde(default)]
    pub checksum: String,
    /// Manifest version; bumps on every re-seed.
    #[serde(default)]
    pub version: u64,
    #[serde(default)]
    pub piece_count: u64,
    #[serde(default)]
    pub pieces_held: u64,
    /// Peers believed to hold some of this world.
    #[serde(default)]
    pub seeders: u64,
    /// Full copies of this world believed to exist, including this node's when it has one.
    ///
    /// Full, not partial: a half-downloaded copy cannot serve the world on its own, and counting
    /// one would let a swarm of half-downloads report itself backed up.
    #[serde(default)]
    pub backup_copies: u64,
    /// Copies a network this size should keep — `ReplicationTarget.replicasFor`.
    ///
    /// Capped by the number of peers that exist, which is what makes a small network keep a full
    /// copy on every peer instead of holding back copies it could have made.
    #[serde(default)]
    pub backup_copies_wanted: u64,
    /// Chance nobody is holding this world right now, in permille: `(1 - availability)^copies`.
    #[serde(default)]
    pub loss_risk_permille: u64,
    /// `true` when this node only keeps the world's bytes alive; `false` when it hosts it.
    #[serde(default)]
    pub seeding: bool,

    /// How many trackers accepted this world's most recent announce.
    ///
    /// Zero with a positive [`Self::announced_to_trackers`] is the state that has no other symptom
    /// on this machine: the world is hosted, complete and healthy here, and **undiscoverable**.
    /// Every screen that says "joinable" is describing an intention until this number is positive.
    #[serde(default)]
    pub listed_on_trackers: i64,
    /// Trackers asked at that announce; `-1` when this world has never been announced.
    #[serde(default = "never_announced")]
    pub announced_to_trackers: i64,

    /// `true` when this peer holds this world's private key and can prove it administers it.
    ///
    /// Independent of [`Self::seeding`], and the two must not be collapsed: a node can host a world
    /// it did not create (it fetched somebody else's and re-shared it), and can administer one it is
    /// currently only seeding. "What am I doing with this world" and "may I speak for it" are
    /// different questions, and the Home screen answers them with two different lists.
    #[serde(default)]
    pub owned: bool,

    /// `true` when somebody on this machine is playing in this world right now.
    ///
    /// The third independent fact about a world, alongside [`Self::seeding`] and [`Self::owned`],
    /// and the one that was missing: a player joins worlds they neither run nor authored, and until
    /// this existed the app had nothing at all to show them for the world they were standing in.
    ///
    /// Backed by a lease the game renews, so a game that dies without saying goodbye stops
    /// claiming this on its own.
    #[serde(default)]
    pub connected: bool,

    /// The world's own public key (base64 X.509) — its administrative root — or empty when this
    /// node has no verified ownership claim for the world.
    #[serde(default)]
    pub world_public_key: String,

    /// Regions of the live world whose validated snapshots are committed and seeded by this node.
    ///
    /// Storage and processing are different contributions, and only this one answers "is this
    /// machine doing any of the world's work". A peer can hold a full copy of a world and validate
    /// none of it; a player standing in a world validates the regions their view covers whether or
    /// not they host anything.
    #[serde(default)]
    pub regions_held: u64,
}

/// Hand-written so `players` defaults to *unknown* rather than to zero.
///
/// A derived `Default` would give `0`, which is a real and different answer — "nobody is in this
/// world" — and a fixture that silently claims it is a fixture that hides the bug this field was
/// widened to fix.
impl Default for WorldRow {
    fn default() -> Self {
        Self {
            world_id: String::new(),
            name: String::new(),
            players: unknown_players(),
            mc_route: String::new(),
            added_at: 0,
            updated_at: 0,
            total_bytes: 0,
            checksum: String::new(),
            version: 0,
            piece_count: 0,
            pieces_held: 0,
            seeders: 0,
            backup_copies: 0,
            backup_copies_wanted: 0,
            loss_risk_permille: 0,
            seeding: false,
            listed_on_trackers: 0,
            announced_to_trackers: never_announced(),
            owned: false,
            connected: false,
            world_public_key: String::new(),
            regions_held: 0,
        }
    }
}

/// A world's piece picture, fetched on demand for the Pieces tab (`NODERA-PIECES`).
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct PieceMap {
    #[serde(default)]
    pub world_id: String,
    #[serde(default)]
    pub manifest_root: String,
    #[serde(default)]
    pub version: u64,
    #[serde(default)]
    pub piece_count: u64,
    #[serde(default)]
    pub held_count: u64,
    #[serde(default)]
    pub total_bytes: u64,
    /// Base64 of the little-endian piece bitmap: bit *i* set = piece *i* verified present here.
    #[serde(default)]
    pub held_bitmap: String,
    /// Peers believed to hold some of this world.
    #[serde(default)]
    pub holders: Vec<String>,
}

impl PieceMap {
    /// Expand [`Self::held_bitmap`] into one boolean per piece — what the grid renders.
    ///
    /// A bitmap that fails to decode yields all-missing rather than an error: an honest grey grid
    /// says "we do not know what is here", which is true, whereas failing the whole tab is not.
    pub fn held_flags(&self) -> Vec<bool> {
        use base64::Engine as _;
        let count = self.piece_count as usize;
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(self.held_bitmap.as_bytes())
            .unwrap_or_default();
        (0..count)
            .map(|index| {
                bytes
                    .get(index / 8)
                    .is_some_and(|byte| byte & (1u8 << (index % 8)) != 0)
            })
            .collect()
    }
}

/// One world this machine has open to LAN, and what the player decided about it.
///
/// Mirrors `LanSessionService.Session`. The state is the whole point: detection is not consent, so a
/// world the worker can see is not a world it is sharing.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct LanSessionRow {
    pub session_id: String,
    pub name: String,
    /// The LAN port Minecraft chose — the session's identity, since a reopened world gets a new one.
    pub port: u16,
    /// `offered` | `shared` | `declined`.
    #[serde(default)]
    pub state: String,
    #[serde(default)]
    pub detected_at: u64,
}

/// The LAN block of the worker's state.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct LanBlock {
    /// `false` when this machine's network stack would not let the worker join the multicast group.
    /// Distinct from an empty session list, and the UI must say so — one is "nothing open", the
    /// other is "this can never work here".
    #[serde(default)]
    pub supported: bool,
    /// Why not, when `supported` is false. Empty otherwise. "Switched off" and "this machine cannot"
    /// are different answers and a screen that shows neither leaves the user with nothing to do.
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub sessions: Vec<LanSessionRow>,
}

/// A configured discovery service (tracker / rendezvous) plus its last-probed reachability — the
/// "VPN server list" health rows.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct EndpointHealth {
    pub host: String,
    pub port: u16,
    /// `tcp` / `udp` — how this endpoint is actually reached, so the UI renders a real URI.
    #[serde(default)]
    pub scheme: String,
    #[serde(default)]
    pub reachable: bool,
    /// Handshake round-trip in milliseconds, or `-1` when unreachable / not yet probed.
    #[serde(default = "unprobed_latency")]
    pub latency_ms: i64,
}

fn unprobed_latency() -> i64 {
    -1
}

/// The full dashboard snapshot pushed to the React frontend.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct Metrics {
    /// This worker's persistent node identity (stable across restarts).
    #[serde(default)]
    pub node_id: String,
    #[serde(default)]
    pub worker_version: String,
    /// This node's own client agent, e.g. `NoderaMC 0.1.0`.
    #[serde(default)]
    pub client: String,
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
    /// Pieces this node tracks across every world it holds or hosts.
    #[serde(default)]
    pub total_chunks: u64,
    /// Upload ÷ download in permille (1000 = 1.00). Integers, so every surface renders the same
    /// number — no float-formatting drift between this app and the in-game HUD.
    #[serde(default)]
    pub share_ratio_permille: u64,
    /// Verified-present pieces as a permille of all pieces this node tracks.
    #[serde(default)]
    pub availability_permille: u64,

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

    /// Worlds opened to LAN on this machine, and the player's decision about each.
    #[serde(default)]
    pub lan: LanBlock,

    /// Whether the supervised headless peer is currently up.
    #[serde(default)]
    pub daemon_up: bool,

    /// The worker's own view of whether it is moving bytes.
    ///
    /// Mirrored from STATE rather than read back from the app's [`crate::power::PauseHandle`] on
    /// purpose: the handle records what the app *asked for*, and a push that never landed would
    /// then show a pause that is not happening. Only the worker can say it is paused. Without this
    /// field on screen the whole feature is indistinguishable from a stalled node.
    #[serde(default)]
    pub transfers_paused: bool,
}

/// A piece map with its bitmap already expanded, as handed to the frontend.
///
/// The grid wants one boolean per cell; doing the expansion here keeps base64 and bit arithmetic
/// out of the UI, and means the frontend cannot disagree with the backend about which bit is which
/// piece.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct PieceMapView {
    pub world_id: String,
    pub manifest_root: String,
    pub version: u64,
    pub piece_count: u64,
    pub held_count: u64,
    pub total_bytes: u64,
    /// One entry per piece: `true` = verified present locally (the green cell).
    pub held: Vec<bool>,
    /// Peers believed to hold some of this world.
    pub holders: Vec<String>,
}

impl PieceMapView {
    /// Expand a worker report into the render-ready form.
    pub fn of(map: PieceMap) -> Self {
        let held = map.held_flags();
        Self {
            world_id: map.world_id,
            manifest_root: map.manifest_root,
            version: map.version,
            piece_count: map.piece_count,
            held_count: map.held_count,
            total_bytes: map.total_bytes,
            held,
            holders: map.holders,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine as _;

    fn bitmap(bits: &[usize], byte_len: usize) -> String {
        let mut bytes = vec![0u8; byte_len];
        for &bit in bits {
            bytes[bit / 8] |= 1u8 << (bit % 8);
        }
        base64::engine::general_purpose::STANDARD.encode(bytes)
    }

    /// The bit order must match Java's `BitSet.toByteArray()` (little-endian within each byte), or
    /// the grid would show the right *number* of green cells in the wrong *places* — a bug that
    /// looks plausible on screen and is therefore worth pinning.
    #[test]
    fn held_flags_match_the_java_bitset_byte_order() {
        let map = PieceMap {
            piece_count: 10,
            held_bitmap: bitmap(&[0, 3, 9], 2),
            ..PieceMap::default()
        };
        let held = map.held_flags();
        assert_eq!(held.len(), 10);
        for (index, present) in held.iter().enumerate() {
            let expected = matches!(index, 0 | 3 | 9);
            assert_eq!(*present, expected, "piece {index}");
        }
    }

    #[test]
    fn held_flags_are_bounded_by_piece_count_not_by_the_bitmap() {
        // A bitmap with trailing capacity must not invent pieces beyond the manifest.
        let map = PieceMap {
            piece_count: 3,
            held_bitmap: bitmap(&[0, 1, 2, 7], 1),
            ..PieceMap::default()
        };
        assert_eq!(map.held_flags(), vec![true, true, true]);
    }

    #[test]
    fn a_short_bitmap_reads_as_missing_rather_than_panicking() {
        // Fewer bytes than pieces: the tail is simply not held yet.
        let map = PieceMap {
            piece_count: 20,
            held_bitmap: bitmap(&[1], 1),
            ..PieceMap::default()
        };
        let held = map.held_flags();
        assert_eq!(held.len(), 20);
        assert!(held[1]);
        assert!(!held[19]);
    }

    #[test]
    fn an_undecodable_bitmap_is_all_missing_not_an_error() {
        let map = PieceMap {
            piece_count: 4,
            held_bitmap: "!!! not base64 !!!".to_owned(),
            ..PieceMap::default()
        };
        assert_eq!(map.held_flags(), vec![false, false, false, false]);
    }

    #[test]
    fn an_empty_map_expands_to_no_cells() {
        assert!(PieceMap::default().held_flags().is_empty());
        assert!(PieceMapView::of(PieceMap::default()).held.is_empty());
    }

    /// Ownership is read from the worker, never inferred here. In particular it is NOT the negation
    /// of `seeding`: this case is a world the node hosts for somebody else, which the old code would
    /// have shown under "your worlds".
    #[test]
    fn hosting_a_world_is_not_the_same_as_owning_it() {
        let json = r#"{"connected_worlds":[
            {"world_id":"a","name":"Someone else's","seeding":false,"owned":false},
            {"world_id":"b","name":"Mine, only seeded here","seeding":true,"owned":true,
             "world_public_key":"MCowBQYDK2VwAyEA"}]}"#;
        let metrics: Metrics = serde_json::from_str(json).expect("parses");
        assert!(
            !metrics.connected_worlds[0].owned,
            "hosting is not authorship"
        );
        assert!(
            metrics.connected_worlds[1].owned,
            "authorship survives not hosting"
        );
        assert_eq!(
            metrics.connected_worlds[1].world_public_key,
            "MCowBQYDK2VwAyEA"
        );
    }

    /// A worker too old to report ownership must not have worlds attributed to it: the field
    /// defaults to `false`, so an unanswered question reads as "not mine" rather than as a claim.
    #[test]
    fn a_worker_that_says_nothing_about_ownership_owns_nothing() {
        let json = r#"{"connected_worlds":[{"world_id":"a","name":"W","seeding":false}]}"#;
        let metrics: Metrics = serde_json::from_str(json).expect("parses");
        assert!(!metrics.connected_worlds[0].owned);
        assert!(metrics.connected_worlds[0].world_public_key.is_empty());
    }

    /// Unknown fields must not break the parse: the worker adds fields additively and an older app
    /// has to keep working against a newer worker.
    #[test]
    fn unknown_state_fields_are_ignored() {
        let json = r#"{"node_id":"n","peers":[],"future_field":42,
                       "connected_worlds":[{"world_id":"w","name":"W","brand_new":true}]}"#;
        let metrics: Metrics = serde_json::from_str(json).expect("additive fields must parse");
        assert_eq!(metrics.node_id, "n");
        assert_eq!(metrics.connected_worlds.len(), 1);
        assert_eq!(metrics.connected_worlds[0].name, "W");
    }
}
