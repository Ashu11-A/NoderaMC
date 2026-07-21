# Task 0 — Nodera Base Document: Orientation, Conventions, Task Index

<!-- AI-AGENT-INSTRUCTION: This is the BASE DOCUMENT for NoderaMC — the synthesis of the former
     docs/Prompt.base.md (base orientation prompt) and the former docs/Task.0.md (conventions +
     task index). Paste/include it at the start of any session working on NoderaMC. It is binding
     for Tasks 1–7. When a later task contradicts this file, fix the later task. Keep it accurate;
     update it when a new "always-read" file is introduced. -->

Not an implementation task. This file is (1) the orientation prompt for any human or agent
session, (2) the binding conventions for all other tasks, and (3) the module-task index and
dependency graph. The per-increment historical specs live in [`docs/old/`](old/) — they are the
detailed class-level references the module tasks (Tasks 1–7) point into; nothing there was
deleted, only reorganized.

---

## 1. What NoderaMC is

**NoderaMC** — a NeoForge-based, decentralized Minecraft system: the world is partitioned into
8×8-chunk regions, each simulated and validated by a small committee of player-run peers. **Any
player can host a world directly** — share an existing world to the network from the pause menu
("Open to Nodera", the analogue of "Open to LAN"), and peers reach each other through a
standalone tracker + rendezvous relay. **No central server is required** (delivered by the old
Task 30 increment); an optional dedicated server is just a well-provisioned archival peer with a
single, non-authoritative vote. The project's central bet is a **bit-for-bit deterministic region
engine**; everything downstream gates on it.

The mod **requires** the always-on peer worker (`nodera-headless`, Task 6) supervised by the
Tauri companion app (Task 7) — a player's node stays on the network with Minecraft closed.

## 2. Read these files first (in this order)

| File | Why it matters |
|---|---|
| [`AGENTS.md`](../AGENTS.md) | **Agent memory.** Build/test commands, layering rules, frozen contracts, and the three non-negotiable disciplines (test-before-commit, update README, commit format). Re-read every session. |
| [`README.md`](../README.md) | **Progress + status.** Progress bar, module table, roadmap (tasks → issues), commit-message standard, issue-system summary. |
| [`Tested.md`](../Tested.md) | **Test status per module** (counts, pass/fail emojis, last run, per-increment history). |
| [`.github/ISSUE_SYSTEM.md`](../.github/ISSUE_SYSTEM.md) | **The normative workflow**: how to open/assign/branch/commit/close/reopen issues, and how to edit README. |
| [`docs/Plan.0.md`](Plan.0.md) | **Architecture & roadmap.** Locked decisions (§3), module layout (§4), implementation phases (§6), invariants (§8). |
| **This file** | Conventions, definitions, module-task index & dependency graph. Binding for all other tasks. |
| [`docs/LIMITATIONS.md`](LIMITATIONS.md) | **The binding limitation register.** §A envelope constraints, §B staged-capability burn-down (each with an owning task + exit test). No permanent exclusions allowed. Owner tags (`T5`, `T28`, …) use the **legacy** task numbering — resolve them via the mapping table in §4 below. |
| [`docs/Roadmap.md`](Roadmap.md) | Implementation order, priority, difficulty (written against the legacy numbering; links into `docs/old/`). |
| [`docs/LEGACY.md`](LEGACY.md) | Legacy ledger for the Rust-services transition: which Java files were REMOVE/REWRITE/KEEP and why. Temporary. |
| [`docs/Task.1.md` … `docs/Task.7.md`](Task.1.md) | **The module tasks** (one per Nodera module — see §4). Each carries an implementation-status table, phased deliverables, cross-task phase dependencies, and pointers into `docs/old/` for full class-level detail. |
| [`docs/torrent/trackers.md`](torrent/trackers.md) · [`docs/torrent/rendezvous.md`](torrent/rendezvous.md) | Reference architecture studies for the tracker (Task 3) and rendezvous/relay (Task 4) services. |
| [`docs/minecraft/MultiPaper/`](minecraft/MultiPaper/) · [`docs/minecraft/folia/`](minecraft/folia/) | Prior-art studies the engine design (Task 1) draws on: regionised ticking, ownership takeover, write barriers, thread-context guards. |

