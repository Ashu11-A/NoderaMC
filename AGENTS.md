# AGENTS.md — NoderaMC

## Repo layout (polyglot monorepo; unified Java API since issue #30, 2026-07-21)
- `java/<module>/` — **exactly seven Gradle modules** (plus `build-logic`): `core` · `engine` ·
  `transport` · `storage` · `peer` · `testing` · `neoforge-mod`. The old fine-grained modules
  merged into them with **packages unchanged** (mapping: `docs/Task.0.md` §5) — e.g.
  `./gradlew :engine:test` runs what used to be `:simulation` + `:consensus` + `:coordinator` +
  `:committee` + `:shadow-validation` + `:fallback`.
- `rust/` — cargo workspace: `nodera-codec` (canonical-encoding port, Task 27),
  `nodera-tracker` (Task 28), `nodera-rendezvous` (Task 29). Channel pinned in
  `rust/rust-toolchain.toml`; crate versions pinned in the workspace `Cargo.toml`.
- `fixtures/wire/` — committed golden canonical frames. Java emits them
  (`transport`'s `WireFixtureTest`), Rust decodes + re-encodes them byte-exactly. **Never edit a
  fixture by hand**: a byte change there is a wire-contract change.

## Build & test (both toolchains — one gate)
- `./gradlew check`        — compile + all Java unit tests (the gate)
- `./gradlew build`        — check + assemble jars
- `./gradlew :core:test`   — one module's tests (substitute module name)
- `./gradlew check --rerun-tasks` — force tests to re-run (ignore up-to-date caching)
- `cd rust && cargo test`  — Rust unit tests + the cross-language fixture/tag-mirror conformance
- `cd rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings` — Rust lint gate
- `scripts/dev.sh --build-only` — compile both toolchains + collect artifacts (2 binaries + the jar) into `build/`; the CI `release-latest` workflow runs this on every push and attaches them to a rolling `latest` prerelease
- `scripts/dev.sh --test` — build both toolchains + run the full gate (no server to start; Task 30 retired it)
- `scripts/dev.sh` — build everything, then run the two infrastructure services (tracker + rendezvous) from `build/`, health-checked; worlds are hosted by a player's client (pause-menu "Share"), not a dedicated server. `--install-mod` drops the jar into `~/.minecraft/mods` for a real-client test

A red cargo job blocks a commit exactly like a red `./gradlew check`: the Rust services speak the
same frozen wire contract, so a codec regression is a consensus regression.

## Environment notes
- Host JDK is **25**; Task 0 pins Java 21. All modules compile with `--release 21` (Java 21
  bytecode, v65) so the `org.gradle.jvm.version` attribute is consistent across module
  boundaries — the NeoForge modules are forced to a Java 21 toolchain by ModDevGradle, so a
  25/21 mismatch breaks project dependency resolution. The test JVM is still the host JDK 25.
- `simulation/ForbiddenApiTest` is enabled: all modules emit Java-21 bytecode (v65), which ArchUnit
  1.3 can parse even though tests execute on host JDK 25. It enforces the simulation ban on clocks,
  entropy, IO, and concurrency APIs alongside `simulation/DeterminismPropertyTest`.

## Layering (Task 0 §7; module boundaries unified by issue #30 — inter-PACKAGE rules unchanged)
- Module graph: `core` → JDK only. `engine`/`transport`/`storage` → `core`. `peer` → `core` +
  `transport` + `storage`. `testing` → `core` + `engine` + `transport`. `neoforge-mod` → all of
  them (the ONLY module with Minecraft/NeoForge types).
- `core` — Task 23's `core/crypto/symmetric` follows the JDK-only rule: AES-GCM-256, `ContentKey`,
  the `PasswordKeyDerivation` seam, and PBKDF2-HMAC-SHA256 use JDK crypto only.
- `engine` (`dev.nodera.simulation` / `consensus` / `shadow` / `coordinator` / `committee` /
  `fallback`):
  - `simulation` is THE region engine; the ArchUnit forbidden-API ban (clocks/entropy/IO/
    concurrency) is scoped to `dev.nodera.simulation..` and enforced by `ForbiddenApiTest` +
    `DeterminismPropertyTest`. `shadow`'s `SnapshotDeltaApplier` measures timing OUTSIDE the
    hashed path (nanoTime around the engine, never inside it).
  - `coordinator`: the delegate→propose→verify→commit→reassign pipeline behind the
    `MutableWorldView` seam (`CoordinatorIT`); ALL world writes go through `WorldMutationApplier`
    (two-pass compare-and-set, all-or-nothing). Durable state via `PersistedCoordinatorState`.
    Task 22's multi-factor `ReliabilityScorer` is ADDITIVE — the Task-6 `ReliabilityLedger` EMA
    stays the frozen correctness source. Task 25's `LagHandoffPolicy`: skew strictly above four
    ticks for consecutive windows, streak resets on assignment change, cooldown prevents
    flapping, only a guarded handoff applies the one-shot reliability penalty. Task 11's
    `interference` package is the single mutation-guard choke point.
  - `committee`: consensus primitives around real engine re-execution; Task 24 `VotePersistence`
    (durably prepare before signing, persist certificate before canonical apply); guarded lag
    handoff pins region/epoch/primary (stale decisions are no-ops); 2-of-3 quorum commits through
    the coordinator applier. Proven by `CommitteeMvpIT`/`ByzantineWorkerTest`/`CrashRecoveryIT`/
    `LagHandoffIT`.
  - `fallback`: committee-lane vs server-lane router + `SoakMetrics` (Phase 4 exit: >90%
    committee-commit, `FallbackRoutingIT`). Wired live by Task 5's live lane.
- `transport` (`dev.nodera.protocol` + `dev.nodera.transport{,.socket,.rendezvous}`):
  - `protocol` is the frozen wire contract (see Frozen contracts below).
  - Carriers implement the `PeerTransport` seam only — consumers never name a concrete
    transport. `Frames` is the one 16 MiB length-prefix framing (socket transport mirrors its
    cap; rendezvous legs and the tracker client call it directly); `Reachability` is the one TCP
    probe.
  - `rendezvous`: direct-first / punch-upgrade / X25519+AES-GCM relay-fallback over the Rust
    `nodera-rendezvous` service (`RendezvousRelayIT` drives the real binary).
- `storage` (`dev.nodera.storage{,.event,.rocksdb,.client}` + `EventChainGuard`/`RegionOrder`/
  `io.AtomicFileWriter`):
  - The seam: canonical state = genesis + certified per-region event logs + checkpoints +
    certificates + content blobs. Append invariants (monotonic ids, unbroken
    `prevRoot→resultingRoot` chain — Invariant 3) live once in `EventChainGuard`, used by both
    the in-memory and RocksDB tiers. `EventReplayer` stops at an uncertified suffix;
    `PeerSyncFlow` is forward-only (Invariant 8).
  - `rocksdb`: WAL-backed column families, heads recovered from the log tail on open (no second
    record that can disagree after a crash), `FsContentStore` atomic hash-verified blobs;
    `RocksCrashRecoveryIT` forcibly kills a writer JVM. `rocksdbjni` stays
    implementation-scoped.
  - `client`: `BoundedClientWorldStore` never evicts an assigned region's current state; eviction
    callbacks run only after the store monitor is released (Task 24 hardening).
- `peer` (`dev.nodera.distribution` + `dev.nodera.peer` + `dev.nodera.diagnostics` +
  `dev.nodera.headless`; carries the `application` plugin — launcher name `nodera-headless` is a
  contract with `rust/nodera-app` (daemon.rs) and `scripts/dev.sh`):
  - `distribution`: the PIECE layer beneath the frozen region layer. Plaintext worlds slice
    byte-for-byte `RegionSnapshot.encode` so `SHA-256(reassembled blob)` IS the region
    `StateRoot`; encrypted worlds slice deterministic AES-GCM ciphertext (piece hashes/
    `manifestRoot`/`ContentId` cover ciphertext; `PieceManifest.regionRoot` stays plaintext
    truth; decryptors root-check recovered bytes). Hash-validate-before-accept is against the
    MANIFEST's hash for an index, never a hash carried beside payload (`ContentChunk` has no hash
    field by design). Selection is deterministic (`StableHash` rarest-first + rendezvous
    tie-break); GCM nonces use domain-separated truncated SHA-256. Serve/stream budgets advance
    by explicit windows, never wall clock; emergency flush uses one absolute deadline and never
    counts the departing peer. BouncyCastle (Argon2id) is this package's only external dep.
  - `peer` runtime: membership/heartbeat/gateway election over the transport SEAM (runs
    identically on `LoopbackTransport` and `SocketPeerTransport` — `SessionContinuityIT`);
    `discovery` (TrackerClient/BootstrapClient/PeerDirectory/ArchiveInventory/CachedPeerStore/
    PersistentIdentityStore), `archival` (placement/audit/repair, Tasks 21/22), deadline-bound
    `PeerShutdownHook`, certified-reference `TickSync`, `control` — the loopback verb endpoint
    (`ControlProtocol` v2), the single source the mod's `CompanionProtocol` and the Tauri app's
    `control.rs` mirror.
  - `diagnostics`: Minecraft-free telemetry/view models; metrics accept injected monotonic time
    and never enter simulation, state roots, or certificates.
  - `headless`: `HeadlessPeerMain` + `WorkerControlHandler` (NODERA-STATE JSON is the Rust
    dashboard contract) + `WorldHostingService`.
