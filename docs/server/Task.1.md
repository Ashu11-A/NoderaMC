# Server Task 1 — Plugin Skeleton, Build Lane, Platform Abstraction

<!-- AI-AGENT-INSTRUCTION: ONE JAR runs on Paper AND Folia. Do not ship two artifacts and do not
     branch on the platform anywhere except inside the NoderaScheduler seam. `folia-supported: true`
     is unconditional. The ALIGN-1 preflight is not optional decoration: it is the invariant every
     later task stands on, and it must refuse to enable — loudly, with the fix in the message —
     rather than degrade silently. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS (jar builds; enables on real Paper 1.21.1; Folia staging remains)
**Category:** server · **Owns:** L-61, L-66 · **Last audit:** 2026-07-25
**Depends on:** —
**Consumed by:** every other server task

---

## Goal

`./gradlew :paper-plugin:build` produces **one** `nodera-endpoint.jar` that drops into `plugins/` on a
Paper server and on a Folia server and enables cleanly on both, logging which platform it detected,
how many scheduler threads it will use, and whether the **ALIGN-1** region-nesting invariant holds. It
does nothing to the world yet — this task is the build lane, the platform seam, and the preflight that
every later task depends on.

## Status detail

Not started. The module does not exist; the three live suites in
[`TESTING.md`](TESTING.md) report `SKIPPED` at their jar preflight, naming
[L-61](LIMITATIONS.md).

## Dependencies

None inside the project. Externally: a **Paper 1.21.1** and a **Folia 1.21.1** build, resolved through
the PaperMC v2 API. Confirming those exist is the first act of this task —
[L-66](LIMITATIONS.md) records that they are pinned in the plan and unverified in practice.

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Gradle module `java/paper-plugin` (`:paper-plugin`, artifact `nodera-endpoint`) + `settings.gradle.kts` entry | ⬜ |
| 2 | `nodera.paper-plugin.gradle.kts` convention: Paper API `provided`, Nodera modules shaded, `runPaper`/`runFolia` dev runs | ⬜ |
| 3 | `plugin.yml` — `api-version`, `folia-supported: true`, `/nodera` command, no hard dependencies | ⬜ |
| 4 | `NoderaScheduler` seam + `FoliaSchedulers` / `PaperSchedulers` implementations | ⬜ |
| 5 | `PlatformProbe` — one-time platform detection, logged at enable | ⬜ |
| 6 | `RegionGridAlignment` — the ALIGN-1 preflight, with a refusing enable path | ⬜ |
| 7 | Pinned Paper + Folia downloads in CI, cached | ⬜ |

## Design

**One jar, one branch point.** Platform differences live in exactly one place: the `NoderaScheduler`
seam. Everything above it is written once. Detection is a single `Class.forName` probe for
`io.papermc.paper.threadedregions.RegionizedServer`, resolved at enable and cached in a `final` field
— never re-probed on a hot path.

```java
public interface NoderaScheduler {
    /** Run on the thread that owns this Nodera region's chunks. */
    void onRegion(RegionId region, Runnable task);
    /** Run repeatedly on the thread that owns this Nodera region's chunks. */
    Cancellable onRegionRepeating(RegionId region, Consumer<Cancellable> task, long delay, long period);
    /** Run on the thread that owns this entity. */
    void onEntity(Entity entity, Runnable task, Runnable retired);
    /** Run on the global region (weather, player list, console, announce). */
    void global(Runnable task);
    /** Run off any tick thread (hashing, encoding, transport, disk). */
    void async(Runnable task);
    /** True iff the calling thread may touch this region's world state right now. */
    boolean ownsRegion(RegionId region);
}
```

On Folia these map to `RegionScheduler`, `EntityScheduler`, `GlobalRegionScheduler`,
`AsyncScheduler`, and `Bukkit.isOwnedByCurrentRegion`. On Paper they map to the main thread and
`Bukkit.isPrimaryThread()`. **`BukkitScheduler` appears nowhere**, on either platform — using it on
Paper would make the Paper path structurally different from the Folia path, which is how the two
drift.

**ALIGN-1 is preflighted, not assumed.** A Nodera region is 8×8 chunks anchored at chunk 0; a Folia
section is `2^gridExponent` chunks anchored at chunk 0. For `gridExponent ≥ 3` every Nodera region
nests wholly inside one section, so all of a region's work lands on one Folia region thread with no
locking — that free per-region single-threading is what makes tasks 3–5 tractable.

At enable the plugin reads `threaded-regions.grid-exponent` and, if it is below 3:

```
[NoderaEndpoint] REFUSING TO ENABLE: threaded-regions.grid-exponent is 2, which splits an
8×8-chunk Nodera region across two Folia regions. Set grid-exponent to 4 (the Folia default)
in paper-global.yml and restart. Nothing has been changed in your world.
```

Refusing beats degrading. A silently split region means two threads writing one authority unit, which
is corruption with a delay fuse.

**Shading, not nesting.** The Minecraft-free modules (`core`, `transport`, `storage`, `engine`,
`peer`) are shaded into the jar exactly as the mod fat-jars them, for the same reason: Bukkit's plugin
classloader will not find them otherwise. Third-party runtime dependencies (RocksDB, zstd, Caffeine,
BouncyCastle, RoaringBitmap) are relocated so an endpoint cannot collide with a plugin that bundles a
different version of the same library — a real and common failure on a server with 30 plugins
installed.

**No hard dependencies in `plugin.yml`.** An endpoint must load on a server whose plugin set is
unknown. Everything optional is discovered through the services manager at enable.

## Files

- `java/paper-plugin/build.gradle.kts`
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/NoderaEndpointPlugin.java`
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/platform/{NoderaScheduler,FoliaSchedulers,PaperSchedulers,PlatformProbe,RegionGridAlignment}.java`
- `java/paper-plugin/src/main/resources/plugin.yml`
- `java/build-logic/src/main/kotlin/nodera.paper-plugin.gradle.kts`
- `settings.gradle.kts` (module entry)

## Testing

- `RegionGridAlignmentTest` — the nesting arithmetic across `gridExponent` 0…6, both signs of the
  coordinate axis, and the region/section boundary cases. Minecraft-free.
- `PlatformProbeTest` — detection resolves once and is stable; an absent Folia class selects the Paper
  path.
- `SchedulerDispatchTest` — each `NoderaScheduler` method routes to the expected target on a fake
  platform; `BukkitScheduler` is never referenced (an ArchUnit rule over the module).
- `runPaper` / `runFolia` — the plugin enables, logs its platform, and disables cleanly.

## Acceptance criteria

1. ⬜ One jar enables on Paper 1.21.1 **and** Folia 1.21.1, logging the platform it detected.
2. ⬜ `folia-supported: true` is present and the plugin loads on Folia without a warning.
3. ⬜ An ArchUnit rule proves `org.bukkit.scheduler.BukkitScheduler` is unreferenced.
4. ⬜ ALIGN-1 preflight passes on the default `grid-exponent = 4` and **refuses to enable** below 3,
   with the fix named in the message.
5. ⬜ Disable is clean: no leaked threads, no leaked tasks, no console errors.
6. ⬜ CI resolves and caches the pinned Paper and Folia builds.

## Limitations

- **L-61** — the plugin does not exist; the three suites skip at their jar preflight.
- **L-66** — the version-pin trilemma (mod 1.21.1 · documented Folia 26.1.2 · no build pinned yet).