Issue templates: `.github/ISSUE_TEMPLATE/bug.md`, `.github/ISSUE_TEMPLATE/task.md`.

## 3. The project pattern

- **Polyglot monorepo — the default architecture** (adopted; the former migration instruction
  file `MONOREPO.md` is retired, its durable content lives here and in `AGENTS.md`/`README.md`):
  - `java/<module>/` — every Gradle module. **Since the Java API unification (issue #30,
    2026-07-21) there are exactly seven**: `core` · `engine` · `transport` · `storage` · `peer` ·
    `testing` · `neoforge-mod` (plus `build-logic`). The old fine-grained modules
    (`protocol`, `transport-*`, `storage-*`, `peer-runtime`, `distribution`, `diagnostics`,
    `nodera-headless`, `simulation`, `consensus`, `coordinator`, `committee`,
    `shadow-validation`, `fallback`, `testkit`) merged into them with **packages unchanged** —
    only the Gradle module boundaries moved (mapping: §5).
  - `rust/` — cargo workspace: `nodera-codec` (canonical-encoding conformance), `nodera-tracker`
    (Task 3), `nodera-rendezvous` (Task 4), `nodera-app` (Task 7, workspace-excluded — Tauri
    native deps). Toolchain pinned in `rust/rust-toolchain.toml`, crate versions in the
    workspace `Cargo.toml`.
  - `fixtures/wire/` — golden canonical frames, emitted by Java, re-encoded byte-exactly by
    Rust. Never edit a fixture by hand.
- **Two toolchains, one gate.** `./gradlew check` **and** `cd rust && cargo test` must both be
  green; `scripts/dev.sh --test` runs both plus the lint gate. The cross-language conformance
  tests (`fixtures/wire/*.bin` + the tag-registry mirror) keep the two canonical-encoding
  implementations honest.
- **Layered, Minecraft-free core.** `core` → JDK only. `engine`, `transport`, `storage` →
  `core`. `peer` → `core` + `transport` + `storage`. `testing` → `core` + `engine` +
  `transport`. Minecraft/NeoForge types live ONLY in `neoforge-mod` (Task 5; the empty
  `transport-neoforge` placeholder was deleted — the in-game relay lane lands inside the mod).
  Every capability is **proven Minecraft-free first** (headless JUnit), then wired to
  NeoForge — the recurring pattern of the whole build.
- **Frozen contracts — do not change without a version bump:**
  - Canonical encoding: `core/crypto/CanonicalWriter` + `CanonicalReader` + `Encodable` +
    `TypeTags`. Every `Encodable.encode` starts with `writeU16(typeTag); writeU16(ENCODING_VERSION);`.
  - `core/Bytes` is the single byte[] value type (never raw `byte[]` in records).
  - Wire tags: `protocol/codec/MessageCodec` (append-only — never renumber). Appending a tag
    means appending it on BOTH the Java and Rust sides in the same commit.
  - Hash: `core/crypto/HashService` (SHA-256 over canonical encoding). Sign: Ed25519 verify on
    `core/crypto/SignatureService`; signing lives on `core/identity/NodeIdentity`.
- **Determinism is sacred.** Inside `simulation`: no wall clocks, no `ThreadLocalRandom`/
  `Math.random`/`UUID.randomUUID`, no unordered-map iteration, no IO. All randomness via
  `DeterministicRandom` (L64X128MixRandom seeded from `StableHash`). Enforced by ArchUnit
  (`simulation/ForbiddenApiTest`) + `simulation/DeterminismPropertyTest`. There is exactly ONE
  region engine (Java `simulation`) — no second implementation may re-execute regions.
- **Rust services are infrastructure, never authority** (rule 7): peers verify everything a
  service says (Ed25519 signatures, content hashes) and never treat it as authority. A service
  outage may degrade discovery or reachability — never correctness. Rust crates carry no game,
  consensus, or storage logic.
- **GitHub-issue-driven.** Every task is an issue; every detected problem becomes a `bug` issue
  before a regression reaches `main`. One task = one branch = one PR. **Task number ≠ issue
  number** — always find the real issue by its exact `Task N — <title>` before writing
  `refs #N`/`Closes #N`.

