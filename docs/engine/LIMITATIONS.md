# Engine — Limitations Register

<!-- AI-AGENT-INSTRUCTION: This register is NORMATIVE for the engine category. Rules: (1) "permanent"
     is a banned classification — every row is either an envelope constraint (§A) or a staged
     capability (§B); (2) every §B row has an owning task and an EXIT TEST, and retires only when
     that exit test is green — verify against the exit clause, never against the prose "remaining"
     note; (3) a newly discovered limitation enters §B as OPEN with a path, an owner, and an exit
     test BEFORE the discovering PR merges; (4) never delete a row — move it to LIMITATIONS.fixed.md
     with its evidence. Update this file in the SAME commit that stages or retires a row. -->

**Category:** engine · **Last audit:** 2026-07-25 · Open or retiring rows: **7**

Status values: `OPEN` → `RETIRING` (owner task in progress) → `RETIRED` (exit test green, row moves
to [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md)).

---

## §A — Envelope constraints (never denied, engineered around)

These do not burn down. Each carries the mechanism that removes its *player-visible* impact; a row is
satisfied when the mechanism has shipped and the impact is not observable in normal play.

| ID | Constraint (immovable fact) | Mechanism that hides it | Owner |
|---|---|---|---|
| A-1 | Consensus latency can exceed the 50 ms tick budget | Batching ([task 4](Task.4.md)); pending-ghost rendering; full client prediction + rollback makes commit latency invisible ([task 12](Task.12.md)) | 4 → 12 |
| A-5 | JVM float math is not reproducible across hardware | Purpose-built engine; Q32.32 fixed point for every continuous quantity in hashed state ([task 1](Task.1.md), [task 2](Task.2.md), [task 8](Task.8.md)) | 1–2, 8 |
| A-6 | Players concentrated in one region serialize on one primary | Correctness unaffected; most-capable-peer primary selection ([task 4](Task.4.md)); sub-region work splitting is a [task 12](Task.12.md) stretch goal | 4 → 12 |

Envelope constraints owned elsewhere: A-2 and A-3 in [`../network/LIMITATIONS.md`](../network/LIMITATIONS.md);
A-4 in [`../network/LIMITATIONS.md`](../network/LIMITATIONS.md); A-7 in
[`../minecraft/LIMITATIONS.md`](../minecraft/LIMITATIONS.md).

---

## §B — Staged capabilities (the burn-down list)