- `testing` (`dev.nodera.testkit`): `LoopbackTransport` (chunks streams exactly like the real
  transports), `FakeRegion`, wire-fixture IO; future home of the multi-peer scenario suite.
- `neoforge-mod` is onboarded via the `nodera.neoforge-mod` convention (ModDevGradle → NeoForge
  21.1.77, Java 21 toolchain); compiles + assembles a fat mod jar of our own classes;
  `runServer`/`runClient` acceptance deferred to a GUI env. Carries `/nodera` + `/noderac` +
  HUD under `dev.nodera.mod.debug`. Client-only code stays under `dev.nodera.mod.client`
  behind `Dist.CLIENT`.
- **Rust crates carry no game, consensus, or storage logic** (Task 0 §4 rule 7). They are
  discovery/forwarding infrastructure: peers verify every claim (Ed25519 signatures, content
  hashes) and never treat a service as authority. A service outage degrades discovery or
  reachability — never correctness.

## Frozen contracts (do not change without a version bump)
- Canonical encoding: `core/crypto/CanonicalWriter` + `CanonicalReader` + `Encodable` + `TypeTags`.
  Every `Encodable.encode` starts with `writeU16(typeTag); writeU16(ENCODING_VERSION);`.
  Since Task 27 there is a **second implementation** — `rust/nodera-codec` — held to the same
  contract by two mechanical checks: `tests/tag_mirror.rs` parses `TypeTags.java`/`MessageCodec.java`
  and fails on any drift, and `tests/fixtures.rs` re-encodes every `fixtures/wire/*.bin`
  byte-exactly. Appending a tag means appending it on BOTH sides in the same commit.
