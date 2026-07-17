# 08 — Redstone Safety: Atomic Ownership Takeover

Redstone is the hardest part of clustering a Minecraft server. Vanilla
redstone, pistons, and flow regularly cross chunk borders — a single contraption
can easily span 2×2, 3×3, or more chunks. If two halves of a contraption are
owned by two different MultiPaper servers, the simulation will desync within
ticks.

MultiPaper solves this with an **atomic chunk-group ownership takeover**
mechanism, implemented in `patches/server/0032-...redstone.patch`. The
central class is `MultiPaperExternalBlocksHandler` (`0032:111-265`).

---

## The problem

Consider a piston in chunk A pushing a block into chunk B. Chunk A is owned
by server X, chunk B is owned by server Y.

1. Server X ticks the piston and computes "extend".
2. Server X must update the piston block entity in chunk A **and** the moved
   block in chunk B.
3. But chunk B is external from X's perspective. X can only send a *packet*
   to Y, asking Y to apply the change.
4. Y might be in the middle of ticking something else in chunk B. The
   resulting state on Y could differ from what X computed.
5. Even worse: if the piston fires every other tick, X and Y can drift
   indefinitely.

A naive approach would be to make redstone cross-server "best-effort" and
accept that some contraptions break. MultiPaper instead detects when a
cross-owner tick is about to fire and **migrates the whole contraption to a
single owner before letting it tick**.

---

## The detection

`MultiPaperExternalBlocksHandler` runs every server tick (`tick()`,
`0032:153-204`). It maintains a queue of "scheduled block ticks that targeted
an external chunk" (`onBlockScheduled`, `:145-151`).

For each chunk that has pending external ticks, it calls
`fillTickingNeighbours(chunkX, chunkZ)` (`:218-232`), which flood-fills a
**3×3 group** of *ticking* neighbours around the chunk. The choice of 3×3
matches the simulation-distance ticking radius — every chunk that the
contraption could possibly interact with in one tick is included.

---

## The takeover

If the resulting group contains **at least one local chunk** (a chunk this
server already owns), the handler sends a `RequestChunkOwnershipMessage` to
the master for **the whole group** (`:189-203`). The master's
`RequestChunkOwnershipHandler` (`handlers/RequestChunkOwnershipHandler.java:12-35`)
grants the request **only if** the requester already owns at least one chunk
in the group. This prevents two servers from racing to grab the same group.

```
Ticking chunks before:
                  ┌────────────────┐
   server X owns: │ A │ B │        │
                  ├───┼───┼────────┤
   server Y owns: │   │ C │  D  E  │   ← piston in C wants to fire,
                  └───┴───┴────────┘     but C is on server Y

Server X detects the pending tick in C and asks the master
to atomically hand X the {A,B,C,D,E} ticking group.

Master: X already owns A,B → granted.
        Sends SetChunkOwnerMessage for each chunk.

After:
                  ┌────────────────┐
   server X owns: │ A │ B │ C │ D │E│   ← whole contraption now ticks
                  └───┴───┴───┴───┴─┘     on a single server. Safe to run.
```

The 3×3 group is per-chunk: if a contraption spans a larger area, multiple
takeovers chain together over a few ticks.

---

## Blocking the tick thread

The critical detail: while the master is processing the takeover, the local
server must **not** continue ticking and produce more cross-owner side
effects. So the handler uses Minecraft's `managedBlock(Runnable)` API
(`:189-203`) — the same mechanism vanilla uses for chunk-loading barriers —
to **block the server's tick thread** until the takeover completes.

```java
// Simplified
level.managedBlock(() -> {
    // Returns true when the master has granted the group.
    return ownershipRequestCompleted;
});
```

`managedBlock` parks the tick loop but keeps the server responsive to packet
I/O and async work. Once the master replies (with `SetChunkOwnerMessage` for
each chunk in the group), the barrier releases and the contraption ticks
normally under single ownership.

---

## The fallback path

If the master **denies** the takeover (because another server already owns
most of the group), the queued scheduled ticks are **shipped back to the
current owner** via `SendTickListPacket`. The current owner then ticks them
locally on the next tick. This is less efficient (we don't get to run the
contraption ourselves) but it remains correct.

In practice, takeovers usually succeed: the redstone contraption tends to be
near the player who triggered it, and that player's server is the natural
candidate to own the surrounding chunks.

---

## When the takeover system can be disabled

`optimizations.disable-safety-redstone-chunk-lock` in `multipaper.yml`
(see [11 — Configuration](./11-configuration.md)) turns this entire mechanism
off. Doing so:

- Reduces the tick-thread blocking that can cause lag spikes.
- **Breaks cross-server redstone.** Contraptions that span chunk boundaries
  owned by different servers will desync.

Use it only if you are confident your players' redstone is local to single
chunks, or if you accept the desync.

---

## Other cross-chunk systems

The same atomic-ownership pattern (or a simplified version) is used for:

- **Pistons** (`patches/server/0130`) — explicit `PistonMoveBlockStartPacket` /
  `PistonMoveBlockEndPacket` so the receiver knows the move is in flight.
- **Hoppers** (`patches/server/0033`, `MultiPaperContainerHandler`) —
  cross-server item transfers via `AddItemToContainerPacket` /
  `PullItemFromContainerPacket`.
- **Explosions** — block updates from explosions are broadcast to all
  subscribers; chunk-group ownership is taken when explosion propagation
  needs to fire scheduled ticks across owners.
- **Fluid flow** — same pattern: broadcast deltas to subscribers, takeover
  when a tick needs to fire across owners.

---

## Why this is the right design

MultiPaper does **not** attempt a distributed simulation. There is no voting,
no consensus, no deterministic multi-execution. The whole system is built on
the principle that **every chunk has exactly one owner**, and the simplest way
to keep redstone correct is to make sure a contraption fits inside one owner.

The atomic group takeover does exactly that: when redstone would cross owners,
move the ownership instead of trying to coordinate the cross. This keeps the
simulation model single-threaded per chunk, which is what Minecraft is
designed for.

---

## Operational implications

- **Concentrated redstone near a chunk boundary** will trigger frequent
  takeovers, which involve the master and briefly block the tick thread. This
  is a real source of TPS drops on busy servers. Pre-generating and
  understanding where your players' contraptions live helps.
- **Players who build redstone contraptions across "natural" server
  boundaries** (e.g. across two spawn regions) will see occasional hiccups
  until the takeover completes.
- **Disabling the safety lock** is a performance tweak, not a correctness
  fix — contraptions will break in subtle ways.

---

## Next

Continue to [09 — File & Data Synchronization](./09-file-sync.md).
