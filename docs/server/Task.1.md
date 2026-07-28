# Server Task 1 — Plugin Skeleton, Build Lane, Platform Abstraction

<!-- AI-AGENT-INSTRUCTION: ONE JAR runs on Paper AND Folia. Do not ship two artifacts and do not
     branch on the platform anywhere except inside the NoderaScheduler seam. `folia-supported: true`
     is unconditional. The ALIGN-1 preflight is not optional decoration: it is the invariant every
     later task stands on, and it must refuse to enable — loudly, with the fix in the message —
     rather than degrade silently. Keep this header's status accurate. -->

**Status:** ✅ COMPLETED (2026-07-26 — enables on real Paper 1.21.1 and Folia; ALIGN-1 passes at the default and refuses at exponent 2; L-61 retired)
**Category:** server · **Owns:** L-61 (RETIRED 2026-07-26), L-66 (OPEN) · **Last audit:** 2026-07-28
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

**Done.** `java/paper-plugin` builds `nodera-endpoint.jar` and all three live suites are green for the
task-1 surface:

- **Paper 1.21.1** (`e2e-endpoint.sh`): the plugin enables, names its platform (*"Nodera endpoint on
  Paper …"*), reports ALIGN-1 as not applicable on a single-threaded server, and disables cleanly with
  no exception in the log.
- **Folia** (`e2e-folia.sh`) — the stage that turns the preflight from decoration into a check: at the
  platform default it logs *"ALIGN-1 preflight passed: grid-exponent 4, 4 Nodera regions per Folia
  section, none split"*, and the same jar at grid-exponent 2 **refuses to enable** with the fix named
  in the message.
- **Co-existence** (`e2e-plugins.sh`): the corpus is whatever an operator staged; nothing is
  downloaded and what is absent is named rather than silently untested.

**L-61 retired 2026-07-26** (evidence in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)). **L-66**
stays OPEN: Folia has no 1.21.1 build (it went 1.21.x → 1.21.4+), so the Folia suite runs the newest
available — sound for plugin enable and ALIGN-1, which are platform properties, and exactly why the
mixed-**client** suites stay blocked.

**Two planned deliverables are deferred by name, not silently dropped.** The `NoderaScheduler` seam
(`FoliaSchedulers` / `PaperSchedulers`) and the in-module ArchUnit rule banning `BukkitScheduler` are
not yet enacted: the plugin does no region-scheduled work until [server 3](Task.3.md)–[server 5](Task.5.md),
and the module today references no scheduler at all. The property holds de facto; the rule lands with
the first code that could violate it.

## Dependencies

None inside the project. Externally: a **Paper 1.21.1** and a **Folia 1.21.1** build, resolved through
the PaperMC API. **Paper 1.21.1-133 is resolved and green; Folia 1.21.1 does not exist** —
[L-66](LIMITATIONS.md) records the gap and the fallback (newest available Folia).

## Deliverables

| # | Deliverable | State |
|---|---|---|
| 1 | Gradle module `java/paper-plugin` (`:paper-plugin`, artifact `nodera-endpoint`) + `settings.gradle.kts` entry | ✅ |
| 2 | `nodera.paper-plugin.gradle.kts` convention: Paper API `provided`, Nodera modules shaded, `runPaper`/`runFolia` dev runs | ✅ |
| 3 | `plugin.yml` — `api-version`, `folia-supported: true`, `/nodera` command, no hard dependencies | ✅ |
| 4 | `NoderaScheduler` seam + `FoliaSchedulers` / `PaperSchedulers` implementations | ⬜ deferred — lands with [3](Task.3.md)–[5](Task.5.md) |
| 5 | `PlatformProbe` — one-time platform detection, logged at enable | ✅ (shipped as `EndpointPlatform`) |
| 6 | `RegionGridAlignment` — the ALIGN-1 preflight, with a refusing enable path | ✅ (shipped as `core/RegionAlignment.preflight`, called from `NoderaEndpointPlugin`) |
| 7 | Pinned Paper + Folia downloads in CI, cached | 🚧 Paper resolved; Folia pinned to newest available ([L-66](LIMITATIONS.md)) |

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
drift. *(The seam above is the spec; the implementing classes land with the first region-scheduled
work in [server 3](Task.3.md).)*

