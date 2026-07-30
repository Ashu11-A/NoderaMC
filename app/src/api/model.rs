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
#[derive(Default)]
pub enum LinkStatus {
    /// No worker has answered yet this run — never confuse with "the worker said zero".
    #[default]
    Connecting,
    /// A snapshot arrived recently over the push stream: what is on screen is current.
    Live,
    /// The worker is answering, but by polling — either it is too old to stream, or the stream
    /// dropped and the fallback took over. Values are current to within the poll interval.
    Polling,
    /// The link is down. Whatever is on screen is the last thing we heard and is dated.
    Offline,
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
    /// `true` when somebody on this machine is in the world right now. Answers "am I playing it",
    /// which the other two flags cannot: the ordinary case is a world you neither run nor authored.
    pub connected: bool,
    pub world_public_key: String,
    /// Players in-world, or `None` when no node that is *in* the world has reported recently.
    ///
    /// Only a node with a game in the world can count its players. Every other peer — a seeder, a
    /// worker whose game has closed, this app before the first report lands — has to say it does
    /// not know, and the UI renders that as an em dash. Collapsing it to `0` is what made the count
    /// wrong everywhere except on the machine hosting the game.
    pub players: Option<u32>,
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
    /// Full copies of this world believed to exist, this node's included when it holds one.
    pub backup_copies: u64,
    /// Copies a network this size should keep, capped by the number of peers that exist — so a
    /// small network wants a full copy on every peer and this equals `backup_copies` when reached.
    pub backup_copies_wanted: u64,
    /// Chance nobody is holding this world at a given moment, in permille. `None` once there are
    /// enough copies for the risk to round to nothing, so the UI stops reporting "0.0%" as news.
    pub loss_risk_permille: Option<u64>,
    /// Verified-present pieces as a permille of the manifest. `None` until the manifest is known —
    /// a world whose pieces have not been counted is not 0% complete, it is unmeasured.
    pub completeness_permille: Option<u64>,
    /// Whether any tracker has accepted this world's announce, or `None` when it has never been
    /// announced (nothing has been asked, so nothing has refused).
    ///
    /// `Some(false)` is the state the app had no way to show: the world is hosted here, complete
    /// here, its game endpoint is open — and no directory on the network lists it, so no other peer
    /// can find it at all. Every "joinable" label is a claim about reach, and this is the only field
    /// that knows whether the reach exists.
    pub discoverable: Option<bool>,
    /// Regions of the live world this node validates and seeds snapshots for.
    ///
    /// The processing half of what a node contributes. Holding bytes and doing work are different
    /// jobs, and a screen that only reports the first one cannot tell a player whether the world's
    /// simulation is shared out or whether one machine is still carrying all of it.
    pub regions_held: u64,
}

impl World {
    /// Bytes of this world verified present on this node.
    ///
    /// Prorated from the piece counts rather than measured, because the worker reports the manifest
    /// and the held count, not a byte total per world — and prorating is exact for every piece but
    /// the last one, which is the only short piece a fixed splitter produces. Zero pieces means zero
    /// bytes, never "the whole world": a node that holds nothing contributes nothing.
    pub fn held_bytes(&self) -> u64 {
        if self.piece_count == 0 {
            return 0;
        }
        ((self.total_bytes as u128 * self.pieces_held as u128) / self.piece_count as u128) as u64
    }

