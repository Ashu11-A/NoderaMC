# 03 — Profiler Engines

spark has two samplers. Which one you got changes the numbers, and it is chosen for you at
runtime with a silent fallback — so the first thing any automated capture should do is record
which one ran.

## The two engines

| | async-profiler | built-in Java sampler |
|---|---|---|
| Implementation | native `libasyncProfiler.so`, bundled in the jar | `ThreadMXBean` via `ManagementFactory` |
| Class | `AsyncSampler` | `JavaSampler` |
| Accuracy | no safepoint bias | **safepoint-biased** |
| Platforms | Linux and macOS, x86-64 and aarch64 only | anywhere |
| Allocation profiling | yes (`--alloc`) | no |
| Selected | automatically, when supported | automatically, as the fallback |

Upstream is blunt about the difference:

> "The async-profiler engine is more accurate than the Java/WarmRoast engine, as it does not
> suffer from the safe-point sampling bias problem. It will be used automatically if your system
> supports it."

Safepoint bias is not a rounding error. The Java sampler can only capture a thread's stack at a
JVM safepoint, and safepoints are not uniformly distributed through your code — tight loops,
inlined methods and array-bounds-eliminated regions have few of them. The effect is that hot,
well-optimised code is *under*-reported and the method that happens to sit next to a safepoint
absorbs its time. Two captures of the same workload on different engines are not comparable.

### Platform support, exactly

From `AsyncProfilerAccess.load()`:

| OS | Arch | Native path |
|---|---|---|
| linux | amd64 | `linux/amd64` |
| linux | aarch64 | `linux/aarch64` |
| macosx | amd64 | `macos` |
| macosx | aarch64 | `macos` |

Anything else — Windows, 32-bit, other architectures — gets the Java sampler. The native library
is extracted from the jar into a temp file named `spark-*-libasyncProfiler.so.tmp` at first use.
On our platforms (Linux, amd64) and on GitHub's runners, async-profiler is what runs.

### Events

async-profiler is used with two different events depending on mode, and this trips people up:

- **Execution profiling uses the `wall` event, not `cpu`.** It measures elapsed wall-clock time
  per thread, including time blocked and parked. That is the right choice for a game server —
  a tick spent waiting on I/O is a lagged tick — but it means an idle thread pool accumulates
  enormous "sampled time" that is not work. See the warning about denominators in
  [13 — Reading a Profile](./13-reading-a-profile.md).
  If `wall` is unsupported spark throws `IllegalStateException("'wall' event is not supported")`
  and falls back to the Java engine.
- **Allocation profiling uses the `alloc` event.** If unavailable, `--alloc` is refused with:
  *"The allocation profiling mode is not supported on your system. This is most likely because
  Hotspot debug symbols are not available."*

### How it degrades

Every failure below falls back to the Java sampler rather than erroring out, which is friendly
and is exactly why you must check what you got:

| Failure | Cause |
|---|---|
| `UnsupportedSystemException` | OS/arch not in the table above |
| `UnsupportedJvmException` | JVM cannot host the agent |
| `NativeLoadingException` | the `.so` would not load — commonly missing `libstdc++` |

Fixes upstream documents: `apk add libstdc++` on Alpine, `apt install libstdc++6` on Debian.
Allocation profiling additionally wants Hotspot debug symbols, which "on modern JVMs (Java 11+)
should be available by default".

### Containers and perf events

> "async-profiler will automatically use the 'itimer' mode to profile if it cannot access
> perf-events. This is the case for most Docker runtimes… if you want to record profiling
> information for native code, you'll need to set the following flag:
> `docker run --cap-add SYS_ADMIN ...`"

For Nodera this is a non-issue: our suites profile a JVM launched directly on the host or on a
GitHub runner, never inside a container. If you ever profile inside `docker/`, expect `itimer`
mode and no native frames.

## Sampling intervals

From `SamplerMode`:

```java
EXECUTION (value -> value / 1000d, 4      /* ms    */, SamplerMetadata.SamplerMode.EXECUTION),
ALLOCATION(value -> value,         524287 /* bytes */, SamplerMetadata.SamplerMode.ALLOCATION);
```

| Mode | `--interval` unit | Default |
|---|---|---|
| execution | milliseconds | **4** |
| allocation | **bytes** | **524287** (512 KiB) |

Two gotchas worth internalising:

1. **In allocation mode `--interval` is a byte count, not a time.** It is a sampling rate: one
   sample recorded per N bytes allocated. `--alloc --interval 4` would be catastrophic.
2. **The stored value is microseconds.** `SamplerMetadata.interval` in the proto is in µs for
   execution mode, and spark's own display divides by 1000. A raw reading of `4000` means 4 ms.
   `scripts/lib/sparkprofile.py` does this conversion; a naive reader does not.

The background profiler uses a coarser 10 ms default.

## Tick-based filtering — `--only-ticks-over`

The lag-spike recipe. Instead of recording everything, record only samples that fall inside ticks
that ran longer than a threshold:

