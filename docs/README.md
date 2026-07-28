# NoderaMC Documentation

<!-- AI-AGENT-INSTRUCTION: This file is the ENTRY POINT of the documentation tree and the binding
     description of the documentation format itself. Read it before writing or editing any other
     `.md` file in this repository. It defines (1) what NoderaMC is, (2) the category layout and the
     file contract every category folder must satisfy, (3) the task-file template, (4) the
     conventions that are binding on all tasks, and (5) the mandatory documentation-maintenance
     discipline. When a task file contradicts this file, fix the task file. Keep every
     `<!-- AI-AGENT-INSTRUCTION: ... -->` comment intact when editing a document. -->

Last documentation reorganization: **2026-07-28** (ten-agent full-sweep: every category's tasks,
limitations, progress, testing refreshed against the tree; a new `REFACTORING.md` added per
category) · Overall system completion: **90.4%** (figure not recomputed this sweep — its weighting
is not raw done/total; see [`ROADMAP.md`](ROADMAP.md) §1 note) · Tests: **2,068 Java · 408 Rust
workspace · 157 `nodera-app`** (2,633 total).

---

## 1. What NoderaMC is

**NoderaMC** is a NeoForge-based, decentralized Minecraft system. The world is partitioned into
8×8-chunk **regions**; each region is simulated and validated by a small **committee of player-run
peers**. Any player can host a world directly — share an existing world to the network from the
pause menu (**"Open to Nodera"**, the analogue of "Open to LAN") — and peers reach each other
through a standalone **tracker** and **rendezvous relay**. **No central server is required**; an
optional dedicated server is just a well-provisioned archival peer with a single, non-authoritative
vote.

The project's central bet is a **bit-for-bit deterministic region engine**. Everything downstream
gates on it: if two honest peers cannot reproduce the same `StateRoot` from the same inputs, no
amount of networking makes the system work.

The mod **requires** the always-on peer **worker** supervised by the Tauri **companion app**, so a
player's node stays on the network with Minecraft closed.

**Assumption A0 (binding everywhere):** every player runs the Nodera mod and joins as a network
peer. There is no vanilla-client population and no second-class lane; the handshake enforces it.
Any design that only makes sense "for vanilla clients" is dead code by definition.

---

## 2. Documentation format (binding)

