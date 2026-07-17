# 02 — Build System & Project Layout

Folia is a **paperweight fork** of Paper. The repository contains **no
`.java` or `.kt` source files** — every Folia-specific line of code lives
inside `.patch` files. After running `./patch.sh` (`applyAllPatches`), real
`paper-api/` and `paper-server/` source directories appear on disk and you
can edit them like any normal Java project.

This file documents the directory layout, the paperweight flow, the patch
directories, the dev workflow (`patch.sh` / `rb.sh`), the build settings, and
the CI.

---

## Top-level layout

```
folia/
├── build.gradle.kts                  # Root build: paperweight patcher config
├── settings.gradle.kts               # Declares folia-api + folia-server; computes version
├── gradle.properties                 # mcVersion, paperRef, channel, Java 25
├── gradle/
│   ├── libs.versions.toml            # (empty placeholder)
│   └── wrapper/                      # gradle wrapper jar + props
├── build-data/
│   ├── dev-imports.txt               # How to import Mojang classes for IDE
│   └── fork.at                      # Access transformer
├── patch.sh, patch.bat               # ./gradlew applyAllPatches
├── rb.sh, rb.bat                     # rebuild patches from edited source
├── README.md
├── PROJECT_DESCRIPTION.md            # Stub redirect to PaperMC docs
├── REGION_LOGIC.md                   # Stub redirect to PaperMC docs
├── PATCHES-LICENSE                   # License for all patches
├── folia.png                         # Logo
├── update.txt                        # Developer TODO notes
├── .github/workflows/build.yml       # CI
├── .github/ISSUE_TEMPLATE/           # Bug report templates
│
├── folia-api/                        # Patches layered on paper-api
│   ├── build.gradle.kts.patch        # Generates folia-api/build.gradle.kts
│   └── paper-patches/features/       # 4 API patches
│
└── folia-server/                     # Patches layered on paper-server
    ├── build.gradle.kts.patch        # Generates folia-server/build.gradle.kts
    ├── minecraft-patches/features/   # 8 NMS/vanilla patches (applied FIRST)
    └── paper-patches/features/       # 7 CraftBukkit patches (applied SECOND)
```

---

## `gradle.properties`

```properties
group                            = dev.folia
mcVersion                        = 26.1.2
apiVersion                       = 26.1.2
channel                          = STABLE
paperRef                         = b4682bfef616ac62e73cc96046dacdf4a6f53eeb
org.gradle.configuration-cache   = true
org.gradle.caching               = true
org.gradle.parallel              = true
org.gradle.vfs.watch             = false
```

`mcVersion` and `paperRef` together pin the exact upstream Paper commit the
fork is based on. `channel` is `STABLE` for releases; other channels (like
`EXPERIMENTAL`) affect version numbering only.

---

## Root `build.gradle.kts`

```kotlin
plugins {
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.21"
}

paperweight {
    filterPatches = false

    upstreams.paper {
        ref = gradleProperty("paperRef")

        patchFile("paperServer") {
            upstreamPath = "paper-server"
            patchPath = layout.projectDirectory.file("folia-server/build.gradle.kts.patch")
            outputPath = layout.projectDirectory.file("folia-server/build.gradle.kts")
        }
        patchFile("paperApi") {
            upstreamPath = "paper-api"
            patchPath = layout.projectDirectory.file("folia-api/build.gradle.kts.patch")
            outputPath = layout.projectDirectory.file("folia-api/build.gradle.kts")
        }

        patchDir("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = layout.projectDirectory.dir("folia-api/paper-patches")
            outputDir = layout.projectDirectory.dir("paper-api")
        }
    }
}

subprojects {
    java {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }
    tasks.withType<JavaCompile> {
        options.release = 25
        options.isFork = true
        options.encoding = Charsets.UTF_8.name()
    }
    // reproducible archives, test logging, etc.
}
```

The Java **25** toolchain is enforced everywhere (`options.release = 25`).
That's a hard requirement — Folia will not build on older Java.

---

## `settings.gradle.kts`

```kotlin
rootProject.name = "folia"
include("folia-api", "folia-server")

gradle.lifecycle.beforeProject {
    val mc = providers.gradleProperty("mcVersion").get()
    val channel = providers.gradleProperty("channel").get()
    val buildNumber = providers.environmentVariable("BUILD_NUMBER").orNull
    version = if (buildNumber == null) "$mc.local-SNAPSHOT"
              else "$mc.build.$buildNumber-$channel"
}
```

So an unconfigured local build is `26.1.2.local-SNAPSHOT`; a CI build is
`26.1.2.build.<n>-stable`. The Maven coordinates are
`dev.folia:folia-api:[26.1.2.build,)`.

