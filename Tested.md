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
| `protocol` | wire messages, MessageCodec, ChunkedStreams (zstd) | 27 | 0 | 0 | ✅ | 2026-07-17 |
| `consensus` | quorum, votes, equivocation, adaptive spot-checks | 26 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-api` | `PeerTransport` seam | 9 | 0 | 0 | ✅ | 2026-07-17 |
| `transport-socket` | real TCP `PeerTransport` (direct P2P data plane) | 4 | 0 | 0 | ✅ | 2026-07-17 |
| `storage-api` | `WorldStore` interfaces (stub — Task 9 fills it) | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `testkit` | `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | 0 | 0 | ✅ | 2026-07-17 |
| `peer-runtime` | `PeerRuntime`, membership, heartbeat, deterministic gateway migration (continuity beta) | 8 | 0 | 0 | 🚧 | 2026-07-17 |
| `transport-neoforge` | NeoForge payload relay transport (skeleton; relay deferred to Task 4) | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `neoforge-mod` | `@Mod` entrypoints + bootstrap-peer wiring, `/nodera` cmd, session payload — compiles + jar; `runServer`/`runClient` deferred | 1 | 0 | 0 | 🚧 | 2026-07-17 |
| `storage-rocksdb` | full-archive RocksDB store | — | — | — | ⬜ | — |
| `storage-client` | bounded/quota'd client store | — | — | — | ⬜ | — |
| `transport-libp2p` | NAT-traversing P2P behind `PeerTransport` (supersedes `transport-socket` for cross-NAT) | — | — | — | ⬜ | — |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | — | — | ⬜ | — |
| **TOTAL (implemented modules)** | | **211** | **0** | **0** | ✅ | 2026-07-17 |

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

<!-- AI-AGENT-INSTRUCTION: When a module goes red, flip its emoji to ❌, open a `bug` issue, and do
     NOT commit the regression. When fixed, flip back to ✅ and close the issue with `fixes #N`. -->
