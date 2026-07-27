# 13 — Reading a Profile

The runbook. Capture, read, conclude — with a real Nodera capture worked through, and the traps
that make people conclude the wrong thing.

## Capture

### The three recipes

**Steady load — "what does this cost all the time?"**

```bash
NODERA_SPARK=1 scripts/e2e-mobs.sh
```

Records every tick. The default, and the right one for "is this feature affordable".

**Lag spikes — "what causes the stutter?"**

```bash
NODERA_SPARK=1 NODERA_SPARK_TICKS_OVER=100 scripts/e2e-mesh-soak.sh
```

Records only samples from ticks that exceeded 100 ms. A steady-load profile buries a spike that
happens twice a minute; this one contains nothing else. If the result is empty, check
`number_of_included_ticks` on the header line — zero means no tick was ever that slow, which is a
result.

**Memory pressure — "who is causing the GC churn?"**

Allocation profiling, driven by hand rather than by the harness:

```
/spark profiler start --alloc --thread *
… load …
/spark profiler stop --save-to-file
```

The tree values become **bytes allocated**, not milliseconds. Add `--alloc-live-only` to keep only
objects that survived to the end of the profile — the leak-hunting variant.

### The dedicated profiling suite

```bash
scripts/e2e-profile.sh
ls run/results/e2e-profile/*/spark/
```

Produces `SUMMARY.txt` (both views of every capture), the `.sparkprofile` files themselves,
`.sources.txt` sidecars, and `health-*.txt`.

## Read

```bash
python3 scripts/lib/sparkprofile.py <profile>                          # everything
python3 scripts/lib/sparkprofile.py <profile> --thread '^Server thread$' # the tick
python3 scripts/lib/sparkprofile.py <profile> --source nodera --top 25   # our frames
```

Or drag the file onto `https://spark.lucko.me/` — parsed in the browser, uploaded nowhere.

### Read the header first

```
engine=async-profiler  mode=execution  interval=4ms
threads=1/95  sampled=69304.0 ms  ticks=1392
```

Four checks before you look at anything else:

| Check | Why |
|---|---|
| `engine=async-profiler` | `built-in java` means safepoint bias. Numbers are not comparable with an async capture. |
| `mode=execution` | In `allocation` mode every number is bytes, not milliseconds. |
| `threads=N/M` | The denominator. `1/95` and `95/95` give wildly different percentages for the same file. |
| `ticks` / `included` | With `--only-ticks-over`, an included count of 0 means the profile is empty *and correct*. |

## The worked example

A real capture: `scripts/e2e-profile.sh` R1, the NeoForge dedicated server under a two-player
entity-lane drive with chickens spawning and a player crossing region boundaries for 60 seconds.

### All 95 threads

```
threads=95/95  sampled=7871348 ms  ticks=1392

THREAD                                 SAMPLED ms        %
Worker-Main (x15)                         1040356    13.2%
ForkJoinPool.commonPool-worker (x1         993748    12.6%
GC Thread (x13)                            902020    11.5%
…
(87 more)                                 3669688    46.6%

SOURCE                            SELF ms    %SELF   SUBTREE ms  HOTTEST OWN FRAME
(unattributed)                    7871168   100.0%          0.0  libc.so.6.__syscall_cancel_arch_start
spark                                80.0     0.0%        220.0  me.lucko.spark…AbstractNodeExporter
neoforge                             68.0     0.0%       1064.0  net.neoforged…IFluidExtension
nodera                               32.0     0.0%       138948  dev.nodera.mod.server.entity.RegionDriveDebug
```

7.87 **million** milliseconds sampled from a 60-second capture. That is not an error: 95 threads ×
60 s of wall-clock sampling, most of them parked. Everything real rounds to 0.0%.

### The same file, the tick thread only

```
threads=1/95  sampled=69304.0 ms

SOURCE                            SELF ms    %SELF   SUBTREE ms  HOTTEST OWN FRAME
(unattributed)                    69212.0    99.9%          0.0  libc.so.6.__syscall_cancel_arch_end
neoforge                             64.0     0.1%        884.0  net.neoforged…IFluidExtension
nodera                               20.0     0.0%          4.0  dev.nodera.mod.server.entity.RegionDriveDebug
spark                                 8.0     0.0%          0.0  me.lucko.spark…SparkTickStatistics
```

### What it actually says

Read the two together and there is a real finding in the gap between them:

- **On the server tick thread Nodera costs almost nothing** — 20 ms of self time out of 69 s, and a
  subtree of only 4 ms. Whatever the entity lane is doing, it is not doing it on the tick.
