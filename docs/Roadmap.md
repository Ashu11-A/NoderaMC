# Roadmap.md — implementation order, priority, difficulty

<!-- AI-AGENT-INSTRUCTION: This file classifies every task by implementation order, priority, and
     difficulty, grounded in what is ALREADY implemented. Keep it in sync on every outcome-changing
     commit (same discipline as README progress): when a task advances, update §1 status, re-check
     the §2 wave it sits in, and strike it from §3/§4 when done. Statuses must agree with
     README.md "Roadmap" + Tested.md. Task links are the specs; the GitHub issue for a task is
     found BY TITLE (`Task N — <title>`), never by assuming issue number == task number. -->

Snapshot: 2026-07-21 · overall `70%` · 773 Java tests green (+144 Rust) · **Task 33 live worker data
+ world identity/authorship + P2P permissions landed** (worker `STATE`/`IDENTITY`/`HOST`/`WORLDID`
verbs → live dashboard; author-signed `WorldIdentity` per-world file + auto-re-share + author-only
password; `WorldRole`/`WorldPermissionGrant`/`WorldPermissions` signed permission model + host
op-grant; multiplayer tracker/rendezvous tab bug fixed; +27 headless — live committee validation /
world-list mixin / grant gossip / worker seeding documented in [`Task.33.md`](Task.33.md), new L-49) ·
**Task 31 GUI redesign + Task 32 companion app first increments landed** ("Open to Nodera" replaces LAN; public-world badge;
tabbed Nodera-only multiplayer screen + torrent piece-map view models (+24 `diagnostics`); Task 32
mod-side presence gate `CompanionGate` (+8) + Tauri `rust/nodera-app` scaffold (Option B, workspace-
excluded); `runClient`/live feeds/headless-peer jar deferred — new L-46/L-47/L-48, L-41 RETIRING) ·
**Task 30 decentralization first increment landed** (dedicated-server gate removed, role-driven host
lane, pause-menu "Share" screen, `scripts/dev.sh` infra-only; genesis/re-manifest/`runClient` deferred
— L-45) · MVP gate ([Task 7](Task.7.md)) proven
headlessly, not yet live · lane B complete to its headless/compile edge: Tasks 19–25 headless +
[Task 26](Task.26.md) view model + `Dist.CLIENT` screens (GUI pass pending) ·
[Task 11](Task.11.md) interference guard landed headless (mixins/tickets live half pending) ·
[Task 9](Task.9.md) RocksDB tier + committee-change certification + capability-weighted gateway
election landed (live forward sync + manager wiring pending) ·
[Task 12](Task.12.md) entity-lane core foundation landed (simulation + mod + transfer half pending) ·
**Rust infrastructure cluster complete** (Tasks [27](Task.27.md)/[28](Task.28.md)/[29](Task.29.md):
monorepo + standalone tracker + rendezvous relay — all three landed; L-23/L-27/L-44 RETIRED;
[Task 10](Task.10.md)/[Task 20](Task.20.md) rewritten, ledger in [`LEGACY.md`](./LEGACY.md)).

---

## 1. Where the build stands

Ground truth: README "Progress" + `Tested.md`. Summary by completion class:

