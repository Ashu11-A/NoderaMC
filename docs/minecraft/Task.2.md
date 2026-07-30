# Minecraft Task 2 — The Live Validation Lane

<!-- AI-AGENT-INSTRUCTION: This task is ADAPTERS, not implementations. Every capability it wires has a
     green headless twin; if you are writing validation, consensus, or storage logic here, stop and
     put it in the owning category. `LevelChunkMixin` is the ONLY write choke point — do not add a
     second. Every mixin needs a "why an event was not enough" header and a COMPATIBILITY.md note.
     Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS (capture/mixin lane landed; apply half + repeatable live evidence remain)
**Category:** minecraft · **Owns:** L-50 (live half), L-80 · **Last audit:** 2026-07-28
**Depends on:** [task 1](Task.1.md); consumes [engine 3](../engine/Task.3.md)–[engine 7](../engine/Task.7.md) and [network 1](../network/Task.1.md)–[network 10](../network/Task.10.md)
**Consumed by:** the live acceptance of the engine and network categories

---

## Goal

The single biggest remaining lane: wire the proven headless stacks to a real `ServerLevel`. Capture
player actions, apply committed deltas through the one applier, suppress or convert every foreign
write at one choke point, run committees against real regions, and let the renderer and applier
consult the piece lock map.

## Status detail

Substantial parts already run live. The entity lane self-bootstraps and has been observed on a real
server (regions bound, the P2P mesh formed, entities validated, versions advancing); per-player FOV
region ownership works with actions forwarded to the owning node and quorum commits; a clean-slate
validated pickup delivers exactly once; session reopen resumes from the store head; the world
continuity flow recovers and re-hosts a world in seconds after its host is killed.

The block half of the capture lane now exists: `BlockCaptureBridge` turns a real place or break into
a signed action on the same submit path the entity lane uses, and the palette binding decides what is
consensus state at all. It is gated on the region being held by this node's lane, which is right for
a player-hosted world and inert on a dedicated server — that gap is **L-80**, and it is L-60's fault
line read across to blocks.

What is still **not** wired is the apply half and everything around it: the three interference
mixins, chunk tickets, the real `WorldMutationApplier` on the main thread, the coordinator live
adapter, the renderer's lock-map consumption, and the live commit/content/lifecycle adapters.

The lane was previously **blocked** on [task 1](Task.1.md)'s CI harness; that harness is now green
(`e2e-live` under Xvfb — **L-45** retired), so the lane is **in progress**: its acceptance is a set of
live scenarios that are now repeatable rather than hand-run. The apply half and everything around it
is what remains: the three interference mixins, chunk tickets, the real `WorldMutationApplier` on the
main thread, the coordinator live adapter, the renderer's lock-map consumption, and the live
commit/content/lifecycle adapters.

## Dependencies

- [task 1](Task.1.md) — the environment and the harness.
- The seams: `MutableWorldView`, `CommitListener`, capture sinks, and telemetry providers, all owned
  and headless-tested by the engine and network categories.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Capture: block events at documented priorities, capture-and-cancel contract | 🚧 (place/break capture landed; owning-node only, L-80) |
| 2 | `SnapshotExtractor` / `PaletteMapper` — real chunks to region snapshots | ✅ `PaletteMapper` both ways + `LiveSnapshotExtractor` (exact dense sections, excluded/missing counts) |
| 3 | The real `WorldMutationApplier` adapter on the server main thread | 🚧 (committed block mutations project into the live world; chunk tickets and the write choke point remain) |
| 4 | `LevelChunkMixin` — the single write choke point | ✅ live and inert until a lane installs; retires engine L-25 |
| 5 | Random-tick and scheduled-tick suppression mixins | ✅ both landed (`LevelTicksMixin`, `ServerLevelRandomTickMixin`), each with its own counter |
| 6 | `ChunkTicketService` + `FakePlayerDetector` | 🚧 (session-scoped region tickets, ref-counted; fake players refused at capture. **A ticket is mutated only on the server thread** — see the Design note; releasing from the re-plan thread crashed a live host) |
| 7 | Live committee runs: the 3-client quorum scenario and the soak with lane metrics | ⏳ |
| 8 | Renderer and applier consulting the piece lock map | 🚧 (edit half landed) |
| 9 | Live commit, content, and lifecycle adapters | ⏳ |
| 10 | The in-game relay transport (the permanent fallback lane) | ⏳ |
| 11 | Entity-lane live activation and gameplay drives | 🚧 (proven live; CI drives remain) |

