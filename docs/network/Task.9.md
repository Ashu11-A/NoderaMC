# Network Task 9 — Crash Safety + Active-Player Continuous Stream

<!-- AI-AGENT-INSTRUCTION: Crash-safety tests here MUST use real forced process kills
     (destroyForcibly / SIGKILL) so no shutdown hook runs. A test that calls a graceful stop proves
     the wrong thing. Vote persistence writes BEFORE the ACCEPT is sent — never after. Keep this
     header's status accurate. -->

**Status:** ✅ COMPLETED (live commit/content/lifecycle adapters → [minecraft 2](../minecraft/Task.2.md))
**Category:** network · **Owns:** — · **Last audit:** 2026-07-28
**Depends on:** [network 4](Task.4.md), [network 6](Task.6.md), [network 7](Task.7.md)
**Consumed by:** [worker 3](../peer/Task.3.md), [minecraft 5](../minecraft/Task.5.md)

---

## Goal

Two coupled durability behaviours. **Rule 6:** an active player continuously transmits their chunks
across the network so the swarm's replicas are always near-current. **Rule 5:** if Minecraft closes or
crashes, an emergency path flushes the player's not-yet-shared pieces to other peers, prioritised by
the network.

## Status detail

Complete headlessly. `ActivePlayerStream` coalesces each region to its newest committed manifest,
reuses physically-held hashes, counts only **verified** store acknowledgements, and advances under
explicit byte windows. `EmergencyFlush` prioritises the lowest-replication pieces under one absolute
deadline and excludes the departing peer. `PeerShutdownHook` runs that flush exactly once before
goodbye. Committee `VotePersistence` durably prepares state **before** ACCEPT and binds certificates
before canonical apply.

`CrashRecoveryIT` forcibly destroys a JVM before any graceful hook runs, drops the primary, restores
snapshot ×5 into real destination stores, and replays the surviving certified log to the committed
root.

The full form of rule 5 — a separate OS process that survives a Minecraft crash — is the always-on
worker ([`worker/Task.1.md`](../peer/Task.1.md)), which is a different process by construction.

## Dependencies

- [network 4](Task.4.md) — the piece plane the stream moves.
- [network 6](Task.6.md) — replication factors the flush prioritises against.
- [network 7](Task.7.md) — quotas that bound what may be held.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `ActivePlayerStream` — bounded latest-per-region streaming | ✅ |
| 2 | `EmergencyFlush` — lowest-replication first, one absolute deadline | ✅ |
| 3 | `PeerShutdownHook` — runs the flush exactly once | ✅ |
| 4 | `VotePersistence` — durable prepare before ACCEPT | ✅ |
| 5 | Continuous archive streaming with a bounded final flush + freshness guard | ✅ |
| 6 | Live commit/content/lifecycle adapters | → [minecraft 2](../minecraft/Task.2.md) |

## Design

**Verified acknowledgements only.** A stream that counts *sent* bytes as replicated is a stream that
lies during exactly the failure it exists to survive. Only a verified destination-store acknowledgement
advances the stream's notion of replication.

**One absolute deadline, not a per-target timeout.** An unreachable flush target must not be able to
reset the clock; otherwise a single dead peer turns "flush for 5 seconds" into "hang on exit". The
deadline is absolute and the flush ends when it expires, having moved what it could, lowest
replication first.

**Persist the vote before you send it.** If a member sends ACCEPT and then crashes, the quorum may
commit a version the member has no record of voting for; on restart it could vote differently and
equivocate *by accident*. Writing the prepared state first makes an honest member's crash indistinct
from a pause.

**Crash safety is argued from redundancy, and then proven by killing a process.** The argument is
that a committed version exists on R holders, so losing one is not losing data. The proof must use a
real forced kill — `destroyForcibly`, no hooks — because a graceful stop exercises the path that
does *not* fail.

**Streaming had to become continuous.** A single flush at exit meant a host that quit produced a long
synchronous save; worse, a joiner could receive a stale network copy. Continuous streaming with a
bounded final flush and a seeded-version freshness marker fixed both.

## Files

- `peer/src/main/java/dev/nodera/distribution/{ActivePlayerStream,EmergencyFlush}.java`
- `peer/src/main/java/dev/nodera/peer/PeerShutdownHook.java`
- `library/java/engine/src/main/java/dev/nodera/committee/VotePersistence.java`

## Testing

- `ActivePlayerStreamIT` — ten committed versions stay within one batch across five physical holders.
- `EmergencyFlushIT` — under-replicated pieces drained on exit; an unreachable target cannot reset the
  deadline.
- `PeerShutdownHookTest` — the flush runs exactly once.
- `CrashRecoveryIT` — a forcibly destroyed JVM (proving no hook ran), primary dropped, snapshot ×5
  restored into real stores, certified replay to the committed root.
- `WorldArchiverStreamingTest` — streaming cadence, bounded final flush, freshness marker.

## Acceptance criteria

1. ✅ Replicas stay within one batch of live state under explicit byte windows.
2. ✅ Emergency flush is deadline-bounded, prioritised, and runs exactly once.
3. ✅ A vote is durable before it is sent.
4. ✅ A forced process kill loses no committed data.
5. ⏳ Live: NeoForge commit signals, content adapters, and lifecycle registration use these seams.

## Limitations

None open. **L-40** is RETIRED — see [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). **L-41** (the
separate OS sidecar) is owned by [`worker/LIMITATIONS.md`](../peer/LIMITATIONS.md).
