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
     (3) docs/minecraft/TESTING.md. Keep comments like this one intact — they guide future agents. See
     .github/ISSUE_SYSTEM.md and AGENTS.md for the full workflow. -->

---

## Progress

<!-- AI-AGENT-INSTRUCTION: Recompute `overall` as the weighted fraction of completed tasks across the
     seven categories (docs/ROADMAP.md §1) and keep filled blocks / 20 ≈ the percentage. The DETAILED
     per-task ledgers live in docs/<category>/PROGRESS.md — update THOSE (and the category's
     TESTING.md) on every outcome-changing commit; this section keeps only the bar. -->

**Overall system completion: `97.4%`**
`███████████████████░`

99.2 → **97.4 %**: the **service-directory lane** opened three tasks
([tracker 5](docs/tracker/Task.5.md), [rendezvous 5](docs/rendezvous/Task.5.md),
[network 13](docs/network/Task.13.md)) and finished none of them yet, so the figure fell against a
larger scope. What landed inside them is substantial: peers discover rendezvous points through
trackers instead of reading a configuration string, score them on their own measured latency and
availability, use several at once, and migrate when one drains — and the services themselves announce,
drain and self-update from a GitHub release. Two real defects fell out on the way: a peer's relay
accept loop used to *end* after one failed re-reservation, so a rendezvous restart was a permanent
invisible outage for anybody who had reserved there; and the companion app never passed the
rendezvous setting it collected to the worker at all. Four limitation rows opened rather than being
papered over (**L-81** release provenance, **L-82** the `curl` fetcher, **L-83** a drain grace that
can still truncate, **L-84** the mod's still-static list).

91.3 → **95.3 %**: the live validation lane's block half landed (issue #5) — the vanilla↔palette
binding, capture at the documented event priority, real chunk extraction, committed blocks projected
back into the world, exact interference measurement, session chunk tickets, and the single
`setBlockState` choke point, which finally gave engine **L-25** the call site it had been waiting
for. A dead-code sweep then found two of the coordinator's own components unwired — committee
outcomes never reached the reliability ledger, and epochs and reputations did not survive a restart —
and the environment lane gained lava/water interactions, crops, and the random-tick suppression
mixin that makes a delegated region's farm the engine's business alone. Then the server category's
gate opened: `nodera-endpoint.jar` builds and enables on a real Paper 1.21.1, with ALIGN-1 — the
invariant that keeps one Nodera region on one Folia thread — living in `core` and tested
exhaustively rather than by eye, and **refusing** on a real Folia configured to split a region. The figure moved 93.1 → 85.7 → 91.3 in one earlier day and both of those moves were real too:
the telemetry programme added ten tasks before it delivered seven. A percentage that only ever rises
is a percentage measured against a scope that quietly moves.

99.0 → **99.2 %**: the worker category emptied. L-41's last clause asked for a world that stays
announced **and** seeded — whole-save archive *and* validated-lane region pieces — on the worker's
own timer after the driving game disconnects. The archive half was old; the region half had never
been built, and `RegionSnapshotSplitter` (which does exactly that split) had no caller outside
tests. It needed no new wire message: `PieceManifest` already carries the region and the snapshot
version, so which lane a manifest belongs to is written on the manifest. `NODERA-SEED-REGION` takes
a committed snapshot from whichever process holds the seat — usually a game client, the one process
guaranteed to go away — and the mod's `RegionSeedSpool` pushes without the commit path ever touching
disk or a socket.

Per-category detail + milestone notes: `docs/<category>/PROGRESS.md` · test counts:
`docs/<category>/TESTING.md` · order, priority, and difficulty:
[`docs/ROADMAP.md`](docs/ROADMAP.md) · documentation entry point:
[`docs/README.md`](docs/README.md)

---

## Module status

<!-- AI-AGENT-INSTRUCTION: This table mirrors docs/minecraft/TESTING.md. Update both together. Status emojis:
     ✅ done · 🚧 partial · ⏳ in progress · ⬜ not started · ❌ failing. -->

| Module | Responsibility | Tests | Status |
|---|---|---|---|
| `core` | domain types, JDK-only crypto, canonical encoding, transition-bound authority/vote/joint-transfer certificates, Task 12 entity records, and shared deterministic fixed-point/chunk-key helpers | 263 | ✅ |
| `engine` | **unified deterministic-engine + validation API (issue #30)** — deterministic engine + consensus/shadow/coordinator/committee/fallback; Task 12 adds fixed-point items, throttled ghost interference, playerless isolation, transfer recovery, pearl policy, and soak metrics; Task 16 adds the **rule-pack SDK** (L-21) — `PackRules` execution hooks dispatched by declared palette ownership through `PackDelegatingRuleSet`, canonical namespace tick order, and a registry-derived engine fingerprint (`docs/engine/SDK.md` is the public contract), the **deterministic command subset** (L-14 — `CommandAction` tag 108 + `CommandRules`: engine-side authority against a committee-agreed operator set on `RegionExecutionContext`, no minting of unplaceable states, volume-bounded fills, `/time set` refused as a context input), the combat-vitals `@Invariant(10)` extension (L-13), and **palette v2 completed** (L-26 — pressure plates couple the entity lane to the redstone lane; sticky pistons pull on retraction; `RULES_VERSION` 4 / `palette.v4`), and the **vanilla↔palette binding** (`VanillaPalette` — every palette id against its vanilla block key and meaning-carrying properties, both directions, with a round-trip test that fails the day the palette outgrows the live capture lane) plus exact block-level interference counting and `RegionChunkHolds`; L-52 centralizes fixed-point multiply, entity motion copies, and packed chunk keys without changing canonical bytes | 504 | ✅ |
| `transport` | **unified network API (issue #30)** — append-only wire plane + socket/rendezvous carriers; message tags through 60 (transfer prepare/accept/commit, tracker routes, continuity-lane `WorldManifestQuery`/`Answer`, genesis approval, `WorldGrantGossip`); shared golden fixtures remain byte-exact; issue #39 pins the socket bind-failure + ephemeral-retry invariants; issue #41 (L-53) adds the authenticated challenge-response handshake — key-proven NodeId attribution at accept | 94 | ✅ |
| `storage` | **unified storage API (issue #30)** — event-sourced and RocksDB tiers include atomic paired event append, joint transfer certificates, and durable transfer stages alongside checkpoints/content/certificates; issue #36/33 signed identity/permission stores | 112 | ✅ |
| `testing` | shared test library (issue #30; formerly `testkit`): `LoopbackTransport`, `FakeRegion`, `FixtureWriter/Reader` | 14 | ✅ |
| `peer` | **unified peer API (issue #30)** — distribution/runtime/diagnostics/headless worker plus authenticated validation, disjoint-committee transfer routing, process-kill recovery, durable journals, and the world-continuity lane (`WorldArchive` + worker seeding/manifest-serving/swarm-fetch, `SEED`/`ARCHIVE`/`GRANT`/`REKEY` verbs, `WorldContinuityIT` host-death survival, `RekeyVerbIT` password re-key round trip, `RelayMetricsTest` per-peer event-relay accounting), plus the mesh-population + boundary-independence lane (issues #45/#46/#47): `ResidentQuorumIT` proves a committee holds full strength through a player's disconnect via standing workers, `LiveLagHandoffIT` drives the Task 25 lag policy into a live primary handoff, and `RegionCertificationTest`/`TrafficDirectionSplitTest` pin honest region-certification + direction-split telemetry, `ByzantineMeshIT` closes L-18's last exit clause by putting a genuinely adversarial peer on the mesh (lying, forging and equivocating over the wire), and the permission/archive-hygiene lanes retire L-54 (`WorldGrantGossipService` + `WorldGrantGossip` tag 60 — grants reach every co-hosting peer and are re-verified there, `GrantGossipIT`) and L-55 (`supersedeOlderVersions` + `ContentStore.remove` — a re-key evicts the ciphertext the old password still opened), and the validated-lane region-piece seeding that retired worker L-41 (`WorldArchiveService.seedRegion` + `NODERA-SEED-REGION` + `CommittedRegionSeeder` — a region stays fetchable from the always-on worker after the node that committed it is gone) | 549 | 🚧 |
| `neoforge-mod` | `@Mod` entrypoints + role-driven host wiring, Task 12 adapters, the continuity halves (`WorldArchiver` share/stop seeding + `packToSpool` re-key blob, `NoderaContinuity` disconnect-rehost, server-dist companion gate), the #36/33/37 permission/identity/re-key lanes (`OperatorBridge`, `/nodera op\|deop`, `CompanionClient.rekey`), the #39 crash-resilience degrade (a P2P bind failure never crashes the integrated server), the #43 continuity hardening (continuous archive streaming + bounded final flush + freshness guard + exit-screen progress), the #44 vanilla-cancel contract (`VanillaCancelGate` — vanilla is only cancelled on the synchronous local-primary path), `ClientStallReporter` (names the screen a stuck client is sitting on — the fact every opaque L-45 CI failure was missing), and the `/nodera selftest` in-game command test+benchmark drive, and the **live block lane** (issue #5): `PaletteMapper`, `BlockCaptureBridge` (place/break at `EventPriority.LOW` onto the signed submit path, vanilla never cancelled), `LiveSnapshotExtractor`, committed-block projection, `ChunkTicketService`, and `LevelChunkMixin` + `BlockWriteGuard` — the single write choke point, inert until a lane installs, which retired engine L-25; Task 5b soak evidence remains, and `RegionSeedSpool` — committed regions reach the worker without the commit path touching disk or a socket (worker L-41), plus the **profiling lane** (minecraft 9): `SparkProfileBridge` drives the spark profiler on a player-hosted world, which RCON cannot reach, and is inert unless `-Dnodera.spark.profile` is set | 137 | 🚧 |
| `paper-plugin` | (server task 1) `nodera-endpoint.jar` — the Paper/Folia endpoint plugin: platform detection by the presence of the regionised scheduler (a fork renames itself; the scheduler is the load-bearing difference), and the **ALIGN-1 preflight** that refuses to enable where a Nodera region would straddle two Folia sections. Green on real Paper 1.21.1 and Folia (`e2e-endpoint.sh`, `e2e-folia.sh`, `e2e-plugins.sh`), `nodera-endpoint.yml` is parsed and enforced at enable, and `peer.mode: external` links the endpoint to an always-on worker over the worker's own control socket — the node lives outside the server JVM and the world **survives a `kill -9` of the server** (L-71 retired) | 20 | 🚧 |
| `rust/nodera-codec` | (Task 27) Rust canonical-encoding conformance crate: byte-exact port + Ed25519 verify + tag mirror through Java type tag 102/message tag 48 + socket framing | 35 | ✅ |
| `rust/nodera-tracker` | (Task 28) standalone tracker service binary — signed announce lifecycle, per-world swarm registry, TTL expiry, sampling with a seeder floor, health + retention countdown, per-IP quotas; embedded Java `TrackerService` deleted (L-44 RETIRED); tracker 4 adds the windowed self-report, **off unless an operator configures an endpoint** | 62 | ✅ |
| `rust/nodera-rendezvous` | (Task 29) rendezvous + relay service binary — signed registration/discovery, HMAC relay reservations + metered tokio circuit bridging, hole-punch coordination (L-23/L-27 RETIRED); rendezvous 4 adds **NAT-pair punch statistics** (four coarse classes, success inferred from whether the pair falls back to a relay) | 62 | ✅ |
| `rust/nodera-telemetry` | (telemetry 1–2) telemetry ingest service — the consent gate, the event registry that no free-text value can enter, rotating HMAC pseudonymisation, coarse country/ASN geolocation with the address discarded, per-source quotas, and a rotating NDJSON spool for the Big Data plane in `docker/telemetry/`. Carries **no authority**: nothing in the network reads it | 80 | ✅ |
| `rust/nodera-app` | (Task 32 · app 5) Tauri companion app — always-on headless-peer supervisor (Option B: bundled Java peer) + loopback control endpoint (mod presence gate) + system tray + autostart + React dashboard (chunks/GB/peers/world). Workspace-EXCLUDED (Tauri native deps); built separately | 63 | 🚧 |
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

scripts/version.sh --check      # every version mirror agrees with the root VERSION file
```

> **After every push, verify the build actually passed.** A green local `./gradlew check` does
> NOT guarantee a green Action: CI runners are slower and have repeatedly exposed
> timing-sensitive integration tests that pass locally but fail remotely. A few minutes after
> pushing, run `gh run list --limit 3` (or `gh run watch <id>`) and treat any failure on `main`
> as stop-the-line — harden the racy wait, never delete the test. Full procedure: `AGENTS.md`
> § "GitHub hygiene sweep" items 3–4.

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

scripts/e2e-telemetry.sh         # the consent lane end to end (headless — no GUI, no Minecraft)
scripts/telemetry-stack.sh up    # the Big Data plane: Vector → Redpanda → ClickHouse → Grafana
scripts/telemetry-stack.sh smoke # …and prove a submitted batch becomes a queryable row

# Hands-on two-player session on one machine (absorbed the former scripts/play-two.sh):
scripts/dev.sh --play            # 2 Minecraft clients + 1 tracker + 1 rendezvous + 3 peer workers
scripts/dev.sh --play --with-app # …plus ONE Tauri companion window per player, each attached to
                                 #   that player's own worker — two dashboards, two nodes, live
scripts/dev.sh --play --apps 1   # exactly one companion window instead of one per player
scripts/dev.sh --play --spare-peers 0   # thin the swarm below the quorum floor and watch it degrade
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
│   ├── nodera-telemetry/    (telemetry 1) consented, schema-bounded, de-identified telemetry ingest
│   └── nodera-app/          (workspace-excluded) Tauri companion app — supervises the peer worker
├── docker/telemetry/    the Big Data plane (Vector → Redpanda → ClickHouse → Grafana/Spark).
│                        NEVER a dependency: everything here can be down and nothing changes
├── VERSION              the ONE product version; every toolchain reads it (scripts/version.sh)
├── fixtures/wire/       golden canonical frames, emitted by Java, re-encoded byte-exactly by Rust
├── scripts/             dev (build Rust + mod, run tracker + rendezvous; --install-mod for a real client)
└── docs/                README.md (entry point + documentation format), ROADMAP.md (the central
                         roadmap), plans/ (programme plans), and ONE FOLDER PER CATEGORY —
                         engine/ network/ tracker/ rendezvous/ minecraft/ worker/ app/ — each with
                         Task.0..n.md, PROGRESS.md, TESTING.md, LIMITATIONS.md, LIMITATIONS.fixed.md
```

Every package also carries its own `README.md` describing that package's architecture
(`java/<module>/README.md`, `rust/<crate>/README.md`).

> **Monorepo is the default architecture.** Module names did not change — only paths — so every
> `./gradlew` invocation and every `build.gradle.kts` kept working untouched. The conventions,
> layering rules, and frozen contracts live in [`docs/README.md`](docs/README.md).

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
- **Closing an issue requires**: `./gradlew check` green, README progress + docs/minecraft/TESTING.md updated, the
  task's acceptance criteria linked from the PR description.

See [`.github/ISSUE_SYSTEM.md`](.github/ISSUE_SYSTEM.md) for the normative rules.

---

## Roadmap (categories → tasks)

<!-- AI-AGENT-INSTRUCTION: This table MIRRORS docs/ROADMAP.md §1, which itself mirrors each
     docs/<category>/Task.<n>.md status header. The task file is the source of truth for scope;
     update it FIRST, then docs/<category>/PROGRESS.md, then docs/ROADMAP.md, then this table.
     GitHub issues keep their historical titles — find them by title, never by number. -->

**2026-07-25 reorganization:** the documentation is organised into **one folder per system
category**, each with its own sequential task set and its own status, testing, and limitation
registers. The entry point is [`docs/README.md`](docs/README.md); the single central roadmap is
[`docs/ROADMAP.md`](docs/ROADMAP.md).

| Category | Scope | Docs | Tasks | Status |
|---|---|---|---|---|
| **Engine** | Deterministic region engine, shadow validation, coordinator, committee quorum, fallback router, interference guard, parity program | [`docs/engine/`](docs/engine/Task.0.md) | 12 | 🚧 7 done |
| **Network** | Wire protocol, transports, peer runtime, event-sourced storage, torrent data plane, discovery, replication, encryption, crash safety, telemetry | [`docs/network/`](docs/network/Task.0.md) | 14 | 🚧 11 done |
| **Tracker** | Always-on world/peer discovery service + its Java client | [`docs/tracker/`](docs/tracker/Task.0.md) | 6 | 🚧 5 done |
| **Rendezvous** | NAT reach: signed registration/discovery, hole punching, E2E-encrypted relay fallback | [`docs/rendezvous/`](docs/rendezvous/Task.0.md) | 6 | 🚧 4 done |
| **Minecraft** | The NeoForge mod: capture, live lanes, GUI, host lane, world identity, companion gate | [`docs/minecraft/`](docs/minecraft/Task.0.md) | 11 | 🚧 5 done |
| **Worker** | The required always-on headless peer + its loopback control protocol | [`docs/worker/`](docs/worker/Task.0.md) | 8 | 🚧 6 done |
| **App** | The Tauri desktop companion that supervises the worker | [`docs/app/`](docs/app/Task.0.md) | 10 | 🚧 6 done |
| **Mobile** | The Android build: the same app and the same Java worker, in one process on a phone | [`docs/mobile/`](docs/mobile/Task.0.md) | 5 | 🚧 4 done |
| **Telemetry** | Consented, de-identified measurement: the Rust ingest service + the Big Data plane | [`docs/telemetry/`](docs/telemetry/Task.0.md) | 3 | 🚧 1 done |
| **Server** | The Paper/Folia endpoint plugin ([`Plan.5`](docs/plans/Plan.5.md)) — excluded from the completion figure until it starts | [`docs/server/`](docs/server/Task.0.md) | 10 | ⬜ 0 done |

> Task counts and done-counts above refreshed 2026-07-28 against the tree. The headline completion
> figure at the top of this README is **not** recomputed on this sweep — its weighting is not raw
> done/total; see [`docs/ROADMAP.md`](docs/ROADMAP.md) §1.

The **"torrent hosting" feature** (a world becomes a shared, content-addressed, multi-seeder
resource) is network tasks 4–10 plus the minecraft GUI and host tasks. It is additive to committee
validation: seeders store and propagate only; the active region's committee still re-executes and
commits.

The **Rust infrastructure services** (tracker, rendezvous) are verified-never-trusted: an outage
degrades discovery and reachability, never correctness.

Delivery order, priority, and difficulty: [`docs/ROADMAP.md`](docs/ROADMAP.md).

---

## Agent memory & discipline

<!-- AI-AGENT-INSTRUCTION: AGENTS.md is the always-loaded agent memory. The three non-negotiable
     disciplines are: (1) run tests before commit, (2) update README progress + docs/minecraft/TESTING.md, (3) use
     the commit-message standard above. Re-read AGENTS.md at the start of every session. -->

The single source of agent instructions is [`AGENTS.md`](AGENTS.md). It is auto-loaded by coding
agents (opencode, Cursor, Claude Code, …) and encodes: build/test commands, layering rules, the
frozen contracts, the test-before-commit / update-README / commit-format disciplines, and the
GitHub issue workflow. **Read it before doing anything.**

The **documentation entry point** — the documentation format, the binding conventions (layering,
frozen contracts, determinism rules, naming, version pins), where progress lives, and the mandatory
documentation-maintenance discipline — is [`docs/README.md`](docs/README.md).

<!-- AI-AGENT-INSTRUCTION: Documentation is part of the deliverable, not a follow-up. On every
     outcome-changing commit, update the owning docs/<category>/Task.<n>.md, that category's
     PROGRESS.md / TESTING.md / LIMITATIONS.md, docs/ROADMAP.md, this README, and the affected
     package README — in the SAME commit as the code. The full rule is docs/README.md §5. -->

**Documentation discipline:** a commit that changes behaviour and leaves the docs stale is an
incomplete delivery. The mandatory checklist is [`docs/README.md`](docs/README.md) §5.
