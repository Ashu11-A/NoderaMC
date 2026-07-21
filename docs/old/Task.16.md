# Task 16 — Player Lane & Trustless Closure: Movement, Inventory, Combat, Portals, Worldgen, Seamless View, BFT, Mod SDK

**Phase:** 8 (closure) · **Depends on:** Tasks 10, 15 · **Modules:** all

## Goal

Empty the ledger. This is a **program-level task**: at planning time it splits into
sub-tasks 16a–16g, each retiring named `LIMITATIONS.md` entries, each with its own
folder/file/class breakdown in the sub-task file (same template as Tasks 1–15). This
file fixes the decomposition, the order, the cross-cutting design decisions, and the
exit condition: **`LIMITATIONS.md` §B empty, every §A hiding mechanism shipped, A0
population fully served with no second-class lane.**

---

## Sub-task decomposition

```
16a  Prediction & local view      retires L-16, L-17          (client-side foundation first —
16b  Inventory & containers       retires L-10, L-11, L-25     everything else renders through it)
16c  Player combat & movement     retires L-12, L-13
16d  Portals, dimensions, commands, worldgen   retires L-14, L-15
16e  BFT membership & dynamic committees       retires L-18, L-19, L-20
16f  RuleSet SDK for third-party mods          retires L-21
16g  Closure audit                             §A mechanisms verified, ledger emptied

Order: 16a → (16b ∥ 16e) → 16c → 16d → 16f → 16g
```

## 16a — Prediction & local-replica view (L-16, L-17)

- `client/view/LocalWorldView`: the modded client renders its **own replica** for
  regions it holds — committed state + locally predicted state. Blocks: player's own
  actions apply instantly to a prediction overlay; reconciliation on `CommitAnnounce`
  (match ⇒ overlay entry dropped; mismatch ⇒ visual rollback, rare by construction).
  This deletes the visible 1–2-tick commit latency (L-16) and the T6 "ghost block"
  interim.
- Gateway independence: with the local view feeding the player's own world, a gateway
  migration no longer interrupts play — the reconnect happens **behind** a live view
  (L-17). `publishCommit` (T10 interface) gets its local implementation, as reserved.
- Server-side `VisualReplicator` (T15) demotes to legacy for players; kept for spectator
  tooling.

## 16b — Inventory & containers (L-10, L-11, L-25)

- Container state (chest/furnace/etc.) enters the region root: `ContainerState` in
  `core.state`, `InventoryAction` family (move/split/craft subset) validated in-engine.
  Comparator, hopper, note block complete the redstone palette (closes the L-26 chain
  with T13/T14).
- Player inventory becomes validated state keyed by player identity, transferred
  between regions with the player (generalizes T12c transfer; dupe-proof tests are the
  exit — the classic cross-server dupe classes from the MultiPaper study become the
  adversarial test set).
- The T12 one-way `inventoryCredits` bridge is deleted (its ledger note closes with it).
- Async mutation API for mods (L-25): the SDK (16f) exposes the legal queue —
  `NoderaApi.submitAction(...)` — and the T11 guard's async-write error becomes a
  pointer to it.

## 16c — Player combat & movement (L-12, L-13)

- Movement: **optimistic validated** — client moves locally (prediction, 16a), signed
  movement envelopes (position deltas per batch window) validated by the committee
  against engine collision + speed/reach rules; violations ⇒ rollback + reliability
  slash. Server-authoritative anti-cheat becomes committee-authoritative.
- Combat: PvP/PvE damage, health, XP, hunger in validated player state; mob→player
  intents from T15 upgrade from applier-side effects to in-lane state transitions
  (deletes `PlayerDamageIntent` bridge).
- Player state lives in the region that owns the player's position; region handoff =
  T12c transfer with the player record (already exercised by inventory in 16b).

## 16d — Portals, dimensions, commands, worldgen (L-14, L-15)

- Cross-dimension travel = generalized cross-region transfer (dimension is already part
  of `RegionId`); portal linking rules deterministic in-engine.
- Commands: deterministic subset (gamemode-neutral world edits, teleports) enter as
  privileged `GameAction`s sequenced by the coordinator; arbitrary command parity is
  explicitly decomposed further at 16d planning (new ledger entries if needed — with
  owners, per the register rules).
- Worldgen at borders (L-15): deterministic generation from seed inside the engine for
  the supported palette (terrain shape + surface rules); regions beyond generated
  terrain become delegable; `OUTSIDE_GENERATED_TERRAIN` reason deleted. Structures
  stage into follow-up entries at 16d planning if the full generator proves too large —
  ledger discipline applies (owner + exit, never "permanent").

## 16e — BFT membership & dynamic committees (L-18, L-19, L-20)

- Committee consensus upgraded to a HotStuff-style pattern for untrusted membership:
  leader rotation inside committees, view changes on timeout, certificates unchanged in
  shape (votes already sign roots — the machinery generalizes).
- Admission control: invitation web + rate-limited identity admission (cost function
  config: proof-of-work-lite or social vouching), reliability bootstrap for newcomers
  (start suspect: N=4 spot-checks, minimal assignments).
- Dynamic committee sizing (L-19): population-aware size/quorum per region with
  certified reconfiguration; static degraded-mode deleted.
- Genesis re-certification (L-20): founding peer set co-signs a genesis superseding
  certificate; single-signer root becomes historical.

## 16f — RuleSet SDK (L-21)

- Public API: third-party mods ship **deterministic rule packs** (`RuleSet` +
  palette extensions + entity kinds) compiled against `simulation` — no Minecraft
  types, forbidden-API checks run on the pack at load (same ArchUnit battery, enforced
  at runtime by classloader policy).
- `registryFingerprint` covers installed packs; committees only form among peers with
  identical pack sets (existing Task 3/4 machinery — no new consensus rules).
- Reference pack in CI (a small machine mod) proving the lane end-to-end; `COMPATIBILITY.md`
  graduates from "exclusion policy" to "integration guide".

## 16g — Closure audit

- Walk `LIMITATIONS.md`: every §B row RETIRED with its exit test named and green in CI;
  every §A row's mechanism shipped and demonstrated (scripted evidence run per row).
- Full-population scenario: N modded peers, zero dedicated-server dependence beyond
  preferred-peer duties (Task 9 invariants re-audited), gateway migrations under load,
  partition + rejoin, adversarial FakePeers active — the closure soak.
- `@Invariant(1..12)` tag audit re-run (Task 10 criterion) + ledger cross-reference:
  every invariant and every retired row maps to at least one green test.

## Cross-cutting rules for all sub-tasks

- Each sub-task file follows the standard template (goal, folders, classes, mod/server
  split, acceptance) and lands **before** its implementation PRs.
- No sub-task may widen §B: discoveries enter the ledger OPEN with owner + exit before
  merge (register rule 3).
- Prediction (16a) is the only client-authority surface — everything it shows is
  reconciled against committed roots; committees remain the sole authority.

## Acceptance criteria (program level)

1. Every ledger row L-10…L-21, L-25 RETIRED via its named exit test; §B empty.
2. Closure soak (16g) green: full modded population, no vanilla lane anywhere, server
   absent for extended windows, adversarial peers active, world state
   root-consistent throughout.
3. A0 audit: grep-able absence of any vanilla-client code path; handshake refusal test
   remains as the A0 enforcement proof.
4. `Plan.md` updated: Phase 8 marked complete; `LIMITATIONS.md` becomes the living
   regression register for any future entries (rules stay in force forever).
