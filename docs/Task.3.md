# Task 3 — P2P Network Tracker (module: `rust/nodera-tracker` + Java `TrackerClient`)

> **Module-unification note (issue #30, 2026-07-21):** the fine-grained Gradle modules this file
> mentions were merged into the seven unified modules — `core` · `engine` · `transport` ·
> `storage` · `peer` · `testing` · `neoforge-mod` — with **packages unchanged**. Read old module
> names as packages inside the new modules (mapping: [`Task.0.md`](Task.0.md) §5).

**Module:** the standalone Rust tracker service + its Java client side ·
**Depends on:** Task 2 (2a wire contract via `nodera-codec`, 2e discovery seams) ·
**Consumed by:** Task 5 (5d multiplayer feed), Task 6 (worker announce loop), Task 7 (dashboard)

**Reference spec:** [`docs/torrent/trackers.md`](torrent/trackers.md) — announce lifecycle,
swarm model, expiry, sampling, abuse defenses. This file binds it to Nodera.

## Implementation status

Audited **2026-07-21**. ✅ completed · 🚧 pending · ⏳ waiting.

| Phase | Deliverable | Status | Waiting on |
|---|---|---|---|
| 3a | `nodera-tracker` service binary: signed announce lifecycle, per-world registry, TTL expiry, sampling + seeder floor, health/countdown, quotas | ✅ (54 Rust tests; `TrackerServiceIT` drives the real binary) | — |
| 3b | Java side: announce family (tags 33–34), `TrackerClient` announce loop + query API, embedded `TrackerService` deleted (L-44 RETIRED) | ✅ (periodic announce **scheduling** rides the live client pass) | ⏳ 5d (timer lands with the live GUI pass); 6c moves the loop into the worker |
| 3c | Ops hardening: `STATS` wire message (operator counters currently ship as a structured log line), persistence polish, deployment docs | 🚧 | — |

## Goal

Always-on world/peer discovery that does not die with any player: any operator runs the
`nodera-tracker` binary on a public host; peers **announce** signed records (identity, roles,
world genesis hash, routes, held manifests, reliability) and **query** per-world swarms; the
multiplayer GUI lists worlds — with player/chunk counts, reliability, red/gray `WorldHealth`
and the 24 h retention countdown — even when every seeder of a world is offline. The tracker is
discovery infrastructure, never authority: peers verify state by hash/signature and treat every
answer as a hint (Task 0 §3 rule 7).

## Context (last audit: 2026-07-21)

- Landed 2026-07-19 (legacy Task 28). The swarm identifier is the world's genesis hash (the
  `info_hash` analog, trackers.md §1); the service answers the **frozen** discovery family
  (tags 27–29) plus the appended announce family (33–34) over u32-length-framed TCP decoded by
  `nodera-codec` — no HTTP, one wire contract, byte-exact cross-language conformance.
- `TrackerServiceIT` spawns the real release binary from Java: two peers announce two worlds
  with per-world isolation; a JDK-signed announce verifies inside the service via
  `ed25519-dalek`; a tampered record is refused (`bad-signature`) and never reaches the
  registry; `STOPPED` removes immediately; and — the L-44 exit — a world whose every Java
  seeder has gone silent past the TTL is **still listed by name with its countdown and a DEAD
  verdict**, which the embedded tracker could not do.
- The embedded Java `TrackerService` (+ its tests) was deleted; `PeerDirectory`/
  `ArchiveInventory` remain as peer-local caches — ledgered in [`LEGACY.md`](LEGACY.md).
- Identity, not IP, keys a record; routes come from the signed record with the observed source
  appended only as a low-priority hint (trackers.md §5 poisoning note). The ack paces announce
  traffic (`nextAnnounceAfterSeconds`, §6). Abuse controls per §26: per-IP/per-identity quotas,
  record-size caps, bounded world/peer counts, no full-scrape endpoint.
- `WorldHealth`/retention: the tracker *surfaces* the 24 h countdown (2g rule); the peers'
  `RetentionPolicy` owns the actual drop — authority stays in the network.

## Folder structure (monorepo default)

```
rust/nodera-tracker/src/
├── main.rs        CLI: --config nodera-tracker.toml (bind, TTLs, limits, log)
├── config.rs      bind_addr, announce_interval, peer_ttl, max_worlds,
│                  max_peers_per_world, per_ip_quota, sample_size
├── registry.rs    swarms: Map<GenesisHash, Swarm>; PeerRecord{routes, roles, manifests,
│                  reliabilityBps, lastSeen}
├── announce.rs    started/heartbeat/stopped + Ed25519 signature check
├── query.rs       TrackerQuery → TrackerResponse (counts, health, sampling + seeder floor)
├── health.rs      WorldHealth derivation + retention-countdown surface
├── expiry.rs      lastSeen sweep (TTL ≈ 2× announce interval)
├── limits.rs      per-IP/per-identity quotas, record-size caps, world bounds
└── wire.rs        u32-length framing, 16 MiB cap (mirrors SocketPeerTransport)

java/transport/.../discovery/{TrackerAnnounce,TrackerAnnounceAck}.java   (appended tags 33–34)
java/peer/.../discovery/TrackerClient.java                      (announce loop + query)
java/neoforge-mod: config tracker.endpoints = [] (client + server toml)
```

