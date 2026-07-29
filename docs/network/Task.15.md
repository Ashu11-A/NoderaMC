# Network Task 15 — Structural benchmarking and the structural code report

<!-- AI-AGENT-INSTRUCTION: Two rules here are load-bearing and must survive every edit.
     (1) A benchmark result is only evidence against a BASELINE measured on the same machine class;
     never turn the CI regression check into a hard merge gate on a shared runner, and never widen
     the regression threshold to make a run green — re-measure instead.
     (2) The structural report's budgets (fixtures/structure/budget.json) RATCHET DOWNWARDS ONLY.
     Raising a limit to make a build pass is forbidden: the numbers are code that ships and does
     nothing, and the whole deliverable is worthless the first time one is raised for convenience.
     Keep this header's status accurate. -->

**Status:** ✅ DONE — the four benchmark lanes, the ranked report with load-scaling and baseline
diff, the bytecode structural report, the JDWP worker probe, the ratchet, and the `benchmarks`
workflow all landed and ran green
**Category:** network · **Owns:** — · **Last audit:** 2026-07-29
**Depends on:** [network 2](Task.2.md), [network 4](Task.4.md), [network 5](Task.5.md),
[worker 1](../worker/Task.1.md)
**Consumed by:** every task that changes `java/peer` or `java/worker`

---

## Goal

Two questions get answers that come from measurement instead of opinion:

1. **Where does the peer system spend its time?** Peer discovery, chunk synchronisation, and the
   always-on worker's internal latency are measured per stage, ranked by cost, checked for how they
   grow with load, and diffed against a committed baseline.
2. **What does the tree contain that never runs?** The compiled bytecode of every module is read for
   its reference graph and for work that costs more than it looks like it does; the real
   `nodera-headless` worker is then run under a debugger to see which of that code actually
   executes. Both halves are ratcheted so the answers cannot quietly get worse.

## Status detail

Landed on 2026-07-29. Both lanes run locally and in the `benchmarks` GitHub Actions workflow.

### What the benchmark lane measures

| Lane | Class | What it covers |
|---|---|---|
| peer discovery | `DiscoveryBenchmark` | route parse, directory ingest, liveness filter, warm-start cache save/load, gateway election — at 16 / 256 / 1024 peers |
| chunk synchronisation | `ChunkSyncBenchmark` | snapshot → blob → piece plane → manifest root → rarest-first order → holder choice → verify → reassemble → state root, at 8 KiB / 24 KiB / 64 KiB pieces and 3 / 24 holders |
| wire codec | `WireBenchmark` | canonical encode/decode of membership, keep-alive, content chunk, heartbeat, plus the SHA-256 every frame pays |
| internal runtime latency | `RuntimeBenchmark` | keep-alive ingestion, local progress publication, per-message metering, the diagnostics sample behind `NODERA-STATE` |

The report (`scripts/bench-report.py`) does the three things a raw JMH table cannot: it **ranks**
every measurement so the bottleneck is the top row, it computes **cost growth against each `@Param`**
(cost ratio ÷ input ratio, so superlinear work is visible before the network is big enough to hurt),
and it **diffs against `fixtures/bench/baseline.json`**, calling a regression only when a result is
both >25% slower and outside its own error bars.

### What the structural report finds

Three verdicts at three confidence levels — *never referenced*, *only tests and benchmarks call it*,
*no entry point can reach it* — plus in-loop cost findings (allocation, boxing, string building,
linear scans, regex compilation, monitor acquisition) and methods past HotSpot's 8000-byte
`DontCompileHugeMethods` limit.

The debugger half is what makes the cost findings actionable: a quadratic loop nobody enters is not
a bottleneck. Section 1 of the report is the intersection — code the debugger watched execute whose
bytecode carries a finding — ranked by entries × severity.

### What the first run found

- `dev.nodera.core.action.*Action#decode(CanonicalReader)` — eight full-frame decoders with no
  caller anywhere; production dispatches through `GameAction.decode` into `decodeBody`.
- `dev.nodera.distribution.WorldArchive.Prepared` — a record referenced by nothing but itself.
- 33 classes that only tests and benchmarks reference, across `engine`, `peer`, `transport`, and
  `storage` — the "built but never called" shape this repository keeps finding by hand.
- 7 severe in-loop findings, all `List.contains`/`List.remove` inside a loop (quadratic in the
  collection size), including one on the worker's `NODERA-CONFIG` path.

Those are recorded, not fixed, by this task: the deliverable is the instrument and the ratchet that
stops the numbers rising. Each finding is now a candidate for its owning category's task.

### Why the analysis is deliberately conservative

Anything whose caller could be outside our bytecode is never called dead: methods satisfying an
external interface (JDK, NeoForge, JUnit), compiler-generated members, mixins (wired by JSON and
applied by a transformer), constant holders (whose `static final` values javac inlines into every
caller), private no-argument constructors, and every member of a class whose supertypes are not on
the classpath. Those are counted as *unanalysable* so the report never claims more coverage than it
has.

Two rules were added because the running worker contradicted the static verdict, which is exactly
what the probe is for: references resolve **through supertypes** (nothing in the bytecode ever names
the anonymous class implementing an interface), and a `<clinit>` is an entry point when its class is
used (every `static final` singleton is constructed there). After both, section 4.3 of the report —
"static analysis contradicted by the running process" — is empty.

## Dependencies

- **network 2 / 4 / 5** — the runtime, distribution, and discovery code under measurement.
- **worker 1** — `HeadlessPeerMain` and the control verbs the probe drives.

## Deliverables

