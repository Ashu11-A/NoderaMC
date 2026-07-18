# Task 22 — Multi-Factor Reliability, Client Storage Quotas, 24-Hour Retention-Before-Drop (Phase 6)

**Phase:** 6 · **Depends on:** Task 19 (pieces), Task 21 (placement) ·
**Modules:** `coordinator/ReliabilityLedger` (widen); new `storage-client` module.

## Goal

Three coupled capabilities:
1. **Multi-factor reliability** (rule 2): widen the single proposal-outcome EMA into a weighted
   blend of connectivity, uptime, availability, and worlds-seeded — the score that drives
   placement, gateway election, and low-TPS handoff (Task 25).
2. **Client storage quotas** (Plan §3.13): the bounded/quota'd client store (`storage-client`)
   with an eviction policy that never drops an assigned region's current state.
3. **24-hour retention-before-drop** (rule end): a coordinated, network-visible countdown before a
   world with zero seeders is dropped from the network entirely.

## Context

- `ReliabilityLedger` (Task 6) is one `double` per node fed only by proposal MATCH/MISMATCH; the
  spec wants connectivity+uptime+availability+worlds-seeded weighted (LIMITATIONS L-36).
  `HeartbeatMonitor`, `PeerLink.lastSeenAgoMillis`, `WorkerLoad`, and the Task 21 seed-share are the
  inputs that exist but aren't folded in.
- `storage-client` is declared but unbuilt (LIMITATIONS L-37); without it a client's partial archive
  grows unbounded — violates Plan §3.13 ("no unbounded maps keyed by remote input").
- No TTL/retention exists anywhere (LIMITATIONS L-38); the spec's 24h-coordinated-drop is entirely
  new and must be **network-coordinated** (visible countdown) so users aren't surprised.

## Folder structure (additions)

```
coordinator/src/main/java/dev/nodera/coordinator/
├── ReliabilityFactors.java        # per-node signals: connectivity, uptime, availability, worldsSeeded
├── ReliabilityLedger.java         # widened: weighted blend → scalar; persisted (Encodable, version bump)
└── ReliabilityConfig.java         # weights + decay (config; defaults from Plan §10)

storage-client/src/main/java/dev/nodera/storage/client/   # new module
├── package-info.java
├── BoundedClientWorldStore.java   # implements WorldStore (Task 9) over a byte-budget ContentStore
├── StorageQuotaManager.java       # byte budget (config, default 2 GiB); tracks usage per world
└── ArchiveEvictionPolicy.java     # evict: oldest cold shard first; NEVER assigned-region current state

peer-runtime/src/main/java/dev/nodera/peer/archival/
└── RetentionPolicy.java           # 24h countdown state per world; coordinates drop across the net
```

## Implementation details — reliability (rule 2)

- `ReliabilityFactors` collects, per node: **connectivity** (reachability + frame-loss from
  `PeerLink`/`SessionKeepAlive` seq gaps), **uptime** (EMA of online fraction over a window),
  **availability** (heartbeat regularity from `HeartbeatMonitor`), **worldsSeeded** (distinct
  manifests held, from Task 21 `ArchiveManager`), plus the existing proposal-outcome EMA (kept as
  the "correctness" factor). `ReliabilityLedger.score(node)` becomes a weighted blend; weights are
  config (`ReliabilityConfig`), defaults from Plan §10 (`0.98·score + 0.02·outcome` stays the
  correctness factor's contribution). Slash-to-0 on equivocation preserved.
- The blend is **quantised before mixing** (same discipline as `RendezvousPlacementPolicy.tier` in
  Task 6) so no floating-point nondeterminism leaks into placement/gateway decisions.
- Offline decay toward `RELIABILITY_OFFLINE_DECAY_TARGET = 0.5` (Plan §10) is actually implemented
  here (the constant exists but is unused today).

## Implementation details — quotas (`storage-client`)

- `BoundedClientWorldStore` wraps an in-memory (later filesystem) `ContentStore` with a
  `StorageQuotaManager` byte budget. When the budget is exceeded, `ArchiveEvictionPolicy` evicts:
  oldest **cold** shard (not held by any assigned region, oldest last-access) first; **never** an
  assigned region's current snapshot/recent-log (the peer would lose its committee duties). Eviction
  signals Task 21's repair so replication factors are re-met elsewhere.
- This module is the client-side peer of `storage-eventsourced`; both implement the Task 9 seam.

## Implementation details — 24h retention (rule end)

- `RetentionPolicy` per world: when the **seeders for a world drop to zero** (no peer holds any of
  its current manifests), a 24h countdown starts, gossipped network-wide and surfaced by the tracker
  (Task 20) so the multiplayer UI shows it (Task 26). If a seeder reappears, the countdown cancels.
  At countdown expiry the world is dropped from the directory/tracker (gray in the UI) — its
  certified history can still be re-imported via an invitation (`InvitationCodec`, Task 20) if a
  holder returns out-of-band.
- The countdown is **coordinated** (every online peer agrees on the deadline, derived from
  `SnapshotVersion`/tick, not wall-clock where possible) so the network decides unanimously.

## Potential limitations (staged in `LIMITATIONS.md` §B)

- **L-36** — reliability single-scalar today. Exit: weighted multi-factor score drives placement +
  gateway + handoff; determinism property test green; offline-decay implemented.
- **L-37** — no client quota/eviction. Exit: `BoundedClientWorldStore` honours byte budget;
  `ArchiveEvictionPolicy` never evicts assigned-region current state; unit tests.
- **L-38** — no retention-before-drop. Exit: 24h coordinated countdown on zero-seeder worlds;
  cancel-on-seeder-return; drop-at-expiry; headless `RetentionIT`.

## Acceptance criteria

1. `ReliabilityPropertyTest`: same factor inputs ⇒ same score on any JVM; slash-to-0; floor gate.
2. A node with high proposal-match but poor connectivity/uptime scores lower than a node with the
   inverse — the blend is not dominated by one factor (unit test across weight configs).
3. `QuotaIT`: client store over budget evicts cold shards, never assigned-region current state;
   eviction triggers repair (Task 21) elsewhere.
4. `RetentionIT`: zero-seeder world → 24h countdown visible to all peers → drop at expiry; a seeder
   returning mid-countdown cancels it.
5. `./gradlew check` green; `storage-client` ✅; L-36/L-37/L-38 → RETIRING; README/Tested updated.
