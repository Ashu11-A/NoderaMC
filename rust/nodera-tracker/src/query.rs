//! Assembling a `TrackerResponse` from the registry.
//!
//! The response shape is the frozen Task 20 wire family, so the Java read path
//! (`TrackerDataSource` → the Task 26 GUI) needs no changes at all: only the process that fills it
//! in has moved.

use crate::config::Config;
use crate::health;
use crate::registry::{PeerRecord, Registry};
use nodera_codec::messages::{
    PeerRoutes, TrackerCatalogEntry, TrackerCatalogResponse, TrackerResponse,
    TrackerRoutesResponse,
};
use nodera_codec::types::{ManifestSeeders, NodeId, PeerEntry};
use std::collections::BTreeMap;

/// Catalog page size when the query asks for `0` ("the tracker's default").
const DEFAULT_CATALOG_PAGE: usize = 64;
/// Hard ceiling on a catalog page, whatever the query asks for.
const MAX_CATALOG_PAGE: usize = 256;

/// Build the answer to a query for `genesis_hash`.
///
/// A world nobody is in still gets an answer — "no such world" and "an empty world" are
/// indistinguishable to a tracker, and silence would only make the UI hang.
pub fn answer(
    registry: &Registry,
    genesis_hash: &[u8],
    config: &Config,
    now_millis: u64,
) -> TrackerResponse {
    let Some(swarm) = registry.swarm(genesis_hash) else {
        return empty_response(genesis_hash, config, now_millis, String::new(), 0);
    };

    let live = swarm.live_peers(now_millis, config.peer_ttl_millis());
    if live.is_empty() {
        return empty_response(
            genesis_hash,
            config,
            now_millis,
            swarm.world_name.clone(),
            swarm.retention_deadline_epoch_millis,
        );
    }

    let seeder_count = live.iter().filter(|(_, r)| r.is_seeder()).count();
    let reliability_bps = mean_reliability_bps(&live);
    let stored_chunks = stored_piece_count(&live);
    let seeders = seeders_by_manifest(&live);
    let sampled = sample(&live, config.sample_size, config.seeder_floor);

    TrackerResponse {
        genesis_hash: genesis_hash.to_vec(),
        world_name: swarm.world_name.clone(),
        peers: sampled,
        seeders,
        // The player count is the whole live swarm, not the sample: the UI is asking how busy the
        // world is, and answering with the page size would under-report every large world.
        world_player_count: live.len() as u64,
        stored_chunks,
        reliability_bps,
        health: health::classify(
            seeder_count,
            config.healthy_seeder_floor,
            swarm.retention_deadline_epoch_millis,
            now_millis,
        ),
        retention_deadline_epoch_millis: swarm.retention_deadline_epoch_millis,
    }
}

fn empty_response(
    genesis_hash: &[u8],
    config: &Config,
    now_millis: u64,
    world_name: String,
    retention_deadline_epoch_millis: u64,
) -> TrackerResponse {
    TrackerResponse {
        genesis_hash: genesis_hash.to_vec(),
        world_name,
        peers: Vec::new(),
        seeders: Vec::new(),
        world_player_count: 0,
        stored_chunks: 0,
        reliability_bps: 0,
        health: health::classify(
            0,
            config.healthy_seeder_floor,
            retention_deadline_epoch_millis,
            now_millis,
        ),
        retention_deadline_epoch_millis,
    }
}

/// Mean of the announced basis points, in pure integer math.
///
/// Integer-only on purpose: the same value is rendered in a GUI and compared in tests, and a float
/// mean would make the answer depend on summation order.
fn mean_reliability_bps(live: &[(&NodeId, &PeerRecord)]) -> u32 {
    if live.is_empty() {
        return 0;
    }
    let total: u64 = live.iter().map(|(_, r)| u64::from(r.reliability_bps)).sum();
    (total / live.len() as u64) as u32
}

/// Distinct pieces held across the world, matching the Java inventory's `storedPieces`: per
/// manifest, the union of every holder's bitmap, summed over manifests.
///
/// A bytewise OR is bit-order independent, so this agrees with the Java `PieceBitmap` packing
/// without duplicating its bit layout here.
fn stored_piece_count(live: &[(&NodeId, &PeerRecord)]) -> u64 {
    let mut unions: BTreeMap<&[u8], Vec<u8>> = BTreeMap::new();
    for (_, record) in live {
        for holding in &record.holdings {
            let union = unions.entry(holding.manifest_root.as_slice()).or_default();
            if union.len() < holding.piece_bitmap.len() {
                union.resize(holding.piece_bitmap.len(), 0);
            }
            for (slot, byte) in union.iter_mut().zip(holding.piece_bitmap.iter()) {
                *slot |= byte;
            }
        }
    }
    unions
        .values()
        .map(|bitmap| {
            bitmap
                .iter()
                .map(|b| u64::from(b.count_ones()))
                .sum::<u64>()
        })
        .sum()
}

