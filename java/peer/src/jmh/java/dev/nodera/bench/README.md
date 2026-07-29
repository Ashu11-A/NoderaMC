# `dev.nodera.bench` — the peer system's benchmark lanes

<!-- AI-AGENT-INSTRUCTION: These benchmarks are measurement, not tests. Two rules must survive every
     edit. (1) Never change a fixture to make a number look better — a benchmark whose input drifts
     measures the input. (2) Never delete or narrow a lane to speed up CI; use `-Pbench.quick`, which
     shortens iterations without dropping measurements. Adding a benchmark class means adding its
     lane to LANES in scripts/bench-report.py, or the report silently under-covers the system. -->

The JMH source set of `:peer` (`src/jmh/java`). It measures the three costs that decide how a session
feels, plus the codec they all pay:

| Class | Lane | What it covers |
|---|---|---|
| `DiscoveryBenchmark` | peer discovery | route parse · directory ingest · liveness filter · warm-start cache save/load · gateway election, at **16 / 256 / 1024 peers** |
| `ChunkSyncBenchmark` | chunk synchronisation | snapshot → blob → piece plane → manifest root → rarest-first order → holder choice → verify → reassemble → state root, at **8 KiB / 24 KiB / 64 KiB** pieces and **3 / 24** holders |
| `WireBenchmark` | wire codec | canonical encode/decode of membership, keep-alive, content chunk, heartbeat + the SHA-256 every frame pays |
| `RuntimeBenchmark` | internal runtime latency | keep-alive ingestion · local progress · per-message metering · the diagnostics sample behind `NODERA-STATE` |

`BenchFixtures` builds every input from its index with plain arithmetic, so two runs on two machines
feed the code byte-identical work.

## Running

```bash
./gradlew :peer:jmh -Pbench.quick          # ~8 min, 1 fork x 3 x 500 ms, wider error bars
./gradlew :peer:jmh                        # 2 forks x 8 x 1 s
./gradlew :peer:jmh -Pbench.include=Discovery
./gradlew :peer:benchmarkReport            # jmh + build/reports/nodera/BENCHMARKS.md
```

The report ranks every measurement, computes how cost grows against each `@Param`, and diffs against
`fixtures/bench/baseline.json`. See [`docs/network/Task.15.md`](../../../../../../../../docs/network/Task.15.md).

## Why these live in `:peer` and not in a module of their own

Everything measured here is a `:peer` class. A separate module would depend on this one anyway, drift
out of step with it, and tempt a benchmark to re-implement the code under measurement. A `jmh` source
set compiles against the same output the worker ships and is invisible to every consumer of the
library.

## What a benchmark here may not do

- **Open a socket.** Discovery latency in the field is the network; a loopback benchmark would
  measure the kernel. What is measured is the per-peer CPU work done with each answer once it lands.
- **Create files per invocation.** `cacheSave`/`cacheLoad` reuse one file created in `@Setup`: a temp
  file per operation measures the filesystem's create/unlink path, which swamped the encode/decode
  cost this lane exists to watch.
- **Depend on a test fixture.** Test classes are not on this source set's compile path on purpose —
  a benchmark that shares a builder with a test starts changing when the test does.
