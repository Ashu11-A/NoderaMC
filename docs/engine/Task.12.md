# Engine Task 12 — Player Lane & Trustless Closure

<!-- AI-AGENT-INSTRUCTION: This is a PROGRAMME-level task, not a single change. It splits into the
     sub-lanes listed below, each retiring named limitation rows with its own exit test. Its exit
     condition is exact: every category's LIMITATIONS.md §B empty, every envelope constraint's hiding
     mechanism shipped, and assumption A0 fully served with no second-class lane. Do not mark it
     complete on partial sub-lanes. Keep this header's status accurate. -->

**Status:** 🚧 IN PROGRESS (programme-level; several sub-lanes landed)
**Category:** engine · **Owns:** L-12, L-16, L-17 · **Last audit:** 2026-07-28
**Depends on:** [engine 11](Task.11.md), [network 2](../network/Task.2.md), [minecraft 2](../minecraft/Task.2.md)
**Consumed by:** the project's exit condition

---

## Goal

Empty the ledger. Bring the player themself into the validated lane — movement, inventory,
containers, combat, portals, commands, worldgen — and close the trust model with open BFT membership,
dynamic committees, multi-party genesis, and a deterministic rule-set SDK for third-party mods.

**Exit condition:** every category's `LIMITATIONS.md` §B is empty, every envelope constraint's hiding
mechanism has shipped, and assumption A0's population is fully served with no second-class lane.

## Status detail

Landed sub-lanes:

- **Player root presence + inventory.** `EntityKind.PLAYER` carries owner and the 36-slot inventory
  in its payload. Pickups and container withdrawals land in the **validated** root inventory. A
  portal or region hand-off moves the whole inventory through the dupe-proof joint-certificate
  pipeline — removed at source, materialised exactly once at target.
- **Containers.** `ContainerEntry` (tag 105) puts container contents in the hashed root; `CHEST` and
  `ContainerAction` give committee-validated deposit/withdraw with kind, slot bounds, reach, and
  current contents checked — no conjuring, no overdraft. `HOPPER` is a self-scheduling 8-tick machine
  on the hashed queue. Container regions are therefore delegable.
- **Portals.** `PortalRules` + `NETHER_PORTAL` prove cross-dimension travel **is** generalized region
  transfer (8:1 fixed-point scale, no new protocol).
- **Movement (L-12, RETIRING).** `MovePlayerAction` (tag 106): the client **proposes** a step and the
  committee decides legality — per-axis speed envelope (≤ 1 block/action), destination passability
  (feet and head not opaque), and a border step becomes the dupe-proof cross-region transfer carrying
  the whole player payload. Teleports, speed hacks, and wall clips die in validation on every honest
  replica.
- **Combat state in the root.** Identical blocks with different mob or player health produce
  **different** roots — a replica that could not see that difference would certify a divergence the
  next arrow makes visible. A dead mob is *absent* from the root, not present at zero.
- **Commands (L-14, RETIRED).** `CommandAction` (tag 108) + `CommandRules` make a command an ordinary
  signed action.
- **Rule-pack SDK (L-21, RETIRED).** `PackRules` is the executable half of a pack (validate / apply /
  tick, opt-in through a default-empty `rules()`), and `PackDelegatingRuleSet` dispatches by
  **declared palette ownership**. Public contract: [`SDK.md`](SDK.md).
- **Committee rotation + admission.** `CommitteeManager.draftRotation` is deterministic
  rendezvous-hash rotation; every replica derives the identical next committee, the epoch input
  reshuffles seats so tenure is bounded, and installing runs through the certified-change quorum.
- **Multi-party genesis (L-20, RETIRED).** `GenesisApprovalFlow` plus the wire pair
  `GenesisApprovalRequest`/`GenesisApprovalGrant` assemble a self-verifying strict-majority
  `GenesisRecertification`.
- **Byzantine mesh proof (L-18, RETIRED).** `ByzantineMeshIT` puts a genuinely adversarial peer on
  the wire against honest members running the production path.

Remaining: the client prediction and rollback overlay (**L-16**), zero-reconnect local-replica view
during migration (**L-17**), deterministic worldgen, and
the closure audit.

## Dependencies

- [engine 11](Task.11.md) — validated entities and mobs.
- [network 2](../network/Task.2.md) — live P2P membership and migration.
- [minecraft 2](../minecraft/Task.2.md) — the capture point and the renderer bind.

## Deliverables (sub-lanes)

