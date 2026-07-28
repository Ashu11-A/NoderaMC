# Engine — Progress Ledger

<!-- AI-AGENT-INSTRUCTION: This is the per-task status ledger for the engine category. On every
     outcome-changing commit that touches this category: update the task's row in §1, add a dated
     milestone note to §2 stating what landed AND the evidence (test or IT name), then reconcile
     ../ROADMAP.md §2 and the root README bar. A milestone note that does not name its evidence is
     not a milestone note. Never rewrite an old note — append a new one. -->

**Category:** engine · **Last audit:** 2026-07-28 · Tasks completed: **7 / 12**

Tests: [`TESTING.md`](TESTING.md) · open gaps: [`LIMITATIONS.md`](LIMITATIONS.md) · retired gaps:
[`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) · charter: [`Task.0.md`](Task.0.md).

---

## 1. Task status

| Task | Title | Status | Notes |
|---|---|---|---|
| [1](Task.1.md) | Domain types, crypto, canonical encoding | ✅ COMPLETED | Frozen contract; extended additively through type tag 108 |
| [2](Task.2.md) | Deterministic region engine | ✅ COMPLETED | `RULES_VERSION` 6, palette literal `palette.v6` (obsidian L-2, farmland + wheat L-1) |
| [3](Task.3.md) | Shadow validation | ✅ COMPLETED (headless) | Live capture soak → [minecraft 2](../minecraft/Task.2.md) |
| [4](Task.4.md) | Coordinator | ✅ COMPLETED (headless) | Live `ServerLevel` applier → [minecraft 2](../minecraft/Task.2.md) |
| [5](Task.5.md) | Committee validation — MVP gate | ✅ COMPLETED (headless) | Also running out of game via [worker 4](../worker/Task.4.md) |
| [6](Task.6.md) | Fallback lane + cross-region router | ✅ COMPLETED (headless) | > 90% committee-commit proven; live soak deferred |
| [7](Task.7.md) | Interference guard + delegability | ✅ COMPLETED (headless) | Mixins/tickets → [minecraft 2](../minecraft/Task.2.md); L-25 open |
| [8](Task.8.md) | Entity & mob lane | 🚧 IN PROGRESS | Headless/durable green; proven live; scripted CI drives remain (L-50) |
| [9](Task.9.md) | Validated redstone | 🚧 IN PROGRESS | Palette complete (L-26 retired); contraption migration remains |
| [10](Task.10.md) | Environment lane | 🚧 IN PROGRESS | L-3/L-4/L-5/L-6 retired; L-1/L-2 retiring |
| [11](Task.11.md) | Deterministic entity simulation | 🚧 IN PROGRESS | L-8/L-9/L-24 retired; L-7 retiring |
| [12](Task.12.md) | Player lane & trustless closure | 🚧 IN PROGRESS | L-10/L-11/L-13/L-14/L-15/L-18/L-20/L-21 retired; L-12/L-16/L-17/L-25 remain |

---

## 2. Milestone notes (newest first)

### 2026-07-28 — Documentation sweep: status reconciliation + refactoring register

A category-wide documentation sweep (no code change) reconciled every task header, the limitations
register, and the testing ledger against the current tree. Findings of substance:

- **Retirements reflected in the task headers.** `L-8` (engine spawn cycle) and `L-24`
  (`mobCapture` default-off) were already in [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md) but the
  [Task 11](Task.11.md) header still described them as RETIRING/OPEN; both are now marked RETIRED in
  the header, the status-detail, and the per-task row above. Evidence: `SpawnRulesTest` (L-8);
  `e2e-mobs.sh` G2a/G2b green on live run 30196607049 (L-24). `L-25` (async-write call site) was
  likewise still described as open in [Task 7](Task.7.md); it is RETIRED (the `LevelChunkMixin` →
  `BlockWriteGuard` call site landed; `MutationGuardTest` pins the headless semantics).
- **`RULES_VERSION` corrected.** [Task 2](Task.2.md) and [Task 9](Task.9.md) status prose carried
  stale version literals (4 / 5); the live values are `RULES_VERSION = 6`, palette literal
  `palette.v6` (v5: obsidian for L-2; v6: farmland + wheat for L-1).
- **Files sections corrected.** [Task 10](Task.10.md) listed `FireRules`/`ObserverRules`/
  `DaylightSensorRules` as separate files; fire lives in `RandomTickRules.applyFireTick`, and
  observer/daylight/comparator logic lives in `RedstoneRules`. The task file now names the real
  locations.
- **Type-tag registry scope.** [Task 1](Task.1.md) stated tags "through 108"; the registry has since
  grown to `TypeTags.NEXT = 118` (world-identity, service-directory, archive types owned by other
  categories). `core` only hosts the constants; the append-only frozen-contract rules are unchanged.
- **Test counts refreshed** in [`TESTING.md`](TESTING.md) from a `@Test`/`@Property` grep (pending
  XML-report re-confirmation): core 257, engine 499 `@Test` + 3 `@Property`, testing 14.
- **No limitation retired this pass.** Every open §B row's exit test is still pending (live-evidence
  gated or unfinished engine scope). Two new rows were opened — see [`LIMITATIONS.md`](LIMITATIONS.md)
  L-51 (cross-region fluid inflow reads one cell per dense halo section) and L-52 (the fixed-point
  multiply / entity-`withMotion` helpers are copy-pasted across four entity-rule classes — a
  determinism-maintainability hazard).
- **New artifact:** [`REFACTORING.md`](REFACTORING.md) — the jscpd-driven duplicate register plus
  manual candidates (god classes, long methods, shared-helper extraction) for this category's three
  modules.

### 2026-07-26 — The prediction overlay finally gets told something (L-16)

`LocalReplicaView` has had prediction, reconciliation and rollback since Task 16a, with seven tests
covering all of it. Reading the tree for L-16 turned up the thing none of those tests could see:
**nothing ever called `predict`**. The only thing that advanced the view was a commit arriving back
from the committee — which is precisely the one-to-two-tick lag the row describes from a player's
chair. The machinery was complete and unplugged.

`PredictionFeed` is the one line of policy in between, kept as its own class on purpose: the capture
path lives next to Minecraft and the view lives in the peer, so a direct call would put a rendering
concern inside the submit path and a Minecraft concern next to the engine.

What it decides is small, and all four of its answers are ordinary rather than errors: **predict only
what this node captured** (another node's proposal rendered before the committee agrees would show a
state no certificate backs), **no view is fine** (a dedicated server has no renderer, and the capture
path must not have to know whether anyone is looking), **a refused prediction is fine** (an untracked
region or an action the engine will not execute — refusing keeps the render at certified truth), and
**a view that throws is contained** (prediction is latency-hiding, so a fault costs a late-looking
block, never the submit path).

The supplier is read per call rather than captured once, because a client validation lane starts and
stops under a long-lived capture path; capturing it would leave the feed permanently blind. That is
its own test.

What remains for L-16 is the half that needs a screen: **the renderer never reads `render()`**. That
is the GUI bind, and it rides L-46.

### 2026-07-26 — Farms work in a delegated region

The everyday thing a player notices first when random ticks are suppressed is that their wheat
stopped growing. L-1's farm half is now engine state: **farmland** plus **wheat ages 0–7**, with
growth gated on farmland directly below and light 9 at the crop's own cell.

The rule's shape matters more than its content. The growth draw is taken **before** any condition is
examined, so the number of random values a selection consumes never depends on the surroundings —
the same discipline the fire tick follows, and the reason a farm replays identically on a replica
whose nearby terrain differs. Only **seeds** are placeable: every grown stage is an engine output,
so nobody can mint a harvest by placing age 7.

`RULES_VERSION` 5→6, palette literal `palette.v6`. `CropGrowthTest` (6) covers the farmer's view —
a field that advances, a crop on stone that never does, a roofed crop that stays put — and the one
that is not about farming at all: a 20 000-tick soak asserting every cell is still a wheat stage,
because the failure worth guarding is a crop walking past age 7 into whatever id follows it, which
would poison the root rather than merely look wrong.

Two version pins elsewhere asserted `RULES_VERSION` as a literal. They now assert `>=`: what those
tests are about is "adding a component moved the fingerprint", and a literal has to be edited by
every unrelated palette bump until it stops meaning anything.

### 2026-07-26 — Lava meets water, and the answer is the same on every replica

L-2's remaining engine clause was the interaction: until now a lava flow and a water flow could sit
next to each other forever, because the rule set had no way to say what that means. Three outcomes,
all vanilla-shaped and all pure functions of the neighbourhood:

- a lava **source** water reaches → **obsidian** (a new palette entry, id 99, placeable and bound to
  `minecraft:obsidian` in both directions);
- a lava **flow** water reaches → **cobblestone**;
- a lava flow arriving **above** water → **stone**, vanilla's "lava flows onto water" case, which is
  why the cell underneath is checked separately from the other five.

**Water is never consumed.** That is vanilla's rule, and it is also the one that keeps the result
order-independent: consuming the water would make the outcome depend on which of the two cells the
engine visited first — precisely the class of order dependence the hashed queue exists to remove.
The interaction is evaluated *before* the desired fluid state, because a lava cell that water has
reached is no longer a fluid, so asking what fluid it should settle to is the wrong question.

`RULES_VERSION` 4→5 with the palette literal `palette.v5`: a peer on the old palette computes a
different registry fingerprint and refuses to validate with this build rather than diverging the
first time someone builds a lava cast. `FluidInteractionTest` (6) runs every case through the full
engine path twice, so each assertion is a root assertion and each outcome is proven replica-stable.

One test elsewhere had pinned `RULES_VERSION == 4` as a literal; it now pins `>= 4`, because what
that assertion is actually about is "adding a component moved the fingerprint", not the specific
integer this palette happens to be on.

### 2026-07-26 — Reputation stops being a design and starts being an observation

The reliability ledger has existed since Task 6. A dead-code re-audit found what nothing had
noticed: in the live lane it was written in exactly **one** place — a private instance used only for
the lag-handoff penalty. Committee outcomes went nowhere. A node that re-executed batches and
reached the *wrong world* every single round kept a spotless score, as long as it answered quickly.

`CommitteeScoring` folds a committed round into the ledger, and `WorkerValidationService` now keeps
one ledger instead of two, so agreement, disagreement and the handoff penalty all land on the same
score. Three rules, each pinned by a test:

- the reference is the **committed** root, never the local one — a primary whose root lost is the
  node that was wrong, and scoring against its own answer would have it punish the honest majority;
- our own vote is scored like everyone else's, for the same reason;
- **absence records nothing.** Silence is indistinguishable from an unreachable peer or a closed
  laptop, and punishing it would make reputation a proxy for network luck. Chronic absence already
  costs a seat through the lag-handoff path, which is an observed fact rather than an inference.

`CommitteeScoringTest` (6) includes the one that matters for safety: sustained disagreement
eventually drops a node below the assignment floor, so `eligibleForAssignment` finally means
something. Reputation stays **local by construction** — it is a view, never consensus state, and
nothing derived from it may enter a root.

### 2026-07-25 — Palette v2 completed, L-26 RETIRED

The row's exit is a three-stage list, and stages 2 and 3 had long been green. The gap was **stage 1**:
palette v2 was still missing two of its nine named components.

**The pressure plate is the component that couples the entity lane to the redstone lane.** Every
other source answers to a block or to a scheduled tick; a plate answers to where something is
*standing*, so it only became expressible once entities were validated root state. A plate is pressed
while a validated entity's block position is the plate's own, emits 15 omni, and releases after 20
ticks through the **hashed** scheduled-tick queue — the delay is consensus state and survives a delta
boundary, and re-entering re-arms it, so a plate is a usable repeat trigger rather than a stutter.
GHOSTs never press one: their positions are server-authoritative, and letting a non-validated input
drive validated state is exactly the hole the lane exists to close. A pressed plate is unplaceable,
so it cannot be minted.

**The sticky piston** made every piston helper family-aware (`isSticky` / `retractedBaseOf` /
`extendedBaseOf` / `headBaseOf`), sharing the whole extend path and differing only on retraction,
where it pulls the block its head was touching back into the vacated cell. An unmovable neighbour, a
redstone component, or a region border all fail **closed** and the piston still retracts.

`RULES_VERSION` 3→4 and the palette literal `palette.v4`, so a peer on the old palette refuses rather
than silently diverging. `PressurePlateStickyPistonTest` (7) runs everything through the full engine
path — each assertion is also a root assertion — and pins order independence: the same entity set
handed in reversed list order produces the identical root. `COMPATIBILITY.md` §4 rewritten.

### 2026-07-25 — Commands enter the validated lane, L-14 and L-13 RETIRED

**L-14.** Commands sat outside the validated path entirely, which is a bigger hole than it sounds: an
operator's `/setblock` mutated a delegated region *behind the committee's back*, so the operator's
world and every validator's replica silently diverged — precisely the failure class the validated lane
exists to make impossible. Two decisions are load-bearing: **authority is checked in the engine**
against a committee-agreed operator set carried on `RegionExecutionContext` (a capture-point check is
advice a modified client can skip, whereas this rejection happens on every honest validator
independently); and **a command may not mint a block a player could not place**, or `/setblock`
becomes a way to conjure network-computed states. `CommandSubsetTest` (8).

**L-13.** Its PvP/PvE and state-in-root clauses were long green; the `@Invariant(10)` clause was the
one actually missing. `CombatStateRootTest` (4) proves identical blocks with different mob health
produce different roots, that a dead mob is *absent* rather than present at zero, and that committed
player health diverges the root the same way.

### 2026-07-25 — Rule-pack SDK ships, L-21 RETIRED

A pack could declare palette entries and an identity but had no *behaviour*. `PackRules` is the
executable half and `PackDelegatingRuleSet` dispatches by declared palette ownership. Writing the
test caught a real defect: `combinedFingerprint` folded an *empty* pack list through the hash, so a
modded-but-packless node computed a different fingerprint from an unmodded one and the two could
never have validated each other — merely shipping the SDK would have forked the network.
`PackRuleExecutionTest` (5); [`SDK.md`](SDK.md) is the public contract.

### 2026-07-25 — Burn-down passes: L-10, L-11, L-15, L-18 RETIRED

Four rows sat at RETIRING while their **stated exit tests** were already green — each "remaining"
note listed follow-on scope (a chest GUI, a deposit debit, caves and biomes, rotation cadence) the
exit clause never asked for. Every clause was re-verified against the code rather than the prose
before the row moved. **L-18's fourth clause was genuinely unmet** and was closed here:
"Byzantine ITs green under adversarial peers" had been credited to a test that handed lying ballots
directly to `CommitteeSession` — no adversary had ever spoken the wire. `ByzantineMeshIT` (3) fixed
that.

Two rows were examined and deliberately **left** RETIRING with the reason recorded: **L-25** (its
exit names the SDK, and the guard still has no live mixin call site) and, at the time, **L-26**.

### 2026-07-24 — Entity, environment, and combat cores

- **L-3, L-4, L-5, L-6 RETIRED** — gravity and bounded fire, deterministic lighting as a pure
  function of committed state, observer + quasi-connectivity, and the daylight sensor.
- **L-9 RETIRED** — projectiles, TNT, and rails/minecarts as validated state.
- **L-11 core** — `EntityKind.PLAYER` puts owner and the 36-slot inventory in the root; a portal
  hand-off moves the whole inventory through the dupe-proof joint-certificate pipeline.
- **L-18 rotation** — `CommitteeManager.draftRotation` is deterministic rendezvous-hash rotation.
- **T16 container lane engine core complete** — four increments in one day: container contents in the
  hashed root; `CHEST` + `ContainerAction`; `HOPPER` as a self-scheduling 8-tick machine; comparators
  emitting container fill with reactive re-settling.
- **L-13 opener** — `EntityKind.MOB` with vitals in the root and every damage source routed through
  one mutation point.

### 2026-07-23 — Redstone state model and the no-host ownership lane

Ten redstone increments landed: the scheduled-tick queue and pending block events as first-class
hashed state; canonical `NeighborUpdateOrder`; per-block dense sections; the static signal graph;
torch timing (the first clock); repeaters and buttons (which exposed a real protocol gap — a delta
crossing a batch boundary with a non-empty queue could not reproduce the root, fixed by `RegionDelta`
body v4); pistons as the two-phase `BlockEventEntry` consumer; `BorderSignal` as the border contract
(the engine **never** mutates halo state); the border-lane protocol and migration decision core; and
live scheduled-tick suppression.

Same period: the validated lane lost its host role. Every member derives the identical FOV ownership
plan, each player's client re-executes and votes on its own region set, and an action captured on a
non-owner is forwarded to the owning player's node — the capture point is a courier with no
authority. `ActionForwardIT` proves the forwarded quorum commit over the transport.

### 2026-07-22 — Entity lane proven live

Dedicated `runServer` plus a scripted joining client produced *entity lane live on 12 region(s)* with
the P2P mesh formed and zero errors. Activation runs on a dedicated bootstrap thread, so re-runs
cause no tick stalls. Later the same lane showed 239 validated/ghost entities across 12 delegated
regions with versions advancing every flush.
