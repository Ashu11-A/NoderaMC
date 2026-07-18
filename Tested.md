# Tested.md — module test status

<!-- AI-AGENT-INSTRUCTION: This file is updated on EVERY commit that changes test outcomes.
     Update the README module table at the same time so the two never drift. Compute "Last run"
     from the most recent `./gradlew check`. Keep emojis consistent with README:
     ✅ all green · 🚧 partial (some sub-systems stubbed) · ⏳ in progress · ❌ failing. -->

Status legend: ✅ passing · 🚧 partial (passing but incomplete scope) · ⏳ in progress · ❌ failing

| Module | Responsibility | Tests | Failures | Skipped | Status | Last run |
|---|---|---:|---:|---:|:---:|---|
| `core` | domain types, crypto, canonical encoding (frozen wire/hash contract) | 92 | 0 | 0 | ✅ | 2026-07-17 |
| `simulation` | deterministic region engine (determinism property tests) | 28 | 0 | 0 | ✅ | 2026-07-17 |
| `protocol` | wire messages, MessageCodec, ChunkedStreams (zstd) | 28 | 0 | 0 | ✅ | 2026-07-17 |
| `consensus` | quorum, votes, equivocation, adaptive spot-checks | 26 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-api` | `PeerTransport` seam | 9 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-socket` | real TCP `PeerTransport` (direct P2P data plane) | 4 | 0 | 0 | ✅ | 2026-07-17 |
| `storage-api` | `WorldStore` interfaces (stub — Task 9 fills it) | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `testkit` | `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | 0 | 0 | ✅ | 2026-07-17 |
| `peer-runtime` | `PeerRuntime`, membership, heartbeat, deterministic gateway migration, `MeteredPeerTransport` + `DiagnosticsIT` (continuity beta) | 14 | 0 | 0 | 🚧 | 2026-07-17 |
| `diagnostics` | Minecraft-free telemetry: TrafficMeter/RateWindow/MessageCounters, TelemetrySnapshot, ZoneClassifier, DiagnosticsView (Task 18) | 35 | 0 | 0 | ✅ | 2026-07-17 |
| `shadow-validation` | Phase 1 shadow lane (Minecraft-free): WorkerRuntime, ReplicaStore, SnapshotDeltaApplier, ShadowWorker/Coordinator, ServerRecompute, DivergenceTracker, InterferenceProbe + `ShadowValidationIT` (Task 5) | 24 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-neoforge` | NeoForge payload relay transport (skeleton; relay deferred to Task 4) | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `neoforge-mod` | `@Mod` entrypoints + bootstrap-peer wiring, redesigned `/nodera` diagnostics tree + `/noderac` + HUD surfaces, session payload — compiles + jar; `runServer`/`runClient` deferred | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `storage-rocksdb` | full-archive RocksDB store | — | — | — | ⬜ | — |
| `storage-client` | bounded/quota'd client store | — | — | — | ⬜ | — |
| `transport-libp2p` | NAT-traversing P2P behind `PeerTransport` (supersedes `transport-socket` for cross-NAT) | — | — | — | ⬜ | — |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | — | — | ⬜ | — |
| **TOTAL (implemented modules)** | | **277** | **0** | **0** | ✅ | 2026-07-17 |

> `simulation/ForbiddenApiTest` is now **re-enabled** (0 skipped): the repo compiles to Java 21
> bytecode (v65) via `--release 21`, so ArchUnit 1.3's bundled ASM parses the classes again. The
> ArchUnit determinism rules (no wall clocks / entropy / IO in `dev.nodera.simulation`) run in CI
> once more, alongside `simulation/DeterminismPropertyTest`.
>
> Test growth (185 → 199) is the adversarial-review remediation: added `CanonicalReaderBoundsTest`
> (allocation-DoS bound), `TypeTagsTest` (tag registry snapshot), `MajorityQuorumPolicy` liveness
> regressions, `RegionCommittee` equals-order, and `ChunkedStreams`/`StreamChunk` validation.
>
> Test growth (199 → 211) is the **P2P session-continuity beta**: `SocketPeerTransport` round-trip +
> disconnect detection (4, `transport-socket`), `GatewayElection` determinism (6) plus the
> loopback failover cycle (1) and the **real-TCP** `SessionContinuityIT` (1, `peer-runtime`), and the
> five appended membership tags in `MessageCodecTypeTagTest`. `SessionContinuityIT` is the executable
> stand-in for the deliverable's manual scenario — two headless player peers stay meshed over a
> direct socket after the bootstrap peer is killed and re-elect the same successor gateway.
>
> Test growth (211 → 243) is **Task 18 — in-game observability & diagnostics HUD**: a new
> Minecraft-free `diagnostics` module (30 tests — `TrafficMeter`/`RateWindow`/`MessageCounters`,
> `ZoneClassifier` with negative coords, `ViewBuilder` Panel/Row/Cell + Semantic, `DiagnosticsCollector`
> rate + health derivation), a `MessageCodec.typeName`/`KNOWN_TAGS` registry snapshot (+1 `protocol`),
> the `MeteredPeerTransport` decorator + per-type `PeerRuntime` counters + the loopback
> `DiagnosticsIT` (+1 `peer-runtime` — asserts real tx/rx bytes+frames, `SessionKeepAlive` in the
> per-type breakdown, and correct member/gateway/epoch). The `Palette` Semantic→colour totality is
> enforced at compile time by the exhaustive enum `switch`, not a runtime test.
>
> Test growth (253 → 277) is **Task 5 — Phase 1 shadow validation** (+24, new Minecraft-free
> `shadow-validation` module): `WorkerRuntimeTest` (INACTIVE/ACTIVE/STOPPED lifecycle, off-thread
> determinism, two-runtime root equality), `SnapshotDeltaApplierTest` (applied delta re-hashes to the
> engine's own root — the delta-transports-the-transition invariant — plus CAS drift + version-mismatch
> guards), `ReplicaStoreTest` (LRU eviction bound), `ShadowWorkerTest` (Computed vs Resync on
> missing/stale replica), `ServerRecomputeTest` (a deliberately flaky engine trips the intra-JVM
> `NondeterminismException` self-check), `DivergenceTracker`/`InterferenceProbe` tests, and the
> headless `ShadowValidationIT` — 3 worker runtimes × 250 random place/break batches with **zero
> divergence**, and a lying worker (corrupted root) caught + its region re-snapshotted. This is the
> executable stand-in for Task 5's manual multi-client soak; the NeoForge capture mixins, live
> `runClient` soak, and bandwidth/interference numbers remain deferred (Phase 0 pattern).
>
> Test growth (243 → 253) is the **Task 18 adversarial-review remediation**: a 6-dimension
> find→verify review workflow (23 agents, 17 confirmed findings, 0 refuted) surfaced a blocker —
> `ViewBuilder.formatBytes`/`formatRate` threw `StringIndexOutOfBoundsException` for any byte value
> in [1024, 2047] (the tab/boss-bar/net HUD hot path) — plus the net bar being unreachable, a stale
> tab list after `/nodera hud tab off`, and dead code. All 17 were fixed; new tests
> (`MeteredPeerTransportTest`, `ViewBuilder` `formatRate`/`serverPanel`/populated `regions`+`entities`,
> `ZoneClassifier`↔`RegionBounds` consistency, deterministic per-type TX in `DiagnosticsIT`) close
> the coverage gaps that hid the blocker.

<!-- AI-AGENT-INSTRUCTION: When a module goes red, flip its emoji to ❌, open a `bug` issue, and do
     NOT commit the regression. When fixed, flip back to ✅ and close the issue with `fixes #N`. -->
