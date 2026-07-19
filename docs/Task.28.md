# Task 28 — Standalone Rust Tracker Service (`nodera-tracker`) (Phase 6)

**Phase:** 6 · **Depends on:** Task 27 (monorepo + `nodera-codec`), Task 20 (Java discovery seams +
`protocol/discovery` wire family), Task 19 (manifests) · **Modules:** `rust/nodera-tracker` (new),
`rust/nodera-codec` (extend), `java/peer-runtime` (tracker **client**), `java/protocol` (announce
family, appended), `java/neoforge-mod` (config + wiring), `fixtures/`

**Reference spec:** [`docs/torrent/trackers.md`](torrent/trackers.md) — announce lifecycle, swarm
model, expiry, sampling, abuse defenses. This file binds it to Nodera.

## Goal

Move the tracker out of the Java peer process into a **standalone Rust service binary**. Today the
tracker is a *role* embedded in a `FULL_ARCHIVE`/`BOOTSTRAP` Java peer
(`peer-runtime/discovery/TrackerService`, Task 20): the world list lives and dies with that peer.
After this task the tracker is always-on infrastructure — any operator can run `nodera-tracker`
on a public host; peers **announce** to it and **query** it; the multiplayer GUI (Task 26) lists
worlds from it even when every seeder of a world is offline. Logic is decoupled at the wire:
peers never trust the tracker (state verifies by hash/signature, exactly as Task 20 established),
and the tracker holds no game, consensus, or storage logic.

## Context

- The **swarm identifier is the world's genesis hash** (the `info_hash` analog — trackers.md §1);
  `TrackerQuery(genesisHash) → TrackerResponse{peers, seeders, counts, reliabilityBps,
  WorldHealth, retentionDeadline}` (tags 27–28) is a frozen wire family that already has a
  headless Java client/view path (`TrackerDataSource`, `TorrentWorldListView`, Task 26). The Rust
  service **answers the same frozen messages** — the read side of the Java network needs no
  changes.
- What Java's embedded tracker got for free — piggybacking on membership gossip and
  `InventoryAdvertisement` — an external service must receive explicitly: this task appends a
  **tracker announce family** to `protocol` (BitTorrent-style `started`/heartbeat/`stopped`
  lifecycle, trackers.md §3) carrying the peer's signed record: identity, roles, world
  (genesis hash), advertised routes, held manifest roots + piece counts, reliability basis
  points.
- The embedded Java `TrackerService` becomes **legacy** and is deleted at the end of this task
  (ledger: [`LEGACY.md`](./LEGACY.md)). `PeerDirectory`/`ArchiveInventory` remain as peer-local
  caches (repair + rarest-first still read them); only the *serving* role moves out.
- The tracker is discovery infrastructure, not authority: a lying tracker can hide peers or
  invent unreachable ones, but cannot forge state (hash-verified) or identities (Ed25519-signed
  records, verified in `nodera-codec`). Same trust model as Task 20, now with a process boundary.

## Folder structure (additions)

```
rust/nodera-tracker/src/
├── main.rs                  # CLI: --config nodera-tracker.toml (bind, TTLs, limits, log)
├── config.rs                # serde config: bind_addr, announce_interval, peer_ttl,
│                            #   max_worlds, max_peers_per_world, per_ip_quota, sample_size
├── registry.rs              # swarms: Map<GenesisHash, Swarm>; Swarm = Map<NodeId, PeerRecord>
│                            #   PeerRecord{routes, roles, manifests, reliabilityBps, lastSeen}
├── announce.rs              # announce lifecycle: started/heartbeat/stopped + signature check
├── query.rs                 # TrackerQuery → TrackerResponse assembly (counts, health, sampling)
├── health.rs                # WorldHealth derivation + retention-countdown surface (Task 22 rule)
├── expiry.rs                # lastSeen sweep (TTL ≈ 2× announce interval; trackers.md §11)
├── limits.rs                # per-IP/per-identity quotas, record-size caps, world-count bounds
└── wire.rs                  # framing (u32-length, 16 MiB cap — mirrors SocketPeerTransport)

rust/nodera-codec/           # extend: announce-family messages + fixtures round-trips

java/protocol/src/main/java/dev/nodera/protocol/discovery/
├── TrackerAnnounce.java     # NEW (appended tag): signed peer record + world + holdings + event
└── TrackerAnnounceAck.java  # NEW (appended tag): accepted, nextAnnounceAfterSeconds (interval)

java/peer-runtime/src/main/java/dev/nodera/peer/discovery/
└── TrackerClient.java       # NEW: announce loop (interval from ack, jittered), query API;
                             #   replaces the serving TrackerService (deleted this task)

java/neoforge-mod: config `tracker.endpoints = []` (client + server toml); NoderaPeerService
wires TrackerClient instead of constructing TrackerService.
```

## Implementation details — service (Rust)

- **Runtime:** `tokio` TCP listener; one lightweight task per connection; length-prefixed frames
  decoded by `nodera-codec`. No HTTP — the peers' native canonical encoding is the protocol
  (decision recorded in Task 27; keeps one frozen contract, enables byte-exact conformance
  tests, and reuses tags 27–29 unchanged).