---

## `folia-server/build.gradle.kts.patch`

This patch generates `folia-server/build.gradle.kts` from Paper's
`paper-server/build.gradle.kts`. The interesting bits:

```kotlin
paperweight {
    filterPatches = false
    gitFilePatches = false

    val fork = forks.register("folia") {
        upstream.patchDir("paperServer") {
            upstreamPath = "paper-server"
            excludes = setOf("src/minecraft", "patches", "build.gradle.kts")
            patchesDir = rootDirectory.dir("folia-server/paper-patches")
            outputDir = rootDirectory.dir("paper-server")
        }
    }
    activeFork = fork
}

// Source sets point at ../paper-server/src/... so IDE sees the patched tree.
dependencies {
    implementation(project(":folia-api"))
    implementation("ca.spottedleaf:concurrentutil:0.0.10")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Folia",
            "Brand-Id" to "papermc:folia",
            "Brand-Name" to "Folia",
        )
    }
}
```

Two things to notice:

1. **Folia's server patches are layered in two tiers.** The
   `minecraft-patches` (under `folia-server/minecraft-patches/features/`)
   modify Paper's NMS source (`net.minecraft.*`), and are applied first.
   The `paper-patches` (under `folia-server/paper-patches/features/`) modify
   the CraftBukkit layer (`org.bukkit.craftbukkit.*`), and are applied on
   top.
2. Folia depends on **`ca.spottedleaf:concurrentutil`** — spottedleaf's own
   concurrency primitives library. This is where `SWMRLong2ObjectHashTable`,
   the work-stealing scheduler, NUMA helpers, etc. come from.

---

## `folia-api/build.gradle.kts.patch`

Rewrites paths so that `folia-api` compiles the patched `paper-api` tree:

```kotlin
sourceSets {
    main {
        java { srcDir("../paper-api/src/main/java") }
        resources { srcDir("../paper-api/src/main/resources") }
    }
}
val generatedDir = layout.projectDirectory.dir("../paper-api/src/generated/java")
```

So the project layout on disk is:

```
paper-api/     ← generated by applyAllPatches (Patched paper-api source)
paper-server/  ← generated by applyAllPatches (Patched paper-server source)
folia-api/     ← gradle config only; builds ../paper-api
folia-server/  ← gradle config + patches; builds ../paper-server
```

---

## Patch inventory

### `folia-api/paper-patches/features/` — 4 API patches

| # | File | Lines | Purpose |
|---|---|---|---|
| 0001 | `Force-disable-timings.patch` | 19 | Hard-disable ACF timings; Folia needs its own profiler. |
| 0002 | `Region-scheduler-API.patch` | 44 | Deprecate `BukkitScheduler`; redirect `SimplePluginManager#cancelTasks` to async scheduler. |
| 0003 | `Require-plugins-to-be-explicitly-marked-as-Folia-sup.patch` | 80 | Add `PluginMeta#isFoliaSupported()`; parse `folia-supported:` from `plugin.yml`. |
| 0004 | `Add-TPS-From-Region.patch` | 92 | Add `Bukkit#getRegionTPS(Location|Chunk|world,x,z): double[]` returning 5-window TPS. |

### `folia-server/minecraft-patches/features/` — 8 NMS-layer patches (applied first)

| # | File | Lines | Purpose |
|---|---|---|---|
| 0001 | **`Region-Threading-Base.patch`** | **20,208** | The core. Adds the entire `io.papermc.paper.threadedregions` package and rewrites `MinecraftServer`, `ServerLevel`, `ChunkHolderManager`, `RegionizedPlayerChunkLoader`. |
| 0002 | `Max-pending-logins.patch` | 42 | Throttle concurrent logins (`misc.maxJoinsPerTick`). |
| 0003 | `Add-chunk-system-throughput-counters-to-tps.patch` | 86 | Add gen/load rate counters to `/tps`. |
| 0004 | `Prevent-block-updates-in-non-loaded-or-non-owned-chu.patch` | 135 | Guard block physics/redstone/tripwire with `TickThread.isTickThreadFor(...,25)`. |
| 0005 | `Block-reading-in-world-tile-entities-on-worldgen-thr.patch` | 24 | `ImposterProtoChunk#getBlockEntity` returns null on non-tick threads. |
| 0006 | `Sync-vehicle-position-to-player-position-on-player-d.patch` | 32 | Reposition saved vehicle on login to avoid thread-check trips. |
| 0007 | `Region-profiler.patch` | 2,048 | Add deep profiling / `/tps` metrics. |
| 0008 | `Add-watchdog-thread.patch` | 185 | Add `FoliaWatchdogThread` that dumps stack of long-running ticks. |

