# 13 — Data Flow & Cross-Region Operations

The previous files described the pieces in isolation. This one walks through
how they fit together in real scenarios: what happens when a player logs
in, moves around, teleports, places a block, fires a piston across a region
boundary, and so on. It also covers how regions split and merge in practice.

---

## Boot sequence

```
1. JVM starts, main class is net.minecraft.server.Main.
2. Paper/Folia bootstrap:
   - Parse server.properties, paper-global.yml, paper-world.yml.
   - Initialize registries, load world(s).
3. MinecraftServer is constructed; old "main thread" is created.
4. MinecraftServer#runServer():
   - After world load, calls RegionizedServer.getInstance().init().
     - Fires RegionizedServerInitEvent on the global region's tick thread.
     - Schedules the global region's tick handle on TickRegionScheduler.
     - Calls TickRegions.start():
       - Reads threaded-regions.threads.
       - Builds the TickRegionScheduler thread pool with N threads
         named "Folia Region Scheduler Thread #1..N".
5. The old main thread does Thread.sleep(Long.MAX_VALUE) and is never
   heard from again.
6. The global region's first tick fires:
   - FoliaGlobalRegionScheduler#tick() runs any scheduled tasks.
   - Per-world globalTick: world border, weather, time, etc.
   - PlayerList.tick() begins accepting connections.
7. Netty IO threads start accepting client connections (configured
   separately from the tick thread pool).
```

From here on, the global region ticks at 20 TPS, and other regions come and
go as players load chunks.

---

## Player login

```
1. Netty accepts the TCP connection.
2. Login handshake proceeds on a Netty IO thread.
3. When the player needs to be added to the player list:
   - The PlayerList work is scheduled onto the global region
     (because no region owns the player yet).
   - globalTick → playerList.tick() processes the connection.
4. The player is placed in the world. The chunk at their spawn position
   loads (via the chunk system).
5. regioniser.addChunk(spawnChunkX, spawnChunkZ) fires.
   - Either a new region is created around the spawn, or the spawn joins
     an existing region that already covers that area.
6. The connection's ownership transfers from the global region to the
   region that owns the spawn chunk (RegionizedServer.tickConnections
     → isNotOwnedByGlobalRegion check).
7. From this point on, the player's tick loop runs on that region's
   tick thread.
```

During step 6, the global region keeps the connection alive to bridge the
ownership transfer.

---

## Player movement (within a region)

```
1. Client sends a ServerboundMovePlayerPacket.
2. Netty IO thread receives it; packet is queued for the owning region.
3. The owning region's tick thread drains packet queue:
   - Player unsets current chunk (if changed).
   - Player's position is updated in the RegionizedWorldData.
4. The new position may cross into a different chunk within the same
   region. No structural change.
5. Movement is broadcast to nearby players' connections (same region).
```

No regioniser activity; no merge/split. This is the cheap path.

---

## Player movement (across a region boundary)

```
1. The player walks towards another region.
2. Their position approaches a chunk that's not currently loaded.
3. The chunk system schedules loading of the next chunk
   (ticket = player tracking).
4. regioniser.addChunk(newChunkX, newChunkZ) fires.
   - If the new chunk is within regionSectionMergeRadius of an existing
     region's section, it joins that region.
   - If it's within mergeRadius of multiple regions, the regions merge.
5. The merged region's sections now form one big region; the player's
   new and old chunks tick on the same thread.
6. RegionizedWorldData.merge redistributes entities, players, ticks.
7. The player continues walking.
```

The merge is invisible to gameplay — entities move, ticks fire, but no
visible state changes.

---

## Player movement (away from another region)

```
1. Two players have been near each other; they share a region.
2. One player walks away.
3. Their loaded-chunk set diverges. Chunks at the boundary unload as the
   players' simulation distances no longer cover them.
4. ChunkHolderManager fires regioniser.removeChunk for each unloaded chunk.
5. After enough chunks unload, the region's dead-section percentage crosses
   maxDeadRegionPercent.
6. On the next onRegionRelease, the split BFS runs:
   - Two connected components are found.
   - Two new ThreadedRegions are created.
   - RegionizedWorldData.split redistributes entities/players/ticks
     by chunk coordinate.
7. From this point, the two players tick on separate threads, in parallel.
```

The split is also invisible to gameplay. The `fromTickOffset` and
`fromRedstoneTimeOffset` adjustments ensure scheduled ticks fire at the
right wall-clock instant regardless of which new region they end up in.

---

## Placing a block