- **Announce lifecycle** (trackers.md §3): `started` registers, heartbeats refresh `lastSeen` and
  update holdings/reliability, `stopped` removes immediately; the sweep expires silent peers
  after `peer_ttl`. The ack carries `nextAnnounceAfterSeconds` — the tracker, not the peer,
  paces announce traffic (interval discipline, trackers.md §6).
- **Identity, not IP, keys a record:** a record replaces the previous record for the same
  `NodeId` + world; routes are taken from the *signed record* (peers advertise their reachable
  routes exactly as `SocketPeerTransport`'s hello does today), with the observed source address
  appended as a low-priority hint, never as proof (trackers.md §5 poisoning note).
- **Signature check:** every announce verifies Ed25519 over the canonical bytes
  (`nodera-codec`); unsigned/invalid → rejected + quota-counted. Self-registration only
  (rendezvous.md §8.3 mitigations apply to the tracker too).
- **Query path:** `TrackerQuery(genesisHash)` → sample up to `sample_size` peers (reservoir
  sampling; seeders — `FULL_ARCHIVE`/`WORLD_SEEDER` roles — always included up to a floor),
  aggregate `worldPlayerCount`, `storedChunks` (distinct manifest roots with ≥1 holder),
  `reliabilityBps` (mean of announced basis points), `WorldHealth` + retention countdown.
- **WorldHealth / retention (Task 22 rule):** HEALTHY while seeders ≥ 1 and replication reports
  clean; a world with zero seeders starts the 24 h countdown (`retentionDeadlineEpochMillis`
  surfaced in the response — the GUI's red/gray + countdown, Task 26); seeder return cancels.
  The tracker *surfaces* the countdown; the peers' `RetentionPolicy` still owns the actual drop
  (authority stays in the network, not the service).
- **Abuse controls** (trackers.md §26): per-IP and per-identity announce quotas, record-size
  caps, bounded world count and per-world peer count (LRU shed + loud log), no full-scrape
  endpoint (world enumeration requires knowing the genesis hash; the *listing* feed for the GUI
  returns only worlds the operator marked listable or that the querying identity has announced
  to — public-listing policy is operator config).
- **Zero persistence by default** (trackers.md §23): peer state is ephemeral announce state; an
  optional `--persist-dir` snapshots world display-name metadata only. Restart = peers re-announce
  within one interval.
- **Ops:** single static binary; `--version`/`--healthcheck`; structured logs; graceful SIGTERM
  (drains, answers queries during drain); Prometheus-style `/metrics` **deferred** — a plain
  `STATS` wire message ships instead (no HTTP dependency).

## Implementation details — Java side

- `TrackerClient` (peer-runtime): announce loop on a virtual thread per configured endpoint
  (multiple trackers = announce to all, merge query results — trackers.md §16 tiers simplified
  to a flat list), backoff on unreachable endpoints, query API returning the existing
  `TrackerResponse`. Existing consumers (`TrackerDataSource` → Task 26 GUI) are unchanged.
- **Delete** `peer-runtime/discovery/TrackerService.java` + its `NoderaPeerService` construction
  path (`tracker.enabled` server flag becomes an endpoint list). `PeerDirectory`,
  `ArchiveInventory`, `BootstrapClient`, `CachedPeerStore`, `InvitationCodec`,
  `PersistentIdentityStore` **stay** (peer-local concerns). Bootstrap gains mechanism #4: a
  tracker endpoint is also a peer-address source (query, then dial peers).
- `LEGACY.md` §1 rows for the deleted files move to *removed* in the same commit; `./gradlew
  check` green with the class gone proves nothing else depended on the serving path.

## Potential limitations (staged in `LIMITATIONS.md` §B)

- **L-44** (new, this task retires it): tracker is embedded in a Java peer — the world list dies
  with its host peer; no always-on listing infrastructure. Exit: `TrackerServiceIT` green —
  world list served by the Rust binary with every Java seeder of a world offline.
- L-34 note gains: "embedded tracker is interim; the standalone service is the exit" (row itself
  stays owned by Task 20's mod-side wiring).
- Sybil resistance of announces remains L-18 (Task 16 BFT admission) — signed records stop
  impersonation, not identity farming.

## Acceptance criteria

1. `cargo test` (unit): announce lifecycle incl. re-announce replacing records, TTL expiry,
   stopped-removes, per-world isolation, sampling bounds + seeder floor, quota rejection,
   invalid-signature rejection, health/countdown transitions.
2. **Cross-language conformance:** every announce/query/response message round-trips byte-exactly
   against Java-emitted `fixtures/` files (extends the Task 27 harness); tag-registry mirror
   assertion still green.
3. `TrackerServiceIT` (Java, headless): spawns the built `nodera-tracker` binary
   (`cargo build --release` artifact); two Java peers announce two worlds; queries return correct
   peers/seeders/counts; killing a peer expires it after TTL; killing **all** seeders starts the
   surfaced countdown; tracker restart → repopulated within one announce interval.
4. Task 26 feed: `TorrentWorldListView` renders rows from a real binary's `TrackerResponse`
   (headless, no GUI env needed).
5. Legacy removal: embedded `TrackerService` deleted; `LEGACY.md` updated; `./gradlew check` +
   `cargo clippy -D warnings` + `cargo test` green; README/Tested/Roadmap + L-44 updated in the
   same commit.
