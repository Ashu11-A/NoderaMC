# NoderaMC

> **Nodera** — "derived from node; clean, modern, and suitable for an engine or platform."
>
> A NeoForge-based system where the Minecraft world is partitioned into chunk regions, each simulated
> and validated by a small committee of player-run peers, with a dedicated server that starts as the
> coordinator and is progressively demoted to a non-authoritative full archival bootstrap peer.

<!-- AI-AGENT-INSTRUCTION: README.md is a living document. Every commit that completes a task or
     changes test status MUST update: (1) the progress bar below, (2) the module status table,
     (3) Tested.md. Keep comments like this one intact — they guide future agents. See
     .github/ISSUE_SYSTEM.md and AGENTS.md for the full workflow. -->

---

## Progress

<!-- AI-AGENT-INSTRUCTION: Recompute `overall` as a weighted fraction of the 8 implementation phases
     (Plan §6). Phase 0 pure-Java slice is complete; later phases dominate total effort. Update the
     block count so that filled blocks / 20 ≈ the percentage. Keep the legend. -->

**Overall system completion: `12%`**
`████░░░░░░░░░░░░░░░░░░░░`

| Phase | Scope | Status |
|---|---|---|
| Phase 0 — Scaffolding | Gradle + pure-Java core/simulation/protocol/consensus/testkit | 🚧 `85%` (NeoForge mod skeleton pending) |
| Phase 1 — Shadow validation | capture mixins, worker runtime, divergence report | ⬜ `0%` |
| Phase 2 — Coordinator | leases, epochs, client proposal + server verify | ⬜ `0%` |
| Phase 3 — Committee validation | **MVP gate** (3-client quorum) | ⬜ `0%` |
| Phase 4 — Server fallback only | cross-region router, soak metrics | ⬜ `0%` |
| Phase 5 — Archival bootstrap peer | peer-runtime, event-sourced storage | ⬜ `0%` |
| Phase 6 — Gateway migration, P2P | libp2p, archival repair, multi-bootstrap | ⬜ `0%` |
| Phase 7–8 — Parity program | redstone, environment, mobs, player lane, BFT, mod SDK | ⬜ `0%` |

**Tests:** `183 passing · 0 failing · 7 skipped` (skipped = ArchUnit rules disabled under JDK 25; see AGENTS.md).

---

## Module status

<!-- AI-AGENT-INSTRUCTION: This table mirrors Tested.md. Update both together. Status emojis:
     ✅ done · 🚧 partial · ⏳ in progress · ⬜ not started · ❌ failing. -->

