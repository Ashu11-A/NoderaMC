# LIMITATIONS.md — The Binding Limitation Register

Target state: **zero permanent scope exclusions and zero player-visible limitations.**

This register is normative (referenced by `Plan.md` and Task 0 §7). Rules:

1. **"Permanent" is banned.** Every limitation is either an *envelope constraint*
   (§A — a fact of physics/platform that is engineered around until players cannot
   observe it) or a *staged capability* (§B — open until its owning task retires it
   with a passing exit test). There is no third category.
2. Every §B entry has an **owning task** and an **exit test**. A task that stages or
   retires an entry updates this file in the same commit (Task 0 §7 definition of done).
3. New limitations discovered during implementation enter §B as OPEN with a path and an
   owner before the discovering PR merges.
4. **Assumption A0** (Task 0): every player runs the Nodera mod and joins as a network
   peer. There is no vanilla-client population; the handshake enforces it. Entries that
   only existed because of a vanilla-client population are RETIRED in §C.

Status values: `OPEN` → `RETIRING` (owner task in progress) → `RETIRED` (exit test green).

---

## §A — Envelope constraints (never denied, engineered around)

These do not burn down; each carries the mechanism that removes its *player-visible*
impact. An §A entry is "satisfied" when its mechanism has shipped and the impact is not
observable in normal play.

| ID | Constraint (immovable fact) | Mechanism that hides it | Owner |
|---|---|---|---|
| A-1 | Consensus latency can exceed the 50 ms tick budget | Batching (T6); pending-ghost rendering (T6); full client prediction + rollback makes commit latency invisible (T16) | 6 → 16 |
| A-2 | All-to-all validation is O(n²) | Regional committees — messaging is O(committee) per region (T7); bulk traffic direct P2P (T10) | 7, 10 |
| A-3 | Under partition, safety beats liveness: a minority partition cannot commit its regions | Regions pause, never fork; forward sync on rejoin (T9); dynamic committee reconfiguration shrinks the blast radius (T16) | 9 → 16 |
| A-4 | NeoForge payload caps (≤ 1 MiB clientbound, < 32 KiB serverbound) | Chunked zstd streams (T4); content-addressed multi-seeder transfer (T9); direct P2P bulk lane (T10) | 4, 9, 10 |
| A-5 | JVM float math is not reproducible across hardware | Purpose-built engine; fixed-point Q32.32 for all continuous quantities in hashed state (T2, T3, T12) | 2–3, 12 |
| A-6 | Players concentrated in one region serialize on one primary | Correctness unaffected; most-capable-peer primary selection (T6); sub-region work splitting as a T16 stretch goal | 6 → 16 |
| A-7 | Minecraft/NeoForge version churn breaks mixins | Pinned versions upgraded in single commits (T0); minimal load-bearing mixin set (T1/T11); Minecraft-free core modules | 0, 1, 11 |

---