The tree follows a **component-per-folder** layout with a **fixed file contract**, in the spirit of
[Diátaxis](https://diataxis.fr/) — each folder separates *explanation* (the category charter),
*reference* (architecture and protocol references), *how-to* (`TESTING.md`), and *status*
(`PROGRESS.md`, `LIMITATIONS*.md`). Task files are specifications, not narratives.

```
docs/
├── README.md              ← you are here: entry point, format, conventions
├── ROADMAP.md             ← the single central roadmap across every category
├── plans/                 ← historical/active programme plans (Plan.0 … Plan.7)
├── engine/                ← category: deterministic engine & committee validation
├── network/               ← category: P2P network (wire, transports, runtime, storage, torrent)
├── tracker/               ← category: standalone tracker service + its client
├── rendezvous/            ← category: rendezvous + relay service + its transport
├── minecraft/             ← category: the NeoForge mod (+ prior-art studies & upstream sources)
├── worker/                ← category: the always-on headless peer worker
├── app/                   ← category: the Tauri companion application
├── mobile/                ← category: the Android build — the same app and worker, on a phone
├── server/                ← category: the Paper/Folia endpoint plugin (plans/Plan.5.md)
└── telemetry/             ← category: consented measurement + the Big Data plane (plans/Plan.6.md)
```

**Only `README.md` and `ROADMAP.md` may live at the root of `docs/`.** Everything else belongs to a
category folder or to `plans/`.

### 2.1 The file contract — every `docs/<category>/` folder contains

| File | Purpose | Updated when |
|---|---|---|
| `Task.0.md` | **Category charter** — what this component is, its architecture, its external dependencies, and the index of its tasks | The component's scope or task list changes |
| `Task.1.md` … `Task.n.md` | **One task per deliverable phase**, numbered sequentially. Header states whether it is completed | That task's status or scope changes |
| `PROGRESS.md` | Per-task status ledger + milestone notes for this category | Every outcome-changing commit that touches the category |
| `TESTING.md` | How this category is tested: suites, commands, counts, live procedures | Test counts or procedures change |
| `LIMITATIONS.md` | Open/retiring limitations **owned by this category**, each with an owning task and an exit test | A limitation is discovered, staged, or moves status |
| `LIMITATIONS.fixed.md` | Retired limitations with their retirement evidence | A row's exit test goes green |

A category may add reference documents (`REFERENCE.md`, `SDK.md`, study folders). It may never drop
one of the six above.

**`REFACTORING.md`** (added to every category by the 2026-07-28 sweep) is a seventh per-category
file: a prioritized register of refactoring candidates with line counts, cross-file duplication %
(sourced from a repo-wide `jscpd` run), the duplicated-with list, and a one-line refactoring plan
per row, followed by a `## Sequencing` section ordering the top 5. It is reference material for
maintainers and agents; it is updated when code lands that changes the duplication picture, and it
is not a task-status surface.

### 2.2 The task-file template

Every `Task.<n>.md` starts with a status header and follows this section order:

```markdown
# <Category> Task <n> — <Title>

**Status:** ✅ COMPLETED | 🚧 IN PROGRESS | ⏳ BLOCKED | ⬜ NOT STARTED
**Category:** <category> · **Owns:** <limitation ids> · **Last audit:** <YYYY-MM-DD>
**Depends on:** <tasks/documents that must be delivered first>
**Consumed by:** <who uses this deliverable>

## Goal            — one paragraph: what exists when this task is done
## Status detail   — what landed, what remains, with evidence (test/IT names)
## Dependencies    — hard prerequisites, in-category and cross-category
## Deliverables    — the concrete units of work
## Design          — the load-bearing decisions and why
## Files           — where the code lives
## Testing         — the tests that prove it
## Acceptance criteria — the checklist that closes the task
## Limitations     — rows this task owns in the category register
```

Status values are exact: **✅ COMPLETED** (all acceptance criteria met and green), **🚧 IN
PROGRESS** (started, no external blocker), **⏳ BLOCKED** (waiting on another task's phase), **⬜
NOT STARTED**.

### 2.3 A push is not the end of a commit

A commit is finished when CI says so, not when `git push` returns. After pushing, **come back and
read the checks** — and read them against the commit you pushed, not the branch in general, because
a green branch with a red job on your head commit is the shape a broken build hides in.

Two rules earned the hard way:

- **A failing job is a claim about the code until proven otherwise.** "Known flake" is a hypothesis
  nobody retested; two rows in this repository's history were labelled that way and both turned out
  to be real defects — a missing latch release, and a handshake that wrote its two frames in the
  wrong order under scheduler pressure. Read the exception before re-running.
- **A test that passes locally has not tested the thing CI failed on.** Scheduling races lose on a
  2-core runner and win on an idle workstation. When a local reproduction does not fail, say so in
  the test's comment rather than implying the fix was proven empirically.

### 2.3.1 Cross-references

Documents reference each other by relative path. A task that cannot start until another task is
delivered **must** name it under `Depends on:` — the dependency graph in
[`ROADMAP.md`](ROADMAP.md) is derived from those headers and must agree with them.

---

## 3. Category map

| Category | Component | Code | Tasks |
|---|---|---|---|
| [`engine/`](engine/Task.0.md) | Deterministic region engine, shadow validation, coordinator, committee quorum, fallback router, interference guard, parity program | `java/core`, `java/engine`, `java/testing` | 12 |
| [`network/`](network/Task.0.md) | Wire protocol, transports, peer runtime, event-sourced storage, torrent data plane, discovery, replication, encryption, crash safety, telemetry | `java/transport`, `java/storage`, `java/peer`, `rust/nodera-codec` | 14 |
| [`tracker/`](tracker/Task.0.md) | Always-on world/peer discovery service and its Java client | `rust/nodera-tracker`, `dev.nodera.peer.discovery` | 6 |
| [`rendezvous/`](rendezvous/Task.0.md) | NAT reach: signed registration/discovery, hole punching, E2E-encrypted relay fallback | `rust/nodera-rendezvous`, `dev.nodera.transport.rendezvous` | 6 |
| [`minecraft/`](minecraft/Task.0.md) | The NeoForge mod — capture, live lanes, GUI, host lane, world identity, companion gate | `java/neoforge-mod` | 11 |
| [`worker/`](worker/Task.0.md) | The required always-on headless peer and its loopback control protocol | `java/worker` (`dev.nodera.headless`), `dev.nodera.peer.control` | 8 |
| [`app/`](app/Task.0.md) | The Tauri desktop companion that supervises the worker | `rust/nodera-app` | 10 |
| [`mobile/`](mobile/Task.0.md) | The Android build: the same app **and the same Java worker**, in one process on a phone | `rust/nodera-app/android`, `scripts/android-*.sh` | 5 |
| [`server/`](server/Task.0.md) | The Paper/Folia endpoint plugin — nodes that are also Minecraft servers | (unwritten) | 10 |
| [`telemetry/`](telemetry/Task.0.md) | Consented, de-identified measurement: ingest service + Big Data plane | `rust/nodera-telemetry`, `docker/telemetry` | 3 |

Category boundaries are **documentation** boundaries; they do not always equal Gradle/Cargo module
boundaries. The mapping from category to module is stated in each category's `Task.0.md` §Files,
and every package carries its own `README.md` describing its architecture
(`java/*/README.md`, `rust/*/README.md`).

---

## 4. Repository conventions (binding on all tasks)

### 4.1 Layout — polyglot monorepo

- `java/<module>/` — nine Gradle modules plus build logic: `core` · `engine` · `transport` ·
  `storage` · `peer` · `worker` · `testing` · `neoforge-mod` · `paper-plugin` (+ `build-logic`).
- `rust/` — cargo workspace: `nodera-codec` (canonical-encoding conformance), `nodera-tracker`,
  `nodera-rendezvous`, `nodera-telemetry`, and `nodera-app` (workspace-**excluded**: Tauri native
  deps).
- `docker/` — deployments that are never dependencies of the game (`docker/telemetry/`).
- `fixtures/wire/` — golden canonical frames, emitted by Java and re-encoded byte-exactly by Rust.
  **Never edit a fixture by hand.**

### 4.2 Two toolchains, one gate

`./gradlew check` **and** `cd rust && cargo test` must both be green. `scripts/dev.sh --test` runs
both plus the lint gate. Cross-language conformance tests (`fixtures/wire/*.bin` + the tag-registry
mirror) hold the two canonical-encoding implementations to the same contract.

### 4.3 Layering

1. `core` depends on nothing but the JDK.
2. `engine`, `transport`, `storage` depend on `core`. `peer` depends on those four; `worker` depends
   on `peer` and the libraries it composes. `testing` depends on `core` + `engine` + `transport`.
3. **No Minecraft/NeoForge types outside `neoforge-mod`.** Where a Minecraft concept is needed,
   `core` defines its own representation (`NBlockState` int id, `NBlockPos` record) and the mod owns
   the mapping.
4. Client-only code lives only under `dev.nodera.mod.client`, guarded by `Dist.CLIENT`.
5. All mutation of a real `ServerLevel` happens in exactly one class (`WorldMutationApplier`) on the
   server main thread. Everything else produces data (`RegionDelta`) and hands it off.
6. A delegated region's chunks are mutated **only** by `WorldMutationApplier`; every other write
   source is suppressed or converted into a certified `ExternalDelta` by the interference guard.
7. **Rust service crates carry no game, consensus, or storage logic.** They are
   discovery/forwarding infrastructure — peers verify everything a service says and never treat it
   as authority.

Every capability is **proven Minecraft-free first** (headless JUnit), then wired to NeoForge. This
is the recurring pattern of the whole build and it is not optional: a phase without a Minecraft-free
proof does not merge.

### 4.4 Frozen contracts — never change without a version bump

- Canonical encoding: `core/crypto/{CanonicalWriter,CanonicalReader,Encodable,TypeTags}`. Every
  `Encodable.encode` starts with `writeU16(typeTag); writeU16(ENCODING_VERSION);`.
- `core/Bytes` is the single `byte[]` value type — never a raw `byte[]` in a record.
- Wire tags: `dev.nodera.protocol.codec.MessageCodec` is **append-only**; never renumber. Appending
  a tag means appending it on **both** the Java and Rust sides in the same commit, with a golden
  fixture.
- Hash: SHA-256 over canonical encoding (`core/crypto/HashService`). Signatures: Ed25519
  (`core/crypto/SignatureService`; signing on `core/identity/NodeIdentity`).

### 4.5 Determinism ground rules (apply to `dev.nodera.simulation` and anything it calls)

Forbidden: `System.currentTimeMillis`, `System.nanoTime`, `ThreadLocalRandom`, `Math.random`,
`UUID.randomUUID`, iteration over `HashMap`/`HashSet`, `String.hashCode`-dependent ordering,
filesystem/network access, static mutable state, and **floats/doubles in hashed state** (continuous
quantities use Q32.32 fixed point via `FixedVec3`).

Required: all randomness via `DeterministicRandom`; all hashing via `HashService` over
`CanonicalEncoder` output; every collection in hashed state sorted at encode time or order-stable by
construction. Enforced by ArchUnit (`simulation/ForbiddenApiTest`) and
`simulation/DeterminismPropertyTest`. **There is exactly one region engine** (Java `engine`) — no
second implementation may re-execute regions, in any language.

### 4.6 Naming and coordinates

| Thing | Value |
|---|---|
| Root project | `nodera` · Maven group `dev.nodera` · mod id `nodera` |
| Base package | `dev.nodera.<component>`; mod package `dev.nodera.mod` (`common`/`server`/`client`) |
| Wire protocol version | `"1"` (NeoForge payload registrar version string) |
| Control protocol version | `2` (worker loopback endpoint, `127.0.0.1:25610`) |
| Config files | `nodera-server.toml`, `nodera-client.toml` (NeoForge config API) |
| Version pins | Minecraft 1.21.1 · NeoForge 21.1.238 · Java 21 toolchain (`--release 21`) · Gradle 8.x · Rust toolchain in `rust/rust-toolchain.toml` |

### 4.7 Shared constants (`core`, class `NoderaConstants`)

```java
REGION_SIZE_CHUNKS   = 8      HALO_CHUNKS               = 1
BATCH_TICKS          = 2      BATCH_MAX_MILLIS          = 100
CHECKPOINT_INTERVAL_TICKS = 100
LEASE_LENGTH_TICKS   = 200    LEASE_RENEW_TICKS         = 40
HEARTBEAT_TICKS      = 20
QUORUM_MVP           = "2 of 3"    QUORUM_PEER          = "3 of 4"
HASH_ALGORITHM       = SHA-256     SIGNATURE_ALGORITHM  = Ed25519
DELEGABLE_NEIGHBOR_RING     = 1    DELEGABILITY_COOLDOWN_TICKS = 200
INTERFERENCE_REVOKE_RATE    = 60/min
```

These are *defaults* surfaced through config: code reads config, tests read constants.

### 4.8 Versioning — the root `VERSION` file (binding)

<!-- AI-AGENT-INSTRUCTION: NEVER hard-code a product version anywhere. If you need the version in new
     code, read it from the mechanism for that toolchain (below) or add a mirror to
     scripts/version.sh so the drift check covers it. A release is `scripts/version.sh --set X.Y.Z`
     plus ONE commit; a hand-edited version in a second file is a bug the gate will catch. -->

**The repository root `VERSION` file is the single source of truth for the product version.** It
contains one line (`0.1.0`); comments and blank lines are allowed and ignored.

| Toolchain | How it gets the version |
|---|---|
| Gradle | `settings.gradle.kts` reads `VERSION` and injects `noderaVersion`/`modVersion` into every project; the root build sets `version` from it |
| Java runtime | `java/core` expands it into `nodera-version.properties`; `NoderaConstants.PRODUCT_VERSION` loads that resource (falling back to `0.0.0-unbuilt`, which means the build did not run) |
| NeoForge mod | The convention plugin stamps `modVersion` into `neoforge.mods.toml` |
| Rust services | Each service crate's `build.rs` reads `VERSION` and sets `NODERA_VERSION`; binaries print that, not `CARGO_PKG_VERSION` |
| Cargo / Tauri / npm manifests | **Mirrors** — formats that cannot read a file at build time. `scripts/version.sh` writes them and `--check` fails the gate when one drifts |

```bash
scripts/version.sh                # print the current version
scripts/version.sh --check        # verify every mirror agrees (runs in the gate)
scripts/version.sh --set 0.2.0    # bump VERSION and rewrite every mirror
```

**Release procedure.** `--set` the new version, run the gate (`scripts/dev.sh --test`), commit
`VERSION` *together with* the rewritten mirrors in **one** commit, tag it, then let CI publish. Never
bump a mirror by hand: `NoderaVersionTest` (Java) and `scripts/version.sh --check` (everything else)
exist precisely to catch that.

### 4.9 Work tracking

GitHub-issue-driven: every task is an issue; every detected problem becomes a `bug` issue before a
regression reaches `main`. One task = one branch = one PR. **A task number is not an issue number** —
find the real issue by its exact title before writing `refs #N` / `closes #N`. The normative
workflow is [`.github/ISSUE_SYSTEM.md`](../.github/ISSUE_SYSTEM.md).

Commit format:

```
<emoji> [<overall-percentage>%] <change type>: <short description in English>
```

---

## 5. Documentation maintenance discipline (mandatory)

<!-- AI-AGENT-INSTRUCTION: THIS SECTION IS NOT ADVISORY. Documentation is part of the deliverable,
     not a follow-up. A change that lands code without the corresponding documentation update is an
     incomplete delivery and must not be committed. Perform the updates below in the FINAL STAGE of
     every delivery, in the SAME commit as the code. -->

**Documentation is updated in the final stage of every delivery, in the same commit as the code.**
A pull request that changes behaviour and leaves the docs stale is not done.

On every outcome-changing commit:

1. **The owning `docs/<category>/Task.<n>.md`** — update the status header (`✅/🚧/⏳/⬜`), the
   status-detail section, and the `Last audit` date.
2. **`docs/<category>/PROGRESS.md`** — move the task's row and add a dated milestone note stating
   what landed and the evidence (test or IT name).
3. **`docs/<category>/TESTING.md`** — update test counts, the last-run date, and any new suite or
   procedure.
4. **`docs/<category>/LIMITATIONS.md`** — if the change stages, advances, or retires a limitation,
   move it here; a **RETIRED** row moves to `LIMITATIONS.fixed.md` **with its evidence**.
5. **`docs/ROADMAP.md`** — if a task changed status, update its row, re-check the wave it sits in,
   and re-derive the overall percentage.
6. **`README.md` (repository root)** — recompute the progress bar and the module table.
7. **The package `README.md`** — if a package's architecture changed (a new subsystem, a moved
   seam, a new external dependency), update `java/<module>/README.md` or `rust/<crate>/README.md`.
8. **The GitHub issue** — `refs #N` while working; `closes #N` with the evidence when the task's
   acceptance criteria are met.

Rules that make this reliable:

- **No permanent exclusions.** A newly discovered limitation enters its category `LIMITATIONS.md`
  as `OPEN` with an elimination path, an owning task, and an exit test — before the discovering PR
  merges. "Permanent" is a banned classification.
- **Never delete a limitation.** Rows move `OPEN → RETIRING → RETIRED`; a retired row moves to
  `LIMITATIONS.fixed.md` carrying the evidence that retired it.
- **Verify against the exit test, not the prose.** A row retires when its *stated exit test* is
  green — not when its narrative "remaining" note looks small.
- **Preserve `<!-- AI-AGENT-INSTRUCTION: ... -->` comments** when editing any document. They are
  the machine-readable half of this discipline.
- **Status must agree across files.** `ROADMAP.md`, the category `PROGRESS.md`, the task header,
  the root `README.md`, and `TESTING.md` must never disagree. If they do, the task file is the
  source of truth for scope and `TESTING.md` for counts.

---

## 6. Where to start

| You want to… | Read |
|---|---|
| Understand the whole system and what is left | [`ROADMAP.md`](ROADMAP.md) |
| Work on a component | that category's `Task.0.md`, then the task you were assigned |
| Know how something is tested | that category's `TESTING.md` |
| Know what does not work yet, and why | that category's `LIMITATIONS.md` |
| Understand a package's internals | `java/<module>/README.md` or `rust/<crate>/README.md` |
| Understand the origin research and prior art | [`minecraft/RESEARCH.md`](minecraft/RESEARCH.md), [`minecraft/folia/`](minecraft/folia/), [`minecraft/MultiPaper/`](minecraft/MultiPaper/) |
| Read the architecture plan and locked decisions | [`plans/Plan.0.md`](plans/Plan.0.md) |
| Set up an agent session | [`../AGENTS.md`](../AGENTS.md) — always-loaded agent memory |