**ALIGN-1 is preflighted, not assumed.** A Nodera region is 8×8 chunks anchored at chunk 0; a Folia
section is `2^gridExponent` chunks anchored at chunk 0. For `gridExponent ≥ 3` every Nodera region
nests wholly inside one section, so all of a region's work lands on one Folia region thread with no
locking — that free per-region single-threading is what makes tasks 3–5 tractable.

At enable the plugin reads `threaded-regions.grid-exponent` from `paper-global.yml` and, if it is
below 3:

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

- `java/paper-plugin/src/main/java/dev/nodera/endpoint/NoderaEndpointPlugin.java:23` — entry point:
  detects platform, preflights ALIGN-1, reads `nodera-endpoint.yml`, links the external worker
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/EndpointPlatform.java:15` — Paper / Folia /
  UNKNOWN detection via a classpath probe for the regionised scheduler
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/EndpointConfig.java:25` — `nodera-endpoint.yml`
  parser + validator (no Bukkit YAML reader; the contract is unit-testable with no server)
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/ControlClient.java:32` — one-line-exchange
  with the worker's control socket (used by the external link in [server 2](Task.2.md))
- `java/paper-plugin/src/main/java/dev/nodera/endpoint/EndpointPeerLink.java:23` — background retry
  link + the `NODERA-HOST` verb (the external-mode half of [server 2](Task.2.md))
- `java/core/src/main/java/dev/nodera/core/region/RegionAlignment.java` — ALIGN-1 arithmetic
  (`preflight`, `regionsPerSectionAxis`, `DEFAULT_GRID_EXPONENT`), exhaustively tested over a ±64
  region grid × exponents 3–6 with no server on the classpath
- `java/paper-plugin/src/main/resources/plugin.yml` — `folia-supported: true`, `api-version`, commands
- `java/build-logic/src/main/kotlin/nodera.paper-plugin.gradle.kts` — convention plugin
- Tests: `EndpointConfigTest` (9) · `EndpointPlatformTest` (5) · `EndpointPeerLinkTest` (6) — **20
  unit tests, 0 failing** (see [`TESTING.md`](TESTING.md) §2)
- Live: `scripts/e2e-endpoint.sh` · `scripts/e2e-folia.sh` · `scripts/e2e-plugins.sh`

## Testing

- `EndpointPlatformTest` — Folia is identified by the regionised scheduler class (not a name); an
  older Paper's legacy config still counts; a Folia fork that also ships Paper's configuration stays
  Folia; an unrecognised platform is `UNKNOWN`, never guessed.
- `EndpointConfigTest` — the harness's own staged file parses exactly; omissions take documented
  defaults; contradictions (not omissions) are refused with every reason at once; a hash inside a
  quoted password survives comment stripping.
- `EndpointPeerLinkTest` — a real stand-in worker on a real socket (the property under test is a wire
  property); only state *changes* are logged; a wrong answer is not a link.
- `RegionAlignmentTest` (in `core`) — the nesting arithmetic across `gridExponent` 0…6, both signs of
  each axis, and the region/section boundary cases. Minecraft-free.
- `runPaper` / `runFolia` and the three live suites — the plugin enables, logs its platform, and
  disables cleanly; ALIGN-1 passes at the default and refuses below 3.

## Acceptance criteria

1. ✅ One jar enables on Paper 1.21.1 **and** Folia, logging the platform it detected.
2. ✅ `folia-supported: true` is present and the plugin loads on Folia without a warning.
3. ⬜ *(deferred)* An ArchUnit rule proves `org.bukkit.scheduler.BukkitScheduler` is unreferenced — the
   property holds today (the module names no scheduler) but the rule is not enacted; it lands with the
   `NoderaScheduler` seam in [server 3](Task.3.md).
4. ✅ ALIGN-1 preflight passes on the default `grid-exponent = 4` and **refuses to enable** below 3,
   with the fix named in the message (proven live on Folia at exponent 2).
5. ✅ Disable is clean: no leaked threads, no leaked tasks, no console errors.
6. 🚧 Paper 1.21.1-133 is resolved and cached in CI; Folia is pinned to the newest available build —
   the 1.21.1 gap is [L-66](LIMITATIONS.md), not a silent degrade.

## Limitations

- **L-61** — RETIRED 2026-07-26: the plugin exists and the three suites are green for the task-1
  surface. Evidence in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
- **L-66** — the version-pin trilemma (mod 1.21.1 · documented Folia 26.1.2 · no 1.21.1 Folia build).