| Module | Responsibility | Tests | Status |
|---|---|---|---|
| `core` | domain types, crypto, canonical encoding (frozen wire/hash contract) | 85 | ✅ |
| `simulation` | deterministic region engine (the determinism bet) | 28 | ✅ |
| `protocol` | wire messages, MessageCodec, zstd chunked streams | 23 | ✅ |
| `consensus` | quorum, votes, equivocation, adaptive spot-checks | 23 | ✅ |
| `transport-api` | `PeerTransport` seam | 9 | ✅ |
| `storage-api` | `WorldStore`/content/checkpoint interfaces (stub) | 1 | 🚧 |
| `testkit` | `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | ✅ |
| `transport-neoforge` | NeoForge payload relay transport | — | ⬜ |
| `neoforge-mod` | `@Mod` entrypoints (common/dedicated/client) | — | ⬜ |
| `storage-rocksdb` | full-archive RocksDB store | — | ⬜ |
| `storage-client` | bounded/quota'd client store | — | ⬜ |
| `peer-runtime` | `PeerRuntime`, discovery, committees, archival, sync | — | ⬜ |
| `transport-libp2p` | direct P2P behind `PeerTransport` | — | ⬜ |
| `integration-tests` | three-client-quorum, failover, byzantine, cross-region, debugger | — | ⬜ |

---

## Build & test

<!-- AI-AGENT-INSTRUCTION: ALWAYS run `./gradlew check` before committing. Never commit on a red
     build. If a test fails and you cannot fix it immediately, open an issue (see
     .github/ISSUE_SYSTEM.md) and do NOT commit the regression. -->

```bash
./gradlew check                 # compile + all unit tests (the gate)
./gradlew build                 # check + assemble jars
./gradlew :core:test            # one module's tests
./gradlew check --rerun-tasks   # force re-run (ignore up-to-date caching)
```

Host runs JDK **25**; Task 0 pins Java 21. The pure-Java modules use only Java 21-era features
(records, sealed interfaces, virtual threads, pattern matching) so they stay source-compatible when
the 21 toolchain is restored.

---

## Project layout

```
nodera/
├── build-logic/         convention plugins (java-library)
├── core/                identity, region, action, state, event, certificates, crypto
├── protocol/            wire messages + MessageCodec + ChunkedStreams (zstd)
├── simulation/          DeterministicRegionEngine, FlatWorldRules, DeterministicRandom
├── consensus/           QuorumPolicy, VoteCollector, EquivocationDetector, SpotCheckPolicy
├── transport-api/       PeerTransport seam
├── storage-api/         WorldStore interfaces
├── testkit/             LoopbackTransport, FakeRegion, FixtureWriter/Reader
├── neoforge-mod/        (Task 1) @Mod entrypoints — not yet onboarded
├── transport-neoforge/  (Task 4) payload relay — not yet onboarded
└── docs/                Plan.md, LIMITATIONS.md, Task.0..16.md, Context/
```

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

<!-- AI-AGENT-INSTRUCTION: This mirrors docs/Plan.md §6 and Task.0.md §1. When a task completes,
     tick it here AND close its GitHub issue. -->

| # | Task | Phase | Issue | Status |
|---|---|---|---|---|
| 1 | Build scaffolding + NeoForge mod skeleton | 0 | `#1` | 🚧 |
| 2 | `core`: domain types + crypto + canonical encoding | 0 | `#2` | ✅ |
| 3 | `simulation`: deterministic region engine | 0 | `#3` | ✅ |
| 4 | `protocol` + `transport-api` + `transport-neoforge` | 0 | `#4` | 🚧 |
| 5 | Shadow validation (capture, worker, divergence) | 1 | `#5` | ⬜ |
| 6 | Coordinator (leases, epochs, client proposal) | 2 | `#6` | ⬜ |
| 7 | Committee validation — **MVP gate** | 3 | `#7` | ⬜ |
| 8 | Server-fallback-only + cross-region router | 4 | `#8` | ⬜ |
| 9 | Peer-runtime + event-sourced storage | 5 | `#9` | ⬜ |
| 10 | Gateway migration, P2P, archival repair | 6 | `#10` | ⬜ |
| 11 | World-interference control, chunk lifecycle, mod compat | 2–4 | `#11` | ⬜ |
| 12 | Entity & mob lane (ghosts, cross-region transfer) | 5+ | `#12` | ⬜ |
| 13 | Validated redstone + contraption migration | 5+ | `#13` | ⬜ |
| 14 | Environment lane (random ticks, fluids, fire, light) | 7 | `#14` | ⬜ |
| 15 | Deterministic entity simulation (mob AI, projectiles) | 7 | `#15` | ⬜ |
| 16 | Player lane & trustless closure (BFT, mod SDK) | 8 | `#16` | ⬜ |
| 17 | **Debugger tool**: P2P comms + event/block/redstone harness, real server-instance emulation, live debug, coverage reports, log files | 0–8 | `#17` | ⬜ |

Full task specs: [`docs/Task.0.md`](docs/Task.0.md) … [`docs/Task.16.md`](docs/Task.16.md).

---

## Agent memory & discipline

<!-- AI-AGENT-INSTRUCTION: AGENTS.md is the always-loaded agent memory. The three non-negotiable
     disciplines are: (1) run tests before commit, (2) update README progress + Tested.md, (3) use
     the commit-message standard above. Re-read AGENTS.md at the start of every session. -->

The single source of agent instructions is [`AGENTS.md`](AGENTS.md). It is auto-loaded by coding
agents (opencode, Cursor, Claude Code, …) and encodes: build/test commands, layering rules, the
frozen contracts, the test-before-commit / update-README / commit-format disciplines, and the
GitHub issue workflow. **Read it before doing anything.**
