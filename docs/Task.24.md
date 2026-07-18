# Task 24 — Crash Safety + Active-Player Continuous Chunk Stream (Phase 6)

**Phase:** 6 · **Depends on:** Task 19 (data plane), Task 21 (replication), Task 22 (quotas) ·
**Modules:** `distribution` (stream path); `peer-runtime` shutdown hook; `neoforge-mod` lifecycle.

## Goal

Two coupled durability behaviours from the user spec:
- **Rule 6** — an active player continuously transmits their chunks/data across the network so the
  swarm's replicas are always near-current (prevents loss/corruption on any single failure).
- **Rule 5** — if Minecraft closes or crashes, an emergency path flushes the player's not-yet-shared
  pieces to other peers, prioritised by the network.

This task delivers rule 6 fully and rule 5's **in-process** flush (JVM shutdown hook + graceful
close), and stages the **separate-OS-sidecar** form of rule 5 as a documented limitation with an
exit path. The core safety argument is **replication redundancy** (Task 21): because every piece is
held by ≥R peers, a single peer's hard crash does not lose data even with zero emergency flush — the
flush is defence-in-depth, not the primary safety mechanism.

## Context

- Today `PeerRuntime.stop()` does a best-effort `PeerGoodbye` + 2 s executor drain; there is **no
  JVM shutdown hook** and no watchdog (LIMITATIONS L-40). NeoForge `ServerStoppingEvent` covers only
  graceful server stop.
- Active-player streaming does not exist: data moves on assignment/resync (Task 9), not
  continuously (LIMITATIONS L-40).
- Nodera's crash-recovery model is **event-sourced replay** (Task 9): on restart, missing deltas are
  re-applied from the certified log. Combined with Task 21 replication, a crash is recoverable
  without a sidecar — the sidecar is a stretch, not a requirement for data safety.

## Folder structure (additions)

```
distribution/src/main/java/dev/nodera/distribution/
├── ActivePlayerStream.java       # continuous: as a primary commits, push new pieces to the swarm
└── EmergencyFlush.java           # drain this peer's not-yet-replicated pieces to holders on shutdown

peer-runtime/src/main/java/dev/nodera/peer/
└── PeerShutdownHook.java         # Runtime.addShutdownHook → EmergencyFlush + PeerGoodbye (best-effort)

neoforge-mod/src/main/java/dev/nodera/mod/dedicated/
└── (ServerBootstrap): register the shutdown hook on server start; on ServerStoppingEvent, flush
```

## Implementation details — continuous stream (rule 6)

- `ActivePlayerStream`: when a region's committee commits a new version (Task 7 → new `StateRoot` +
  `PieceManifest`, Task 19), the primary (and holders) immediately offer the **delta pieces** to the
  swarm — push to the expected holders (Task 21) proactively rather than waiting for a pull. This
  keeps replicas within `commit → stream` latency of the live state, so the failure window is one
  batch, not an epoch.
- Stream is rate-bounded (`stream.bandwidthBudget`) and piggybacks on the `ContentAvailability` /
  `ContentChunk` messages (Task 19/20) — no new wire protocol, just a push trigger.
- Rule 7 (batched exchange): the stream carries committed deltas (already batched at ~2 ticks by
  Task 6), not per-tick state — the 20-tps flood is avoided by construction.

## Implementation details — emergency flush (rule 5)

- **In-process (ships now):** `PeerShutdownHook` registers a JVM shutdown hook that, on SIGTERM /
  clean exit, runs `EmergencyFlush`: for each piece this peer holds that is **under-replicated**
  (below factor per Task 21), push it to next-ranked holders before the process exits. NeoForge
  `ServerStoppingEvent` triggers the same path on graceful server stop. Bounded by a hard
  time budget (`flush.budgetMillis`, default 5 s) so a hanging peer cannot stall shutdown forever;
  unflushed pieces fall back to replication redundancy.
- **Hard-crash safety (the real guarantee):** with Task 21 replication (×5 snapshot / ×4 log), a
  `kill -9` or power loss loses no committed data because ≥R−1 other holders already have each
  piece; the audit/repair service restores the factor. `CrashRecoveryIT` proves this. The flush is
  belt-and-suspenders, not load-bearing.
- **Separate OS-sidecar (rule 5 full form, DEFERRED — L-41).** A secondary process that survives a
  Minecraft JVM crash and flushes a quarantine buffer is a large, separate build (its own process,
  IPC, peer stack). This task ships the in-process flush + redundancy argument and **stages** the
  sidecar in LIMITATIONS with an exit test that accepts either (a) the sidecar, or (b) a formal
  argument + IT proving replication makes it unnecessary for data safety. The sidecar, if built,
  would live in a sibling `transport-sidecar`-style module.

## Potential limitations (staged in `LIMITATIONS.md` §B)

- **L-40** — no continuous active-player stream / no shutdown-hook flush today. Exit:
  `ActivePlayerStream` keeps replicas within one batch of live state; `EmergencyFlush` +
  shutdown hook drain under-replicated pieces on clean exit; `CrashRecoveryIT` proves no committed
  data loss on `kill -9` via redundancy.
- **L-41** — no separate-OS-sidecar emergency process (rule 5 full form). Exit: sidecar ships, OR a
  formal argument + `CrashRecoveryIT` proves replication-redundancy makes the sidecar unnecessary
  for data safety (reclassify). OPEN, stretch owner.

## Acceptance criteria

1. `ActivePlayerStreamIT`: a primary commits 10 versions; after each, the holders' replicas reach
   the new manifest within the configured latency window; replica staleness never exceeds one batch.
2. `EmergencyFlushIT` (graceful): on shutdown, under-replicated pieces are pushed to next-ranked
   holders within `flush.budgetMillis`; the leaving peer's shard reaches factor R elsewhere.
3. `CrashRecoveryIT` (hard): `kill -9` a peer holding unique pieces — no committed data is lost
   (other holders + repair restore everything); certified log replays cleanly on restart.
4. Shutdown hook does not stall past its budget even if a holder is unreachable.
5. `./gradlew check` green; L-40 → RETIRING; L-41 stays OPEN with the documented exit path.