| Class | Tasks |
|---|---|
| **Done (pure-Java scope)** | [2](Task.2.md) `core` · [3](Task.3.md) `simulation` |
| **Done (Rust services)** | [28](Task.28.md) standalone tracker — real binary driven from Java peers by `TrackerServiceIT`, embedded `TrackerService` deleted, L-44 RETIRED (mod-side announce scheduling rides the Task 26 live pass) |
| **Done (build architecture)** | [27](Task.27.md) monorepo + Rust workspace — `java/`+`rust/`+`fixtures/`, `nodera-codec` byte-exact against the Java golden frames, both toolchains gating CI |
| **Done (Rust services)** | [29](Task.29.md) standalone rendezvous + relay — `nodera-rendezvous` (registration/discovery/reservations/circuit-bridging/punch-coordination) + `java/transport-rendezvous` (direct-first / punch-upgrade / E2E-relay-fallback `PeerTransport`); `RendezvousRelayIT` drives the real binary; L-23 + L-27 RETIRED |
| **Shipped, GUI-env acceptance pending** | [1](Task.1.md) mod skeleton · [4](Task.4.md) protocol/transport (relay impl also pending) · [18](Task.18.md) diagnostics HUD (L-31 placeholders wait on 6/12) |
| **Proven headless, live wiring pending** | [5](Task.5.md) shadow validation · [6](Task.6.md) coordinator · [7](Task.7.md) committee/MVP · [8](Task.8.md) fallback/router |
| **Partially shipped** | [12](Task.12.md) entity lane (12a core foundation — `FixedVec3`/`NetworkEntityId`/`PersistedEntityState` + item actions/events landed headlessly; region-root `EntityStore` + item physics + `mobCapture` ghost stream + 12c transfer + NeoForge bridge deferred) · [26](Task.26.md) multiplayer GUI (`TorrentWorldListView` + world-health `Semantic`/`Palette` + `client/multiplayer` screens compile, issue #29; live tracker feed/create pipeline/`runClient` pass deferred — L-43 RETIRING) · [11](Task.11.md) interference guard (`coordinator/interference` + full `DelegabilityPolicy`/`DelegabilityMonitor` + `ServerAuthorityCertificate`/`ExternalDelta` + `COMPATIBILITY.md` landed headless; mixins, `ChunkTicketService`, `FakePlayerDetector`, live acceptance deferred with the NeoForge lane) · [9](Task.9.md) peer-runtime + event-sourced store (~~RocksDB tier~~ **landed** — `storage-rocksdb` crash-consistent store + `FsContentStore` + forced-kill recovery IT; ~~committee-change certification~~ **landed** — `CommitteeChangeCertificate` + authority-free `CommitteeManager` + capability-weighted gateway election (L-29 retired); live forward sync + live manager wiring missing) · [10](Task.10.md) gateway/P2P (`transport-socket` continuity beta shipped; cross-NAT reach now rides [29](Task.29.md)'s `transport-rendezvous` — spec rewritten 2026-07-19) · [19](Task.19.md) torrent data plane (`distribution` module + `DistributionIT` green; mod-side `ChunkLockMap` consumers deferred with the NeoForge lane) · [20](Task.20.md) tracker + multi-bootstrap (`peer-runtime/discovery` + `TrackerIT`/`MultiBootstrapIT` green; mod-side tracker wiring deferred with the NeoForge lane) · [21](Task.21.md) placement/replication/repair (`peer-runtime/archival` + `ArchiveRepairIT` green; mod-side repair coordinator deferred with the NeoForge lane) · [22](Task.22.md) reliability/quotas/retention (`ReliabilityScorer` + `storage-client` + `RetentionPolicy` green; mod-side wiring deferred with the NeoForge lane) · [23](Task.23.md) per-world content encryption (AES-GCM + bounded Argon2id/PBKDF2 + keyless-seeder `EncryptedDistributionIT` green; opt-in create/join wiring deferred with the NeoForge lane) · [24](Task.24.md) crash safety + active stream (`ActivePlayerStream`/`EmergencyFlush`/`PeerShutdownHook`, vote-before-sign persistence, physical repair, and forced-process `CrashRecoveryIT` green; live commit/content/lifecycle adapters deferred) · [25](Task.25.md) tick-lag/TPS handoff (compatible keep-alive v2, `TickSync`, integer metrics, sustained `LagHandoffPolicy`, guarded failover, and replaying `LagHandoffIT` green; live commit feeds/policy scheduling/HUD/NeoForge construction deferred) · 17\* debugger (first scenario `SessionContinuityIT` landed — README still shows ⬜) |
| **Partially shipped (cont.)** | [31](Task.31.md) Nodera GUI redesign (31a "Open to Nodera" replaces LAN + 31b public-world badge + 31c tabbed multiplayer screen + 31d piece-map — view models headless-tested, screens compile-clean, no mixin; `runClient`/live feeds deferred — L-46) · [32](Task.32.md) companion app (Option B locked: Tauri supervises a bundled headless Java peer; `rust/nodera-app` scaffold workspace-excluded + mod-side `CompanionGate` presence gate green; enforcement off until the app ships; headless-peer jar/live metrics/cross-machine continuity deferred — L-41 RETIRING, L-47/L-48) |
| **Not started** | [13](Task.13.md) · [14](Task.14.md) · [15](Task.15.md) · [16](Task.16.md) |

\* Task 17 has no `Task.17.md` spec file — it is the standing debugger issue (`Task 17 — Nodera
debugger`); scope lives in the issue + `AGENTS.md`.

A recurring pattern follows from the layering rules: every phase is **proven Minecraft-free
first**, then wired to NeoForge. The headless halves of 5–8 exist; their live halves do not. That
is why "live wiring" items dominate §2's early waves.

---

## 2. Implementation order (dependency-driven waves)

Derived from the [Task 0](Task.0.md) §1 dependency graph plus current state. Waves are sequential;
items inside a wave are parallelizable.

| Wave | Work | Why here |
|---|---|---|
| **0 — now, parallel** | (a) GUI-env acceptance passes for [1](Task.1.md)/[4](Task.4.md)/[18](Task.18.md)/[26](Task.26.md) (`runServer`/`runClient`); (b) ~~[19](Task.19.md)→[25](Task.25.md) headless + [26](Task.26.md) view model/screens~~ **done** — lane B is at its headless/compile edge; (c) keep growing 17\* scenarios; (d) ~~[27](Task.27.md) monorepo restructure~~ **done** — 28/29 unblocked | (a) is small and blocks every live milestone; (b) the torrent cluster is Minecraft-free and does NOT wait on waves 1–3; (c) standing; (d) one mechanical chore commit that unblocks the whole Rust lane (28/29) |
| **1 — the gate** | [5](Task.5.md) live: NeoForge capture mixins, snapshot streaming on a real server, 3-client soak, divergence burn-down | Phase 1 exit is the project's hard gate — live determinism evidence decides everything downstream |
| **2 — MVP** | [6](Task.6.md) live (capture/cancel + `ServerLevel` applier + 2-client run) → [7](Task.7.md) live 3-client quorum = **first playable milestone**; [11](Task.11.md) live half in parallel (~~headless guard/policy/monitor~~ **done** — mixins + tickets + fake-player detection remain) | 7 is the MVP gate; 11 is required before wave 3 runs on non-flat worlds |
| **3 — demotion prep** | [8](Task.8.md) live soak (needs 11 on real worlds); [9](Task.9.md) completion: RocksDB tier, chunk attachments, live forward-sync, continuity milestone | Phase 4 exit numbers + Phase 5 canonical state |
| **4 — network era** | ~~[28](Task.28.md) Rust tracker~~ ∥ ~~[29](Task.29.md) Rust rendezvous relay~~ **both landed** (Minecraft-free); [10](Task.10.md) gateway migration over the 29 transport (NAT reach); torrent chain ~~[20](Task.20.md)→[25](Task.25.md)~~ (headless); [26](Task.26.md) last (needs 19–25 + the 28 live feed) | 28/29 done after 27 (like lane B, they didn't wait on waves 1–3); 10's cross-NAT half now rides 29's shipped `transport-rendezvous`; live Task-25 adapters also need 7 |
| **5 — parity program** | [12](Task.12.md) → [13](Task.13.md) → [14](Task.14.md) → [15](Task.15.md) → [16](Task.16.md) | Burns `LIMITATIONS.md` §B to empty; 16 closes the ledger |

Biggest schedule lever remains **wave 0(b)** — Tasks 19–25 are now proven Minecraft-free while
waves 1–3 remain live-wiring work; Task 26 is the remaining torrent-hosting feature.

---

## 3. Priority (most important first)

Importance = how much it unblocks + how directly it proves the central bet + player-visible value.

| Rank | Task | Why |
|---|---|---|
| P0 | [1](Task.1.md)/[4](Task.4.md)/[18](Task.18.md) acceptance remainders | Hours of work; gate every live milestone below |
| P0.5 | ~~[27](Task.27.md) monorepo + Rust foundation~~ | **Landed 2026-07-19** — layout moved, `nodera-codec` proven byte-exact against `fixtures/wire/`, both toolchains gate CI; 28/29 unblocked |
| 1 | [5](Task.5.md) live shadow validation | The determinism bet on real servers — everything gates on Phase 1 exit |
| 2 | [7](Task.7.md) committee MVP live | First playable milestone; the product exists once this passes |
| 3 | [6](Task.6.md) coordinator live | Direct prerequisite of 2 |
| 4 | [11](Task.11.md) interference guard (live half) | Headless guard/policy/monitor landed; the mixin choke point + tickets unblock every post-MVP phase on non-flat worlds |
| 5 | [9](Task.9.md) completion | Canonical certified state (Invariants 3–8); prerequisite for server demotion AND torrent value |
| 6 | ~~[19](Task.19.md) torrent data plane~~ | **Landed headless** — the foundation the rest of 19–26 builds on; only its mod-side consumers remain |
| 7 | [8](Task.8.md) live soak | Phase 4 exit (>90% committee-commit, honest CPU numbers) |
| 8 | 17\* debugger | Multiplies confidence of every lane; standing investment |
| 9 | ~~[29](Task.29.md) rendezvous relay~~ + [10](Task.10.md) migration over it | **29 landed** — `nodera-rendezvous` + `transport-rendezvous` retire L-23/L-27 (`RendezvousRelayIT` over the real binary); 10's cross-NAT migration rides this transport in the live pass |
| 10 | ~~[20](Task.20.md) tracker/multi-bootstrap~~ → ~~[28](Task.28.md) standalone Rust tracker~~ | **Landed 2026-07-19** — the always-on Rust service retired L-44: `TrackerServiceIT` lists a world by name, with its countdown and a DEAD verdict, after every Java seeder went silent |
| 11 | ~~[21](Task.21.md) replication/repair~~ | **Landed headless** — the durability guarantee (rules 0/1/3) |
| 12 | ~~[22](Task.22.md) reliability/quotas/retention~~ | **Landed headless** — the scoring placement/handoff depend on; unbounded-growth fixed |
| 13 | ~~[24](Task.24.md) crash safety + stream~~ | **Landed headless** — physical receipt, bounded flush, vote persistence, forced-process crash/replay proof; live adapters remain |
| 14 | [12](Task.12.md) entity lane | Starts the parity program; mobs are the most-missed gameplay gap |
| 15 | ~~[25](Task.25.md) lag handoff~~ | **Landed headless** — compatible per-region gossip, certified-reference metrics, sustained/cooldown policy, stale-safe epoch+1 failover and replay proof; live adapters remain |
| 16 | ~~[23](Task.23.md) encryption~~ | **Landed headless** — bounded Argon2id/PBKDF2 + AES-GCM ciphertext swarm; opt-in UI/live join remains |
| 17 | [13](Task.13.md) redstone | High player value, but meaningless before entity/env context matures |
| 18 | [26](Task.26.md) multiplayer GUI (live remainder) | View model + screens landed; the `runClient` pass + live tracker feed + create-world pipeline ship the feature to players |
| 19 | [14](Task.14.md) environment lane | Parity mid-game |
| 20 | [15](Task.15.md) deterministic mobs | Parity late-game; ghosts (12) are the working fallback meanwhile |
| 21 | [16](Task.16.md) trustless closure | Endgame; empties the ledger |

---

## 4. Difficulty (hardest first)

Remaining work only (done scope excluded). Difficulty = technical risk × breadth × novelty.

| Rank | Task | What makes it hard |
|---|---|---|
| 1 | [16](Task.16.md) | Several research-grade problems at once: BFT open membership, validated movement with prediction+rollback, deterministic worldgen, zero-reconnect local-replica view, mod SDK |
| 2 | [15](Task.15.md) | Deterministic mob AI + spawning inside vanilla's rate envelope; fixed-point pathfinding; per-species retirement |
| 3 | [14](Task.14.md) | Deterministic lighting is notoriously hard; fluids/fire/random-tick parity envelopes vs vanilla |
| 4 | [13](Task.13.md) | Redstone semantic fidelity + contraption ownership migration across regions |
| 5 | [5](Task.5.md) (live remainder) | Real-world divergence hunting — nondeterminism debugging on live servers is the project's riskiest grind |
| 6 | [12](Task.12.md) | Entity state in roots, ghost lanes, cross-region transfer without dupes |
| 7 | ~~[29](Task.29.md)~~ + [10](Task.10.md) (remainder) | **29 done** — signed-record registration, HMAC reservations, metered E2E relay circuits, punch coordination + `TransportSelector`, proven over the real binary; 10's migration UX under failure remains |
| 8 | [11](Task.11.md) (live remainder) | Wide, subtle surface: every foreign write source must reach the one mixin choke point, cheaply; the headless classification/conversion machinery is done |
| 9 | [9](Task.9.md) (remainder) | ~~RocksDB crash-consistency, replay-on-boot windows~~ done; live forward sync + committee-change certification remain |
| 12 | ~~[28](Task.28.md)~~ | **Done.** Tracker semantics are well understood (`docs/torrent/trackers.md`); the work is the announce family + cross-language conformance + ops hardening — and deleting the embedded Java tracker safely (`LEGACY.md`) |
| 13 | [8](Task.8.md) (remainder) | Real vanilla cross-region execution + live soak |
| 16 | [6](Task.6.md)/[7](Task.7.md) (remainders) | NeoForge wiring of already-proven pipelines |
| 18 | ~~[27](Task.27.md)~~ | **Done** — paths moved with history preserved (module names unchanged), codec port green against the golden fixtures on the first cross-language run |
| 19 | [26](Task.26.md) (live remainder) | Headless view model + compile-clean screens done; the fiddly part left is the GUI-env pass + live data feed |
| 20 | 17\*| Steady incremental scenario-writing, not deep |
| 21 | [1](Task.1.md)/[4](Task.4.md)/[18](Task.18.md) remainders | Acceptance passes in a GUI env |

---

## 5. Cross-cutting notes

- **Standing tasks.** 17\* (debugger) and [18](Task.18.md) (HUD) never "finish" in a
  wave — every lane adds debugger scenarios; 6/12 fill the HUD's L-31 placeholder panels.
- **GUI-deferred pool.** One environment unlocks a batch of pending acceptance: 1, 4, 18 manual
  passes, later 26 (`runClient`). Do them together when the env exists.
- **Limitations map.** Each task's exit tests live in [`LIMITATIONS.md`](LIMITATIONS.md) §B —
  torrent cluster owns L-32…L-43; the parity program (12–16) owns most of the rest. A task is only
  done when its register rows move.
- **Issue lookup.** Task number ≠ issue number. Find issues by exact `Task N — <title>`; Tasks
  19–23 are #24–#28 and Task 26 is #29 (per the README table). Tasks 24–25 and 27–29 have no
  issue number yet — never reuse an older task's issue number as a placeholder.
- **Three-lane staffing.** Lane A (Minecraft-facing): waves 1→2→3. Lane B (pure-Java): ~~19→20→21→22→
  23→24→25~~ → 26. Lane C (Rust services): [27](Task.27.md)→{[28](Task.28.md) ∥ [29](Task.29.md)} —
  Minecraft-free, parallel to everything; joins lane A only at 10's cross-NAT runs and 26's live
  feed. Lanes A/B join at wave 4; only live-mesh adapters and 26 need lane A's output.
- **Monorepo rewrite queue.** Task 27 ([`MONOREPO.md`](./MONOREPO.md)) moves every Gradle module
  under `java/`; **all implemented task specs (1–16, 18–26) must be rewritten for the new
  architecture** after it lands — a mechanical path-prefix pass, substantive for Tasks 1/4 (build
  + transport layout). Queue tracked in [`LEGACY.md`](./LEGACY.md) §2 and the README roadmap
  note. Do not rewrite before the move lands.