| # | Sub-lane | State |
|---|---|---|
| 1 | Player root presence, inventory, containers | ✅ |
| 2 | Validated movement | 🚧 (L-12) |
| 3 | Combat: PvP/PvE, vitals in the root | 🚧 (XP and drops remain) |
| 4 | Portals and dimensions | ✅ |
| 5 | Deterministic command subset | ✅ |
| 6 | Client prediction + rollback (`LocalReplicaView`) | 🚧 (L-16, L-17) |
| 7 | Deterministic worldgen | ⬜ |
| 8 | BFT open membership + dynamic committees + multi-party genesis | 🚧 |
| 9 | Rule-pack SDK | ✅ |
| 10 | Closure audit — every register empty, every `@Invariant(1..12)` green | ⬜ |

## Design

**Authority is checked in the engine, not at the capture point.** A capture-point check is *advice* a
modified client can skip. `CommandRules` therefore checks a command's authority against a
committee-agreed operator set carried on `RegionExecutionContext` (root-determining, derived from the
signed grant chain), so the rejection happens on every honest validator independently.

**A command may not mint a block a player could not place.** Otherwise `/setblock` becomes a way to
conjure network-computed states like a powered wire. The subset is deliberately narrow and says why:
`/setblock` and `/fill` are pure functions of committed state; `/time set` is **refused** because
committed world time is a member-agreed context input, not region state. Fills are volume-bounded
(32,768) so one authorised action cannot stall every validator and trip the lag handoff, and iterate
in canonical (y, z, x) order so the delta's **bytes** are identical rather than merely equivalent. An
unset operator set means *nobody*, never everyone.

**Pack safety is by construction, not by convention.** The base palette is frozen below a pack-id
floor so a pack can never intercept a vanilla block; registration refuses cross-pack id collisions so
ownership is a function; pack ticks run in canonical namespace order so installation order cannot
change a root; and the engine **derives** its expected fingerprint from the packs it will actually
run. Writing that test caught a real defect: an empty pack list was being folded through the hash, so
a modded-but-packless node computed a different fingerprint from an unmodded one — shipping the SDK
without the fix would have forked the network.

**Prediction is what hides consensus latency.** Commit latency can exceed the 50 ms tick budget; that
is an envelope constraint. The hiding mechanism is a local replica applied instantly and reconciled
invisibly. The commit feed already exists (`WorkerValidationService.onCommit` streams every committed
snapshot and root; `ClientValidationLane` constructs a `LocalReplicaView` and feeds it on every
applied commit) — what remains is the renderer bind and the capture-point predict calls.

## Files

- `java/engine/src/main/java/dev/nodera/simulation/rules/{PlayerRules,MovementRules,ContainerRules,PortalRules,CommandRules,MobCombatRules}.java`
- `java/engine/src/main/java/dev/nodera/simulation/pack/{PackRules,PackDelegatingRuleSet,RulePackRegistry}.java`
- `java/peer/src/main/java/dev/nodera/peer/committee/CommitteeManager.java`
- `java/peer/src/main/java/dev/nodera/peer/genesis/GenesisApprovalFlow.java`

## Testing

- `PlayerInventoryTest` (3) — including the dupe-proof `portalHandOffCarriesTheInventoryExactlyOnce`.
- `ContainerStateRootTest` (5) + `ContainerRulesTest` (7).
- `MovementRulesTest` (3) — speed envelope, passability, border transfer.
- `CommandSubsetTest` (8), `CombatStateRootTest` (4), `PackRuleExecutionTest` (5).
- `CommitteeRotationTest` (3), `GenesisApprovalFlowIT`, `ByzantineMeshIT` (3).

## Acceptance criteria

1. 🚧 Optimistic validated movement with rollback; cheat movement rejected by the committee
   (**L-12** exit).
2. ⬜ Client prediction and rollback: local apply instant, reconciliation invisible in normal play
   (**L-16** exit).
3. ⬜ Local-replica world view: play continues with **zero reconnect** during migration
   (**L-17** exit).
4. ✅ The guard's documented async rejection has a live mixin call site: `LevelChunkMixin` → `BlockWriteGuard`, which reaches `verdictChecked` by thread (**L-25** RETIRED 2026-07-25).
5. ⬜ Deterministic worldgen.
6. ⬜ **Closure audit:** every category's `LIMITATIONS.md` §B empty; a closure soak green with
   adversarial peers and the server absent for extended windows; `@Invariant(1..12)` each with a
   green test.

## Limitations

- **L-12** movement (RETIRING) · **L-16** commit latency / prediction (OPEN) · **L-17** migration
  reconnect (OPEN). **L-25** (async writes under the guard) retired 2026-07-25.
- Retired here: L-10, L-11, L-13, L-14, L-18, L-20, L-21 — see
  [`LIMITATIONS.fixed.md`](LIMITATIONS.fixed.md).
