# Roadmap.md — implementation order, priority, difficulty

<!-- AI-AGENT-INSTRUCTION: This file classifies every task by implementation order, priority, and
     difficulty, grounded in what is ALREADY implemented. Keep it in sync on every outcome-changing
     commit (same discipline as README progress): when a task advances, update §1 status, re-check
     the §2 wave it sits in, and strike it from §3/§4 when done. Statuses must agree with
     README.md "Roadmap" + Tested.md. Task links are the specs; the GitHub issue for a task is
     found BY TITLE (`Task N — <title>`), never by assuming issue number == task number. -->

Snapshot: 2026-07-18 · overall `48%` · 556 tests green · MVP gate ([Task 7](Task.7.md)) proven
headlessly, not yet live · lane B: Tasks 19–23 landed headless; [Task 24](Task.24.md) next.

---

## 1. Where the build stands

Ground truth: README "Progress" + `Tested.md`. Summary by completion class:

| Class | Tasks |
|---|---|
| **Done (pure-Java scope)** | [2](Task.2.md) `core` · [3](Task.3.md) `simulation` |
| **Shipped, GUI-env acceptance pending** | [1](Task.1.md) mod skeleton · [4](Task.4.md) protocol/transport (relay impl also pending) · [18](Task.18.md) diagnostics HUD (L-31 placeholders wait on 6/12) |
| **Proven headless, live wiring pending** | [5](Task.5.md) shadow validation · [6](Task.6.md) coordinator · [7](Task.7.md) committee/MVP · [8](Task.8.md) fallback/router |
| **Partially shipped** | [9](Task.9.md) peer-runtime + event-sourced store (RocksDB tier + live sync missing) · [10](Task.10.md) gateway/P2P (`transport-socket` continuity beta shipped; libp2p/NAT missing) · [19](Task.19.md) torrent data plane (`distribution` module + `DistributionIT` green; mod-side `ChunkLockMap` consumers deferred with the NeoForge lane) · [20](Task.20.md) tracker + multi-bootstrap (`peer-runtime/discovery` + `TrackerIT`/`MultiBootstrapIT` green; mod-side tracker wiring deferred with the NeoForge lane) · [21](Task.21.md) placement/replication/repair (`peer-runtime/archival` + `ArchiveRepairIT` green; mod-side repair coordinator deferred with the NeoForge lane) · [22](Task.22.md) reliability/quotas/retention (`ReliabilityScorer` + `storage-client` + `RetentionPolicy` green; mod-side wiring deferred with the NeoForge lane) · [23](Task.23.md) per-world content encryption (AES-GCM + bounded Argon2id/PBKDF2 + keyless-seeder `EncryptedDistributionIT` green; opt-in create/join wiring deferred with the NeoForge lane) · 17\* debugger (first scenario `SessionContinuityIT` landed — README still shows ⬜) |
| **Not started** | [11](Task.11.md) · [12](Task.12.md) · [13](Task.13.md) · [14](Task.14.md) · [15](Task.15.md) · [16](Task.16.md) · [24](Task.24.md) · [25](Task.25.md) · [26](Task.26.md) |

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
| **0 — now, parallel** | (a) GUI-env acceptance passes for [1](Task.1.md)/[4](Task.4.md)/[18](Task.18.md) (`runServer`/`runClient`); (b) ~~[19](Task.19.md)→[23](Task.23.md) headless~~ **done** — lane B continues at [24](Task.24.md); (c) keep growing 17\* scenarios | (a) is small and blocks every live milestone; (b) the torrent cluster is Minecraft-free and does NOT wait on waves 1–3; (c) standing |
| **1 — the gate** | [5](Task.5.md) live: NeoForge capture mixins, snapshot streaming on a real server, 3-client soak, divergence burn-down | Phase 1 exit is the project's hard gate — live determinism evidence decides everything downstream |
| **2 — MVP** | [6](Task.6.md) live (capture/cancel + `ServerLevel` applier + 2-client run) → [7](Task.7.md) live 3-client quorum = **first playable milestone**; [11](Task.11.md) in parallel (interference guard is largely headless-able) | 7 is the MVP gate; 11 is required before wave 3 runs on non-flat worlds |
| **3 — demotion prep** | [8](Task.8.md) live soak (needs 11 on real worlds); [9](Task.9.md) completion: RocksDB tier, chunk attachments, live forward-sync, continuity milestone | Phase 4 exit numbers + Phase 5 canonical state |
| **4 — network era** | [10](Task.10.md) libp2p/NAT + gateway migration; torrent chain ~~[20](Task.20.md)→[23](Task.23.md)~~ (headless) → [24](Task.24.md)/[25](Task.25.md); [26](Task.26.md) last (needs 19–23) | 20 soft-depends on 10 only for NAT reach (LAN/VPN works without it); 25 also needs 7 |
| **5 — parity program** | [12](Task.12.md) → [13](Task.13.md) → [14](Task.14.md) → [15](Task.15.md) → [16](Task.16.md) | Burns `LIMITATIONS.md` §B to empty; 16 closes the ledger |