/// Who holds each manifest, in deterministic order.
fn seeders_by_manifest(live: &[(&NodeId, &PeerRecord)]) -> Vec<ManifestSeeders> {
    let mut by_manifest: BTreeMap<Vec<u8>, Vec<NodeId>> = BTreeMap::new();
    for (id, record) in live {
        for holding in &record.holdings {
            if holding.held_piece_count() == 0 {
                // A holder of nothing is not a seeder of that manifest; advertising it would send
                // downloaders to a peer with no pieces.
                continue;
            }
            by_manifest
                .entry(holding.manifest_root.clone())
                .or_default()
                .push(**id);
        }
    }
    by_manifest
        .into_iter()
        .map(|(manifest_root, mut seeders)| {
            seeders.sort_by_key(|id| (id.msb, id.lsb));
            seeders.dedup();
            ManifestSeeders {
                manifest_root,
                seeders,
            }
        })
        .collect()
}

/// Build the directory listing (`TrackerCatalogQuery` → `TrackerCatalogResponse`).
///
/// Every tracked world lists — including one whose every seeder has gone silent (the DEAD /
/// countdown row is exactly what the catalog exists to show). Sorted by name then hash so the
/// same registry state always produces the same page.
pub fn catalog(
    registry: &Registry,
    limit: u32,
    config: &Config,
    now_millis: u64,
) -> TrackerCatalogResponse {
    let mut worlds: Vec<TrackerCatalogEntry> = Vec::new();
    for (hash, swarm) in registry.swarms() {
        let live = swarm.live_peers(now_millis, config.peer_ttl_millis());
        let seeder_count = live.iter().filter(|(_, r)| r.is_seeder()).count();
        worlds.push(TrackerCatalogEntry {
            genesis_hash: hash.clone(),
            world_name: swarm.world_name.clone(),
            world_player_count: live.len() as u64,
            stored_chunks: stored_piece_count(&live),
            reliability_bps: mean_reliability_bps(&live),
            health: health::classify(
                seeder_count,
                config.healthy_seeder_floor,
                swarm.retention_deadline_epoch_millis,
                now_millis,
            ),
            retention_deadline_epoch_millis: swarm.retention_deadline_epoch_millis,
        });
    }
    worlds.sort_by(|a, b| {
        a.world_name
            .cmp(&b.world_name)
            .then_with(|| a.genesis_hash.cmp(&b.genesis_hash))
    });
    let cap = if limit == 0 {
        DEFAULT_CATALOG_PAGE
    } else {
        limit as usize
    };
    worlds.truncate(cap.min(MAX_CATALOG_PAGE));
    TrackerCatalogResponse { worlds }
}

/// Answer a routes query: every live peer's full claimed dial-route list, relayed verbatim
/// (`TrackerRoutesQuery` → `TrackerRoutesResponse`). An unknown world answers empty.
pub fn routes(
    registry: &Registry,
    genesis_hash: &[u8],
    config: &Config,
    now_millis: u64,
) -> TrackerRoutesResponse {
    let peers = registry
        .swarm(genesis_hash)
        .map(|swarm| {
            swarm
                .live_peers(now_millis, config.peer_ttl_millis())
                .into_iter()
                .map(|(id, record)| PeerRoutes {
                    peer: *id,
                    routes: record.dial_routes(),
                })
                .collect()
        })
        .unwrap_or_default();
    TrackerRoutesResponse {
        genesis_hash: genesis_hash.to_vec(),
        peers,
    }
}

/// Pick at most `sample_size` peers, seeders first up to `seeder_floor`.
///
/// Deterministic rather than randomised: given the same registry state the same page comes back,
/// which is what makes the bound testable, and a downloader that retries gets a stable answer
/// instead of a reshuffled one. Seeders are guaranteed slots because a page of pure leechers is
/// useless to a joining peer.
fn sample(
    live: &[(&NodeId, &PeerRecord)],
    sample_size: usize,
    seeder_floor: usize,
) -> Vec<PeerEntry> {
    let mut picked: Vec<PeerEntry> = Vec::with_capacity(sample_size.min(live.len()));
    let mut taken = vec![false; live.len()];

    let seeder_slots = seeder_floor.min(sample_size);
    for (index, (id, record)) in live.iter().enumerate() {
        if picked.len() >= seeder_slots {
            break;
        }
        if record.is_seeder() {
            picked.push(entry_of(id, record));
            taken[index] = true;
        }
    }
    for (index, (id, record)) in live.iter().enumerate() {
        if picked.len() >= sample_size {
            break;
        }
        if !taken[index] {
            picked.push(entry_of(id, record));
        }
    }
    picked
}

