# Engine — Limitations Register

<!-- AI-AGENT-INSTRUCTION: This register is NORMATIVE for the engine category. Rules: (1) "permanent"
     is a banned classification — every row is either an envelope constraint (§A) or a staged
     capability (§B); (2) every §B row has an owning task and an EXIT TEST, and retires only when
     that exit test is green — verify against the exit clause, never against the prose "remaining"
     note; (3) a newly discovered limitation enters §B as OPEN with a path, an owner, and an exit
     test BEFORE the discovering PR merges; (4) never delete a row — move it to LIMITATIONS.fixed.md
     with its evidence. Update this file in the SAME commit that stages or retires a row. -->

**Category:** engine · **Last audit:** 2026-07-25 · Open or retiring rows: **8**

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
| L-1 | Random ticks suppressed in delegated regions (no grass, fire, or crop ticking there). Engine-owned random ticks landed: vanilla-shaped selection with state-derived eligibility that skips **without consuming randomness**, canonical column order, grass MVP semantics, and a 200-tick 3-replica identical-root soak with the lane actively spreading. Remaining: fire and crops, the live random-tick suppression mixin, and a farm soak with the suppression counter at zero | [10](Task.10.md) | Farm soak: engine-owned random ticks, identical roots on 3 replicas, suppression counter deleted | RETIRING |
| L-2 | Fluids excluded from the validated lane. Finite deterministic fluids landed: a per-cell automaton on the hashed scheduled-tick queue, level encoded in the block id, pure-neighbourhood desired state (fall first; horizontal contribution requires the neighbour to sit on solid; support loss drains the network), vanilla cadence and reach, deterministic water-over-lava, `BorderSignal.Kind.FLUID` with no halo writes. Remaining: cross-region spread consumption, fluid interactions (obsidian/cobblestone), live evidence | [10](Task.10.md) | Water and lava spread deterministically across replicas, including cross-region via the migration lane | RETIRING |
| L-7 | Mobs are server-authoritative ghosts; AI is not validated. Deterministic ghost-mob AI landed: ghost zombies move from the validated root, one decision per 10 ticks in canonical id order with a **fixed** draw count regardless of branch, steps only onto walkable cells, borders fail closed, despawn horizon enforced every tick. A 2400-tick soak ends with every ghost on a walkable cell and replica-identical roots. Remaining: targeting, pathfinding, combat, per-species retirement and the mirroring default flip (L-24), live evidence | [11](Task.11.md) | Per-species retirement: ghost share = 0 for every shipped species in soak | RETIRING |
| L-12 | Player movement is server-authoritative. Engine core landed: `MovePlayerAction` (tag 106) — the client **proposes**, the committee decides legality (per-axis ≤ 1 block/action, destination passability at feet and head), and a border step becomes the dupe-proof cross-region transfer carrying the whole player payload. Teleports, speed hacks, and wall clips die in validation on every honest replica. Remaining: the client prediction/rollback overlay (rides L-16), mod-side capture of vanilla movement into `MovePlayerAction`s, live evidence | [12](Task.12.md) | Optimistic validated movement with rollback; cheat movement rejected by the committee | RETIRING |
| L-16 | Committed effects appear 1–2 ticks late (ghost/pending render). Commit-feed wiring landed: `WorkerValidationService.onCommit` streams every committed snapshot and root to one observer, and `ClientValidationLane` constructs a `LocalReplicaView` and feeds it on every applied commit. Remaining: the renderer bind to `render()` and the capture-point `predict()` calls — the GUI half | [12](Task.12.md) | Client prediction + rollback: local apply instant, reconciliation invisible in normal play | OPEN |
| L-17 | Gateway migration is a brief reconnect plus an action freeze | [12](Task.12.md) | Local-replica world view: play continues with zero reconnect during migration | OPEN |
| L-24 | `mobCapture` ghost lane defaults off until proven | [11](Task.11.md) | Default flips per species as validation ships | OPEN |
| L-25 | Async world writes by other mods are undefined under the guard. Both exit halves landed: the legal async API (`AsyncActionGate.submit` accepts signed actions from any thread into a bounded FIFO; the server thread drains once per tick, so asynchrony ends at the gate and determinism begins at the drain) and the documented rejection (`MutationGuard.verdictChecked` throws `AsyncWriteException` for an UNKNOWN-source write into a delegated region, with an actionable message naming the gate and `SDK.md` — never a silent block or convert). The SDK clause is green since the rule-pack SDK shipped. **The only reason this row is not RETIRED:** `verdictChecked` still has no live mixin call site, so a guard nothing calls rejects nothing in practice — the switch rides the same live lane as the mod's harness | [12](Task.12.md) | The rule-set SDK provides the legal async mutation API; the guard rejects the rest with a documented error | RETIRING |
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
