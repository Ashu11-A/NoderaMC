//! The dashboard's view model — the single shape every React page reads.
//!
//! # Why this is not `metrics::Metrics`
//!
//! [`crate::metrics::Metrics`] is the *wire* type: a faithful mirror of the worker's
//! `NODERA-STATE` JSON, field for field, so the two can be kept in lockstep. It is deliberately
//! flat, deliberately all-numeric, and deliberately says nothing about whether those numbers are
//! current — because the worker, at the moment it renders them, has no way to know what a reader
//! will still believe five seconds later.
//!
//! That gap is what put a screen full of confident zeros in front of a user. Every tile read
//! `0`, and `0` was indistinguishable from four different situations: the worker is down; the
//! worker is up and has genuinely done nothing; the app has not managed to read it yet; the app
//! read it once, ten minutes ago, and has been showing that ever since. A dashboard that cannot
//! tell those apart is not a dashboard.
//!
//! So this module adds the one thing the wire type cannot carry — **provenance**. Every snapshot
//! says when it was taken, what carried it, and how many it is in the sequence, and the pages are
//! built to render "—" whenever the honest answer is "nobody has told us". A number appears only
//! when a worker actually said it.
//!
//! # Rates are computed here, not in React
//!
//! Throughput is a derivative, and taking it in the UI means taking it from whatever pair of
//! renders React happened to see. That produced a real bug once already: the first frame differenced
//! a lifetime counter against a zeroed placeholder and rendered gigabytes per second. Rates are
//! measured here, against a real elapsed time between two accepted snapshots, and shipped as
//! numbers. The UI formats them and nothing else.

use serde::{Deserialize, Serialize};

use crate::metrics::{Metrics, PeerRow, WorldRow};

/// How the app is currently getting its data.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LinkStatus {
    /// No worker has answered yet this run — never confuse with "the worker said zero".
    Connecting,
    /// A snapshot arrived recently over the push stream: what is on screen is current.
    Live,
    /// The worker is answering, but by polling — either it is too old to stream, or the stream
    /// dropped and the fallback took over. Values are current to within the poll interval.
    Polling,
    /// The link is down. Whatever is on screen is the last thing we heard and is dated.
    Offline,
}

impl Default for LinkStatus {
    fn default() -> Self {
        Self::Connecting
    }
}

/// Where a snapshot came from and how much it can be trusted right now.
///
/// This travels with every payload rather than being a separate query on purpose: a page that has
/// to ask two questions to render one number will eventually render the number without asking the
/// second one.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Link {
    pub status: LinkStatus,
    /// `stream` (the worker pushes), `poll` (the app asks), or `none`.
    pub transport: String,
    /// The worker's reported build, empty until it has said.
    pub worker_version: String,
    /// Epoch millis of the newest accepted snapshot. **Zero means never** — the flag every "is this
    /// a real zero" question resolves against.
    pub last_update_ms: u64,
    /// Milliseconds since that snapshot, computed when this payload is built.
    pub age_ms: u64,
    /// Monotonic counter of accepted snapshots. A UI can tell "unchanged" from "not updating" by
    /// watching this rather than by diffing values that legitimately stay the same.
    pub revision: u64,
    /// Why the link is not live, in the words of whatever failed. Empty when it is.
    pub last_error: String,
    /// How many times the link has had to be re-established this run.
    pub reconnects: u64,
    /// Whether any worker snapshot has ever been accepted. When false, **every** number below is a
    /// placeholder and the UI must render "—".
    pub has_data: bool,
}

/// The node's own identity and posture.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Node {
    pub node_id: String,
    pub client: String,
    pub self_route: String,
    pub roles: Vec<String>,
    pub uptime_seconds: u64,
    pub is_gateway: bool,
    /// The worker's own report that it is not moving bytes — mirrored, never inferred from a
    /// request this app made.
    pub transfers_paused: bool,
}

