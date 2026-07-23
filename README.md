# NoderaMC

> **Nodera** — "derived from node; clean, modern, and suitable for an engine or platform."
>
> A NeoForge-based system where the Minecraft world is partitioned into chunk regions, each simulated
> and validated by a small committee of player-run peers. **Any player can host a world directly** —
> share an existing world to the network from the pause menu (like "Open to LAN"), and peers reach
> each other through a tracker + rendezvous relay. **No central server is required** (Task 5e); an
> optional dedicated server is just a well-provisioned archival peer with a single, non-authoritative
> vote.

<!-- AI-AGENT-INSTRUCTION: README.md is a living document. Every commit that completes a task or
     changes test status MUST update: (1) the progress bar below, (2) the module status table,
     (3) Tested.md. Keep comments like this one intact — they guide future agents. See
     .github/ISSUE_SYSTEM.md and AGENTS.md for the full workflow. -->

---

## Progress

<!-- AI-AGENT-INSTRUCTION: Recompute `overall` as a weighted fraction of the 8 implementation
     phases (Plan §6) and keep filled blocks / 20 ≈ the percentage. The DETAILED per-phase
     table + milestone notes live in docs/PROGRESS.md — update THAT file (and Tested.md) on
     every outcome-changing commit; this section keeps only the bar. -->

**Overall system completion: `75.2%`**
`███████████████░░░░░`

Per-phase detail + milestone notes: [`docs/PROGRESS.md`](docs/PROGRESS.md) · test counts:
[`Tested.md`](Tested.md) · order/priority: [`docs/Roadmap.md`](docs/Roadmap.md)

---

## Module status

<!-- AI-AGENT-INSTRUCTION: This table mirrors Tested.md. Update both together. Status emojis:
     ✅ done · 🚧 partial · ⏳ in progress · ⬜ not started · ❌ failing. -->

