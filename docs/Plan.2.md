# Java API Unification & Refactor — Final Plan

## Context

NoderaMC's `java/` tree carries 22 Gradle modules grown per-increment (legacy Tasks 1–33). Many
are thin slices of one concern (4 transport modules, 4 storage modules, 4 peer-ish modules, a
6-module engine cluster), with real duplication across them (3× length-prefix framing, 4×
atomic-file-write, parallel in-memory/RocksDB store impls, near-duplicate test fixtures) and one
fully orphaned module (`fallback`) plus one empty placeholder (`transport-neoforge`). The goal:
consolidate into a small set of unified, modern internal APIs, remove redundancy and dead code,
update docs, and keep/extend the test suite (773 Java + 144 Rust tests currently green).

## Decisions locked with user

1. **Java target:** modern idiom on `--release 21` everywhere. NeoForge 21.1.x pin forces Java 21
   bytecode across module boundaries. "Modern" = records, sealed interfaces, pattern-matching
   switch, virtual threads — all available in 21. No bytecode split.
2. **Granularity:** ONE Gradle module per unified API.
3. **`protocol` lives in `java/transport`** (merging it into peer creates compile cycles:
   transports + testkit need protocol while peer needs transports).
4. **`fallback` is production code** (Task-8 runtime, currently orphaned) → merged into the engine
   cluster, not deleted, not put in a test lib.
5. **Engine cluster consolidates too:** `java/engine` = simulation + consensus + coordinator +
   committee + shadow-validation + fallback. `dev.nodera.simulation` package preserved (ArchUnit
   determinism ban is package-scoped).
6. **`diagnostics` is production telemetry** → merged into `java/peer`. Test library =
   testkit + shared fixtures only.

## Target architecture (22 modules → 7)

```
java/core       dev.nodera.core.*          (unchanged; JDK only)
java/engine     ← simulation, consensus, coordinator, committee, shadow-validation, fallback
java/transport  ← protocol, transport-api, transport-socket, transport-rendezvous
                  (transport-neoforge DELETED — no main source, placeholder smoke test only)
java/storage    ← storage-api, storage-eventsourced, storage-rocksdb, storage-client
java/peer       ← peer-runtime, distribution, diagnostics, nodera-headless
                  (application plugin; launcher stays `nodera-headless`)
java/testing    ← testkit (LoopbackTransport, FakeRegion, FixtureWriter/Reader)
java/neoforge-mod  (unchanged module; consumes the new modules)
java/build-logic   (convention plugins, updated)
```

Dependency graph (main scope), acyclic:

```
core ← engine
core ← transport            (zstd; caffeine)
core ← storage              (rocksdbjni confined here)
core, transport, storage ← peer        (bouncycastle via distribution code)
core, engine, transport ← testing
core, engine, transport, storage, peer ← neoforge-mod
```
Test scope: `peer(test) → testing`; `engine(test) → peer, storage`; `storage(test) → testing` —
all legal (no main-configuration cycles; verified against current build files).

**Existing packages are kept** (`dev.nodera.protocol`, `dev.nodera.transport.socket`,
`dev.nodera.storage.rocksdb`, `dev.nodera.peer`, `dev.nodera.headless`, `dev.nodera.diagnostics`,
`dev.nodera.distribution`, `dev.nodera.simulation`, …). Unification is at module + API level;
mass package renames would churn every import (incl. neoforge-mod's 33 Minecraft-bound files) and
risk the ArchUnit scope for zero architectural gain. Each merged module gets a `package-info` /
module README describing its internal layering.

## Frozen-contract guardrails (must not change)

- Canonical encoding (`CanonicalWriter/Reader`, `Encodable`, `TypeTags`), `core/Bytes`.
- Wire tags in `protocol/codec/MessageCodec` — append-only; no renumbering; Rust mirror untouched.
- `fixtures/wire/*.bin` byte-exact; `protocol/WireFixtureTest` + Rust conformance stay green.
- Control protocol v2 (`NODERA-PROBE/STATE/IDENTITY/HOST/JOIN/STOP/PASSWORD/STATUS/WORLDID`):
  `peer-runtime/control/ControlProtocol` remains single source; mod `CompanionProtocol` delegate
  and Rust `control.rs`/`metrics.rs` mirrors unchanged.
- `rust/nodera-app` worker launch: `daemon.rs` resolves `NODERA_WORKER_BIN`, default
  `resources/nodera-headless/bin/nodera-headless` — the `:peer` module keeps
  `applicationName = "nodera-headless"`, `mainClass = dev.nodera.headless.HeadlessPeerMain`, and
  the same `installDist` layout. `scripts/dev.sh` re-checked.
- SocketPeerTransport's binary hello handshake bytes unchanged (it's live wire).