```
1. Client sends ServerboundUseItemOnPacket for a position (x, y, z).
2. Packet arrives on the owning region's tick thread.
3. The region thread:
   - Fires BlockPlaceEvent (async modifier deprecated; runs synchronously
     on the region thread).
   - Calls level.setBlock(x, y, z, newState).
4. setBlock updates the chunk (which is owned by this region).
5. The CollectingNeighborUpdater (in RegionizedWorldData) queues
   neighbour updates:
   - For each neighbouring block within the same chunk: apply immediately.
   - For neighbouring blocks in chunks owned by this region: apply
     immediately (within the same tick).
   - For neighbouring blocks in chunks owned by another region: guarded
     by the 0004 "Prevent block updates in non-loaded or non-owned chunks"
     patch. The update is either dropped or routed via a task.
6. Block changes are tracked for save.
```

If the block update would cross into another region (e.g. a piston), see
the next scenario.

---

## Piston across a region boundary

This is one of the trickiest cases. A piston in region A pushes a block
into region B.

```
1. Region A ticks the piston. It wants to extend.
2. The extension would create a block update at position (x', y', z')
   in region B's territory.
3. Patch 0004 ("Prevent block updates in non-loaded or non-owned chunks")
   catches this: the update is guarded by TickThread.isTickThreadFor(world,
   x', z', x'', z'', 25).
4. Since region A doesn't own region B's chunks, the update cannot apply
   synchronously.
5. Two possible resolutions:
   a) Region A schedules the update onto region B via RegionizedTaskQueue.
      Region B applies it on its next tick.
      → This may produce a 1-tick delay before the piston's effect appears
        in region B. Usually fine.
   b) For pistons specifically (and a few other cases), Folia may merge the
      regions if the players causing the contraption are close enough.
      This is the natural outcome of regionisation — if the contraption
      is hot, its chunks are loaded by the same player, so they end up in
      the same region naturally.
```

In practice, regionisation usually prevents the cross-region case: a piston
contraption that's actually being used tends to be in the same player's
simulation distance, so all its chunks are in one region.

If two players' contraptions are competing across a region boundary, they
will experience brief delays — that's an inherent property of partitioned
simulation.

---

## Teleport (`teleportAsync`)

Synchronous teleport (`Entity#teleport`) is gone. Use `teleportAsync`:

```
1. player.teleportAsync(targetLocation) is called (from any thread).
2. Internally:
   - If the source and target are in the same region: schedule to that
     region's next tick; move the entity; complete the future.
   - If they're in different regions:
     a) The source region schedules a "prepare teleport" task.
     b) The source region's tick thread removes the player from its
        RegionizedWorldData.
     c) The player's connection ownership transfers to the global region
        (briefly).
     d) The target region's tick thread adds the player to its
        RegionizedWorldData.
     e) Connection ownership transfers to the target region.
     f) The future completes.
3. During step (c-e), the player sees a brief pause; the connection is
   being moved.
```

The player might cross a region, a world, or even a dimension.
`teleportAsync` returns `CompletableFuture<Boolean>` so plugins can chain
post-teleport work.

---

## Console command

```
1. Console input arrives on stdin.
2. Parsed on a console-reader thread.
3. Scheduled to the global region:
   - Bukkit.getGlobalRegionScheduler().execute(...) is called implicitly.
4. The global region's tick thread runs the command:
   - For player/entity-targeting commands, the global region may need to
     dispatch the actual work to the region owning the entity (using
     EntityScheduler or RegionScheduler).
   - For server-wide commands (whitelist, op, stop), they execute directly
     on the global region.
```

So console commands always start on the global region, regardless of who or
what they target.

---

## Player command

```
1. Player types /mycommand.
2. Packet arrives on the player's region tick thread.
3. Command parsing + dispatch happens on that region thread.
4. If the command touches entities near the player: fine, the player's
   region probably owns them.
5. If the command touches something far away (e.g. /give items to a
   player on the other side of the world), the work needs to be
   re-scheduled:
   - Look up the target player's region.
   - Bukkit.getRegionScheduler().execute(targetWorld, targetChunkX,
     targetChunkZ, ...) or targetPlayer.getScheduler().execute(...).
```

Commands that don't pay attention to this will throw `ensureTickThread`.

---

## Scheduled task (`runAtFixedRate`)

