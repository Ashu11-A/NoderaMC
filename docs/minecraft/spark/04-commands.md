# 04 — Commands & Permissions

Verified against `spark-common/src/main/java/me/lucko/spark/common/command/modules/` at `f181ccf`.
Where this differs from the website, the source is what is documented here and the difference is
called out.

## The command prefix depends on the platform

| Platform | Prefix |
|---|---|
| Bukkit / Paper / Folia / Sponge / NeoForge **server** | `/spark` |
| NeoForge / Fabric **client** | `/sparkc` (alias `/sparkclient`) |
| BungeeCord | `/sparkb` |
| Velocity | `/sparkv` |

This matters for Nodera in one specific place: the client bridge dispatches `sparkc`, not `spark`
([11 — Nodera on NeoForge](./11-nodera-neoforge.md)). Getting it wrong yields "unknown command"
and a silently missing capture.

## The profiler

```
/spark profiler [subcommand] [flags…]
```

Aliases: `profiler`, `sampler`. Subcommands: `info`, `start`, `open`, `stop`, `upload` (alias of
`stop`), `cancel`, `trust-viewer`. Each subcommand can equivalently be given as a flag
(`--start`, `--stop`, …), which is how a platform with an awkward argument parser can still drive
it.

### Start flags

| Flag | Type | Meaning |
|---|---|---|
| `--thread <name>` | string, repeatable | Sample only these threads. |
| `--thread *` | — | Sample **all** threads. **Nodera always passes this** — see the empty-capture warning in [03](./03-profiler-engines.md). |
| `--regex` | bool | Treat `--thread` values as regular expressions. |
| `--interval <n>` | double | Sampling interval: **milliseconds** in execution mode (default 4), **bytes** in allocation mode (default 524287). |
| `--timeout <seconds>` | int | Auto-stop and auto-**upload** after N seconds. **Rejected if ≤ 10**; warns below 30. Absent means run until stopped. |
| `--only-ticks-over <ms>` | int | Record only samples from ticks exceeding N ms. Requires a `TickHook`. |
| `--combine-all` | bool | Merge every thread into one tree (`AS_ONE`). |
| `--not-combined` | bool | Disable thread-pool grouping (`BY_NAME`). |
| `--ignore-sleeping` | bool | Drop samples from sleeping/parked threads. |
| `--force-java-sampler` | bool | Force the built-in Java engine. |
| `--alloc` | bool | Allocation profiling instead of CPU. |
| `--alloc-live-only` | bool | With `--alloc`: keep only objects not GC'd by the end. |

> **`--timeout` uploads.** It auto-*uploads* on expiry unless `--save-to-file` was also given at
> start. Nodera never uses `--timeout`; the harness times the capture itself and stops it
> explicitly with `--save-to-file`.

### Stop / open flags

| Flag | Type | Meaning |
|---|---|---|
| `--save-to-file` | bool | Write the capture to disk **instead of uploading**. |
| `--comment <text>` | string | Embedded as `SamplerMetadata.comment`. Nodera writes `<suite>/<label>` here. |
| `--separate-parent-calls` | bool | Export with `MergeStrategy.SEPARATE_PARENT_CALLS` rather than `SAME_METHOD`. **Undocumented upstream.** |
| `--id <client id>` | string | `trust-viewer` only: trust a pending live-viewer client. **Undocumented upstream.** |

### Behaviour worth knowing

- The start reply names the engine: `Profiler is now running! (async)` or `(built-in java)`.
- Starting a profiler while the **background** profiler is running silently stops the background
  one first (*"Stopping the background profiler before starting… please wait"*).
- After `stop`, if background profiling is enabled it is **automatically restarted**. To prevent
  that, use `cancel`. Nodera sets `backgroundProfiler: false`, so neither applies.
- On upload failure spark automatically falls back to saving to disk.

## Everything else

