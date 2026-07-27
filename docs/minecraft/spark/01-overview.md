# 01 — Overview & Platforms

## What spark is

> "spark is a performance profiler for Minecraft clients, servers and proxies." — upstream README

It is three tools that happen to ship together, and it is worth being clear about which one you
are reaching for, because they answer different questions.

| Pillar | Question it answers | Commands |
|---|---|---|
| **CPU profiler** | Where did the time go? Which method, in which mod? | `/spark profiler …` |
| **Memory inspection** | What is being allocated, and what is still alive? | `/spark heapsummary`, `/spark heapdump`, `/spark profiler --alloc` |
| **Health reporting** | Is the server actually healthy, and is the host the problem? | `/spark health`, `/spark tps`, `/spark tickmonitor`, `/spark gc` |

For Nodera the first is the deliverable and the third is the context that keeps you from
misreading it — a profile taken while the host was swapping tells you about the host.

## What makes it different from a stopwatch

Two properties matter for our use.

**It is statistical, not instrumented.** spark does not modify our bytecode or wrap our methods.
It periodically asks "what is each thread doing right now" and counts the answers. A method that
shows up in 5% of samples was, to a good approximation, executing 5% of the time. This costs very
little and — crucially — it measures code we did not think to instrument, including code we did
not write.

**It knows who owns a frame.** Given `dev.nodera.entity.LiveEntityControlProvider.sweep`, spark
can say that class came from the `nodera` mod, and it does this by inspecting classloaders and
module names at runtime rather than by string-matching package prefixes. That is what turns a
call tree into a per-mod cost breakdown. It is also the part with the most caveats — see
[05 — Source Attribution](./05-source-attribution.md).

## Platform matrix

spark is one core (`spark-common`) behind a per-platform bridge implementing the `SparkPlugin`
SPI. The bridge supplies the things only the platform knows: where the config directory is, how
to hook the tick loop, how to check a permission, and — the interesting one — how to identify
which mod or plugin a class came from.

### In the main repository

| Module | Platform | Notes |
|---|---|---|
| `spark-bukkit` | Bukkit / Spigot / **Paper** | The jar you install on any Bukkit-family server. Not Minecraft-version-specific. |
| `spark-paper` | Paper (bundled) | **Not** something you install — this is the module Paper 1.21+ ships *inside* its own server jar. |
| `spark-neoforge` | NeoForge | Separate client and server plugins; the mod id is `spark`. |
| `spark-forge` | Forge | Same shape as NeoForge. |
| `spark-fabric` | Fabric | The only platform with mixin-aware attribution. |
| `spark-sponge` | Sponge (API 12) | |
| `spark-bungeecord`, `spark-velocity` | Proxies | No tick loop, so no TPS and no `--only-ticks-over`. |
| `spark-standalone-agent` | Any JVM | `-javaagent:` attachment plus an SSH control interface. |

### In `spark-extra-platforms`

A separate repository (`lucko/spark-extra-platforms`) builds `folia`, `geyser`, `hytale`,
`minestom`, `nukkit`, `sponge7`, `velocity4` and `waterdog`. Its README is explicit:

> "These releases do not receive the same level of support as the main spark plugins/mods. They
> are provided as-is, and may be out of date or contain bugs."

**The `folia` artifact matters after all.** Folia bundles `spark-paper` like Paper but declines to
enable it, so the community jar is the only route — and the current build is incompatible with our
pinned Folia 1.21.4. This was assumed to be a non-issue and turned out not to be; see
[12](./12-nodera-paper-folia.md).

## Which artifact goes where

This is the single most useful table in this directory, because the intuitive answer ("download
the jar for your platform") is wrong for all three of our targets — in three different ways.

| Nodera target | What to install | Why |
|---|---|---|
| NeoForge dev client & server | `spark-1.10.124-neoforge.jar` into `<gameDir>/mods/` | NeoForge bundles nothing. The jar is pinned and cached by `scripts/lib/spark.sh`. |
| **Paper 1.21.1** | **nothing** | Paper 1.21+ bundles spark. Verified in this repo: booting `run/servers/paper.jar` resolves `me/lucko/spark-paper/1.10.119-SNAPSHOT/` into `<stage>/libraries/` and creates `plugins/spark/` on first run. |
| **Folia 1.21.4** | **nothing that works** | Folia inherits the bundling and then refuses to enable it; the community `spark-folia` jar loads but targets a newer Folia. Profiling is unavailable on our pinned build — see [12](./12-nodera-paper-folia.md). |

Upstream states the Paper case plainly:

> "If you are running Paper 1.21 or newer, spark is **already bundled as part of the server** - no
> need to install the plugin jar!"

If you ever *do* need a standalone plugin to take precedence over the bundled copy — a newer spark
than the server shipped, say — the server must be started with `-Dpaper.preferSparkPlugin=true`.
Nodera does not do this on Paper. On Folia it is the only mode that could work, which is why
`nodera_spark_bukkit_jvm_args` sets it there and unsets it everywhere else.

## Licensing

spark is **GPL-3.0-only**. This matters for one reason and does not matter for another:

- **It does not matter** for how we use it. We ship nothing; the jar is fetched at test time into
  `run/spark/` (gitignored) and dropped next to the game. No Nodera artifact links against it, and
  the vendored source in `docs/minecraft/upstream/spark` is a reference checkout recorded as a
  submodule gitlink, not vendored code we compile into anything.
- **It would matter** if we ever linked against spark's internals. The one module that is safe to
  depend on is `spark-api`, which is separately **MIT**-licensed for exactly this reason — and
  which cannot do what you would want it for anyway ([10 — The Developer API](./10-api.md)).

The documentation site's own content is CC-BY-SA-4.0.

## Where this leaves Nodera

The practical shape of the integration, in one paragraph: one pinned mod jar for NeoForge, nothing
at all for Paper, nothing that works for Folia, every capture driven by command and written to disk
with `--save-to-file`, and nothing uploaded anywhere. The rest of this directory is the detail
behind each of those choices.

---

**Next:** [02 — Build System & Project Layout](./02-build-system.md) ·
**Index:** [README](./README.md)