/// Bytes in, bytes out, and how fast right now.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Traffic {
    pub total_sent_bytes: u64,
    pub total_received_bytes: u64,
    /// Measured against the elapsed time between two accepted snapshots, never assumed from the
    /// event cadence. `None` until there are two to measure between.
    pub up_bytes_per_sec: Option<u64>,
    pub down_bytes_per_sec: Option<u64>,
    /// Upload ÷ download, permille. `None` when nothing has been downloaded — a ratio with no
    /// denominator is undefined, and rendering ∞ or 0.00 both state something untrue.
    pub share_ratio_permille: Option<u64>,
}

/// What this node is holding for the network.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Content {
    pub maintained_pieces: u64,
    pub maintained_bytes: u64,
    pub tracked_pieces: u64,
    /// Verified-present pieces as a permille of tracked ones. `None` when nothing is tracked, so
    /// the UI does not render a triumphant 100% for a node holding nothing.
    pub availability_permille: Option<u64>,
}

/// One world, with the two independent facts about it kept apart.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct World {
    pub world_id: String,
    pub name: String,
    /// `true` when this peer holds the world's private key. Answers "may I speak for it".
    pub administered: bool,
    /// `true` when this node hosts the world rather than only keeping its bytes. Answers "what am
    /// I doing with it". Neither implies the other.
    pub hosting: bool,
    pub world_public_key: String,
    pub players: u32,
    /// The host's Minecraft endpoint while its game runs; `None` once it closes.
    pub game_endpoint: Option<String>,
    pub added_at: u64,
    pub updated_at: u64,
    pub total_bytes: u64,
    pub checksum: String,
    pub version: u64,
    pub piece_count: u64,
    pub pieces_held: u64,
    pub seeders: u64,
    /// Verified-present pieces as a permille of the manifest. `None` until the manifest is known —
    /// a world whose pieces have not been counted is not 0% complete, it is unmeasured.
    pub completeness_permille: Option<u64>,
}

impl World {
    fn of(row: &WorldRow) -> Self {
        Self {
            world_id: row.world_id.clone(),
            name: row.name.clone(),
            administered: row.owned,
            hosting: !row.seeding,
            world_public_key: row.world_public_key.clone(),
            players: row.players,
            // The worker sends an empty string for "the game is closed". An Option says that once,
            // here, instead of every call site remembering that "" is not an endpoint.
            game_endpoint: if row.mc_route.is_empty() {
                None
            } else {
                Some(row.mc_route.clone())
            },
            added_at: row.added_at,
            updated_at: row.updated_at,
            total_bytes: row.total_bytes,
            checksum: row.checksum.clone(),
            version: row.version,
            piece_count: row.piece_count,
            pieces_held: row.pieces_held,
            seeders: row.seeders,
            completeness_permille: if row.piece_count == 0 {
                None
            } else {
                Some(row.pieces_held.saturating_mul(1000) / row.piece_count)
            },
        }
    }
}

/// One peer this node is exchanging data with.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Peer {
    pub node_id: String,
    pub route: String,
    /// `direct` | `relayed` | `unknown`.
    pub path: String,
    pub client: String,
    pub up_bytes_per_sec: u64,
    pub down_bytes_per_sec: u64,
    pub total_up_bytes: u64,
    pub total_down_bytes: u64,
}

impl Peer {
    fn of(row: &PeerRow) -> Self {
        Self {
            node_id: row.node_id.clone(),
            route: row.route.clone(),
            path: row.path.clone(),
            client: row.client.clone(),
            up_bytes_per_sec: row.up_bytes_per_sec,
            down_bytes_per_sec: row.down_bytes_per_sec,
            total_up_bytes: row.total_up_bytes,
            total_down_bytes: row.total_down_bytes,
        }
    }
}

/// A discovery service and whether it is answering.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Endpoint {
    pub host: String,
    pub port: u16,
    pub scheme: String,
    pub reachable: bool,
    /// Round trip in milliseconds. `None` when unreachable **or not yet probed** — the worker sends
    /// `-1` for both, and rendering that as `0 ms` claims an instant handshake that never happened.
    pub latency_ms: Option<i64>,
}