fn entry_of(id: &NodeId, record: &PeerRecord) -> PeerEntry {
    let routes = record.dial_routes();
    PeerEntry {
        node_id: *id,
        // `PeerEntry` carries one route; the peer's own first claim wins, with the observed
        // address as the fallback when it advertised none. Non-P2P route forms (the `mc/` game
        // endpoint) are skipped — this entry bootstraps the mesh, and the full claimed list is
        // served by the routes query (tag 49).
        route: routes
            .iter()
            .find(|r| !r.starts_with("mc/"))
            .or_else(|| routes.first())
            .cloned()
            .unwrap_or_default(),
        capabilities: record.capabilities.clone(),
        bootstrap: record
            .capabilities
            .roles
            .contains(&nodera_codec::types::PeerRole::Bootstrap),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::AnnounceOutcome;
    use crate::test_support::{announce, caps, holding, seeder_caps};
    use nodera_codec::messages::AnnounceEvent;
    use nodera_codec::types::WorldHealth;

    fn config() -> Config {
        Config {
            sample_size: 3,
            seeder_floor: 2,
            healthy_seeder_floor: 2,
            ..Config::default()
        }
    }

    fn registry_with(peers: &[(u64, bool)], now: u64) -> Registry {
        let mut registry = Registry::new();
        for (n, is_seeder) in peers {
            let mut a = announce(
                *n,
                b"world",
                AnnounceEvent::Started,
                if *is_seeder { seeder_caps() } else { caps() },
            );
            a.reliability_bps = 9_000;
            if *is_seeder {
                a.holdings = vec![holding(0x22, &[0b1111_0000])];
            }
            assert_eq!(
                registry.apply_announce(&a, None, now, 100, 100, 300_000),
                AnnounceOutcome::Registered
            );
        }
        registry
    }

    #[test]
    fn an_unknown_world_still_answers() {
        let registry = Registry::new();
        let response = answer(&registry, b"nope", &config(), 1_000);
        assert!(response.peers.is_empty());
        assert_eq!(response.world_player_count, 0);
        assert_eq!(response.health, WorldHealth::Degraded);
        assert_eq!(response.genesis_hash, b"nope".to_vec());
    }

    #[test]
    fn counts_health_and_reliability_come_from_live_peers() {
        let registry = registry_with(&[(1, true), (2, true), (3, false)], 1_000);
        let response = answer(&registry, b"world", &config(), 1_000);
        assert_eq!(response.world_player_count, 3);
        assert_eq!(response.reliability_bps, 9_000);
        assert_eq!(
            response.health,
            WorldHealth::Healthy,
            "2 seeders meets the floor"
        );
        assert_eq!(response.stored_chunks, 4, "union of the seeders' bitmaps");
        assert_eq!(response.seeders.len(), 1);
        assert_eq!(response.seeders[0].seeders.len(), 2);
    }

    #[test]
    fn expired_peers_drop_out_of_the_answer() {
        let registry = registry_with(&[(1, true), (2, true)], 1_000);
        let response = answer(&registry, b"world", &config(), 1_000 + 300_001);
        assert_eq!(response.world_player_count, 0);
        assert!(response.seeders.is_empty());
    }

    #[test]
    fn the_sample_is_bounded_and_seeders_get_their_floor() {
        // Six peers, only the last two seed: the floor must pull them into a 3-slot page.
        let registry = registry_with(
            &[
                (1, false),
                (2, false),
                (3, false),
                (4, false),
                (5, true),
                (6, true),
            ],
            1_000,
        );
        let response = answer(&registry, b"world", &config(), 1_000);
        assert_eq!(response.peers.len(), 3, "sample_size is a hard bound");
        let seeder_ids: Vec<u64> = response
            .peers
            .iter()
            .filter(|p| p.capabilities.roles.iter().any(|r| r.is_seeder()))
            .map(|p| p.node_id.lsb)
            .collect();
        assert_eq!(seeder_ids, vec![5, 6], "both seeders are in the page");
        assert_eq!(
            response.world_player_count, 6,
            "the count reports the swarm, not the page"
        );
    }

    #[test]
    fn a_holder_of_zero_pieces_is_not_advertised_as_a_seeder_of_that_manifest() {
        let mut registry = Registry::new();
        let mut a = announce(1, b"world", AnnounceEvent::Started, seeder_caps());
        a.holdings = vec![holding(0x22, &[0x00])];
        registry.apply_announce(&a, None, 1_000, 100, 100, 300_000);
        let response = answer(&registry, b"world", &config(), 1_000);
        assert!(response.seeders.is_empty());
        assert_eq!(response.stored_chunks, 0);
    }

    #[test]
    fn overlapping_holdings_are_counted_once() {
        let mut registry = Registry::new();
        for n in 1..=2u64 {
            let mut a = announce(n, b"world", AnnounceEvent::Started, seeder_caps());
            a.holdings = vec![holding(0x22, &[0b1100_0000])];
            registry.apply_announce(&a, None, 1_000, 100, 100, 300_000);
        }
        let response = answer(&registry, b"world", &config(), 1_000);
        assert_eq!(response.stored_chunks, 2, "union, not sum");
    }

    #[test]
    fn the_countdown_is_surfaced_and_turns_dead_only_after_it_expires() {
        let mut registry = registry_with(&[(1, true)], 1_000);
        registry.set_world_metadata(b"world", "w".to_owned(), 50_000, 1_000);

        let degraded = answer(&registry, b"world", &config(), 1_000);
        assert_eq!(
            degraded.health,
            WorldHealth::Degraded,
            "1 seeder < floor of 2"
        );
        assert_eq!(degraded.retention_deadline_epoch_millis, 50_000);

        // Every seeder gone and the deadline passed.
        let dead = answer(&registry, b"world", &config(), 400_000);
        assert_eq!(dead.health, WorldHealth::Dead);
        assert_eq!(dead.world_name, "w", "metadata survives an empty swarm");
    }

    #[test]
    fn the_catalog_lists_every_world_sorted_and_bounded() {
        let mut registry = Registry::new();
        for (n, world, name) in [(1u64, b"beta " as &[u8], "Beta"), (2, b"alpha", "Alpha")] {
            let a = announce(n, world, AnnounceEvent::Started, seeder_caps());
            assert_eq!(
                registry.apply_announce(&a, None, 1_000, 100, 100, 300_000),
                AnnounceOutcome::Registered
            );
            registry.set_world_metadata(world, name.to_owned(), 0, 1_000);
        }

        let listing = catalog(&registry, 0, &config(), 1_000);
        assert_eq!(listing.worlds.len(), 2);
        assert_eq!(listing.worlds[0].world_name, "Alpha", "sorted by name");
        assert_eq!(listing.worlds[1].world_name, "Beta");
        assert_eq!(listing.worlds[0].world_player_count, 1);

        let one = catalog(&registry, 1, &config(), 1_000);
        assert_eq!(one.worlds.len(), 1, "limit bounds the page");

        // A world whose peers all aged out still lists — that row is the catalog's raison d'être.
        let stale = catalog(&registry, 0, &config(), 400_000);
        assert_eq!(stale.worlds.len(), 2);
        assert_eq!(stale.worlds[0].world_player_count, 0);
        assert_eq!(
            stale.worlds[0].health,
            WorldHealth::Degraded,
            "no countdown running yet — degraded, not dead"
        );
    }

    #[test]
    fn the_routes_query_returns_full_claimed_lists_while_the_peer_entry_skips_mc_routes() {
        let mut registry = Registry::new();
        let mut a = announce(7, b"world", AnnounceEvent::Started, seeder_caps());
        a.routes = vec![
            "198.51.100.7:25599".to_owned(),
            "mc/198.51.100.7:25565".to_owned(),
        ];
        assert_eq!(
            registry.apply_announce(&a, None, 1_000, 100, 100, 300_000),
            AnnounceOutcome::Registered
        );

        let full = routes(&registry, b"world", &config(), 1_000);
        assert_eq!(full.peers.len(), 1);
        assert_eq!(
            full.peers[0].routes,
            vec![
                "198.51.100.7:25599".to_owned(),
                "mc/198.51.100.7:25565".to_owned()
            ],
            "the routes query relays the full claimed list verbatim"
        );

        let sampled = answer(&registry, b"world", &config(), 1_000);
        assert_eq!(
            sampled.peers[0].route, "198.51.100.7:25599",
            "the single-route PeerEntry never surfaces the mc/ form"
        );

        let unknown = routes(&registry, b"nope", &config(), 1_000);
        assert!(unknown.peers.is_empty(), "unknown world answers empty");
    }
}