## Migration steps (each = one commit, `./gradlew check` + `cd rust && cargo test` green; `git mv` to preserve history)

Branch: new task branch off `main` per `.github/ISSUE_SYSTEM.md` (open a `task` issue "Java API
unification"; current `feature/task-30-decentralization` has uncommitted work — commit/land that
first or branch from its merged state).

### Step 0 — prep
- `java/build-logic`: keep `nodera.java-library` / `nodera.neoforge-mod` conventions; add a
  JaCoCo aggregation convention (coverage report per module + merged HTML/XML).
- Delete stale `java/build-logic/bin/` accessor artifacts already `git rm`'d in the working tree.

### Step 1 — `java/storage` (cleanest merge, proves the pattern)
- `git mv` the four storage trees into `java/storage/src/{main,test}` (packages already disjoint:
  `storage`, `storage.event`, `storage.rocksdb`, `storage.client`).
- `settings.gradle.kts`: replace 4 `module(...)` lines with `module("storage")`; repoint
  consumers (`distribution`→later `peer`, `neoforge-mod`, `nodera-headless`, `committee` test).
- Carry the RocksDB `java.io.tmpdir` build override; keep `rocksdbjni` `implementation`-scoped.
- Unify duplications:
  - single event-chain-validation support shared by `InMemoryRegionEventStore` + Rocks event store;
  - one `AtomicFileWriter` (temp+move) utility in `dev.nodera.storage.io` — adopt in
    `FsContentStore`; later steps adopt it in `PersistentIdentityStore`, `CachedPeerStore`,
    `NoderaWorldStore`;
  - merge `StorageFixtures`/`RocksFixtures` via `java-test-fixtures` plugin.
- Preserve `RocksCrashRecoveryIT` (forked-JVM kill/recover) unchanged.

### Step 2 — `java/transport`
- `git mv` protocol + transport-api + transport-socket + transport-rendezvous into
  `java/transport`; delete `transport-neoforge` entirely (placeholder; note in LEGACY.md that the
  future in-game relay lands in `neoforge-mod` per layering rule).
- Unify duplications:
  - one `Frames` (length-prefixed, 16 MiB cap) used by `SocketPeerTransport`,
    `RendezvousFrames` call sites, and (step 3) `TrackerClient`;
  - one `Reachability.probe(host, port, timeout)` utility — adopted by `WorldHostingService`,
    `TrackerClient` (step 3) and mod `CompanionClient`/`MultiplayerStatusFeed` (step 5);
  - shared `sendStream` chunking helper over `ChunkedStreams` (SocketPeerTransport +
    testing.LoopbackTransport).
- API pass: `PeerTransport`/`MessageHandler` seam kept; message families under
  `dev.nodera.protocol.*` untouched on the wire; internal helpers get sealed/record treatment.
- `RendezvousRelayIT` (real Rust binary) preserved.

### Step 3 — `java/engine`
- `git mv` simulation, consensus, coordinator, committee, shadow-validation, fallback into
  `java/engine` (all packages disjoint; deps of all six ⊆ {core, each other}).
- ArchUnit `ForbiddenApiTest` + `DeterminismPropertyTest` keep their `dev.nodera.simulation..`
  scope — verify they still run in the merged module.
- `fallback` stops being an orphan module; stays an orphan *package* wired for Task-8 live soak —
  documented in the engine package-info (no behavior change).
- `committee` test deps (distribution, peer-runtime, storage-eventsourced) become
  `testImplementation(project(":peer"))` + `(":storage")` — ordering note: this forces Step 3 to
  land **after** Step 4, or temporarily point at the old modules. Simplest: do Step 3 after
  Step 4. (Final order: storage → transport → peer → engine → testing.)

### Step 4 — `java/peer`
- `git mv` peer-runtime, distribution, diagnostics, nodera-headless into `java/peer`.
- Plugins: `java-library` + `application` (`mainClass dev.nodera.headless.HeadlessPeerMain`,
  `applicationName nodera-headless`). Verify `installDist` output path used by
  `rust/nodera-app/src/daemon.rs` and `scripts/dev.sh` byte-for-byte.