```
/spark profiler start --only-ticks-over 100
```

It needs a platform `TickHook`; without one spark answers *"Tick counting is not supported!"*
(so: not on proxies).

The two engines implement it differently, which explains a subtlety in the output:

- **Java engine** → `TickedJavaDataAggregator`. Samples are buffered per tick and committed only
  if that tick exceeded the threshold.
- **async engine** → `TickedAsyncDataAggregator` + `ExceedingTicksFilter`, which records the
  *time windows* of slow ticks and afterwards keeps only samples whose timestamps fall inside one:

```java
public void onTick(double duration) {
    if (duration > this.tickLengthThreshold) {
        long end = nanoTimeSource.getAsLong();
        long start = (long) (end - (duration * 1_000_000)); // ms to ns
        this.ticksOver.add(new ExceededTick(start, end));
        this.tickCounter.getAndIncrement();
    }
}
```

The count lands in the metadata as `number_of_included_ticks` alongside `tick_length_threshold`,
and both are reported by our reader. **If `number_of_included_ticks` is 0, the profile is empty
and that is the correct answer** — no tick was slow enough. That is a result, not a failure.

## Thread selection — and the trap

Two independent concepts, easy to confuse:

**`ThreadDumper` — which threads get sampled.**

| Flag | Dumper |
|---|---|
| *(none)* | `platform.getPlugin().getDefaultThreadDumper()` — usually the game thread |
| `--thread *` | `ThreadDumper.ALL` |
| `--thread <name>` (repeatable) | `ThreadDumper.Specific` |
| `--thread <pattern> --regex` | `ThreadDumper.Regex` |

**`ThreadGrouper` — how sampled threads are merged into trees.**

| Flag | Grouper | Effect |
|---|---|---|
| *(none)* | `BY_POOL` | `Worker-1`, `Worker-2` collapse into one `Worker (xN)` node |
| `--not-combined` | `BY_NAME` | every thread separately |
| `--combine-all` | `AS_ONE` | one tree for everything |

The pool regex is `^(.*?)[-# ]+\d+$` — a trailing number separated by space, `-` or `#`.

> ### ⚠ The default thread dumper can produce a completely empty capture
>
> Observed in this repository, reproducibly, on **Paper 1.21.1-133** with async-profiler, under
> real tick load, twice: `/spark profiler start --interval 4` with **no `--thread` flag** produced
> a valid `.sparkprofile` containing **zero `ThreadNode` entries**. The same server, same load,
> same capture length, with `--thread *` produced 43–47 threads and correct attribution.
>
> A capture that silently contains nothing is the worst possible failure mode for automation:
> it is indistinguishable from "your code is fast". So **Nodera always passes `--thread`
> explicitly** — `scripts/lib/spark.sh` defaults `NODERA_SPARK_THREADS` to `*`, and
> `SparkProfileBridge` hardcodes `--thread *` for the client. Do not remove it.
>
> The cost is that `*` also samples dozens of parked threads whose idle wall-clock time swamps
> the percentages; that is a *reporting* problem with a clean fix
> (`sparkprofile.py --thread 'Server thread'`), and a reporting problem is much better than a
> silently empty artifact.

## Other capture flags

| Flag | Effect |
|---|---|
| `--ignore-sleeping` | drop samples from threads in a sleeping/parked state. Gained async-profiler support in Nov 2024. |
| `--force-java-sampler` | use the Java engine even where async-profiler works — for comparing the two, or working around a native issue. |
| `--alloc` | allocation profiling instead of CPU. |
| `--alloc-live-only` | with `--alloc`, keep only objects not garbage-collected by the end of the profile — the leak-hunting mode. |

> **`--ignore-native` does not exist.** It was added in 2020 (`bdc2641e`) and later removed; it is
> absent from `SamplerBuilder` and `SamplerModule` at `f181ccf`. Some third-party guides still
> list it. Passing it does nothing.

## `-XX:+EnableDynamicAgentLoading`

Not a spark flag — a JVM flag spark needs, and the reason it appears in both
`nodera.neoforge-mod.gradle.kts` and `nodera_spark_jvm_args`.

To turn the string `dev.nodera.entity.Foo` from a stack frame into a `Class<?>` it can ask
questions about, spark uses `ClassFinder`. Its good implementation, `InstrumentationClassFinder`,
obtains an `Instrumentation` handle by loading a Java agent dynamically at runtime. Java 21+
deprecates that and prints a loud warning; where it is disallowed outright, spark

> "will gracefully fall back to using a different (albeit less effective) method"

— and "less effective" means frames that belong to a mod get attributed to nobody. Since
attribution is the entire reason we run this, the flag is passed unconditionally on every dev run
and every profiled server. It costs nothing when spark is absent.

---

**Previous:** [02 — Build System & Project Layout](./02-build-system.md) ·
**Next:** [04 — Commands & Permissions](./04-commands.md) ·
**Index:** [README](./README.md)