## Related files

- Service: `rust/nodera-tracker/src/*.rs` (54 unit tests)
- Conformance: `rust/nodera-codec/tests/{fixtures,tag_mirror}.rs` + `fixtures/wire/*.bin`
- Java client: `java/peer/src/main/java/dev/nodera/peer/discovery/TrackerClient.java`
- IT: `java/peer/src/test/.../TrackerServiceIT.java` (drives the real binary)
- Consumers: `java/neoforge-mod/.../client/multiplayer/TrackerDataSource.java` (5d),
  `java/peer/.../view/TorrentWorldListView.java` (2k)
- Run/ops: `scripts/dev.sh` (builds + runs + health-checks the binary), CI release job
- Legacy specs: [`old/Task.28.md`](old/Task.28.md) (this service),
  [`old/Task.20.md`](old/Task.20.md) (the superseded embedded stage)

## Implementation details (phases)

- **3a — The service binary.** ✅ Full spec: [`old/Task.28.md`](old/Task.28.md). `tokio` TCP,
  one task per connection; announce lifecycle (`started` registers, heartbeats refresh
  lastSeen/holdings, `stopped` removes, sweep expires); reservoir-sampled query responses with
  a seeder floor (`FULL_ARCHIVE`/`WORLD_SEEDER` always included up to the floor); aggregate
  `worldPlayerCount`/`storedChunks`/`reliabilityBps`/`WorldHealth`/countdown; zero persistence
  by default (peers re-announce within one interval after a restart; optional `--persist-dir`
  for display-name metadata); graceful SIGTERM drain. Deps: 2a (codec/framing).
- **3b — The Java client side.** ✅ Full spec: [`old/Task.28.md`](old/Task.28.md) §Java.
  `TrackerAnnounce`/`TrackerAnnounceAck` appended; `TrackerClient` announces to every
  configured endpoint (flat multi-tracker list, merged query results, backoff on unreachable)
  and exposes the query API returning the existing `TrackerResponse` — `TrackerDataSource` and
  `TorrentWorldListView` (2k) are unchanged consumers. A tracker endpoint is also bootstrap
  mechanism #4 (2e). Remaining: the announce loop is constructed but **not yet scheduled on a
  timer** — it lands with the Task 5 (5d) live client pass, and Task 6 (6c) moves the loop into
  the always-on worker so a host's world stays listed with Minecraft closed. Deps: 2a, 2e.
- **3c — Ops hardening.** 🚧 The deferred `STATS` wire message (operator counters currently a
  structured log line), `--healthcheck`/`--version` polish, public-listing policy config, and
  deployment notes for community operators. Deps: none.

## Testing strategy

- Rust unit tests: announce lifecycle incl. re-announce replacement, TTL expiry,
  stopped-removes, per-world isolation, sampling bounds + seeder floor, quota rejection,
  invalid-signature rejection, health/countdown transitions.
- **Cross-language conformance:** every announce/query message round-trips byte-exactly against
  Java-emitted `fixtures/` files; tag-registry mirror assertion green (a tag appended on one
  side only fails CI).
- `TrackerServiceIT`: the real release binary driven from Java peers (the L-44 exit scenario).
- Live feed acceptance (the periodic announce + GUI rows from a real binary) rides Task 5 (5d).

## Limitations

- **L-44 RETIRED** ([`LIMITATIONS.md`](LIMITATIONS.md)) — the always-on binary serves the world
  list with every seeder offline; the embedded serving path is deleted.
- Remaining gap tracked with 5d, not here: the mod's announce loop is wired but unscheduled.
- Sybil resistance of announces remains L-18 (1l BFT admission) — signed records stop
  impersonation, not identity farming.
- The tracker can hide peers or invent unreachable ones; it cannot forge state or identities —
  by design, peers never trust it (Task 0 §3 rule 7).
- Reference: [`torrent/trackers.md`](torrent/trackers.md) §23 (persistence), §26 (abuse).

## Acceptance criteria

1. 3a/3b: legacy Task 28 acceptance holds — `cargo test` green (54), conformance green,
   `TrackerServiceIT` green against the real binary, embedded tracker deleted with
   `./gradlew check` green.
2. 3b remainder (with 5d): the announce loop runs on its ack-paced timer from a live client;
   `TorrentWorldListView` renders rows from a real binary's `TrackerResponse`.
3. 3c: `STATS` answered over the wire; operator docs committed; quotas/bounds configurable.
4. Both toolchains green; README/Tested + this status table updated in the same commit.

## Notes for the implementing model

- The peers' native canonical encoding **is** the protocol — no HTTP, no second serialization.
  Any new message = Java record + tag + golden fixture + Rust mirror in one commit.
- The service holds no signing keys (verify-only, `ed25519-dalek`); self-registration only.
- Never let the tracker become authority: no endpoint may be load-bearing for correctness —
  test degradation paths (tracker down ⇒ discovery degrades, mesh + state unaffected).
- World display names are directory metadata keyed by genesis hash — `GenesisManifest` stays
  name-free and frozen.