```
1. Plugin calls:
   Bukkit.getRegionScheduler().runAtFixedRate(
     world, chunkX, chunkZ, plugin, task -> { ... }, 5L, 20L);

2. FoliaRegionScheduler:
   - Locates the section containing (chunkX, chunkZ).
   - Adds a TicketType.REGION_SCHEDULER_API_HOLD ticket (so the chunk
     stays loaded).
   - Adds a LocationScheduledTask to the section's task map with deadline
     = currentRegionTick + 5L.

3. On each tick, FoliaRegionScheduler.tick() runs:
   - For each region:
     - Walk tasksByDeadlineBySection.
     - Run every task whose deadline ≤ currentTick.
     - For repeating tasks, set next deadline = currentTick + 20L.

4. If the region merges with another: tasks are moved, deadlines offset
   by fromTickOffset.

5. If the region splits: tasks are redistributed by section to the new
   regions; deadlines offset per new region.

6. When the task is cancelled or the plugin disables: the ticket is
   released and the section can unload if no one else needs it.
```

The task runs on whichever region currently owns (chunkX, chunkZ), even if
that changes over time.

---

## Async work

```
1. Plugin calls:
   Bukkit.getAsyncScheduler().runNow(plugin, task -> {
       String result = database.query("SELECT ...");
       // do not touch world state here
       Bukkit.getRegionScheduler().execute(world, x >> 4, z >> 4, plugin,
           () -> world.getBlockAt(x, y, z).setType(...));
   });

2. AsyncScheduler spawns the task on its dedicated thread pool.
3. The task runs on an async thread, not a tick thread.
4. Any attempt to touch world state directly throws ensureTickThread.
5. To mutate world state, the task hops back to a region via
   RegionScheduler / EntityScheduler / GlobalRegionScheduler.
```

The `AsyncScheduler` is the right place for blocking I/O (database, HTTP,
file system). The hop back to a region is the standard pattern for
"compute then apply".

---

## Region split: detailed walkthrough

Imagine region R owns sections S1 (chunks around Alice) and S2 (chunks
around Bob). Alice walks west, Bob walks east. The chunks between them
unload.

```
Tick T-k:   R = {S1 (Alice's chunks), S_mid (now empty), S2 (Bob's chunks)}
            regioniser.removeChunk fires for each chunk in S_mid.
            S_mid becomes empty but the section object lingers.

Tick T:     onRegionRelease(R):
              hasNoAliveSections() → false (S1, S2 still alive)
              getDeadSectionPercent() > maxDeadRegionPercent → true
              (because S_mid is dead)
              removeDeadSections = true
              BFS over remaining sections with mergeRadius:
                Start from S1: reachable = {S1}.
                Start from S2: reachable = {S2}.
                Two connected components.
              preSplit(R, [R_A, R_B])
              for each new region:
                onRegionActive → schedule tick handle
              R is killed.

Tick T+1:   R_A ticks Alice's chunks on thread #3.
            R_B ticks Bob's chunks on thread #7.
            They run in parallel.
```

From Alice's and Bob's perspective, nothing changed — their chunks tick at
20 TPS, their entities are still there, their scheduled ticks still fire.

---

## Region merge: detailed walkthrough

Alice walks east, towards Bob. The chunks between them load.

```
Tick T-k:   R_A = {S1 + new sections east of S1}.
            Bob's region R_B = {S2}.

Tick T:     Alice loads a chunk whose section is within mergeRadius of
            a section in R_B.
            regioniser.addChunk(newChunkX, newChunkZ):
              nearbyRegions = {R_A, R_B}.
              pick R_A (or whichever isn't ticking).
              add the new section to R_A.
              R_B.killAndMergeInto(R_A):
                if R_B is ticking: defer until R_B's onRegionRelease.
                else: merge now.
                  RegionizedWorldData.merge(R_B, R_A):
                    Move all of R_B's entities, chunks, ticks into R_A.
                    Apply fromTickOffset so deadlines stay consistent.
                  R_B is killed.

Tick T+1:   R_A ticks both Alice's and Bob's chunks on one thread.
            They no longer parallelise.
```

Again, invisible to gameplay.

---

## Operational summary

- **Region transitions are invisible.** Players never see merge/split; they
  just see consistent world state.
- **Concentrated workloads don't parallelise.** If everyone piles into one
  area, they end up in one region, on one thread.
- **Cross-region interactions are eventually consistent.** A piston across
  a boundary may take a tick to fire on the other side. For most mechanics
  this is fine; for tight redstone contraptions it can cause hiccups.
- **The global region is the bottleneck for global work.** Console,
  weather, scoreboard, login — all serialise through it.
- **Async + hop back** is the standard pattern for any non-trivial plugin
  work.

---

This concludes the Folia documentation. Start over at the
[README](./README.md) or jump to a specific topic.