- `core/Bytes` is the single byte[] value type (use everywhere, never raw byte[] in records).
- Wire tags: `protocol/codec/MessageCodec` (append-only, never renumber). `SessionKeepAlive` keeps tag
  23 but emits body version 2 for canonical per-region progress; its decoder must continue accepting
  v1 as empty progress while all unchanged tags remain on global version 1.
- Hash/sign: `core/crypto/HashService` (SHA-256 over canonical encoding) + `SignatureService`
  (Ed25519 verify; signing lives on `core/identity/NodeIdentity`).

---

## ⚠️ Non-negotiable agent disciplines (this file IS the agent memory)

These three rules apply to EVERY session and EVERY commit, no exceptions:

1. **Run tests before committing.** Execute `./gradlew check`. If it is red, you do NOT commit.
   If you cannot fix a failure immediately, open a `bug` issue (`.github/ISSUE_SYSTEM.md`) and stop.
2. **Update `README.md` + `Tested.md` in the same commit** that changes outcomes: recompute the
   progress-bar percentage, the module status table, the roadmap ticks, and the test counts.
   Keep every `<!-- AI-AGENT-INSTRUCTION: ... -->` comment.
3. **Use the commit-message standard** (see README.md → "Commit message standard"):
   ```
   <emoji> [<overall-percentage>%] <change type>: <short description in English>
   ```
   Reference the issue: `refs #N` while working, `fixes #N` / `closes #N` to close.

## GitHub issue workflow (see `.github/ISSUE_SYSTEM.md` for the full rules)
- GitHub issues are the source of truth. Every task phase has an issue; every detected
  problem becomes a `bug` issue before a regression reaches `main`. Existing issues use the
  **legacy** task numbering (old Tasks 1–33, preserved in `docs/old/`) — find issues by exact
  title, never by number; the legacy→module-task mapping is `docs/Task.0.md` §4.
- One task = one branch (`<type>/<slug>-#<issue>`) = one PR.
- A task is "done" only when: `./gradlew check` green, acceptance criteria evidenced in the PR,
  README/Tested updated, and the issue closed via `Closes #N`.
- The orchestrator reviews every PR; unsatisfactory work stays open and is revised on the same
  branch until it passes.

## The debugger (issue #17)
A standing task (`#17`) owns the **Nodera debugger**: an integration harness that boots real server
instances over `LoopbackTransport`, drives P2P scenarios for every implemented lane (block-break
validation, redstone, entities, …), does real-time execution debugging, counts passing tests, and
emits debug logs + coverage reports — driving toward 100% coverage. Each lane task (5–16) adds
scenarios to it. First landed scenario: `peer-runtime/SessionContinuityIT` — three real-TCP peer
runtimes (bootstrap + two players); kill the bootstrap; assert the players detect it, re-elect the
same successor gateway deterministically, and keep exchanging keep-alives over their direct socket
(the `base-peer-disconnection` continuity scenario).

## Base document
[`docs/Task.0.md`](docs/Task.0.md) is the base document (it absorbed the former
`docs/Prompt.base.md` and the old conventions file): the ordered list of files to read first, the
project pattern, the module-task index (Tasks 1–7, one per Nodera module) + dependency graph, the
legacy→new task mapping, and how the issue workflow operates. Point new contributors/agents at it.
Legacy per-increment specs (old Tasks 0–33) are preserved verbatim in `docs/old/`.
