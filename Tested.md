# Tested.md — module test status

<!-- AI-AGENT-INSTRUCTION: This file is updated on EVERY commit that changes test outcomes.
     Update the README module table at the same time so the two never drift. Compute "Last run"
     from the most recent `./gradlew check`. Keep emojis consistent with README:
     ✅ all green · 🚧 partial (some sub-systems stubbed) · ⏳ in progress · ❌ failing. -->

Status legend: ✅ passing · 🚧 partial (passing but incomplete scope) · ⏳ in progress · ❌ failing

| Module | Responsibility | Tests | Failures | Skipped | Status | Last run |
|---|---|---:|---:|---:|:---:|---|
| `core` | domain types, crypto, canonical encoding (frozen wire/hash contract) | 85 | 0 | 0 | ✅ | 2026-07-17 |
| `simulation` | deterministic region engine (determinism property tests) | 28 | 0 | 7 | ✅ | 2026-07-17 |
| `protocol` | wire messages, MessageCodec, ChunkedStreams (zstd) | 23 | 0 | 0 | ✅ | 2026-07-17 |
| `consensus` | quorum, votes, equivocation, adaptive spot-checks | 23 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-api` | `PeerTransport` seam | 9 | 0 | 0 | ✅ | 2026-07-17 |
| `storage-api` | `WorldStore` interfaces (stub — Task 9 fills it) | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `testkit` | `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-neoforge` | NeoForge payload relay transport (skeleton; relay deferred to Task 4) | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `neoforge-mod` | `@Mod` entrypoints (common/dedicated/client) — compiles, `runServer`/`runClient` deferred | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `storage-rocksdb` | full-archive RocksDB store | — | — | — | ⬜ | — |
| `storage-client` | bounded/quota'd client store | — | — | — | ⬜ | — |
| `peer-runtime` | `PeerRuntime`, discovery, committees, archival, sync | — | — | — | ⬜ | — |
| `transport-libp2p` | direct P2P behind `PeerTransport` | — | — | — | ⬜ | — |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | — | — | ⬜ | — |
| **TOTAL (implemented modules)** | | **185** | **0** | **7** | ✅ | 2026-07-17 |

> The 7 skipped tests are `simulation/ForbiddenApiTest`. The repo now compiles to Java 21
> bytecode (v65) via `--release 21`, so ArchUnit 1.3's bundled ASM can once again parse the
> class files — re-enabling this test is unblocked and tracked as a follow-up (it still runs
> under the JDK 25 test JVM, so a dedicated verification pass is needed before flipping it on).
> Determinism is meanwhile enforced by `simulation/DeterminismPropertyTest`.

<!-- AI-AGENT-INSTRUCTION: When a module goes red, flip its emoji to ❌, open a `bug` issue, and do
     NOT commit the regression. When fixed, flip back to ✅ and close the issue with `fixes #N`. -->