## 4. Module-task index and dependency graph

One task per Nodera module (or coherent module cluster). Detailed phase tables live inside each
task file; a phase is referenced as `<task><letter>` (e.g. `2d` = Task 2 phase d).

| Task | Module | Scope | Depends on |
|---|---|---|---|
| [1](Task.1.md) | **Deterministic engine & committee validation** (`java/core`, `java/engine`, `java/testing`) | Domain types, crypto, canonical encoding, the region engine, shadow validation, coordinator, committee quorum/MVP gate, fallback router, interference guard, and the parity program (entities, redstone, environment, mobs, player lane, BFT) | — (root) |
| [2](Task.2.md) | **P2P network** (`java/transport`, `java/storage`, `java/peer`) | Wire messages, transports, peer runtime/membership/gateway, event-sourced storage, torrent data plane, discovery, replication/repair, reliability/quotas/retention, encryption, crash safety, tick-lag handoff, telemetry core | 1 (types + engine) |
| [3](Task.3.md) | **P2P network tracker** (`rust/nodera-tracker` + Java `TrackerClient` in `java/peer`) | Always-on world/peer discovery service; announce/query lifecycle | 2 (wire + discovery seams) |
| [4](Task.4.md) | **P2P rendezvous** (`rust/nodera-rendezvous` + `dev.nodera.transport.rendezvous` in `java/transport`) | NAT reach for users with moderate/poor NAT: signed registration/discovery, hole punching, E2E-encrypted relay fallback | 2 (transport seam) |
| [5](Task.5.md) | **NeoForge Minecraft (Java) module** (`java/neoforge-mod`) | The mod: entrypoints, capture/mixins, live validation lane, HUD + commands, multiplayer/share GUI, decentralized host lane, world identity/permissions (mod half), companion gate, `runClient` harness | 1, 2, 3, 4, 6 |
| [6](Task.6.md) | **Peer worker** (`dev.nodera.headless` + `dev.nodera.peer.control` in `java/peer`) | The required always-on headless peer the mod probes: control protocol/verbs, telemetry, host/join delegation, worker seeding, out-of-game validation | 2; 3/4 (services it dials); 5e (genesis) for 6c |
| [7](Task.7.md) | **Tauri companion app** (`rust/nodera-app`) | Desktop supervisor of the worker: tray, autostart, dashboard, installers | 6 (the worker it supervises) |

```
1 ──► 2 ──►┬─► 3 ─┐
           ├─► 4 ─┼─► 5 ◄─ 6 ◄─ 7        5 delivers the LIVE halves of 1 and 2
           └──────┘        (6 runs 2's peer runtime out-of-game; 7 supervises 6)
```