| Command | Aliases | Permission | Flags |
|---|---|---|---|
| `/spark profiler` | `profiler`, `sampler` | `spark.profiler` | above |
| `/spark tps` | `tps`, `cpu` | `spark.tps` | — |
| `/spark ping` | `ping` | `spark.ping` | `--player <username>` |
| `/spark health` | `healthreport`, `health`, `ht` | **`spark.healthreport`** | `--upload`, `--memory`, `--network` |
| `/spark tickmonitor` | `tickmonitor`, `tickmonitoring` | `spark.tickmonitor` | `--threshold <pct>`, `--threshold-tick <ms>`, `--without-gc` |
| `/spark gc` | `gc` | `spark.gc` | — |
| `/spark gcmonitor` | `gcmonitor`, `gcmonitoring` | `spark.gcmonitor` | — (toggle) |
| `/spark heapsummary` | `heapsummary` | `spark.heapsummary` | `--save-to-file` *(undocumented upstream)*, `--run-gc-before` *(deprecated)* |
| `/spark heapdump` | `heapdump` | `spark.heapdump` | `--compress <gzip\|xz\|lzma>`, `--include-non-live`, `--run-gc-before` *(both deprecated)* |
| `/spark activity` | `activity`, `activitylog`, `log` | `spark.activity` | `--page <n>` |

> **The `/spark health` permission is `spark.healthreport`, not `spark.health`.** The node is
> built from the command's *primary* alias, and `healthreport` is listed first. This is the single
> most common permission mistake with spark.

## The permission model

From `CommandManager`:

```java
if (sender.hasPermission("spark")) {
    return this.commands;                                   // everything
}
return this.commands.stream()
        .filter(c -> sender.hasPermission("spark." + c.primaryAlias()))
        .collect(Collectors.toList());
```

So: `spark` grants everything, or `spark.<primaryAlias>` grants one command. Failure prints
*"You do not have permission to use this command."*

### On NeoForge

`NeoForgeServerSparkPlugin.onPermissionGather` registers a `PermissionNode<Boolean>` per command
under the `spark` namespace, plus one special node — and note the remap:

```java
// special case for the "spark" permission: map it to "spark.all"
if (permission.equals("spark")) {
    permission = "spark.all";
}
```

The default resolver grants: `false` for a null player (non-player senders), `true` for the
singleplayer world owner, and otherwise requires `PermissionLevel.OWNERS`. In other words **spark
defaults to op/owner level on NeoForge**, and a permissions mod can override via `spark.<alias>`
or `spark.all`.

### Console, RCON, and why Nodera does not care

Console and RCON senders bypass permission checks on every platform Nodera uses. The harness
drives spark exclusively over RCON as the console, so no permission is ever configured, granted or
checked in any Nodera suite.

## ⚠ Spark's replies do not come back over RCON

**Verified live on Paper 1.21.1-133:** every `/spark …` command issued over RCON returns an
**empty string**. Not an error, not a partial reply — nothing.

The cause is structural: `CommandManager.executeCommand` runs the command on spark's own executor
and answers the sender when it finishes, by which time the RCON connection has already been handed
its (empty) response and closed the exchange.

Three consequences, all of which shaped the integration:

1. **You cannot confirm a capture started by reading the reply.** There is nothing to read.
2. **You cannot learn the saved file's path from the reply.** The "Data has been written to: …"
   line is real, but it goes to the sender asynchronously and never reaches RCON.
3. `disableResponseBroadcast: true` (which Nodera sets) additionally keeps output off every
   online admin's chat, so it does not appear in the console log either.

`scripts/lib/spark.sh` therefore identifies a capture by **file mtime against a marker file**
dropped at start, and treats the reply as informational only. spark's own `activity.json` records
every capture with its path and is swept up as a cross-check — see
[10 — The Developer API](./10-api.md).

## Discrepancies with the official docs

Collected here so nobody re-derives them:

| Claim | Reality at `f181ccf` |
|---|---|
| `--ignore-native` exists | **Removed.** Not in `SamplerBuilder` or `SamplerModule`. |
| `sparkEnabled` config option | **Does not exist.** Nothing by that name in the codebase. |
| `/spark health` needs `spark.health` | It is `spark.healthreport`. |
| `--separate-parent-calls`, `--id`, `heapsummary --save-to-file` | Implemented, absent from the docs site. |
| `spark-api` from "Sonatype Snapshots" | Every code sample uses `https://repo.lucko.me/`. |

---

**Previous:** [03 — Profiler Engines](./03-profiler-engines.md) ·
**Next:** [05 — Source Attribution](./05-source-attribution.md) ·
**Index:** [README](./README.md)
