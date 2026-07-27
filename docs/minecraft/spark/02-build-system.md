# 02 — Build System & Project Layout

Everything here refers to the vendored copy at
[`docs/minecraft/upstream/spark`](../upstream/spark) (`f181ccf`). Populate it with:

```bash
git submodule update --init docs/minecraft/upstream/spark
```

## Module map

`settings.gradle` includes twelve projects. Read them as: one core, one API, one shared-Minecraft
layer, and a thin bridge per platform.

```
spark/
├── spark-api                 the public, MIT-licensed statistics API
├── spark-common              EVERYTHING: samplers, aggregators, commands, proto, config, ws
├── spark-minecraft           shared base for the four Minecraft mod platforms
├── spark-bukkit              Bukkit/Spigot/Paper plugin
├── spark-paper               the module Paper BUNDLES (not an install target)
├── spark-neoforge            NeoForge mod  ← what Nodera pins
├── spark-forge               Forge mod
├── spark-fabric              Fabric mod
├── spark-sponge              Sponge
├── spark-bungeecord          BungeeCord proxy
├── spark-velocity            Velocity proxy
└── spark-standalone-agent    -javaagent for any JVM, with an SSH console
```

Nukkit, Minestom, Folia, Geyser and friends are **not here** — they live in
`lucko/spark-extra-platforms`.

### `spark-common` — where the work happens

`SparkPlatform` is the object every platform hands control to. It owns, in one constructor:

| It owns | Which is why |
|---|---|
| `Configuration` | the layered config chain — see [08](./08-configuration.md) |
| `SamplerContainer` | the single active profiler, if any |
| `BackgroundSamplerManager` | the boot-time profiler, when enabled |
| `TickHook` / `TickReporter` / `TickStatistics` | TPS, MSPT, and `--only-ticks-over` |
| `PingStatistics`, `PlatformStatisticsProvider` | `/spark ping`, `/spark health` |
| `BytebinClient`, `BytesocksClient`, `TrustedKeyStore` | uploads and the live viewer — **all unused by Nodera** |
| `ActivityLog` | `activity.json`, the machine-readable record of every capture |
| `CommandManager` | the command tree and its permission checks |

### The `SparkPlugin` SPI

A platform bridge implements this interface. The methods worth knowing, because they explain the
per-platform differences documented elsewhere in this directory:

| Method | Why it matters here |
|---|---|
| `getPluginDirectory()` | Where `config.json`, `activity.json` and saved captures live. **Differs per platform** — see below. |
| `getCommandName()` | `spark` on servers, `sparkc` on clients, `sparkb`/`sparkv` on proxies. |
| `createTickHook()` / `createTickReporter()` | Absent on proxies, which is why `--only-ticks-over` is server-only. |
| `createClassSourceLookup()` | **The per-mod attribution strategy.** The single biggest behavioural difference between platforms — [05](./05-source-attribution.md). |
| `getDefaultThreadDumper()` | Which threads are sampled when `--thread` is not given. Nodera never relies on this; see [03](./03-profiler-engines.md). |

### The plugin directory, per platform

Worth stating explicitly because it is where you go looking for a capture, and the NeoForge answer
is not the obvious one:

| Platform | Directory | Source |
|---|---|---|
| Bukkit / Paper / Folia | `<server>/plugins/spark/` | the normal plugin data folder |
| **NeoForge** | **`<gameDir>/config/spark/`** | `NeoForgeSparkMod` resolves `FMLPaths.CONFIGDIR.get().resolve(modId)` |
| Standalone agent | `./spark/` | relative to the JVM's working directory |

`scripts/lib/spark.sh` encodes exactly this table in `nodera_spark_dirs`.

## Artifact naming and distribution

CI produces one jar per platform, named `spark-<version>-<platform>.jar`:

```
spark-1.10.175-bukkit.jar            spark-1.10.175-neoforge.jar
spark-1.10.175-paper.jar             spark-1.10.175-fabric.jar
spark-1.10.175-forge.jar             spark-1.10.175-standalone-agent.jar
spark-1.10.175-velocity.jar          spark-1.10.175-bungeecord.jar
spark-1.10.175-sponge.jar
```

Three distribution channels, and the difference between them is the thing that catches people:

| Channel | Carries |
|---|---|
| `ci.lucko.me/job/spark` | The newest build — but **only for the newest Minecraft version**. |
| Modrinth / CurseForge | Per-Minecraft-version builds, including old ones. |
| `repo.lucko.me` | `spark-api` only. |

This is why Nodera pins a **Modrinth** URL for `spark-1.10.124-neoforge.jar`: 1.21.1 is not the
newest Minecraft version, so CI has no jar for it. The download page at `spark.lucko.me/download`
is a client-side React page that queries the Jenkins API at runtime, which is also why you cannot
treat any URL scraped from it as stable.

## Building the NeoForge jar from our own vendored copy

The pinned download is the normal path. Building from source is the fallback for when Modrinth is
unreachable, or when you need to test a spark change:

```bash
git submodule update --init docs/minecraft/upstream/spark
cd docs/minecraft/upstream/spark
./gradlew :spark-neoforge:build
ls spark-neoforge/build/libs/
```

Then point the harness at the result — no code change required, because the path is a variable:

```bash
NODERA_SPARK_JAR=/abs/path/to/spark-<ver>-neoforge.jar NODERA_SPARK=1 scripts/e2e-mobs.sh
```

Two things to know before you try it:

- The build resolves from `maven.neoforged.net`, `maven.fabricmc.net` and
  `maven.minecraftforge.net`; it is a genuinely networked build, not a quick offline one.
- `gradle.properties` sets `org.gradle.daemon=false` with the comment `# thanks, forge`. Expect it
  to be slow, and expect it not to reuse a daemon.

A jar built from the vendored `f181ccf` targets a **newer** Minecraft than 1.21.1. If you build it
yourself for our dev runs, check that the resulting mod's `neoforge.mods.toml` still accepts
NeoForge 21.1.x — the pinned 1.10.124 jar declares `versionRange = "[20,)"`, which does.

## What Nodera builds: nothing

Worth saying plainly, because it is the reason this integration is cheap. Nodera compiles no spark
code, links against no spark artifact, and adds no dependency to any `build.gradle.kts`. The
integration is: fetch a jar, put it in a directory, send commands, read the bytes it writes.

The only Gradle change is two JVM arguments on the dev runs
(`java/build-logic/src/main/kotlin/nodera.neoforge-mod.gradle.kts`), and neither of them mentions
spark's classes — one enables dynamic agent loading, the other passes a Nodera system property.

---

**Previous:** [01 — Overview & Platforms](./01-overview.md) ·
**Next:** [03 — Profiler Engines](./03-profiler-engines.md) ·
**Index:** [README](./README.md)