**Standing harness (old issue #17, no file):** the Nodera debugger — headless integration
scenarios over `LoopbackTransport`/real TCP (first landed: `peer-runtime/SessionContinuityIT`).
Every task adds scenarios; scope lives in the GitHub issue + `AGENTS.md`.

**Declared-but-unbuilt module:** `integration-tests` (commented out in `settings.gradle.kts`;
README/Tested list it ⬜) — the planned home of the multi-peer scenario suite
(`FakePeer`/`ClusterHarness`, `ThreeClientQuorumIT`, …; spec:
[`old/Task.7.md`](old/Task.7.md)). Today those scenarios live inside each module's own test
tree (the standing-harness pattern above); the dedicated module materializes with Task 5's
live lane (5b) when cross-module live ITs need a shared home. Owners: Task 1 (scenarios) +
Task 5 (live runs).

### Legacy → module-task mapping

The 2026-07-21 consolidation replaced the 33 per-increment specs with the 7 module tasks. The
old files are preserved verbatim in [`docs/old/`](old/); `LIMITATIONS.md` owner tags,
`Roadmap.md`, `Tested.md` history, and GitHub issues still cite the legacy numbers — resolve
them here:

| Legacy task | Now lives in |
|---|---|
| 0 (conventions) + `Prompt.base.md` | this file |
| 1 (build scaffolding + mod skeleton) | 5a |
| 2 (`core`) | 1a |
| 3 (`simulation`) | 1b |
| 4 (`protocol` + transports) | 2a (relay impl + live handshake: 5b) |
| 5 (shadow validation) | 1c (live half: 5b) |
| 6 (coordinator) | 1d (live half: 5b) |
| 7 (committee MVP gate) | 1e (live half: 5b) |
| 8 (server fallback + router) | 1f (live soak: 5b) |
| 9 (peer-runtime + storage) | 2b/2c (committee-change certs: 1a) |
| 10 (gateway migration + P2P) | 2b (cross-NAT live: 4c + 5b) |
| 11 (interference guard) | 1g (mixins/tickets: 5b) |
| 12 (entity & mob lane) | 1h |
| 13 (validated redstone) | 1i |
| 14 (environment lane) | 1j |
| 15 (deterministic mobs) | 1k |
| 16 (player lane & trustless closure) | 1l |
| 17 (debugger — no file) | standing harness (above) |
| 18 (diagnostics HUD) | 2k (telemetry core) + 5c (HUD/commands) |
| 19 (torrent data plane) | 2d |
| 20 (tracker/directory/multi-bootstrap) | 2e (serving half superseded by Task 3) |
| 21 (placement/replication/repair) | 2f |
| 22 (reliability/quotas/retention) | 2g |
| 23 (per-world encryption) | 2h |
| 24 (crash safety + stream) | 2i (sidecar answer: Task 6) |
| 25 (tick-lag/TPS handoff) | 2j |
| 26 (multiplayer GUI) | 5d |
| 27 (monorepo + `nodera-codec`) | done; layout is the §3 default (`nodera-codec` conformance: 2a) |
| 28 (Rust tracker) | 3 |
| 29 (Rust rendezvous) | 4 |
| 30 (decentralization + Share) | 5e |
| 31 (Nodera GUI redesign) | 5d |
| 32 (companion app + worker + gate) | 6 (worker) + 7 (app) + 5g (gate) |
| 33 (live worker data + identity + permissions) | 6b (verbs/telemetry) + 5f (mod half) + 7b (dashboard) |

**Assumption A0 (binding everywhere)**: every player runs the Nodera mod and joins as a network
peer. There is no vanilla-client population and no second-class lane; the handshake enforces it.
Any design that only makes sense "for vanilla clients" is dead code by definition.

## 5. Naming and coordinates

| Thing | Value |
|---|---|
| Root project name | `nodera` |
| Maven group | `dev.nodera` |
| Mod id | `nodera` |
| Base package | `dev.nodera.<module>` |
| Mod package | `dev.nodera.mod` (subpackages `common`, `server`, `client`) |
| Wire protocol version | `"1"` (NeoForge payload registrar version string) |
| Config files | `nodera-server.toml` (server), `nodera-client.toml` (client) via NeoForge config API |

Packages per module (all under `java/`; the unification of issue #30 moved module boundaries,
never packages — old module names map to packages inside the seven modules):

```
core       → dev.nodera.core             (identity, region, action, state, event, crypto)
engine     → dev.nodera.simulation       (THE region engine; determinism ban is package-scoped)
             dev.nodera.consensus
             dev.nodera.shadow           (was module shadow-validation)
             dev.nodera.coordinator
             dev.nodera.committee
             dev.nodera.fallback
transport  → dev.nodera.protocol         (frozen wire contract; was module protocol)
             dev.nodera.transport        (PeerTransport seam + Frames/Reachability; was transport-api)
             dev.nodera.transport.socket (was transport-socket)
             dev.nodera.transport.rendezvous (was transport-rendezvous)
storage    → dev.nodera.storage          (WorldStore seam + EventChainGuard/RegionOrder/io; was storage-api)
             dev.nodera.storage.event    (was storage-eventsourced)
             dev.nodera.storage.rocksdb  (was storage-rocksdb)
             dev.nodera.storage.client   (was storage-client)
peer       → dev.nodera.distribution     (was module distribution)
             dev.nodera.peer             (was peer-runtime; control/ = the worker verb endpoint)
             dev.nodera.diagnostics      (was module diagnostics)
             dev.nodera.headless         (was nodera-headless; installDist launcher stays `nodera-headless`)
testing    → dev.nodera.testkit          (was module testkit)
neoforge-mod → dev.nodera.mod
```

Rust crates (cargo package names, under `rust/`):

```
rust/nodera-codec       → nodera-codec        (canonical encoding + Ed25519 verify + tag mirror)
rust/nodera-tracker     → nodera-tracker      (Task 3 service binary)
rust/nodera-rendezvous  → nodera-rendezvous   (Task 4 service binary)
rust/nodera-app         → nodera-app          (Task 7 Tauri app; workspace-excluded)
```

## 6. Version pins (change only via a commit that updates this file)

- **Minecraft 1.21.1 + NeoForge 21.1.x (LTS line)**, Java **21** toolchain everywhere.
  Rationale: stable mappings and payload API; the design needs virtual threads (Java 21+),
  nothing newer. Re-pin later versions in one dedicated upgrade commit, never mid-task.
  Known skew to reconcile before trusting live acceptance: the project pins NeoForge **21.1.77**
  while the real test client at `~/.minecraft` runs **21.1.238** (see 5a).
- Gradle 8.x, Kotlin DSL, ModDevGradle. Dependency versions live in
  `gradle/libs.versions.toml` (version catalog); no hardcoded versions in module build files.
- Rust toolchain pinned in `rust/rust-toolchain.toml`; crate versions pinned in the workspace
  `Cargo.toml`. Same discipline: upgrades are single dedicated commits, never mid-task.
- Host JDK is 25; all modules compile with `--release 21` (Java 21 bytecode) — see `AGENTS.md`.

## 7. Layering rules (enforced by module dependencies)

1. `core` depends on nothing (JDK only) — including `core/crypto/symmetric` (AES-GCM/PBKDF2
   are JDK crypto; Argon2id lives in `peer` (`dev.nodera.distribution`) behind pinned
   BouncyCastle).
2. `engine`, `transport`, `storage` depend on `core` only. `peer` depends on `core` +
   `transport` + `storage`. `testing` depends on `core` + `engine` + `transport`. Inside a
   unified module the old inter-package layering still holds (e.g. `dev.nodera.simulation`
   never imports `dev.nodera.coordinator`).
3. **No Minecraft/NeoForge types outside `neoforge-mod`.**
   Where a Minecraft concept is needed, `core` defines its own representation (`NBlockState`
   int id, `NBlockPos` record) and `neoforge-mod` owns the mapping.
4. Client-only code (`net.minecraft.client.*`, screens, overlays) lives only under
   `dev.nodera.mod.client` and is guarded by `Dist.CLIENT` entrypoints — a dedicated server
   must never classload it.
5. All world mutation of the real `ServerLevel` happens in exactly one class
   (`WorldMutationApplier`) on the server main thread. Everything else produces data
   (`RegionDelta`) and hands it off.
6. A delegated region's chunks are mutated **only** by `WorldMutationApplier`. Every other
   write source — random ticks, fluids, gravity blocks, fire, mobs, fake players, other mods,
   vanilla mechanics bleeding across the lane boundary — is suppressed or converted into a
   certified `ExternalDelta` by the interference guard (1g). Violations are logged and
   converted, never silently passed.
7. **Rust service crates carry no game, consensus, or storage logic.** They are
   discovery/forwarding infrastructure: peers verify everything a service says and never treat
   it as authority.

## 8. Shared constants (defined once in `core`, class `NoderaConstants`)

```java
REGION_SIZE_CHUNKS   = 8      // 8×8 chunks per region
HALO_CHUNKS          = 1      // read-only ring around the region
BATCH_TICKS          = 2      // execution batch length
BATCH_MAX_MILLIS     = 100
CHECKPOINT_INTERVAL_TICKS = 100
LEASE_LENGTH_TICKS   = 200
LEASE_RENEW_TICKS    = 40
HEARTBEAT_TICKS      = 20
QUORUM_MVP           = "2 of 3"   // committee MVP gate (1e)
QUORUM_PEER          = "3 of 4"   // peer-runtime era (2b+)
HASH_ALGORITHM       = SHA-256
SIGNATURE_ALGORITHM  = Ed25519

DELEGABLE_NEIGHBOR_RING     = 1       // regions within this ring must be palette-compatible (1g)
ENTITY_EXCLUSION            = true    // entity presence ⇒ non-delegable, until 1h narrows it
DELEGABILITY_COOLDOWN_TICKS = 200     // hysteresis against delegable/non-delegable flapping
INTERFERENCE_REVOKE_RATE    = 60/min  // foreign-mutation rate that demotes a region (1g)
```

All of these are *defaults* surfaced through config; code reads config, tests read constants.

## 9. Determinism ground rules (apply to `simulation` and anything it calls)

Forbidden inside the engine: `System.currentTimeMillis`, `System.nanoTime`,
`ThreadLocalRandom`, `Math.random`, `UUID.randomUUID`, iteration over `HashMap`/`HashSet`
(use sorted or insertion-ordered structures), `String.hashCode`-dependent ordering,
filesystem/network access, static mutable state.

Required: all randomness via `DeterministicRandom` (1b); all hashing via `HashService` over
`CanonicalEncoder` output (1a); all collections in hashed state either sorted at encode time or
order-stable by construction. No floats/doubles in hashed state — continuous quantities use
Q32.32 fixed-point (`FixedVec3`).

Enforcement: ArchUnit test bans the forbidden APIs from `dev.nodera.simulation..`.

## 10. How progress is tracked (and where it lives)

- **Overall % and per-phase %** → `README.md` → "Progress". Recomputed on every commit that
  changes outcomes.
- **Per-module test status** → `README.md` module table AND `Tested.md` (authoritative).
  Emojis: ✅ done · 🚧 partial · ⏳ in progress · ⬜ not started · ❌ failing.
- **Roadmap / task status** → `README.md` "Roadmap" table, mirrored by GitHub issues labelled
  `task`.
- **Limitations burn-down** → `docs/LIMITATIONS.md` §B (OPEN → RETIRING → RETIRED).
- **Per-task phase status** → each `docs/Task.<n>.md` "Implementation status" table.

### To view progress

```bash
gh issue list --state open --label task        # what's left (legacy titles — find by title)
gh issue list --state closed --label task      # what's done
cat README.md | sed -n '/Progress/,/^---/p'    # the bar + phase table
cat Tested.md                                  # test counts + emojis
```

### To update progress (mandatory on every outcome-changing commit)

1. Edit `README.md`: recompute `Overall system completion: <p>%` + the block bar; tick the
   module table and roadmap rows. Preserve every `<!-- AI-AGENT-INSTRUCTION: ... -->` comment.
2. Edit `Tested.md`: update test counts, `Last run` date, and the module emoji.
3. Update the owning `docs/Task.<n>.md` implementation-status table (and its audit date).
4. Close/open the relevant GitHub issue (`Closes #N` / `Reopen #N`).
5. If a §B limitation was staged or retired, update `docs/LIMITATIONS.md` in the same commit.

## 11. Non-negotiable disciplines (every commit, no exceptions)

1. **Run `./gradlew check` and `cd rust && cargo test` first.** If red, you do NOT commit. If
   you can't fix it, open a `bug` issue and stop.
2. **Update `README.md` + `Tested.md` in the same commit** (see §10).
3. **Commit message format:**
   ```
   <emoji> [<overall-percentage>%] <change type>: <short description in English>
   ```
   Emoji/type legend + examples: `README.md` → "Commit message standard". Reference the issue:
   `refs #N` while working; `fixes #N` / `closes #N` to close.

## 12. Definition of done (every task phase)

- Code + unit tests green in CI (both toolchains).
- New public types have Javadoc stating thread-context expectations ("called on server main
  thread", "any thread", "worker executor only").
- The phase's acceptance criteria demonstrably satisfied (each criterion is a test or a
  scripted manual scenario recorded in the task's verification notes).
- No TODOs referencing the same phase; leftover work becomes a bullet in a later phase.
- `Plan.0.md` updated if a locked decision changed (rare; call it out in review).
- `LIMITATIONS.md` updated in the same commit whenever the phase stages or retires a
  limitation. Introducing a **permanent** exclusion is forbidden — any new limitation enters
  the ledger OPEN with an elimination path, an owning task, and an exit test.