| # | Deliverable | Where |
|---|---|---|
| 1 | Four JMH benchmark lanes | `java/peer/src/jmh/java/dev/nodera/bench/` |
| 2 | JMH wiring + `benchmarkReport` task | `java/peer/build.gradle.kts` |
| 3 | Ranked report with scaling + baseline diff | `scripts/bench-report.py` |
| 4 | Committed benchmark baseline | `fixtures/bench/baseline.json` |
| 5 | Bytecode reference graph + cost scanner | `java/worker/src/test/java/dev/nodera/structure/CodeGraph.java` |
| 6 | Reachability / dead-code verdicts | `.../structure/DeadCodeAnalysis.java` |
| 7 | JDWP probe of the real worker | `.../structure/HeadlessDebugProbe.java` |
| 8 | Report renderer + JSON | `.../structure/StructureReport.java` |
| 9 | The suite and the ratchet | `.../structure/StructuralReportTest.java`, `fixtures/structure/budget.json` |
| 10 | `structureReport` task | `java/worker/build.gradle.kts` |
| 11 | CI workflow (PR, main, weekly, manual) | `.github/workflows/benchmarks.yml` |

## Design

### Why the benchmarks live in `:peer` and the report in `:worker`

The benchmarks are a `jmh` source set inside `:peer` because everything they measure is a `:peer`
class: a separate module would have to depend on it anyway, would drift, and would tempt a benchmark
to re-implement the code under measurement.

The structural report lives in `:worker`'s test source set because it needs two things at once — the
compiled output of **every** module (to know who calls what) and the ability to launch the real
worker process (to know what runs). `:worker` is the only module that can have both, and nothing
depends on it, so pointing it at the whole tree creates no cycle.

### Why a debugger and not a coverage agent

A coverage agent needs the build to instrument classes; a sampling profiler says where time went but
not which services were ever touched. JDI attaches over JDWP to the **unmodified** `nodera-headless`
process — the same binary the companion app supervises — and reports class-prepare and method-entry
events. Method-entry tracing deoptimises the filtered classes, so the probe reports **counts and
coverage only, never durations**: speed is the benchmark lane's job, on an uninstrumented JVM.

### Why `:testing` main is scanned as test code

`java/testing`'s main source set is a library *for* tests. Counting it as production would let a test
harness keep production code looking alive — the exact illusion the report exists to remove.

## Files

```
java/peer/build.gradle.kts                                  # jmh plugin, lanes, benchmarkReport
java/peer/src/jmh/java/dev/nodera/bench/
    BenchFixtures.java  DiscoveryBenchmark.java  ChunkSyncBenchmark.java
    WireBenchmark.java  RuntimeBenchmark.java    README.md
java/worker/build.gradle.kts                                # structureReport task, tag exclusion
java/worker/src/test/java/dev/nodera/structure/
    CodeGraph.java  DeadCodeAnalysis.java  HeadlessDebugProbe.java
    StructureReport.java  StructuralReportTest.java  README.md
scripts/bench-report.py
fixtures/bench/baseline.json
fixtures/structure/budget.json
.github/workflows/benchmarks.yml
```

## Testing

```bash
./gradlew :peer:jmh -Pbench.quick            # ~8 min, wider error bars
./gradlew :peer:benchmarkReport              # + build/reports/nodera/BENCHMARKS.md
python3 scripts/bench-report.py --check      # regression gate against the baseline
python3 scripts/bench-report.py --write-baseline

./gradlew :worker:structureReport                          # analysis + debugger probe
./gradlew :worker:structureReport -Pstructure.debug=false  # static half only (~5 s)
```

`:worker:structureReport` is tagged `structure` and excluded from `:worker:test`, so `./gradlew
check` is unchanged in scope and runtime. The task declares every module's `classes`/`testClasses`
as dependencies, which is what makes "a module was not compiled, so its callers vanished" impossible
rather than merely unlikely.

Assertions, not printouts: the probe must observe the worker loading classes, executing methods, and
answering all twelve control verbs; the analysis must reach over 1000 methods; every budget must
hold. The CI job additionally re-reads `runtime-profile.json` and fails if the probe observed almost
nothing — a claim in a report is worth what the check behind it is worth.

## Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| 1 | Discovery, chunk-sync, wire, and runtime latency are each measured per stage | 79 measurements across four lanes, `build/reports/nodera/BENCHMARKS.md` |
| 2 | The report ranks bottlenecks rather than dumping scores | § "Where the time goes" |
| 3 | Superlinear growth is detected, not inferred | § "How cost grows with load", cost ratio ÷ input ratio per `@Param` |
| 4 | A regression against the baseline is reported and can fail a check | `bench-report.py --check`, `fixtures/bench/baseline.json` |
| 5 | Dead code is detected with stated confidence levels | § 2 of `STRUCTURE.md`, three verdicts |
| 6 | Poorly optimised code is identified from the emitted bytecode | § 3, in-loop findings + JIT limits |
| 7 | The headless peer is used in debugger mode for deeper information | `HeadlessDebugProbe`, JDWP attach, § 4 |
| 8 | The numbers cannot silently get worse | `fixtures/structure/budget.json` ratchet, asserted in the suite |
| 9 | Both reports are generated automatically | `.github/workflows/benchmarks.yml` (PR, main, weekly, dispatch) |

## Limitations

None opened. The known boundaries are stated where they are relevant rather than hidden:

- Benchmark numbers are machine-dependent; the baseline is evidence on the same machine class and
  advisory across machines. The CI regression check is therefore reported, not merge-blocking.
- The probe's scenario is read-only (it drives the twelve control verbs the app sends), so hosting,
  seeding, and join paths show as "never loaded" in coverage. Extending the scenario is additive.
- Method bytecode size is estimated from instruction encodings; ASM's tree API does not expose
  `code_length`. The estimate is within a few percent, which is all the two thresholds need.