| Module | Responsibility | Tests | Status |
|---|---|---|---|
| `core` | domain types, JDK-only crypto, canonical encoding, transition-bound authority/vote/joint-transfer certificates, and Task 12 entity snapshots/deltas/mutations/credits/transfer records (tags through 102) | 211 | ✅ |
| `engine` | **unified deterministic-engine + validation API (issue #30)** — deterministic engine + consensus/shadow/coordinator/committee/fallback; Task 12 adds fixed-point items, throttled ghost interference, playerless isolation, transfer recovery, pearl policy, and soak metrics | 286 | ✅ |
| `transport` | **unified network API (issue #30)** — append-only wire plane + socket/rendezvous carriers; message tags through 52 (transfer prepare/accept/commit, tracker routes, continuity-lane `WorldManifestQuery`/`Answer`); shared golden fixtures remain byte-exact | 79 | ✅ |
| `storage` | **unified storage API (issue #30)** — event-sourced and RocksDB tiers include atomic paired event append, joint transfer certificates, and durable transfer stages alongside checkpoints/content/certificates | 64 | ✅ |
| `testing` | shared test library (issue #30; formerly `testkit`): `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | ✅ |
| `peer` | **unified peer API (issue #30)** — distribution/runtime/diagnostics/headless worker plus authenticated validation, disjoint-committee transfer routing, process-kill recovery, durable journals, and the world-continuity lane (`WorldArchive` + worker seeding/manifest-serving/swarm-fetch, `SEED`/`ARCHIVE` verbs, `WorldContinuityIT` host-death survival) | 318 | 🚧 |
| `neoforge-mod` | `@Mod` entrypoints + role-driven host wiring, Task 12 adapters, and the continuity halves (`WorldArchiver` share/stop seeding, `NoderaContinuity` disconnect-rehost, server-dist companion gate); Task 5b evidence remains | 32 | 🚧 |
| `rust/nodera-codec` | (Task 27) Rust canonical-encoding conformance crate: byte-exact port + Ed25519 verify + tag mirror through Java type tag 102/message tag 48 + socket framing | 35 | ✅ |
| `rust/nodera-tracker` | (Task 28) standalone tracker service binary — signed announce lifecycle, per-world swarm registry, TTL expiry, sampling with a seeder floor, health + retention countdown, per-IP quotas; embedded Java `TrackerService` deleted (L-44 RETIRED) | 54 | ✅ |
| `rust/nodera-rendezvous` | (Task 29) rendezvous + relay service binary — signed registration/discovery, HMAC relay reservations + metered tokio circuit bridging, hole-punch coordination (L-23/L-27 RETIRED) | 55 | ✅ |
| `rust/nodera-app` | (Task 32) Tauri companion app — always-on headless-peer supervisor (Option B: bundled Java peer) + loopback control endpoint (mod presence gate) + system tray + autostart + React dashboard (chunks/GB/peers/world). Workspace-EXCLUDED (Tauri native deps); built separately | scaffold | 🚧 |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | ⬜ |

---

## Build & test

<!-- AI-AGENT-INSTRUCTION: ALWAYS run `./gradlew check` before committing. Never commit on a red
     build. If a test fails and you cannot fix it immediately, open an issue (see
     .github/ISSUE_SYSTEM.md) and do NOT commit the regression. -->

```bash
./gradlew check                 # compile + all Java unit tests (the gate)
./gradlew build                 # check + assemble jars
./gradlew :core:test            # one module's tests (names unchanged after the java/ move)
./gradlew check --rerun-tasks   # force re-run (ignore up-to-date caching)

cd rust && cargo test           # Rust unit + cross-language conformance tests (equally required)
cd rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings
```

## Run the local stack

Task 30 retired the central NeoForge dedicated server; Task 32 added the always-on **peer worker**
(`nodera-headless`) that keeps a player on the network with Minecraft closed and that the mod
**requires**. One script builds the toolchains and runs the two **untrusted** infrastructure services
— `nodera-tracker` (peers locate worlds) and `nodera-rendezvous` (NAT hole-punch + relay) — plus the
peer worker (the control endpoint the mod probes):

```bash
scripts/dev.sh                   # build Rust + mod + worker, run tracker + rendezvous + worker
scripts/dev.sh --with-app        # also build + launch the Tauri companion app (attach mode) alongside the worker
scripts/dev.sh --install-mod     # also copy build/neoforge-mod.jar into ~/.minecraft/mods (NODERA_MC_DIR)
scripts/dev.sh --no-worker       # infra services only (mod will refuse to launch without a worker)
scripts/dev.sh --build-only      # compile everything, collect artifacts into build/, then exit
scripts/dev.sh --test            # run the full gate (gradlew build + cargo test) as part of the build
scripts/dev.sh --help            # options + env overrides (ports, dirs)
```

To play/test: drop `build/neoforge-mod.jar` into a **NeoForge 1.21.1** client's `mods/` folder (or
use `--install-mod`), **keep `scripts/dev.sh` running** (the mod requires the peer worker — it aborts
startup with an install prompt if the worker is not answering on `127.0.0.1:25610`), launch the
client, open a world, and press **"Open to Nodera"** in the pause menu to broadcast it to the network
— with an optional password. (To run the mod without the worker, set `companion.required = false` in
`config/nodera-client.toml`.)

Every build collects both toolchains' outputs — the `nodera-tracker` and `nodera-rendezvous`
binaries and `neoforge-mod.jar` — together into the top-level `build/` directory, and the run phase
starts the tracker + rendezvous from there and health-checks each on its port. CI
(`.github/workflows/release-latest.yml`) runs the same `scripts/dev.sh --build-only` on every push
and attaches the three artifacts to a rolling `latest` GitHub **prerelease** (marked latest, not an
officially published release). Ctrl-C stops both services.

Host runs JDK **25**; Task 0 pins Java 21. The pure-Java modules use only Java 21-era features
(records, sealed interfaces, virtual threads, pattern matching) so they stay source-compatible when
the 21 toolchain is restored.

---

## Project layout

Polyglot monorepo (Task 27): Java modules under `java/`, Rust service crates under `rust/`, one
shared `fixtures/` corpus proving the two encodings agree byte-for-byte.

```
nodera/
├── java/                ALL Gradle modules (names unchanged: `./gradlew :core:test` still works)
│   ├── build-logic/         convention plugins (java-library)
│   ├── core/                identity, region, action, state, event, certificates, JDK crypto (incl. AES-GCM/PBKDF2)
│   ├── engine/              unified engine+validation API (issue #30): simulation (determinism ban intact) + consensus + shadow/coordinator/committee/fallback phases
│   ├── transport/           unified network API (issue #30): protocol wire plane + PeerTransport carriers (socket, rendezvous) + Frames/Reachability
│   ├── storage/             unified storage API (issue #30): WorldStore seam + event-sourced, RocksDB, and bounded-client tiers + EventChainGuard/RegionOrder/AtomicFileWriter
│   ├── peer/                unified peer API (issue #30): distribution data plane + peer runtime/discovery/archival/control + diagnostics telemetry + the nodera-headless worker
│   ├── testing/             shared test library: LoopbackTransport, FakeRegion, FixtureWriter/Reader
│   ├── neoforge-mod/        (Task 1) @Mod entrypoints + bootstrap-peer wiring, redesigned /nodera diagnostics tree + /noderac + HUD surfaces; runServer/runClient deferred
├── rust/                cargo workspace (rust-toolchain.toml pins the channel)
│   ├── nodera-codec/        (Task 27) byte-exact canonical-encoding port + Ed25519 verify + tag mirror + framing
│   ├── nodera-tracker/      (Task 28) standalone tracker service — announce/query, real binary driven by TrackerServiceIT
│   └── nodera-rendezvous/   (Task 29) rendezvous + relay service — registration/discovery/reservations/circuit bridging
├── fixtures/wire/       golden canonical frames, emitted by Java, re-encoded byte-exactly by Rust
├── scripts/             dev (build Rust + mod, run tracker + rendezvous; --install-mod for a real client)
└── docs/                Task.0.md (base doc: orientation + conventions + index),
                         Task.1..7.md (one task per Nodera module), Plan.0.md, LIMITATIONS.md,
                         Roadmap.md, LEGACY.md, torrent/ (tracker + rendezvous reference specs),
                         minecraft/ (MultiPaper + Folia studies), old/ (legacy per-increment
                         Task.0..33.md specs + Prompt.base.md + MONOREPO.md — preserved verbatim),
                         Context/
```

> **Monorepo is the default architecture** (restructure landed 2026-07-19; former migration file
> `MONOREPO.md` retired — durable content lives in [`docs/Task.0.md`](docs/Task.0.md) §3 and
> `AGENTS.md`): [`docs/LEGACY.md`](docs/LEGACY.md) ledgers the Java code the Rust services
> replace. Module names did not change — only paths — so every `./gradlew` invocation and every
> `build.gradle.kts` kept working untouched.

---

## Commit message standard

<!-- AI-AGENT-INSTRUCTION: EVERY completed-task commit MUST use this exact format. Pick the emoji
     + change type from the legend. Update the README progress bar in the SAME commit. -->

```
<emoji> [<overall-percentage>%] <change type>: <short description in English>
```

**Legend**

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

**Examples**
```
✨ [14%] feature: implement Phase 1 shadow capture mixins (refs #5)
🐛 [14%] fix: align FlatWorldRules.MAX_Y with column ceiling (fixes #21)
🧪 [13%] test: add jqwik property test for negative-coordinate halo reads
```

---

## GitHub issue system (how work is tracked)

<!-- AI-AGENT-INSTRUCTION: Treat GitHub issues as the source of truth for what to do next. Open an
     issue for every task AND every detected problem. Full rules: .github/ISSUE_SYSTEM.md. -->

- **Every task is an issue.** The roadmap lives at the GitHub Issues tab, labelled `task`. See
  `.github/ISSUE_SYSTEM.md` for the complete workflow (open / assign / branch / commit / close /
  reopen) and the issue templates in `.github/ISSUE_TEMPLATE/`.
- **Every detected problem becomes an issue**, labelled `bug`, before (not after) a regression
  reaches `main`.
- **One task = one branch = one PR.** Branch name: `<emoji-less-type>/<short-slug>-#<issue>` e.g.
  `feature/shadow-capture-#5`. Commits cite the issue (`refs #5` while working, `fixes #5` /
  `closes #5` to close).
- **Closing an issue requires**: `./gradlew check` green, README progress + Tested.md updated, the
  task's acceptance criteria linked from the PR description.

See [`.github/ISSUE_SYSTEM.md`](.github/ISSUE_SYSTEM.md) for the normative rules.

---

## Roadmap (tasks → issues)

<!-- AI-AGENT-INSTRUCTION: This mirrors docs/Task.0.md §4 (module-task index) and each
     docs/Task.<n>.md "Implementation status" table. When a phase completes, tick it here, update
     the owning task file's status table, AND close the phase's GitHub issue. Legacy per-increment
     history (old Tasks 0–33 + their issue numbers) is preserved in docs/old/ and mapped in
     docs/Task.0.md §4 — GitHub issues keep their legacy titles; find them by title, never by
     number. -->

**2026-07-21 consolidation:** the 33 per-increment specs were consolidated into **one task per
Nodera module** (Tasks 0–7). The old files are preserved verbatim in
[`docs/old/`](docs/old/); the legacy→new mapping lives in [`docs/Task.0.md`](docs/Task.0.md) §4.

| # | Task (module) | Spec | Status |
|---|---|---|---|
| 0 | **Base document** — orientation, conventions, task index (absorbs `Prompt.base.md` + old Task 0; monorepo default) | [`docs/Task.0.md`](docs/Task.0.md) | ✅ living doc |
| 1 | **Deterministic engine & committee validation** — `core`/`simulation`/`consensus`/`committee`/`coordinator`/`shadow-validation`/`fallback` | [`docs/Task.1.md`](docs/Task.1.md) | 🚧 (1a–1g ✅ headless; 1h headless/durable acceptance green with live adapters composed, but Task 5b genesis bindings + `runClient` remain; 1i redstone next, 1j/1k/1l waiting) |
| 2 | **P2P network** — `protocol`/`transport-*`/`peer-runtime`/`storage-*`/`distribution`/`diagnostics` | [`docs/Task.2.md`](docs/Task.2.md) | 🚧 (2a/2c–2k ✅ headless — wire+transports, event-sourced+RocksDB storage, torrent data plane, discovery/multi-bootstrap, replication+repair, reliability/quotas/retention, encryption, crash safety+stream, tick-lag handoff, telemetry; 2b gateway-migration remainder + every live half rides Task 5) |
| 3 | **P2P network tracker** — `rust/nodera-tracker` + Java `TrackerClient` | [`docs/Task.3.md`](docs/Task.3.md) | ✅ core (L-44 RETIRED; 3b announce scheduling rides 5d; 3c ops hardening 🚧) |
| 4 | **P2P rendezvous** — `rust/nodera-rendezvous` + `transport-rendezvous` (NAT reach) | [`docs/Task.4.md`](docs/Task.4.md) | ✅ core (L-23/L-27 RETIRED; 4c live cross-internet soak ⏳ waits 5b) |
| 5 | **NeoForge Minecraft (Java) module** — `neoforge-mod` + `transport-neoforge` | [`docs/Task.5.md`](docs/Task.5.md) | 🚧 (5g gate ✅; 5c HUD + 5d GUI + 5e host lane + 5f identity/permissions landed compile+headless; 5a `runClient` harness (L-45) and 5b live validation lane are the blockers) |
| 6 | **Peer worker** — `nodera-headless` + `peer-runtime/control` (required always-on node) | [`docs/Task.6.md`](docs/Task.6.md) | 🚧 (6a boot+probe ✅, 6b control v2+telemetry ✅ verified live; 6c host/join delegation + seeding 🚧; 6d out-of-game validation ⏳ — L-41 RETIRING, L-48) |
| 7 | **Tauri companion app** — `rust/nodera-app` | [`docs/Task.7.md`](docs/Task.7.md) | 🚧 (7a scaffold + 7b live metrics ✅; 7c installers/CI 🚧; 7d end-to-end continuity ⏳ — L-47) |

The **"torrent hosting" feature** (a world becomes a shared, content-addressed, multi-seeder
resource) is Task 2 phases 2d–2j + the Task 5 GUI/host phases. Additive to committee validation:
seeders store/propagate only; the active region's committee (1e) still re-executes+commits.

The **Rust infrastructure services** (Tasks 3/4) are verified-never-trusted: an outage degrades
discovery/reach, never correctness ([`LEGACY.md`](docs/LEGACY.md) ledgers the Java code they
replaced).

Full task specs: [`docs/Task.0.md`](docs/Task.0.md) … [`docs/Task.7.md`](docs/Task.7.md);
legacy class-level specs: [`docs/old/`](docs/old/).
Implementation order + priority + difficulty rankings (legacy numbering):
[`docs/Roadmap.md`](docs/Roadmap.md).

---

## Agent memory & discipline

<!-- AI-AGENT-INSTRUCTION: AGENTS.md is the always-loaded agent memory. The three non-negotiable
     disciplines are: (1) run tests before commit, (2) update README progress + Tested.md, (3) use
     the commit-message standard above. Re-read AGENTS.md at the start of every session. -->

The single source of agent instructions is [`AGENTS.md`](AGENTS.md). It is auto-loaded by coding
agents (opencode, Cursor, Claude Code, …) and encodes: build/test commands, layering rules, the
frozen contracts, the test-before-commit / update-README / commit-format disciplines, and the
GitHub issue workflow. **Read it before doing anything.**

The **base document** (orientation prompt + conventions + task index — which files are
load-bearing, the project pattern, where progress lives, how to open/close issues) is
[`docs/Task.0.md`](docs/Task.0.md).