impl Endpoint {
    fn of(row: &crate::metrics::EndpointHealth) -> Self {
        Self {
            host: row.host.clone(),
            port: row.port,
            scheme: row.scheme.clone(),
            reachable: row.reachable,
            latency_ms: if row.latency_ms < 0 {
                None
            } else {
                Some(row.latency_ms)
            },
        }
    }
}

/// How this node is discoverable, and how well.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Discovery {
    pub trackers: Vec<Endpoint>,
    pub rendezvous: Vec<Endpoint>,
    /// The best rendezvous round trip — the number worth putting on the front page, because it is
    /// what makes this node reachable from behind a router. `None` when none is reachable.
    pub relay_latency_ms: Option<i64>,
}

/// One world this machine has open to LAN, as the UI sees it.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct LanSession {
    pub session_id: String,
    pub name: String,
    pub port: u16,
    /// `offered` — detected, nobody has said yes. `shared` — on the network. `declined` — the
    /// player said no, and it stays listed so they can still change their mind.
    pub state: String,
    pub detected_at: u64,
}

impl LanSession {
    /// Whether this world is waiting on an answer — the condition the modal is raised for.
    pub fn awaiting_answer(&self) -> bool {
        self.state == "offered"
    }
}

/// What this machine can see opened to LAN.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Lan {
    /// `false` when this worker is not watching. Not the same as an empty list: one means "nothing
    /// is open", the other means "nothing here will ever appear".
    pub supported: bool,
    /// Why it is not watching, in the worker's own words. Empty when it is.
    pub reason: String,
    pub sessions: Vec<LanSession>,
}

/// The headline counters, precomputed so two pages cannot disagree about them.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Counts {
    pub administered_worlds: usize,
    pub supported_worlds: usize,
    pub players: u32,
    pub peers: usize,
}

/// One coherent picture of the node, as of one moment.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Dashboard {
    pub link: Link,
    pub node: Node,
    pub traffic: Traffic,
    pub content: Content,
    pub worlds: Vec<World>,
    pub peers: Vec<Peer>,
    pub discovery: Discovery,
    pub counts: Counts,
    /// Worlds opened to LAN on this machine — the lane that lets an unmodified Minecraft play here.
    pub lan: Lan,
}

impl Dashboard {
    /// Build the view model from one accepted worker snapshot.
    ///
    /// `rates` are measured by the caller, which owns the previous snapshot and the clock; passing
    /// them in keeps this function pure and directly testable.
    pub fn of(metrics: &Metrics, rates: Rates) -> Self {
        let worlds: Vec<World> = metrics.connected_worlds.iter().map(World::of).collect();
        let peers: Vec<Peer> = metrics.peers.iter().map(Peer::of).collect();
        let rendezvous: Vec<Endpoint> = metrics.rendezvous.iter().map(Endpoint::of).collect();

        let counts = Counts {
            administered_worlds: worlds.iter().filter(|w| w.administered).count(),
            supported_worlds: worlds.iter().filter(|w| !w.administered).count(),
            // Players are counted across the worlds this peer administers: a supported world's
            // player count belongs to whoever runs it, and adding it here would report other
            // people's players as yours.
            players: worlds
                .iter()
                .filter(|w| w.administered)
                .map(|w| w.players)
                .sum(),
            peers: peers.len(),
        };

        let relay_latency_ms = rendezvous
            .iter()
            .filter(|e| e.reachable)
            .filter_map(|e| e.latency_ms)
            .min();

        Self {
            link: Link::default(),
            node: Node {
                node_id: metrics.node_id.clone(),
                client: metrics.client.clone(),
                self_route: metrics.self_route.clone(),
                roles: metrics.roles.clone(),
                uptime_seconds: metrics.uptime_seconds,
                is_gateway: metrics.is_gateway,
                transfers_paused: metrics.transfers_paused,
            },
            traffic: Traffic {
                total_sent_bytes: metrics.total_sent_bytes,
                total_received_bytes: metrics.total_received_bytes,
                up_bytes_per_sec: rates.up,
                down_bytes_per_sec: rates.down,
                share_ratio_permille: if metrics.total_received_bytes == 0 {
                    None
                } else {
                    Some(metrics.share_ratio_permille)
                },
            },
            content: Content {
                maintained_pieces: metrics.maintained_pieces,
                maintained_bytes: metrics.maintained_bytes,
                tracked_pieces: metrics.total_chunks,
                availability_permille: if metrics.total_chunks == 0 {
                    None
                } else {
                    Some(metrics.availability_permille)
                },
            },
            worlds,
            peers,
            discovery: Discovery {
                trackers: metrics.trackers.iter().map(Endpoint::of).collect(),
                rendezvous,
                relay_latency_ms,
            },
            counts,
            lan: Lan {
                supported: metrics.lan.supported,
                reason: metrics.lan.reason.clone(),
                sessions: metrics
                    .lan
                    .sessions
                    .iter()
                    .map(|row| LanSession {
                        session_id: row.session_id.clone(),
                        name: row.name.clone(),
                        port: row.port,
                        state: row.state.clone(),
                        detected_at: row.detected_at,
                    })
                    .collect(),
            },
        }
    }
}