| ID | Limitation today | Owner | Exit test | Status |
|---|---|---|---|---|
| L-1 | Random ticks suppressed in delegated regions (no grass, fire, or crop ticking there). Engine-owned random ticks landed: vanilla-shaped selection with state-derived eligibility that skips **without consuming randomness**, canonical column order, grass MVP semantics, and a 200-tick 3-replica identical-root soak with the lane actively spreading. **Crops landed** (`CropGrowthTest`, 6): farmland plus wheat ages 0–7, growth gated on farmland below and light 9 at the crop's own cell, one stage per selection with the draw taken before any condition is read so the consumed randomness never depends on the surroundings, and only seeds placeable — a grown crop is an engine output, so a harvest cannot be minted. Fire landed with L-3. The **live suppression mixin landed**: `ServerLevelRandomTickMixin` cancels the whole chunk pass in a delegated region before vanilla consumes the level RNG (a per-block filter would already have drawn), counted separately from the scheduled-tick counter so a farm soak reads its own number. Remaining: the farm soak itself, with that counter read at the end — and it is gated on the same seat problem as minecraft L-80, because the scripted suites run a dedicated server that the field-of-view planner leaves holding no regions, so nothing is suppressed there and the counter is honestly zero | [10](Task.10.md) | Farm soak: engine-owned random ticks, identical roots on 3 replicas, suppression counter deleted | RETIRING |
| L-2 | Fluids excluded from the validated lane. Finite deterministic fluids landed: a per-cell automaton on the hashed scheduled-tick queue, level encoded in the block id, pure-neighbourhood desired state (fall first; horizontal contribution requires the neighbour to sit on solid; support loss drains the network), vanilla cadence and reach, deterministic water-over-lava, `BorderSignal.Kind.FLUID` with no halo writes. Fluid **interactions** landed (`FluidInteractionTest`, 6): a lava source water reaches becomes obsidian, a lava flow becomes cobblestone, and lava arriving above water becomes stone — water is never consumed, which is both vanilla's rule and what keeps the outcome a pure function of the neighbourhood. `RULES_VERSION` 4→5, palette literal `palette.v5`. **Remaining, stated precisely (audited 2026-07-26):** a fluid reaching a region edge emits `BorderSignal.Kind.FLUID` and **nothing consumes it** — a river stops dead at the boundary. The signal alone cannot be consumed: it carries `kind, origin, target, tick` and no state, so the receiving region cannot compute the desired level at `target` (the driving cell is in the sender's region, outside the receiver's snapshot, and the engine never mutates halo). The mechanism that closes that gap **already exists and has never been called**: `HaloUpdate` (tag 56) is defined as "after `region` commits `version`, its coordinator sends the region's EDGE COLUMNS to the committees of every neighbor whose halo overlaps them", it encodes and decodes, and it has no producer and no consumer anywhere outside the codec — as does the read side, where `RegionWorldView.getBlock` outside the covered chunks is still the documented MVP stub "until the real halo arrives". So the work is a producer, a consumer, and the halo-version staleness assertion the `HaloUpdate` javadoc already describes. **The one open design question** is trust: `HaloUpdate` carries region, version and columns and **no certificate**, so a naive consumer would believe its neighbour's edge state — deciding whether the receiver must verify those columns against the sender's committed root, and how it obtains that certificate, is the part that may still need a wire change. Also remaining: live evidence | [10](Task.10.md) | Water and lava spread deterministically across replicas, including cross-region via the migration lane | RETIRING |
| L-7 | Mobs are server-authoritative ghosts; AI is not validated. Deterministic ghost-mob AI landed: ghost zombies move from the validated root, one decision per 10 ticks in canonical id order with a **fixed** draw count regardless of branch, steps only onto walkable cells, borders fail closed, despawn horizon enforced every tick. A 2400-tick soak ends with every ghost on a walkable cell and replica-identical roots. Remaining: targeting, pathfinding, combat, per-species retirement and the mirroring default flip (L-24), live evidence | [11](Task.11.md) | Per-species retirement: ghost share = 0 for every shipped species in soak | RETIRING |
| L-12 | Player movement is server-authoritative. Engine core landed: `MovePlayerAction` (tag 106) — the client **proposes**, the committee decides legality (per-axis ≤ 1 block/action, destination passability at feet and head), and a border step becomes the dupe-proof cross-region transfer carrying the whole player payload. Teleports, speed hacks, and wall clips die in validation on every honest replica. Remaining: the client prediction/rollback overlay (rides L-16), mod-side capture of vanilla movement into `MovePlayerAction`s, live evidence | [12](Task.12.md) | Optimistic validated movement with rollback; cheat movement rejected by the committee | RETIRING |
| L-16 | Committed effects appear 1–2 ticks late (ghost/pending render). Commit-feed wiring landed: `WorkerValidationService.onCommit` streams every committed snapshot and root to one observer, and `ClientValidationLane` constructs a `LocalReplicaView` and feeds it on every applied commit. Remaining: the renderer bind to `render()` and the capture-point `predict()` calls — the GUI half. **The overlay is now fed**: `LocalReplicaView` has had prediction and reconciliation since Task 16a and *nothing ever called `predict`* — the only thing that advanced the view was a commit coming back from the committee, which is exactly the one-to-two-tick lag this row describes from a player's chair. `PredictionFeed` (peer, 6 tests) sits between the capture path and the view — a rendering concern stays out of the submit path and a Minecraft concern stays out of the engine — and `LiveEntityLaneRuntime.submit` offers every signed action to it. Four answers are all ordinary and none is an error: no view (every dedicated server), an untracked region, an action the engine refuses, and a view that throws (prediction is latency-hiding, so a fault costs a late-looking block and never the submit path). Remaining: **the renderer never reads `render()`** — the GUI bind, which rides L-46 | [12](Task.12.md) | Client prediction + rollback: local apply instant, reconciliation invisible in normal play | OPEN |
| L-17 | Gateway migration is a brief reconnect plus an action freeze | [12](Task.12.md) | Local-replica world view: play continues with zero reconnect during migration | OPEN |
| L-50 | The entity lane's live activation self-bootstraps and is proven live (entity lane on 12 regions with the P2P mesh formed; 239 validated/ghost entities across 12 delegated regions with versions advancing; reopen resumes from the store head; clean-slate pickup delivered exactly once). Headless exits are green: jqwik 3 replicas, disjoint committees, forced-process paired-log `@Invariant(11)`, bootstrap plan determinism, dirty-shutdown compensation, and 23,040 B/mob/min at 0 resync bps. Remaining: the certified genesis-from-existing-world replacing the interim manifest end to end, and the scripted pickup/zombie/**pearl** gameplay drives running in CI | [8](Task.8.md) | Reopen resumes from the store head; ghost captures refresh expected state from canonical; validated pickup credit lands exactly once on the vanish repro; the certified extractor replaces coarse digests; per-joiner identities in the plan; pearl ghost/materialize/teleport drives | RETIRING |

---

## Reading guide for the implementing model

- Build the current task **as specified**; do not implement a §B entry early, but do not build
  anything that structurally blocks its owner task.
- When a design choice trades against a §B entry, prefer the choice that keeps the exit test
  achievable.
- Verify a retirement against the row's **exit test**, not its prose. Four rows once sat at RETIRING
  while their exit clauses were already green because the "remaining" notes listed follow-on scope
  the exit never asked for; two others were deliberately held back because their headline claim was
  not yet true. Both mistakes are cheap to make and expensive to trust.
- If you believe an entry is genuinely impossible (not merely hard), do not silently drop it: file it
  as a challenge against this register with the physical argument. The register accepts
  reclassification to §A **with a hiding mechanism**, never deletion.