- **Across all threads Nodera's subtree is 138,948 ms.** That is nearly *four orders of magnitude*
  larger than its tick-thread subtree. The work is real and it is substantial — it is simply
  happening on worker pools, which is exactly where a design that offloads validation and capture
  off the tick is supposed to put it.
- **The hottest Nodera frames are `RegionDriveDebug.trackRegions` and `TabListRenderer.header`** —
  both *diagnostics*, not the lane itself. In a 60-second capture of an entity-lane drive, our most
  expensive tick-thread code is the code that draws the debug tab list.

That last one is the kind of conclusion this integration exists to produce, and it is not one
anybody would have guessed.

### What it does *not* say

It does not say Nodera is free. A wall-clock profile of a mostly-idle 95-thread server has a
denominator dominated by parked threads, and 60 seconds of two players is a light drive. It says:
*on this workload, on the tick thread, our cost is dominated by debug rendering.* Widening that to
"Nodera is cheap" would be exactly the overreach [05](./05-source-attribution.md) warns about.

## The traps

**Quoting a percentage without its denominator.** `nodera 0.0%` across 95 threads and `nodera 0.0%`
on the tick thread are different claims. Always say which.

**Reading SELF when you meant SUBTREE.** SELF is "our bytecode executing". SUBTREE is "work we
caused, including the vanilla calls we made". A method that calls `getBlockAt()` in a loop has tiny
SELF and enormous SUBTREE, and the cost is genuinely ours. In the example above the two differ by
four orders of magnitude, and the interesting number is SUBTREE.

**Treating "unattributed" as overhead.** It is Minecraft, the JDK, native frames, lambdas, and — on
NeoForge — every mixin. 99.9% unattributed is a healthy profile, not a broken one.

**Concluding "fast" from "absent".** A source with no samples might be cheap, or might never have
loaded, or might be mixin-injected and therefore invisible. The `installed but never sampled:` line
distinguishes the first two. Nothing distinguishes the third on NeoForge.

**Comparing across engines.** Check `engine=` on both files. Safepoint bias systematically
under-reports tight optimised loops.

**Comparing across capture lengths or thread sets.** Compare percentages within a view, never
absolute milliseconds across runs of different duration.

**Profiling an idle server.** An idle tick is cheap for everyone. If the capture ran while nothing
happened, it measured nothing — which is why R1 keeps summoning mobs and teleporting a player
across region boundaries *for the whole capture*.

**Taking a heap dump mid-capture.** `/spark heapdump` pauses the JVM. The profile will faithfully
record the pause and tell you nothing.

## Isolating a regression

The comparison that actually works, because it holds everything else fixed:

```bash
git checkout <good-ref>
NODERA_SPARK=1 scripts/e2e-mobs.sh
cp run/results/e2e-mobs/*/spark/*.sparkprofile /tmp/before.sparkprofile

git checkout <suspect-ref>
NODERA_SPARK=1 scripts/e2e-mobs.sh
cp run/results/e2e-mobs/*/spark/*.sparkprofile /tmp/after.sparkprofile

for f in /tmp/before /tmp/after; do
  echo "== $f"
  python3 scripts/lib/sparkprofile.py $f.sparkprofile --thread '^Server thread$' --source nodera --top 20
done
```

Same suite, same drive, same machine, same capture length. Then compare the **ranked frame list**
rather than the absolute numbers — a frame that moved up the list is the signal, and it survives
the run-to-run noise that absolute milliseconds do not.

## When the profile is empty

In order of likelihood:

1. **No `--thread` flag.** The default dumper has produced captures with zero threads
   ([03](./03-profiler-engines.md)). Our tooling always passes `--thread *`; a hand-typed command
   might not.
2. **`--only-ticks-over` with no qualifying tick.** Check `number_of_included_ticks`. This is a
   correct empty profile.
3. **Stopped too soon.** A capture shorter than a few seconds may contain nothing useful.
4. **The profiler never started.** Remember the RCON reply is empty either way
   ([04](./04-commands.md)) — check `activity.json`, which records every capture that completed.

## When the Sources view is empty but threads were sampled

`createClassSourceLookup()` threw, and spark silently substituted `NO_OP`. Grep the server log for
`Failed to create ClassSourceLookup`. Also confirm `-XX:+EnableDynamicAgentLoading` was passed —
without it, class resolution degrades and attribution thins out rather than disappearing entirely.

---

**Previous:** [12 — Nodera on Paper & Folia](./12-nodera-paper-folia.md) ·
**Index:** [README](./README.md)