/// Throughput measured between two accepted snapshots.
#[derive(Clone, Copy, Debug, Default)]
pub struct Rates {
    pub up: Option<u64>,
    pub down: Option<u64>,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn world(id: &str, owned: bool, seeding: bool, players: u32) -> WorldRow {
        WorldRow {
            world_id: id.to_owned(),
            name: id.to_owned(),
            owned,
            seeding,
            players,
            ..WorldRow::default()
        }
    }

    #[test]
    fn administered_and_hosting_are_independent() {
        let metrics = Metrics {
            connected_worlds: vec![
                world("mine-hosted", true, false, 2),
                world("mine-seeded", true, true, 0),
                world("theirs-hosted", false, false, 9),
                world("theirs-seeded", false, true, 0),
            ],
            ..Metrics::default()
        };

        let dash = Dashboard::of(&metrics, Rates::default());

        assert_eq!(dash.counts.administered_worlds, 2);
        assert_eq!(dash.counts.supported_worlds, 2);
        // A world this node hosts for somebody else is theirs, and its players are not ours.
        assert_eq!(dash.counts.players, 2);
        assert!(dash.worlds[2].hosting && !dash.worlds[2].administered);
    }

    #[test]
    fn an_unprobed_endpoint_is_unknown_rather_than_instant() {
        let metrics = Metrics {
            rendezvous: vec![
                crate::metrics::EndpointHealth {
                    host: "a".into(),
                    port: 1,
                    scheme: "tcp".into(),
                    reachable: false,
                    latency_ms: -1,
                },
                crate::metrics::EndpointHealth {
                    host: "b".into(),
                    port: 2,
                    scheme: "tcp".into(),
                    reachable: true,
                    latency_ms: 12,
                },
            ],
            ..Metrics::default()
        };

        let dash = Dashboard::of(&metrics, Rates::default());

        // -1 is the worker's "no answer", and it must never reach a screen as `0 ms`.
        assert_eq!(dash.discovery.rendezvous[0].latency_ms, None);
        assert_eq!(dash.discovery.relay_latency_ms, Some(12));
    }

    #[test]
    fn relay_latency_is_none_when_nothing_is_reachable() {
        let metrics = Metrics {
            rendezvous: vec![crate::metrics::EndpointHealth {
                host: "a".into(),
                port: 1,
                scheme: "tcp".into(),
                reachable: false,
                latency_ms: -1,
            }],
            ..Metrics::default()
        };
        assert_eq!(
            Dashboard::of(&metrics, Rates::default())
                .discovery
                .relay_latency_ms,
            None
        );
    }