Biggest schedule lever remains **wave 0(b)** — Tasks 19–23 are now proven Minecraft-free while
waves 1–3 remain live-wiring work; Tasks 24/25 continue that parallel lane.

---

## 3. Priority (most important first)

Importance = how much it unblocks + how directly it proves the central bet + player-visible value.

| Rank | Task | Why |
|---|---|---|
| P0 | [1](Task.1.md)/[4](Task.4.md)/[18](Task.18.md) acceptance remainders | Hours of work; gate every live milestone below |
| 1 | [5](Task.5.md) live shadow validation | The determinism bet on real servers — everything gates on Phase 1 exit |
| 2 | [7](Task.7.md) committee MVP live | First playable milestone; the product exists once this passes |
| 3 | [6](Task.6.md) coordinator live | Direct prerequisite of 2 |
| 4 | [11](Task.11.md) interference guard | Unblocks every post-MVP phase on non-flat worlds; protects world integrity |
| 5 | [9](Task.9.md) completion | Canonical certified state (Invariants 3–8); prerequisite for server demotion AND torrent value |
| 6 | ~~[19](Task.19.md) torrent data plane~~ | **Landed headless** — the foundation the rest of 19–26 builds on; only its mod-side consumers remain |
| 7 | [8](Task.8.md) live soak | Phase 4 exit (>90% committee-commit, honest CPU numbers) |
| 8 | 17\* debugger | Multiplies confidence of every lane; standing investment |
| 9 | [10](Task.10.md) libp2p/NAT + migration | Real-internet play; removes last full-peer dependencies |
| 10 | ~~[20](Task.20.md) tracker/multi-bootstrap~~ | **Landed headless** — network survivability + the data the GUI reads; L-28 retired, L-34 retiring |
| 11 | ~~[21](Task.21.md) replication/repair~~ | **Landed headless** — the durability guarantee (rules 0/1/3) |
| 12 | ~~[22](Task.22.md) reliability/quotas/retention~~ | **Landed headless** — the scoring placement/handoff depend on; unbounded-growth fixed |
| 13 | [24](Task.24.md) crash safety + stream | Defence-in-depth on top of 7+21 redundancy |
| 14 | [12](Task.12.md) entity lane | Starts the parity program; mobs are the most-missed gameplay gap |
| 15 | [25](Task.25.md) lag handoff | QoS — boundary protection against laggards |
| 16 | ~~[23](Task.23.md) encryption~~ | **Landed headless** — bounded Argon2id/PBKDF2 + AES-GCM ciphertext swarm; opt-in UI/live join remains |
| 17 | [13](Task.13.md) redstone | High player value, but meaningless before entity/env context matures |
| 18 | [26](Task.26.md) multiplayer GUI | Ships the feature to players; last because it needs 19–23 data |
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
| 7 | [10](Task.10.md) | NAT traversal + unproven jvm-libp2p (L-23, sidecar plan B); migration UX under failure |
| 8 | [11](Task.11.md) | Wide, subtle surface: every foreign write source must be caught or certified, cheaply |
| 9 | [9](Task.9.md) (remainder) | RocksDB crash-consistency, replay-on-boot windows, live forward sync |
| 13 | [8](Task.8.md) (remainder) | Real vanilla cross-region execution + live soak |
| 14 | [25](Task.25.md) | Skew metrics outside the engine + handoff tuning without flapping |
| 16 | [6](Task.6.md)/[7](Task.7.md) (remainders) | NeoForge wiring of already-proven pipelines |
| 17 | [24](Task.24.md) | Mostly plumbing once 19/21 exist; the safety argument is already structured |
| 19 | [26](Task.26.md) | Screen work is fiddly but shallow; view-model pattern already established by 18 |
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
- **Issue lookup.** Task number ≠ issue number. Find issues by exact `Task N — <title>`; Task 23 is
  #28. Open missing task issues from the template before work and update README's Issue column.
- **Two-lane staffing.** Lane A (Minecraft-facing): waves 1→2→3. Lane B (pure-Java): ~~19→20→21→22→
  23~~ → 24→25 headless. They join at wave 4; only live-mesh ITs and 26 need lane A's output.
