# Engine Task 8 — Entity & Mob Lane

<!-- AI-AGENT-INSTRUCTION: The cross-region transfer path in this task is the project's only
     no-dupe/no-loss guarantee for entities. Never add a transfer path that does not go through the
     joint source/target certificate; a "fast path" here silently duplicates items. Keep this
     header's status accurate and keep L-50 in LIMITATIONS.md in agreement with it. -->

**Status:** 🚧 IN PROGRESS
**Category:** engine · **Owns:** L-50 (live evidence) · **Last audit:** 2026-07-28
**Depends on:** [engine 7](Task.7.md), [network 3](../network/Task.3.md), [minecraft 2](../minecraft/Task.2.md)
**Consumed by:** [engine 10](Task.10.md), [engine 11](Task.11.md), [engine 12](Task.12.md)

---

## Goal

Make entity-bearing regions delegable. Three sub-lanes, in order:

1. **Validated item entities** — deterministic item physics inside the engine; the entity table
   becomes part of the region state root.
2. **Ghost mobs (`mobCapture` mode)** — mobs stay server-authoritative but are mirrored into the
   root as ghosts, so their presence no longer forces a region non-delegable.
3. **Cross-region transfer** — an entity crossing a border moves between committees exactly once:
   no dupes, no loss.

## Status detail

**Headless and durable exits are green.** Entity roots and items, jqwik 3-replica fall/merge/despawn
determinism, exactly-once inventory credits with a retained outbox, coalesced ghost external deltas
with throttling, delegability and playerless-ticket isolation, joint source/target transfer
certificates over **disjoint** committees, RocksDB-backed stages with atomic paired histories,
forced-process recovery with `@Invariant(11)` no-dupe/no-loss replay, a named pearl routing policy,
and a mixed one-minute soak at **23,040 B/mob/min with zero resyncs**.

**Live activation self-bootstraps and has been proven on a real server.** `EntityLaneBootstrap`
derives the genesis manifest, byte-stable all-AIR initial region snapshots, and epoch-1 leases from
the decentralized FOV ownership plan; the mod wires it behind `entity.laneAutoActivate`. A dirty
shutdown's dangling RESERVED actions are compensated at reopen. Live runs recorded: *entity lane live
on 12 region(s)* with the P2P mesh formed and zero errors; an RCON-driven drive showed **239
validated/ghost entities across 12 delegated regions** with versions advancing every flush; session
reopen resumes from the store head; a clean-slate validated pickup delivers **exactly once**.

**Remaining:** the certified genesis-from-existing-world replaces the interim manifest end to end,
remote joiners get their own node identities in the plan (landed; being exercised), and the scripted
pickup/zombie/**pearl** gameplay acceptance runs in CI. Tracked as **L-50**.

## Dependencies

- [engine 7](Task.7.md) — delegability and the guard, since entities are a delegability input.
- [network 3](../network/Task.3.md) — durable paired logs and forced-kill recovery.
- [minecraft 2](../minecraft/Task.2.md) — capture/projection adapters and the live evidence.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `FixedVec3` (Q32.32), `NetworkEntityId`, `PersistedEntityState` | ✅ |
| 2 | Deterministic item physics: fall, merge, despawn | ✅ |
| 3 | Entity table in the region root (`@Invariant(10)` extension) | ✅ |
| 4 | `DropItemAction` / `PickupItemAction` with exactly-once credit keys | ✅ |
| 5 | Ghost lane: `mobCapture` mirroring, coalesced external deltas, throttling | ✅ |
| 6 | Cross-region transfer: joint source/target certificates over disjoint committees | ✅ |
| 7 | Durable stages + paired logs + forced-process recovery (`@Invariant(11)`) | ✅ |
| 8 | Ender-pearl routing policy | ✅ |
| 9 | `EntityLaneBootstrap` — genesis, snapshots, FOV-plan leases | ✅ |
| 10 | Dirty-shutdown compensation (`DurableActionJournal.abortPending`) | ✅ |
| 11 | Scripted live pickup/zombie/pearl acceptance in CI | 🚧 (L-50) |

## Design

**Fixed point, not floats.** Entity positions are continuous quantities in hashed state, so they use
Q32.32 `FixedVec3`. JVM float math is not reproducible across hardware; that is an envelope
constraint, and this is the mechanism that hides it.

**Entity ids are allocated, never random.** `NetworkEntityId.allocate` is a pure function of
(sequence, region, domain). A `UUID.randomUUID` would be a determinism break disguised as an
identifier.

**Ghosts before validated mobs, deliberately.** Full mob AI in the engine is
[task 11](Task.11.md)'s problem. Ghost mirroring solves a *different* problem now: it removes
`ENTITY_PRESENT` as a blanket delegability veto, so regions with mobs become delegable long before
mobs become deterministic. Ghost positions stay server-authoritative — and because they are not
validated input, a ghost may never *drive* validated state (it cannot press a pressure plate, for
instance; see [task 9](Task.9.md)).

**Transfer is a two-committee certificate, not a message.** The source committee certifies removal
and the target committee certifies materialisation, and the pair is what makes the move atomic across
two independent quorums. Idempotent replay means a redelivered certificate is a no-op rather than a
duplicate. This is why the forced-process recovery test asserts both **no dupe** and **no loss**:
either failure alone would pass a weaker test.

**Credits are keyed and retained.** A pickup credits an inventory exactly once because the credit
carries a key and the outbox is retained across restarts. The one-way credit that predates the
player-root inventory survives only as a migration stopgap.

## Files

- `java/engine/src/main/java/dev/nodera/simulation/entity/` + `EntityRuleSet`
- `java/core/src/main/java/dev/nodera/core/entity/`
- `java/peer/src/main/java/dev/nodera/peer/entity/EntityLaneBootstrap.java`
- Mod adapters: `java/neoforge-mod/src/main/java/dev/nodera/mod/` (capture bridge, projection,
  `LiveEntityLaneSession`) — [minecraft 2](../minecraft/Task.2.md)

## Testing

- jqwik 3-replica determinism for fall, merge, and despawn.
- Exactly-once credit tests including replay and restart.
- Ghost interference throttling; delegability narrowing (ITEM-only delegable initially).
- Disjoint six-worker handoff; atomic paired histories; forced-process `@Invariant(11)` recovery.
- Mixed item/ghost soak: 23,040 B/mob/min at 0 resync bps.
- Live scripted drives: `scripts/e2e-pickup.sh`, `scripts/e2e-ownership.sh` (see
  [`../minecraft/TESTING.md`](../minecraft/TESTING.md)).

## Acceptance criteria

1. ✅ 3-replica item-physics determinism fixtures.
2. ✅ Pickup and drop credited exactly once, including after a forced process kill.
3. ✅ Delegability narrowing: item-bearing regions delegable, ghost stream root-consistent.
4. ✅ Idempotent cross-region transfer with no dupe and no loss (`@Invariant(11)`).
5. 🚧 Live scripted pickup, zombie, and pearl drives green in CI (**L-50**).

## Limitations

- **L-50** — live evidence and the last activation clauses. See [`LIMITATIONS.md`](LIMITATIONS.md).
- **L-24** — `mobCapture` default-off per species: **RETIRED 2026-07-26** (see
  [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)); the species-default capture landed and `e2e-mobs.sh`
  G2a/G2b pass on a live run. Tracked to closure by [task 11](Task.11.md).