- Internal layering inside the module (enforced by package convention + review, optionally
  ArchUnit): `distribution` (data plane) → `peer` runtime → `headless` worker; `diagnostics`
  telemetry consumed by all.
- Adopt Step-1/2 utilities: `AtomicFileWriter` in `PersistentIdentityStore`/`CachedPeerStore`;
  `Frames` + `Reachability` in `TrackerClient`, `Reachability` in `WorldHostingService`.
- All ITs preserved: `TrackerServiceIT`, `MultiBootstrapIT`, `SessionContinuityIT`,
  `DistributionIT`, `EncryptedDistributionIT`, `CrashRecoveryIT`, `LagHandoffIT`, control tests.

### Step 5 — `java/testing` + neoforge-mod repoint
- `git mv` testkit → `java/testing`; deps: core, engine, transport.
- `neoforge-mod/build.gradle.kts`: depend on `:engine`, `:transport`, `:storage`, `:peer`;
  remove `:transport-neoforge`; fat-jar bundling list updated to the merged modules.
- Mod-side adoption: `NoderaWorldStore` → `AtomicFileWriter`; `CompanionClient`/
  `MultiplayerStatusFeed` → `Reachability`.
- Re-enable note for future `integration-tests` scenario suite: its home becomes `java/testing`
  (update the commented `settings.gradle.kts` line + Task-0 note).

### Step 6 — dead-code sweep + modernization polish
- Remove: nothing else found module-sized (`EchoTest` is a real wire message TAG 17 — keep;
  only 1 TODO in all of main; zero `@Deprecated`). Sweep per-class zero-reference types inside
  merged modules with the compiler + IDE-style unused analysis; delete or wire, case by case.
- Modern-idiom pass inside merged modules where cheap and behavior-preserving: sealed interfaces
  for closed hierarchies (e.g. transport path/message-class enums already exist), records for
  value carriers, pattern-matching switch in codec dispatch — **no wire/bytecode-visible change**.

### Step 7 — docs
- `docs/Task.0.md`: §4 module-task table (module names/paths), §5 package map, §7 layering rules
  (rule 3 now: "No Minecraft/NeoForge types outside `neoforge-mod`"), module-name mapping
  old→new appended to the legacy mapping table.
- `docs/Task.1.md`–`Task.7.md`: path/module references (Task 1 → `java/engine` (+`core`),
  Task 2 → `java/transport|storage|peer`, Task 6 → `java/peer` worker half).
- `AGENTS.md` (module list, build commands), `README.md` (module table, progress),
  `Tested.md` (rows collapsed to 7 modules, counts carried over, history note),
  `docs/LEGACY.md` (transport-neoforge deletion row), `docs/Roadmap.md` snapshot note,
  `docs/LIMITATIONS.md` only if a row cites a dead path.
- `docs/old/` untouched.

### Step 8 — coverage push
- JaCoCo merged report; identify gaps (known-thin: nodera-headless ~1 test file for 699 LOC —
  `WorldHostingService`/`WorkerControlHandler` unit tests; new `Frames`/`Reachability`/
  `AtomicFileWriter`/chain-validation utilities get dedicated tests; fallback executor already
  10 tests).
- Target: line coverage of the unified modules as close to 100% as the IO/IT-bound classes allow;
  report the final number honestly in Tested.md rather than claiming 100%.

## Verification (per step and final)

1. `./gradlew check` (all 773+ tests) + `cd rust && cargo test` (144) + clippy/fmt gate.
2. Wire conformance: `WireFixtureTest` + Rust fixture decode — proves no contract drift.
3. Worker end-to-end: `./gradlew :peer:installDist` then `NODERA_WORKER_BIN=... rust/nodera-app`
   probe (`NODERA-PROBE` → `NODERA-OK`), or `scripts/dev.sh --test`; confirms Tauri↔worker path
   survived the merge.
4. ITs that drive real binaries/processes: `RendezvousRelayIT`, `TrackerServiceIT`,
   `RocksCrashRecoveryIT` explicitly re-run.
5. NeoForge lane: `./gradlew :neoforge-mod:build` assembles the mod jar (runClient acceptance
   stays deferred to a GUI env, as today).
6. `scripts/dev.sh --build-only` — CI artifact collection still finds the 2 binaries + jar.

## Commit discipline

One commit per step, message per README standard (`♻️ [<p>%] refactor: ...`), README/Tested
updated in outcome-changing commits, issue referenced (look up by title, never assume number).