## Design

### A chunk ticket may only be added or removed on the server thread

`DistanceManager` and the `TickingTracker` behind it are thread-confined by contract and
unsynchronised in fact. Mutating a ticket from any other thread corrupts vanilla's lighting/ticking
priority queue — and **the corruption is not observed where it is caused**. A live `ownership` run
on 2026-07-26 crashed the host's integrated server with

```
ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 33
  at fastutil LongLinkedOpenHashSet.removeFirstLong
  at lighting.LeveledPriorityQueue.removeFirstLong
  at TickingTracker.runAllUpdates → DistanceManager.runAllUpdates
```

— every frame vanilla's, on the server thread, with nothing of ours in the stack, because
`closeEntityLane()` runs on `nodera-entity-lane-replan` and released this lane's tickets from
there. The re-plan's *activation* had always hopped through `server.execute`; its *close* never did.

`ChunkTicketService` now enforces this itself rather than trusting callers: the ref-counting stays
synchronous and synchronised, and only the vanilla call hops to the server thread when it is not
already on it. The executor is FIFO, so a hold and a release scheduled in that order still arrive in
that order.

**The general rule this is an instance of:** a crash whose stack is entirely vanilla is not evidence
that the cause is vanilla's. Ask which of our threads touched a structure that frame owns.



**Events first, mixins second — and only three mixins.** Minecraft version churn breaks mixins, so the
load-bearing set is kept minimal and each one justifies itself in its own header. `LevelChunkMixin` is
the single write choke point: a guard with two entry points is not a guard.

**Capture-and-cancel is a contract, not a convenience.** Cancelling vanilla behaviour is only correct
where the Nodera commit is **synchronous and local** — otherwise the player sees the action vanish
while the network is still deciding. That rule was learned from a real defect (an item drop cancelled
against an unconfirmed commit) and is now pinned Minecraft-free by a dedicated gate class, so the rule
is testable without a running game.

**Everything crossing into the game thread must be crash-contained.** NeoForge's event bus does **not**
isolate listener exceptions: an uncaught exception in a handler reachable from a server event kills
the integrated server. Code on those paths self-catches and degrades. Two live crashes came from this
exact gap — a P2P bind failure and a per-ghost lane failure — and both are now contained.

**Suppression must be at the source.** Scheduled ticks are cancelled where they are scheduled, for
every chunk of a delegated region, rather than filtered later. The suppression registry is
Minecraft-type-free, so its semantics are pinned headlessly.

**Activation is asynchronous and idempotent.** The entity lane boots on a dedicated thread, so
activation never stalls a tick, and a dirty shutdown's dangling reserved actions are compensated at
reopen rather than replayed.

## Files

- `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/server/` (shadow, coordinator, commit, fallback,
  interference adapters)
- `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/mixin/`
- `endpoints/neoforge-mod/src/main/java/dev/nodera/mod/common/` (networking, attachments, adapters)

## Testing

- Headless first: the Minecraft-free halves of every adapter rule (the vanilla-cancel gate, the
  suppression registry, bind-failure classification) are unit-tested on the ordinary gate.
- Live: the scripted suites in [`TESTING.md`](TESTING.md) — ownership, churn, pickup, crash, and the
  commands drive.
- The engine and network categories' live acceptance criteria are satisfied **here**; see their task
  files for the exact scenarios.

## Acceptance criteria

1. ⏳ A zero-unexplained-divergence soak on a real server.
2. ⏳ The three-client MVP quorum scenario live.
3. ⏳ A soak clearing > 90% committee-commit with guard counters stable.
4. ⏳ Foreign writes converted into certified external deltas live, with the interference probe near
   zero on a normal world.
5. ⏳ Chunk tickets and fake-player detection behaving under real load.
6. 🚧 Entity-lane pickup, mob, and pearl drives green in CI.

## Limitations

- **L-50** (live entity-lane evidence) — see [`LIMITATIONS.md`](LIMITATIONS.md); its headless half is
  owned by [`engine/Task.8.md`](../engine/Task.8.md).
- Gated by **L-45** ([task 1](Task.1.md)) for repeatable evidence.