### `folia-server/paper-patches/features/` — 7 CraftBukkit-layer patches (applied second)

| # | File | Lines | Purpose |
|---|---|---|---|
| 0001 | **`Region-Threading-Base.patch`** | **4,147** | Modifies **204 CraftBukkit files**: `CraftServer`, `CraftWorld`, every `CraftEntity` subclass (adds `ensureTickThread`), `CraftScheduler` (disabled), config classes, plugin loaders. |
| 0002 | `Update-Logo.patch` | 1,037 | Binary logo swap. |
| 0003 | `Build-changes.patch` | 91 | bStats → "Folia", version URLs → PaperMC/Folia, brand strings. |
| 0004 | `Fix-tests-by-removing-them.patch` | 19 | Remove tests that don't compile under region threading. |
| 0005 | `Region-profiler.patch` | 90 | Wire up the profiler in CraftBukkit. |
| 0006 | `Add-watchdog-thread.patch` | 21 | Wire up the watchdog. |
| 0007 | `Add-TPS-From-Region.patch` | 80 | Implement `CraftServer#getRegionTPS`. |

Total: ~**28,500 lines of patch**. The two `Region-Threading-Base.patch`
files together are the heart of Folia.

---

## Dev workflow

### `patch.sh` / `patch.bat`

```bash
./patch.sh   # == ./gradlew applyAllPatches
```

What it does:

1. Clones Paper at `paperRef` into `paper-api/` and `paper-server/` (these
   are normal git checkouts, with a remote called `paper` pointing upstream).
2. Applies `folia-api/paper-patches/features/*.patch` to `paper-api/`.
3. Applies `folia-server/minecraft-patches/features/*.patch` to
   `paper-server/` (NMS layer, first).
4. Applies `folia-server/paper-patches/features/*.patch` to `paper-server/`
   (CraftBukkit layer, second).

After this you have a fully editable Java project. Import the gradle build
into IntelliJ and you can navigate the patched source.

### Editing and rebuilding patches

Make your changes directly in `paper-api/` or `paper-server/` (these are git
working trees). Then:

```bash
./rb.sh   # == ./gradlew rebuildPaperPatches rebuildPaperServerPatches \
          #         rebuildServerPatches      rebuildMinecraftPatches
```

Each `rebuild*` task diffs the current working tree against its upstream
baseline and regenerates the corresponding `.patch` file.

### Building the runnable jar

```bash
./gradlew build
```

The runnable server jar ends up in `folia-server/build/libs/`.

### Patch ordering within a directory

Patches within a `features/` directory are applied in **filename order**
(`0001`, `0002`, …). Adding a new patch at the end of the directory keeps
existing patches stable. Inserting or removing a patch in the middle changes
every later patch's context and is generally avoided.

### `minecraft-patches` vs `paper-patches` ordering

The `minecraft-patches` are applied **before** the `paper-patches`. This is
because the CraftBukkit layer (`org.bukkit.craftbukkit.*`) depends on the
NMS layer (`net.minecraft.*`), and many CraftBukkit calls reference
Folia-added NMS types (e.g. `RegionizedServer`, `ThreadedRegionizer`,
`TickRegionScheduler`).

---

## Access transformers

`build-data/fork.at` is the access transformer file. Current contents:

```
public net.minecraft.data.registries.TradeRebalanceRegistries BUILDER
```

This widens access to one field so the Folia server can use it. Add new
entries here when you need to access package-private vanilla members.

---

## Continuous integration

`.github/workflows/build.yml`:

- Triggers on every push and PR.
- Uses Temurin JDK 25.
- Sets `git config user.name/email` (required by paperweight).
- Runs `./gradlew applyAllPatches --stacktrace` then `./gradlew build`.

If `applyAllPatches` fails on CI, the most common cause is a patch that no
longer applies cleanly against the pinned `paperRef` — usually because
someone bumped `paperRef` without reworking the patches.

---

## Issue templates

`.github/ISSUE_TEMPLATE/`:

- `behavior-bug.yml` — "Plugin X doesn't work" style bugs.
- `performance-problems.yml` — TPS drops, high CPU.
- `server-crash-or-stacktrace.yml` — crashes.
- `feature-request.yml`.
- `config.yml` — disables the blank issue template.

The bug templates explicitly ask whether the user's plugins are all marked
`folia-supported: true`, since that's the most common source of "Folia
doesn't work" reports.

---

## Next

Continue to [03 — Region Logic: The Threaded Regionizer](./03-region-logic.md).
