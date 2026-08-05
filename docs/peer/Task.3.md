# Worker Task 3 — Host/Join Delegation + World Seeding

<!-- AI-AGENT-INSTRUCTION: This task moves the announce loop and rendezvous registration OUT of the
     game process and into the worker. There must never be a window in which a world is ANNOUNCED but
     UNSERVED — announce after the content is seedable, not before. Keep the mod's in-JVM path behind
     a fallback flag for worker-less environments. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS
**Category:** worker · **Owns:** — (L-41 retired 2026-07-26) · **Last audit:** 2026-07-28
**Depends on:** [minecraft 5](../minecraft/Task.5.md), [network 4](../network/Task.4.md), [tracker 2](../tracker/Task.2.md), [rendezvous 2](../rendezvous/Task.2.md)
**Consumed by:** [minecraft 5](../minecraft/Task.5.md), [frontend 4](../frontend/Task.4.md)

---

## Goal

Make the worker the **primary client** of the tracker and rendezvous services: the announce loop and
rendezvous registration live here and persist across game sessions, so a host closing Minecraft is a
player-session leave rather than a node leave. On `HOST`, the world's content is seeded from the
worker, and permission grants gossip from here.

## Status detail

**World-archive seeding landed.** `WorldArchiveService` seeds the canonical save archive via `SEED`,
rides its manifest holdings on every tracker announce, answers manifest queries from other peers,
fetches any world's archive from the swarm via `ARCHIVE`, and reports real maintained pieces and bytes
in `STATE`. `WorldContinuityIT` proves host-death survival over the **real** tracker and rendezvous
binaries: share → listed → P2P fetch byte-exact → host game closed → **host worker killed** → a second
peer still reproduces the world.

Grant gossip landed alongside: `WorldGrantGossipService` relays every grant this node issues or
accepts, wired into the worker as a live consumer.

**Remaining:** validated-lane **region-piece** seeding (as distinct from whole-save archive seeding),
which waits on the mod's snapshot extraction; and moving the periodic announce loop fully into the
worker so it runs on its ack-paced timer independent of any game process.

## Dependencies

- [minecraft 5](../minecraft/Task.5.md) — certified genesis and region extraction production.
- [network 4](../network/Task.4.md) — the splitter and manifests being reused.
- [tracker 2](../tracker/Task.2.md) / [rendezvous 2](../rendezvous/Task.2.md) — the clients whose
  loops move here.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | `WorldArchiveService` — seed, serve manifests, fetch from the swarm | ✅ |
| 2 | Manifest holdings ride every tracker announce | ✅ |
| 3 | `SEED` / `ARCHIVE` verbs | ✅ |
| 4 | Real maintained pieces and bytes in `STATE` | ✅ |
| 5 | `WorldGrantGossipService` wired as a live consumer | ✅ |
| 6 | Superseded-version eviction on re-key | ✅ |
| 7 | Periodic announce loop owned by the worker's timer | ✅ |
| 8 | Validated-lane region-piece seeding | ✅ |
| 9 | Rendezvous registration persisting across game sessions | 🚧 |

## Design

**Announce after the content is seedable, never before.** A world listed on the tracker whose pieces
nobody can serve is worse than an unlisted world: a joiner finds it, tries, and fails. Holdings ride
the announce, so what is advertised is what is actually held.

**Whole-save archive first, region pieces second.** The archive is a coarse-grained but *complete*
unit: it makes "the world survives its host" true immediately, without waiting on the validated lane's
extraction. Region-piece seeding is the finer-grained lane that follows, and it needs the mod to
produce certified region digests first.

**A player leaving is not a node leaving.** Once the announce loop lives here, closing Minecraft stops
removing the world from discovery. This is the single behavioural change that makes player-hosted
worlds feel like hosted worlds.

**Keep the in-JVM path behind a flag.** Development and worker-less environments still need to work;
removing the fallback would make the mod untestable without a full companion install.

**Eviction is deliberately not called from the seeding path.** Continuous streaming appends a version
every interval, and evicting superseded versions under a joiner mid-fetch would trade a security fix
for a data-availability regression. Superseding happens on re-key, where it is the point.

## Files

- `peer/src/main/java/dev/nodera/headless/WorldArchiveService.java`
- `peer/src/main/java/dev/nodera/headless/WorldGrantGossipService.java`
- `peer/src/main/java/dev/nodera/headless/WorkerControlHandler.java`
- `peer/src/main/java/dev/nodera/headless/WorldHostingService.java` (the announce + rendezvous-register loop, on the worker's own timer; restored from the registry at construction)

## Testing

- `WorldContinuityIT` — the full chain over the real service binaries, including host-worker death.
- `CompanionCrashSurvivalIT` — the actual worker distribution runs as a separate daemon, a co-located
  stand-in game process is **SIGKILLed** (so no shutdown hook runs), and the daemon keeps answering
  control verbs with every seeded piece still maintained.
- `GrantGossipIT` (6) — grants converge across co-hosts; a non-author's signed grant is refused
  everywhere and is not even relayed by the attacker's own node.
- `SupersededManifestEvictionTest` (3) — a replica drops a superseded version and does not re-adopt a
  late-arriving older one.

## Acceptance criteria

1. ✅ A hosted world stays listed, seeded, and reproducible after the driving client disconnects.
2. ✅ The worker survives a game-process kill with its seeding intact.
3. ✅ Grants gossip and are re-verified by every receiver.
4. ✅ The announce and rendezvous-registration loops run on the worker's own timer, independent of any
   game process — and every heartbeat re-reads what this node holds, so content seeded after the
   world was hosted is advertised rather than described as it was at HOST time.
5. ✅ Validated-lane region pieces are seeded, not just whole-save archives: `NODERA-SEED-REGION`
   takes a committed `RegionSnapshot` from whichever process holds the seat, and both lanes ride one
   announce.

## Limitations

None open. **L-41** — the always-on out-of-game process — retired on 2026-07-26 with its evidence in
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md). The one thing that evidence deliberately does not
claim is a run with a real Minecraft client: the pushing side is proven by its control-channel
behaviour, and the in-game path rides the live suites.