    fn of(row: &WorldRow) -> Self {
        Self {
            world_id: row.world_id.clone(),
            name: row.name.clone(),
            administered: row.owned,
            hosting: !row.seeding,
            connected: row.connected,
            world_public_key: row.world_public_key.clone(),
            // Negative is the worker's "nobody in that world has reported"; it must not survive as
            // a number the UI could format.
            players: u32::try_from(row.players).ok(),
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
            backup_copies: row.backup_copies,
            backup_copies_wanted: row.backup_copies_wanted,
            loss_risk_permille: if row.loss_risk_permille == 0 {
                None
            } else {
                Some(row.loss_risk_permille)
            },
            completeness_permille: if row.piece_count == 0 {
                None
            } else {
                Some(row.pieces_held.saturating_mul(1000) / row.piece_count)
            },
            discoverable: if row.announced_to_trackers < 0 {
                None
            } else {
                Some(row.listed_on_trackers > 0)
            },
            regions_held: row.regions_held,
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
    // The modal condition is asserted by this module's tests; the UI evaluates it from the state
    // string it is handed.
    #[allow(dead_code)]
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
    /// Worlds somebody on this machine is in right now.
    pub connected_worlds: usize,
    /// Worlds this node keeps alive for other people — supported and *not* being played here.
    ///
    /// Counted separately from `supported_worlds` because the Worlds screen lists them separately:
    /// a world you are playing in belongs under "you are here", not under "you are helping out",
    /// even though the node is doing the same work for both.
    pub shared_for_others: usize,
    /// Content bytes verified present on this node across every world it does not administer — the
    /// honest answer to "how much am I contributing", as distinct from how much exists.
    pub shared_bytes: u64,
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
            connected_worlds: worlds.iter().filter(|w| w.connected).count(),
            shared_for_others: worlds
                .iter()
                .filter(|w| !w.administered && !w.connected)
                .count(),
            // Held, not total: a world half-downloaded contributes half a world, and reporting its
            // full size as "shared" would credit this node for bytes it cannot serve.
            shared_bytes: worlds
                .iter()
                .filter(|w| !w.administered)
                .map(|w| w.held_bytes())
                .sum(),
            // Players across the worlds this node is answerable for: the ones it administers and
            // the ones somebody here is playing in. A supported world nobody here has joined is
            // excluded, because its players belong to whoever runs it.
            //
            // Only *known* counts are summed. A world whose count nothing has reported contributes
            // nothing rather than a zero, so the headline stops silently averaging in worlds this
            // node cannot see into.
            players: worlds
                .iter()
                .filter(|w| w.administered || w.connected)
                .filter_map(|w| w.players)
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

    /// A world row with a *reported* player count. Pass a negative count for "nobody has reported".
    fn world(id: &str, owned: bool, seeding: bool, players: i64) -> WorldRow {
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

    /// "Nobody is in this world" and "nothing here can see who is" are different answers.
    ///
    /// They shared the value `0` for the whole life of this field, which is why every peer but the
    /// one hosting the game reported a confident zero for a world with people standing in it.
    #[test]
    fn an_unreported_player_count_is_unknown_rather_than_none_present() {
        let metrics = Metrics {
            connected_worlds: vec![
                // Nothing has reported: this node holds the bytes and has no game in the world.
                world("supported-cannot-see", false, true, -1),
                // A node IN the world reported that it is empty. A real, different answer.
                world("reported-empty", true, false, 0),
                // Playing in somebody else's busy world.
                WorldRow {
                    connected: true,
                    ..world("theirs-busy", false, true, 3)
                },
            ],
            ..Metrics::default()
        };

        let dash = Dashboard::of(&metrics, Rates::default());

        assert_eq!(dash.worlds[0].players, None);
        assert_eq!(dash.worlds[1].players, Some(0));
        assert_eq!(dash.worlds[2].players, Some(3));
        // The headline counts the worlds this node is answerable for — its own, plus the one it is
        // playing in — and skips the unknown entirely rather than adding a zero for it.
        assert_eq!(dash.counts.players, 3);
    }

    /// The three flags are three questions, and the screen groups worlds by the answers.
    ///
    /// Written because the app had only two of them and therefore no row at all for the ordinary
    /// case: a player inside somebody else's world. That peer's Worlds screen read "Nothing is on
    /// the network until you share it" while the player was standing in the world.
    #[test]
    fn a_world_being_played_here_is_counted_apart_from_one_merely_supported() {
        let metrics = Metrics {
            connected_worlds: vec![
                // Playing in somebody else's world — supported, and where the player is.
                WorldRow {
                    seeding: true,
                    connected: true,
                    piece_count: 100,
                    pieces_held: 100,
                    total_bytes: 1000,
                    ..world("theirs-im-in", false, true, 3)
                },
                // Carried for other people, nobody here is in it.
                WorldRow {
                    piece_count: 100,
                    pieces_held: 25,
                    total_bytes: 1000,
                    ..world("theirs-i-carry", false, true, 0)
                },
                // Own world, not being played.
                world("mine", true, false, 0),
            ],
            ..Metrics::default()
        };

        let dash = Dashboard::of(&metrics, Rates::default());

        assert_eq!(dash.counts.connected_worlds, 1);
        // Still supported — playing in a world does not stop you carrying it…
        assert_eq!(dash.counts.supported_worlds, 2);
        // …but the "helping out with" list is the other one, so the two are not the same number.
        assert_eq!(dash.counts.shared_for_others, 1);
        // Held, not total: 1000 + 250 across the two worlds this node does not administer.
        assert_eq!(dash.counts.shared_bytes, 1250);
    }

    /// Held bytes are prorated from the pieces, and a node holding nothing holds nothing.
    #[test]
    fn held_bytes_never_credits_a_world_whose_pieces_are_absent() {
        let none = World::of(&WorldRow {
            piece_count: 10,
            pieces_held: 0,
            total_bytes: 5000,
            ..world("empty", false, true, 0)
        });
        assert_eq!(none.held_bytes(), 0);

        // No manifest at all is not "complete", and must not be reported as the full size either.
        let unmeasured = World::of(&WorldRow {
            piece_count: 0,
            total_bytes: 5000,
            ..world("unmeasured", false, true, 0)
        });
        assert_eq!(unmeasured.held_bytes(), 0);
        assert_eq!(unmeasured.completeness_permille, None);

        let half = World::of(&WorldRow {
            piece_count: 4,
            pieces_held: 2,
            total_bytes: 999,
            ..world("half", false, true, 0)
        });
        assert_eq!(half.held_bytes(), 499);
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

    /// "Announced" and "listed" are different facts, and the third state is "neither has happened".
    ///
    /// A world hosted with an open game and no tracker that will take its announce is unreachable,
    /// and nothing else on the screen says so — the piece count, the completeness and the game
    /// endpoint are all healthy while no peer in the world can find it.
    #[test]
    fn a_world_no_tracker_accepted_is_not_discoverable() {
        let world = |listed: i64, asked: i64| Metrics {
            connected_worlds: vec![WorldRow {
                world_id: "w".into(),
                mc_route: "10.0.0.1:25565".into(),
                listed_on_trackers: listed,
                announced_to_trackers: asked,
                ..WorldRow::default()
            }],
            ..Metrics::default()
        };
        let of = |m: Metrics| Dashboard::of(&m, Rates::default()).worlds[0].discoverable;

        assert_eq!(of(world(0, 3)), Some(false), "asked three, listed by none");
        assert_eq!(
            of(world(1, 3)),
            Some(true),
            "one tracker is enough to be found"
        );
        assert_eq!(
            of(world(0, -1)),
            None,
            "never announced is not the same claim as refused everywhere"
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