## §B — Staged capabilities (the burn-down list)

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| L-1 | Random ticks suppressed in delegated regions (no grass/fire/crop ticking there) | T14 | Farm soak: engine-owned random ticks, identical roots on 3 replicas, suppression counter deleted | OPEN |
| L-2 | Fluids excluded from validated lane | T14 | Water/lava spread deterministic across replicas, incl. cross-region via T13 migration | OPEN |
| L-3 | Gravity blocks + fire excluded | T14 | Sand/gravel fall + fire spread validated; parity envelope vs vanilla documented | OPEN |
| L-4 | No deterministic lighting (light-dependent mechanics can't validate) | T14 | Engine skylight/blocklight matches committed roots; crops/spawning read engine light | OPEN |
| L-5 | Observer + quasi-connectivity missing; redstone palette v2 semantic gaps | T14 | Palette v3 ships observer + QC; `COMPATIBILITY.md` gap note deleted | OPEN |
| L-6 | Daylight/time-coupled blocks excluded (time not in validated context) | T14 | Committed world-time in `RegionExecutionContext`; daylight sensor validated | OPEN |
| L-7 | Mobs are server-authoritative ghosts; AI not validated | T15 | Per-species retirement: ghost-share = 0 for every shipped species in soak | OPEN |
| L-8 | Mob spawning cycles vanilla-only | T15 | Deterministic spawn cycles (engine light + seeded RNG) match vanilla rate envelope | OPEN |
| L-9 | Projectiles, TNT, rails/minecarts not validated | T15 | Arrow/pearl/TNT/cart determinism fixtures green on 3 replicas | OPEN |
| L-10 | Comparator, hoppers, note block outside every lane (need containers) | T16 | Container lane ships; palette completes; regions with them delegable | OPEN |
| L-11 | Player inventory outside region root (pickup = one-way credit) | T16 | Inventory state validated; dupe-proof under cross-region transfer tests | OPEN |
| L-12 | Player movement server-authoritative | T16 | Optimistic validated movement with rollback; cheat-movement rejected by committee | OPEN |
| L-13 | Combat / health / XP not validated | T16 | PvP + PvE damage validated; state in root; `@Invariant(10)` extended test | OPEN |
| L-14 | Portals, dimensions, commands outside validated path | T16 | Cross-dimension = generalized region transfer; deterministic command subset | OPEN |
| L-15 | Regions outside generated terrain non-delegable | T16 | Deterministic world-gen-from-seed in engine; `OUTSIDE_GENERATED_TERRAIN` reason deleted | OPEN |
| L-16 | Committed effects appear 1–2 ticks late (ghost/pending render) | T16 | Client prediction + rollback: local apply instant, reconciliation invisible in normal play (interim: T6 ghost render) | OPEN |
| L-17 | Gateway migration = brief reconnect + action freeze | T16 | Local-replica world view: play continues with zero reconnect during migration | OPEN |
| L-18 | Membership semi-trusted (whitelist); Sybil/collusion not defended | T16 | BFT committee rotation + admission control + dynamic sizing; Byzantine ITs green under adversarial FakePeers | OPEN |
| L-19 | Small populations degrade quorum (3-of-4 → 2-of-3 degraded mode) | T16 | Dynamic committee sizing with certified reconfiguration replaces static degraded mode | OPEN |
| L-20 | Genesis is a single-signer trust root (server self-certifies) | T16 | Multi-party genesis re-certification signed by founding peer set | OPEN |
| L-21 | Third-party mods excluded from validated regions (palette exclusion) | T16 | Deterministic RuleSet SDK: mods ship rule packs, covered by registryFingerprint; SDK sample mod validated in CI | OPEN |
| L-22 | Fixed spot-check floor costs ~12.5% server re-execution | T7/T8 | Adaptive spot-check (N=4→64 by reliability); steady-state ≤ 2% for proven committees | RETIRING |
| L-23 | jvm-libp2p maturity unproven | T10 | Direct-P2P soak green; `TransportSelector` fallback exercised; sidecar plan B documented | OPEN |
| L-24 | `mobCapture` ghost lane default-off until proven | T15 | Flips default per-species as validation ships | OPEN |
| L-25 | Async world writes by other mods undefined under the guard | T16 | RuleSet SDK provides the legal async mutation API; guard rejects the rest with a documented error | OPEN |
| L-26 | Redstone bounded to palette v2 | T13→T14→T16 | v2 (T13) → +observer/QC/daylight (T14) → +comparator/hopper/note (T16): full redstone parity | OPEN |

## §C — Retired by assumption A0 (every player is a peer)

| ID | Former limitation | Why void |
|---|---|---|
| R-1 | Vanilla clients cannot join / are refused at handshake | Not a limitation: there is no vanilla-client population by definition. The refusal is the enforcement of A0. |
| R-2 | Actions by session-less players skipped by capture | Session-less players do not exist under A0; the defensive skip remains as a bug counter (`sessionlessActors`, expected 0). |
| R-3 | Gateway migration served "modded peers only"; vanilla players stranded when the full peer is down | Void: all players are modded peers; migration (T10) + local-replica view (T16) covers the whole population. |

---

## Reading guide for the implementing model

- Build the current task **as specified**; do not implement a §B entry early, but do not
  build anything that structurally blocks its owner task (same discipline as the
  Invariants in `Plan.md` §8).
- When a design choice trades against a §B entry, prefer the choice that keeps the exit
  test achievable.
- If you believe an entry is genuinely impossible (not merely hard), do not silently
  drop it: file it as a challenge against this register with the physical argument —
  the register only accepts reclassification to §A with a hiding mechanism, never
  deletion.