    #[test]
    fn a_ratio_with_no_denominator_is_unknown_not_zero() {
        let seeding_only = Metrics {
            total_sent_bytes: 5_000,
            total_received_bytes: 0,
            share_ratio_permille: 0,
            ..Metrics::default()
        };
        assert_eq!(
            Dashboard::of(&seeding_only, Rates::default())
                .traffic
                .share_ratio_permille,
            None,
            "a node that has only uploaded has no ratio, and 0.00 says the opposite"
        );
    }

    #[test]
    fn availability_of_nothing_is_unknown_not_perfect() {
        let empty = Metrics {
            total_chunks: 0,
            availability_permille: 1000,
            ..Metrics::default()
        };
        assert_eq!(
            Dashboard::of(&empty, Rates::default())
                .content
                .availability_permille,
            None,
            "100% of nothing is not a health reading"
        );
    }

    #[test]
    fn a_closed_game_has_no_endpoint_rather_than_an_empty_one() {
        let metrics = Metrics {
            connected_worlds: vec![WorldRow {
                world_id: "w".into(),
                mc_route: String::new(),
                ..WorldRow::default()
            }],
            ..Metrics::default()
        };
        assert_eq!(
            Dashboard::of(&metrics, Rates::default()).worlds[0].game_endpoint,
            None
        );
    }

    #[test]
    fn completeness_is_unknown_before_the_manifest_is() {
        let unmeasured = Metrics {
            connected_worlds: vec![WorldRow {
                world_id: "w".into(),
                piece_count: 0,
                pieces_held: 0,
                ..WorldRow::default()
            }],
            ..Metrics::default()
        };
        assert_eq!(
            Dashboard::of(&unmeasured, Rates::default()).worlds[0].completeness_permille,
            None,
            "a world whose pieces have not been counted is unmeasured, not 0% complete"
        );

        let measured = Metrics {
            connected_worlds: vec![WorldRow {
                world_id: "w".into(),
                piece_count: 4,
                pieces_held: 1,
                ..WorldRow::default()
            }],
            ..Metrics::default()
        };
        assert_eq!(
            Dashboard::of(&measured, Rates::default()).worlds[0].completeness_permille,
            Some(250)
        );
    }

    #[test]
    fn a_lan_world_is_not_shared_until_somebody_says_so() {
        let json = r#"{"lan":{"supported":true,"sessions":[
            {"session_id":"aa","name":"My World","port":54321,"state":"offered"},
            {"session_id":"bb","name":"Other","port":54322,"state":"shared"}]}}"#;
        let metrics: Metrics = serde_json::from_str(json).expect("parses");
        let dash = Dashboard::of(&metrics, Rates::default());

        assert!(dash.lan.supported);
        assert!(
            dash.lan.sessions[0].awaiting_answer(),
            "offered is what raises the modal"
        );
        assert!(
            !dash.lan.sessions[1].awaiting_answer(),
            "already answered, do not ask again"
        );
    }

    #[test]
    fn a_machine_that_cannot_watch_for_lan_worlds_says_why() {
        // The distinction that stops the UI showing "no worlds open" forever on a worker where the
        // answer will never change — and the reason, because "switched off" is fixable and "this
        // machine cannot" is not, and a bare false makes them identical.
        let json = r#"{"lan":{"supported":false,
            "reason":"LAN detection is switched off for this worker (NODERA_LAN_WATCH=0)",
            "sessions":[]}}"#;
        let metrics: Metrics = serde_json::from_str(json).expect("parses");
        let dash = Dashboard::of(&metrics, Rates::default());
        assert!(!dash.lan.supported);
        assert!(dash.lan.reason.contains("switched off"));
        assert!(dash.lan.sessions.is_empty());
    }

    #[test]
    fn a_worker_too_old_to_know_about_lan_reports_it_unsupported() {
        let dash = Dashboard::of(&Metrics::default(), Rates::default());
        assert!(!dash.lan.supported);
    }

    #[test]
    fn a_fresh_dashboard_says_it_has_no_data() {
        let dash = Dashboard::default();
        assert!(!dash.link.has_data);
        assert_eq!(dash.link.status, LinkStatus::Connecting);
        assert_eq!(dash.link.last_update_ms, 0);
    }
}
