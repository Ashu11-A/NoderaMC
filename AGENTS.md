# AGENTS.md — NoderaMC

## Repo layout (polyglot monorepo; unified Java API since issue #30, 2026-07-21)

> **`layout.properties` at the repository root is THE layout table. Never hardcode a module or crate
> directory anywhere else.** Gradle's settings script, the Java harness (`LayoutManifest`), the
> structural report, `scripts/lib/layout.{sh,py}` and `nodera_codec::repo` all read it, and
> `LayoutManifestTest` fails the build if any of them drift from it. Before it existed the same
> table lived in five places and two were already wrong — a test had silently skipped for months
> against a directory that had moved, and a CI workflow watched a file that was no longer there.
> Neither failed; the copies just disagreed. Workflow YAML, Dockerfiles, `.dockerignore` and
> `tauri.*.conf.json` cannot read it (GitHub, Docker and Tauri parse them first), so
> `scripts/check-layout-drift.sh` greps those in CI. The paths below are what the manifest says
> today, not a second copy of it.
>
> **The published tracker/rendezvous list is not in this tree.** It lives on the orphan `services`
> branch (`git show services:README.md`), which shares no history with `main` and must never be
> merged with it — a list that changes when an operator joins is data, not source. `layout.properties`
> names the branch, the two file names and the raw URL under `services.*`; three builds resolve it
> identically (`scripts/services.py`, `:core`'s `generateOfficialServices`, `app/build.rs`) with the
> same fallback ladder — local ref, remote ref, shallow fetch, published URL, cache under
> `build/services`. Never add a copy of the list to `main` to "fix" a build; fetch the branch.

- **Nine Gradle modules** (plus `build-logic`): `core` · `engine` · `transport` · `storage` ·
  `peer` · `endpoint` · `testing` · `neoforge-mod` · `paper-plugin`. `:endpoint`
  (`library/java/endpoint`, `dev.nodera.endpoint.*`) is what a Minecraft-hosting process needs in
  order to BE a node, with **no Minecraft and no Paper types in it** — both endpoints consume it,
  which is what makes each of them a thin shell rather than the place the logic lives. The old fine-grained modules
  merged with **packages unchanged** (package map: `docs/README.md` §3) — e.g. `./gradlew :engine:test`
  runs what used to be `:simulation` + `:consensus` + `:coordinator` + `:committee` +
  `:shadow-validation` + `:fallback`.
  **`:worker` is gone** (2026-07-30): `dev.nodera.headless` is back in `:peer`, so a peer cannot be
  built without the always-on services that make it serve. The `nodera-headless` ENTRY POINT lives
  in `:peer`'s `src/headless` source set, which `tasks.jar` does not carry, so the mod's fat jar
  still cannot contain a launchable `main` (`ModJarCarriesNoEntryPointTest` asserts that on the
  built artefact). `./gradlew :peer:installDist` builds it; the artefact name is unchanged.
- **Cargo workspace rooted at the repository root** (`Cargo.toml`, `Cargo.lock`,
  `rust-toolchain.toml`, output in `target/`): `library/rust/nodera-codec` (canonical-encoding port,
  Task 27) and `library/rust/nodera-service` are the shared crates; `tracker/` (Task 28),
  `rendezvous/` (Task 29) and `telemetry/` (telemetry 1) are the service binaries. `app/` — the
  Tauri companion — is workspace-**excluded** (Tauri native deps) and declares its own
  `[workspace]`; run its gate from inside it. Crate versions are kept in step with the root
  `VERSION` file (see "Versioning" below).
- `web/` — the static site served at noderamc.org, published by `scripts/deploy-site.sh`.
- `docker/telemetry/` — the Big Data plane (Vector → Redpanda → ClickHouse → Grafana/Spark).
  **Never a dependency**: every container here can be down and no peer, tracker, or rendezvous
  service behaves differently.
- `fixtures/wire/` — committed golden canonical frames. Java emits them
  (`transport`'s `WireFixtureTest`), Rust decodes + re-encodes them byte-exactly. **Never edit a
  fixture by hand**: a byte change there is a wire-contract change.

## Build & test (both toolchains — one gate)
- `./gradlew check`        — compile + all Java unit tests (the gate)
- `./gradlew build`        — check + assemble jars
- `./gradlew :core:test`   — one module's tests (substitute module name)
- `./gradlew check --rerun-tasks` — force tests to re-run (ignore up-to-date caching)
- `cargo test`  — Rust unit tests + the cross-language fixture/tag-mirror conformance
- `cargo fmt --check && cargo clippy --all-targets -- -D warnings` — Rust lint gate
- `scripts/nodera-test.sh list|run|bench|structure|all` — **THE test entry point** (docs/testing/Task.0.md). One tool over the acceptance scenarios (`dev.nodera.testkit.scenario`, formerly the twenty `scripts/e2e-*.sh`), the benchmark lanes and the structural report; writes `build/reports/nodera/TEST-REPORT.md`. Live scenarios take `run/.e2e-suite.lock` and run one at a time
- `./gradlew :peer:jmh -Pbench.quick` / `./gradlew :peer:benchmarkReport` — the peer benchmark lanes (discovery · chunk sync · wire · runtime latency) and the ranked report with load-scaling and a baseline diff (network task 15). NOT part of `check`: minutes, and not a correctness gate
- `./gradlew :peer:structureReport` — the structural code report: dead code, in-loop cost findings, and a **debugger-profiled** run of the real `nodera-headless` worker. `-Pstructure.debug=false` skips the probe. Budgets in `fixtures/structure/budget.json` ratchet DOWN only
- `scripts/android-apk.sh --require-release-key` — the SIGNED Android build. Without the flag the script mints a development key (hardcoded password, reproducible by anyone) so a developer can install on a phone; with it, an absent key is a refusal. The release lane always passes it, because Android identifies an app by its signing certificate and an install signed with the dev key could never be updated by a genuine release. Key material arrives as `NODERA_ANDROID_KEYSTORE_BASE64` + `NODERA_ANDROID_KEY_PASS` secrets, is decoded mode-600 to a temp file, and is removed by the script's single EXIT trap — never add a second `trap` to that script
- `scripts/check-case-collisions.sh` — no two tracked paths, and no two module stems in one directory, may differ only in case. Linux is case-sensitive and macOS/Windows are not, so such a pair is two files here and one there; it is invisible from a case-sensitive machine and breaks every checkout on the other two
- `scripts/test-totals.sh --java | --cargo <log> | --merge … --badges DIR` — how many tests PASSED and FAILED, read from JUnit XML and libtest's own `test result:` lines. The three CI test jobs each emit their half; the `badges` job sums them and force-pushes two shields endpoint documents to the orphan `badges` branch, which is what README's two test badges render. Never hand-write those numbers into README — and do not put a workflow-status badge back, because "passing" is what a job that skipped into green also says
- `scripts/dev.sh --build-only` — compile both toolchains + collect the DEV artifacts (2 binaries + the mod jar + the worker dist) into `build/`. This is a development convenience, not the release lane
- `scripts/release.sh` — **the release lane**: builds each deliverable, stages it into `build/release` under its release name, and `--verify`s the result against `scripts/lib/release.sh`, which is the one naming table. `--names` prints the complete set; `--component jars|services|app|android` is what one CI matrix leg runs. `.github/workflows/release.yml` calls exactly this script — never add an asset name to the workflow YAML
- `scripts/dev.sh --test` — build both toolchains + run the full gate (no server to start; Task 30 retired it)
- `scripts/dev.sh` — build everything, then run the two infrastructure services (tracker + rendezvous) from `build/`, health-checked; worlds are hosted by a player's client (pause-menu "Share"), not a dedicated server. `--install-mod` drops the jar into `~/.minecraft/mods` for a real-client test
- `scripts/dev.sh --play` — the hands-on two-player stack (2 Minecraft clients · 1 tracker · 1 rendezvous · 3 peer workers), through the same `scripts/lib/e2e-main.sh` launcher the scripted suites use, so manual play matches what CI measures. `--with-app` adds one Tauri companion window **per player**, each attached in `NODERA_APP_ATTACH` mode to that player's own worker. Absorbed the former `scripts/play-two.sh`, which no longer exists

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
- Module graph: `core` → JDK only. `engine`/`transport`/`storage` → `core`. `peer` → those four,
  and also composes the always-on node (`PeerNode.start` is the only way to build one).
  `endpoint` → `core` + `peer`: what a Minecraft-hosting process needs in order to BE a node, with
  **no Minecraft and no Paper types in it**. `testing` → `core` + `engine` + `transport` + `peer`.
  `neoforge-mod` → those plus `endpoint` (the ONLY module with Minecraft/NeoForge types).
  `paper-plugin` → `core` + `peer` + `endpoint` and contains Paper types only.
- A wire payload is SPLIT: the data is a record in `library/java/endpoint`, and the
  `CustomPacketPayload` wrapper that adds a `StreamCodec` to it stays in the mod. `NoderaConfig` is
  read through `endpoint.config.NoderaSettings`, never directly — it is a `ModConfigSpec` holder and
  a NeoForge type on the library's path is what kept 1,000 lines of peer wiring inside the mod.
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
- `peer` (`dev.nodera.distribution` + `dev.nodera.peer` + `dev.nodera.diagnostics`):
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
  - `headless` (`dev.nodera.headless`): the ALWAYS-ON SERVICES, in `src/main` — a peer that does
    not run them is not a peer, which is why they are not a separate module any more.
    `WorkerControlHandler` owns NODERA-STATE; `WorldHostingService` owns persisted hosting/announce
    state; `WorldRegistryStore`/`WorldKeyStore`/`WorldTombstoneStore` own durable local state.
    The ENTRY POINT `HeadlessPeerMain` is the one class in `src/headless`, because `:peer` is zipped
    into the mod's fat jar and a player's `mods/` folder must not contain a launchable `main`.
    `./gradlew :peer:installDist`; launcher name `nodera-headless` is a contract with
    `app` (`daemon.rs`), `tauri.*.conf.json`, CI and `scripts/dev.sh`.
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

## Versioning — one file, never a literal

**The root `VERSION` file is the single source of truth for the product version.** One line, e.g.
`0.1.0`. Never hard-code a version anywhere; never bump one file's copy by hand.

| Toolchain | How it reads `VERSION` |
|---|---|
| Gradle | `settings.gradle.kts` reads it and injects `noderaVersion` + `modVersion` into every project |
| Java runtime | `library/java/core` expands it into `nodera-version.properties`; `NoderaConstants.PRODUCT_VERSION` loads it (`0.0.0-unbuilt` ⇒ the build did not run) |
| NeoForge mod | The convention plugin stamps `modVersion` into `neoforge.mods.toml` |
| Rust services | Each service crate's `build.rs` sets `NODERA_VERSION`; binaries print that, **not** `CARGO_PKG_VERSION` |
| Cargo/Tauri/npm manifests | Mirrors — they cannot read a file. `scripts/version.sh` writes them; `--check` fails the gate on drift |

```bash
scripts/version.sh                # print
scripts/version.sh --check        # run this before committing anything version-adjacent
scripts/version.sh --set 0.2.0    # release: bump VERSION + rewrite every mirror
```

**Releasing:** `--set X.Y.Z` → run the gate (`scripts/dev.sh --test`) → commit `VERSION` *and* the
rewritten mirrors in **one** commit → tag `vX.Y.Z` → let CI publish. `NoderaVersionTest` and
`scripts/version.sh --check` exist to catch the hand-edit.

The **tag**, not `VERSION`, is what names the release assets (`release_version_token`) — a rolling
`latest` republishes on every merge to `main` while `VERSION` stays put, so an asset named after
`VERSION` would be overwritten by a different build under the same name. A `v*` release is strict:
one missing deliverable fails the run and publishes nothing. `latest` warns and publishes what built.

⚠️ **Do not edit `library/java/build-logic/src/main/kotlin/*.gradle.kts` to plumb a version through.** On
this toolchain any edit there re-triggers the kotlin-dsl accessor breakage documented in
`gradle.properties`; inject the property from `settings.gradle.kts` instead — a `by project`
delegate reads extra properties too.

---

## Telemetry (opt-in, no authority) — `docs/plans/Plan.6.md`

Three rules, refusable if violated:
1. **Nothing is collected without an explicit yes.** Off by default; the question is asked once, in
   the companion app; absent consent = denied consent.
2. **Only `telemetry/src/schema.rs` may be stored,** and no value in it may be free
   text. Adding an event or attribute is a *policy* change: it lands with `Plan.6.md` §4 in the same
   commit. The gate is at the receiver, so it holds for forked clients too.
3. **Nothing in the network may read telemetry.** Stricter than the tracker/rendezvous rule — those
   at least influence discovery. A peer with an unreachable telemetry endpoint must be
   byte-identical to one with telemetry off.

One emitter per node, and it is the **worker**: the mod and the app hand it events, they never send.

```bash
scripts/nodera-test.sh run telemetry   # the consent lane end to end (headless; first in the queue)
scripts/telemetry-stack.sh up     # the Big Data plane; `smoke` proves a batch becomes a row
```

The decisive test is that suite's **T6**: kill the collector, and the node's whole `NODERA-STATE`
answer must be unchanged apart from the telemetry block and the clock.

---

## Frozen contracts (do not change without a version bump)
- Canonical encoding: `core/crypto/CanonicalWriter` + `CanonicalReader` + `Encodable` + `TypeTags`.
  Every `Encodable.encode` starts with `writeU16(typeTag); writeU16(ENCODING_VERSION);`.
  Since Task 27 there is a **second implementation** — `library/rust/nodera-codec` — held to the same
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
   When the change touches the mod's host/join/lane/continuity/command surfaces, also run the
   relevant live scenario(s) via `scripts/nodera-test.sh run <id…>` (docs/testing/TESTING.md) — the
   headless gate cannot see NeoForge-config-gated lifecycle paths. Live suites run strictly one
   at a time (the runner + suites hold `run/.e2e-suite.lock`).
2. **Update the documentation in the SAME commit** that changes outcomes — it is part of the
   deliverable, not a follow-up. The mandatory checklist is [`docs/README.md`](docs/README.md) §5:
   the owning `docs/<category>/Task.<n>.md` status header and audit date, that category's
   `PROGRESS.md` (a dated note naming the evidence), `TESTING.md` (counts + last run),
   `LIMITATIONS.md` / `LIMITATIONS.fixed.md` if a row moved, `docs/ROADMAP.md`, the root
   `README.md` module + roadmap tables, and the affected package `README.md`. The root `README.md`
   is capped at 200 visible lines and carries no narrative — never grow it back.
   Keep every `<!-- AI-AGENT-INSTRUCTION: ... -->` comment.
3. **Use the commit-message standard** (defined in the next section).

## Commit message standard

<!-- AI-AGENT-INSTRUCTION: This section is the NORMATIVE home of the commit format (it used to live
     in README.md, which is now the shop window and capped at 200 visible lines). EVERY
     completed-task commit MUST use this exact format. -->

```
<emoji> [<overall-percentage>%] <change type>: <short description in English>
```

The percentage is the overall system completion figure from [`docs/ROADMAP.md`](docs/ROADMAP.md) §1.
Reference the issue: `refs #N` while working, `fixes #N` / `closes #N` to close.

| Emoji | Change type | Use for |
|---|---|---|
| 🎉 | `init` | initial / repo bootstrap |
| ✨ | `feature` | new module, type, or capability |
| 🐛 | `fix` | bug fix (reference the issue: `fixes #N`) |
| 🧪 | `test` | test additions/improvements only |
| ♻️ | `refactor` | behaviour-preserving restructure |
| 📝 | `docs` | README / docs / issue-system updates |
| 🔧 | `chore` | build, deps, CI, tooling |
| 🚀 | `release` | version bump / publish |

```
✨ [90%] feature: implement Phase 1 shadow capture mixins (refs #5)
🐛 [90%] fix: align FlatWorldRules.MAX_Y with column ceiling (fixes #21)
🧪 [90%] test: add jqwik property test for negative-coordinate halo reads
```

## GitHub hygiene sweep (EVERY session, via the `gh` CLI — before starting new work)

<!-- AI-AGENT-INSTRUCTION: Run this sweep at the start of every session and again after every
     push. It is not optional housekeeping: stale issues rot the task ledger, an unreviewed PR
     blocks the pipeline, and a red action on main means the tree is broken for everyone. -->

1. **Review every open pull request.** `gh pr list --state open` — for each PR:
   `gh pr view <n> --files` + `gh pr diff <n>`, review against the acceptance criteria of its
   issue, then `gh pr review <n> --approve|--request-changes -b "<findings>"`. Merge only when
   both gates are green on the PR head (`gh pr checks <n>`). Never leave an open PR unreviewed
   at the end of a session.
2. **Reconcile the issue ledger.** `gh issue list --state open --limit 100` — for each issue,
   compare against the CURRENT tree (code + `docs/<category>/TESTING.md` + `docs/<category>/LIMITATIONS.md`):
   - work landed → `gh issue close <n> -c "<evidence: tests/register rows/commits>"` (always
     with an evidence comment, never a bare close);
   - work newly discovered (a live defect, a precise repro, a staged exit) →
     `gh issue create --label bug|task --title "..." --body "<repro/exit criteria + pointers>"`;
   - issue references stale numbering → find by exact `Task N — <title>` (task ≠ issue number).
   The ledger must reflect reality at session end — an issue whose scope shipped days ago is a
   bug in the ledger itself.
3. **Analyze failed actions.** `gh run list --limit 10` — any `failure` on `main` is a
   stop-the-line event: `gh run view <id> --log-failed` to isolate the failing step, reproduce
   locally (`./gradlew check` / `cargo test && cargo fmt --check && cargo clippy
   --all-targets -- -D warnings`), fix, push, then re-check `gh run list` until green. A push
   is not "done" until its `build` and `release` runs pass; watch them with
   `gh run watch <id>` after every push to `main`.
4. **Check back on the build a few minutes after every push.** Do not assume a green local
   `./gradlew check` means a green Action: CI runners are slower and have exposed
   timing-sensitive integration tests that pass locally but fail remotely (e.g. run
   30057069565: `ActionForwardIT.forwardedActionWithSkewedSignedTickStillCommits` raced the
   capturer's convergence; `SessionContinuityIT` hit a 15 s keep-alive timeout). After
   `git push`, run `gh run list --limit 3` after a while (or `gh run watch`) and treat any
   failure as stop-the-line: harden the racy wait (converge-on-both, longer `Await`
   deadlines), never delete the test.

## GitHub issue workflow (see `.github/ISSUE_SYSTEM.md` for the full rules)
- GitHub issues are the source of truth. Every task phase has an issue; every detected
  problem becomes a `bug` issue before a regression reaches `main`. Existing issues use the
  **legacy** task numbering — find issues by exact
  title, never by number; the current task set is `docs/<category>/Task.<n>.md` (index: `docs/ROADMAP.md` §2).
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

## Documentation

<!-- AI-AGENT-INSTRUCTION: Read docs/README.md before writing or editing ANY .md file in this repo.
     It defines the documentation format, the per-category file contract, the task-file template, the
     binding conventions, and the mandatory maintenance discipline (§5). Documentation is part of the
     deliverable and is updated in the SAME commit as the code. -->

[`docs/README.md`](docs/README.md) is the entry point: the documentation format, the binding
conventions (layering, frozen contracts, determinism rules, naming, version pins, shared constants),
where progress lives, and the **mandatory documentation-maintenance discipline** (§5). Read it before
writing or editing any `.md` file.

[`docs/ROADMAP.md`](docs/ROADMAP.md) is the single central roadmap across every category.

The tree is one folder per system category — `engine/` `network/` `tracker/` `rendezvous/`
`minecraft/` `peer/` `frontend/` — each containing `Task.0.md` (the category charter), `Task.1..n.md`
(one task per deliverable, with a completion status in the header), `PROGRESS.md`, `TESTING.md`,
`LIMITATIONS.md`, and `LIMITATIONS.fixed.md`. Programme plans live in `docs/plans/`. Every package
also carries its own `README.md` describing that package's architecture.
