# Engine — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: This register is advisory, not a contract. It is regenerated from
     build/jscpd/jscpd-report.json plus a manual read of library/java/core, library/java/engine, library/java/testing.
     Every row names a concrete refactor plan; none of them are blockers for a commit. A
     frozen-contract type (core/crypto, core Encodable tags) must keep its wire bytes identical
     when a "dedup via shared util" refactor lands — re-run fixtures/wire + the Rust tag_mirror
     conformance in the same commit as any such change. -->

Source: the `jscpd` duplicate run on 2026-07-28 (`build/jscpd/jscpd-report.json`) plus a manual
read-through of the three engine-category modules (`library/java/core`, `library/java/engine`, `library/java/testing`).
`% duplicated` follows the sweep formula `Σ duplicated line-runs / file total lines × 100`; a value
above 100 means the file participates in more clone-runs than it has lines — a duplication *hub*,
usually a small `Encodable` record whose tag/version guard and `decode` boilerplate recurs across
dozens of siblings.

Scope: only the engine category's three modules. `build/`, `target/`, and generated code are
excluded. Sorted by `% duplicated` descending; `—` marks a manual candidate jscpd did not flag.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---:|---:|---|---|
| `library/java/engine/src/test/.../border/BorderSignalTest.java` | 151 | 290.7 | most engine rule/IT tests | Extract a shared `RegionFixture`/`FakeRegion`-based snapshot+batch builder for the assertion scaffolding every rule test re-pastes (the 8-line "build snapshot, run engine, assert root" preamble). |
| `library/java/core/.../state/BlockEventEntry.java` | 56 | 244.6 | BlockMutation, ChunkColumnState, ContainerEntry, all Encodables | The tag+version guard + `decode` boilerplate: extract `CanonicalFrame.expectTag(reader, tag)` / `CanonicalFrame.writeTag(writer, tag)` helpers used by every `Encodable` (wire bytes unchanged). |
| `library/java/core/.../action/AttackEntityAction.java` | 66 | 201.5 | every GameAction permit, ActionEnvelope | The `encode`/`encodeBody`/`decode`/`decodeBody` 4-method skeleton shared by all permits — fold into a `SealedActionCodec` once the tag/version helper above exists. |
| `library/java/core/.../action/ActionEnvelope.java` | 105 | 196.2 | ActionBatch, every action, ~~CachedPeerStore~~ (deleted 2026-08-06), … | Same `Encodable` boilerplate; the `signedPortion()`/`writeSignedFields` split is genuine, keep that, dedup the rest via the frame helper. |
| `library/java/core/.../consensuscert/VoteDecision.java` | 55 | 147.3 | BlockMutation, PeerRole, … | Tag+version guard (single `u8` ordinal body) — `Encodable`-enum helper. (`CommitteeChangeCertificate`, a 2026-07-28 partner, was deleted on 2026-08-06 with the committee-manager design; tag 53 stays reserved in `TypeTags`.) |
| `library/java/engine/src/main/.../consensus/ProposalKey.java` | 49 | 132.7 | ActionEnvelope, ActionRejection, PeerAddress, PeerEntry | Small record triple-paste; a `RecordKey`/`Pair`-style base would remove it. Low value alone, folds into the frame helper. |
| `library/java/engine/src/test/.../entity/MobAiRulesTest.java` | 142 | 152.8 | BorderSignalTest, EntityLaneSoakIT, GravityFireRulesTest, MobCombatTest | Soak-test harness (the "spawn N ghosts, step T ticks, assert walkable + root-identical" block) — extract a `MobSoakHarness`. **Moved 2026-08-06** (`08291ff`): the file no longer exists. The subject is now `@Nested class MobAiRulesTest` inside `library/java/engine/src/test/.../entity/MobRulesTest.java`, which also absorbed this row's `MobCombatTest` partner; `GravityFireRulesTest` moved the same way into `simulation/rules/AmbientRulesTest.java`. `BorderSignalTest` and `EntityLaneSoakIT` are still files of their own. The harness was not extracted, so the plan stands. |
| `library/java/core/.../identity/PeerRole.java` | 81 | 91.4 | NodeCapabilities, RegionReplicaRole, VoteDecision, WorldHealth | The `enum implements Encodable` `encode`/`decode` pair — one shared `EnumEncodable` helper covers all four enums. |
| `library/java/core/.../identity/PersistedNodeIdentity.java` | 164 | 90.9 | ActionEnvelope, DimensionKey, EncryptedPiece, ManifestSeeders, PersistedWorldKey | Tag+version guard + UUID-pair + bytes-triple; the `Encodable` frame helper covers most. |
| `library/java/engine/src/main/.../committee/CommitteeFailover.java` | 74 | 89.2 | LagHandoffPolicy, LeaseManager | `Comparator.comparing(NodeId::value)` canonical-order literal and the `TreeMap`/`HashMap` field trio recur — a `CanonicalNodeIdOrder` constant removes it. Three of this row's five 2026-07-28 partners (`NodeRegistry`, `ProposalManager`, `RegionAllocator`) were deleted with the central-coordinator design on 2026-08-06, so the cluster is now two files, not five. |
| `library/java/core/.../action/ActionBatch.java` | 87 | 88.5 | ActionEnvelope, EntityTransferDescriptor, GenesisManifest, QuorumCertificate | `Encodable` frame helper. |
| `library/java/core/.../action/BreakBlockAction.java` | 58 | 82.8 | AttackEntityAction, BlockChangedEvent, InteractBlockAction, PlaceBlockAction | Sealed-action codec consolidation. |
| `library/java/core/.../region/RegionCommittee.java` | 140 | 68.6 | NodeCapabilities, RegionLease, RegionProgress | The "defensive-copy + UUID-sort validators" compact-constructor block is shared with `RegionLease` — extract `NodeIdList.canonicalCopyOf`. |
| `library/java/core/.../region/RegionLease.java` | 127 | 67.7 | NodeCapabilities, RegionCommittee | Same as above (`NodeIdList.canonicalCopyOf`). |
| `library/java/engine/src/main/.../entity/PortalRules.java` | 70 | — | MobAiRules, ProjectileRules, RailRules | L-52 removed the entity-reconstruction clone. Target-region resolution + `transferEntity` hand-off still recur; extract only that remaining policy if the four paths must evolve together. |
| `library/java/engine/src/main/.../simulation/rules/RedstoneRules.java` | 726 | 5.9 | EntityRuleSet, FlatWorldRules | — God class. Split into `WireNetwork`, `PistonMotion`, `ObserverTiming`, and `ComparatorDaylight` helpers; `recomputeNetwork` and `firePistonEvent` are each ~80 lines and the only callers of the family-aware piston helpers. |
| `library/java/engine/src/main/.../simulation/rules/FlatWorldRules.java` | 539 | 10.2 | EntityRuleSet | — Extract the 100-row `PALETTE` table + `buildPlaceable`/`buildWhitelist` into a `FlatWorldPalette` type; `validate`/`apply` action-dispatch stays. |
| `library/java/engine/src/main/.../simulation/MutableRegionState.java` | 559 | 3.3 | SnapshotDeltaApplier, RegionHalo, InMemoryWorldView | — Five responsibilities (blocks / entities / scheduled ticks / block events / containers / border signals). The misplaced `worldTime()` javadoc (sitting where the `bindOperators` doc belongs) is a symptom. Extract a `ScheduledState` and `ContainerState` helper owned by the region state. |
| `library/java/engine/src/main/.../coordinator/entity/EntityTransferCoordinator.java` | 581 | 11.9 | EntityTransferCoordinatorTest, EntityTransferCrashRecoveryIT | — Split the live `transfer()` path from the `restorePending()`/recovery path; the `finish()` step-machine is shared but the validation surfaces are not. |
| `library/java/engine/src/main/.../coordinator/WorldMutationApplier.java` | 300 | 9.7 | (none, manual) | — `applyAll` runs three near-identical verify+apply passes (block / entity / credit targets). Extract a per-target `TargetStrategy` so the two-pass loop reads once. |
| `Encodable` tag+version guard across ~40 core types | — | — | every `Encodable` in `core` | — `CanonicalReader.expectTag(tag)` + `CanonicalWriter.writeFrame(tag)` so the `writeU16(tag).writeU16(ENCODING_VERSION)` / `readU16`-compare-`readVersion` triplet is one call. Wire bytes identical. |
| `instanceof` action-dispatch chains | — | — | FlatWorldRules, EntityRuleSet, BorderClassifier, PackDelegatingRuleSet | — Constrained: a type-pattern `switch` compiles to `SwitchBootstraps` which Android ART does not implement (mobile M-8). Keep the chains, but factor the "resolve target position / id" step into one `Actions.targetBlockIdOf(action)` so the four copies agree. |
| One-line Javadoc boilerplate riding the same `Encodable` types (Plan.11 phase 2 audit, 2026-08-05) | — | — | 65 files repeat `<p>Thread-context: immutable record, safe for any thread.`; 35 repeat `@Thread-context not thread-safe; one reader per decode call.`; 12 repeat `@throws IllegalArgumentException if any argument is null.`; 7 repeat `Full-frame decode (tag + version + body).` verbatim (`core/action/*`) | Not a missed duplication — jscpd's line-based clone detector already counts these lines as part of the `%` figures on the two rows above, since it does not strip comments. They are listed separately here because the fix is different: the `CanonicalFrame` helper (row above) collapses the *code*; the matching one-line Javadoc disappears with it as a side effect, not because a docs pass rewrote 65 files. No standalone action — do not "fix" this by editing comments file-by-file; it retires when the `Encodable` frame helper lands. |

## Resolved

- **2026-08-06 — one engine fixture instead of nine** (Plan 11 round 3, issue #214). `CommFixtures`,
  `CoordFixtures`, `shadow/Fixtures` and `FbFixtures` are deleted; `simulation/TestFixtures`,
  `DistFixtures`, `StoreFixtures` and `testkit.peer.RegionFixtures` keep only the half that is
  genuinely theirs (a bare-`GameAction` façade, a swarm peer, a storage row, a signed envelope) and
  delegate the rest to `dev.nodera.testkit.engine.EngineFixtures`. The two rows this register
  carried for `CommFixtures` and `CoordFixtures` are struck because the files no longer exist. Two
  behaviours the deleted copies held — `CommFixtures.corruptingEngine()` and `CoordFixtures.caps()`
  — were dropped rather than moved: neither had a caller anywhere in the tree at the time of the
  consolidation. Nothing else was lost; every test method name and every assertion line in the
  touched trees is accounted for.

- **2026-08-06 — the retired central-coordinator design is gone** (Plan 11 round 2, issue #210).
  30 production files across `coordinator/`, `committee/`, `consensus/` and `shadow/`, plus
  `core/region/RegionPlacementPolicy`, had zero production callers: a transitive closure over
  `java.main` from the real entry points (the two endpoint shells and `HeadlessPeerMain`) could not
  reach any of them, and `:peer:structureReport`'s debugger-profiled worker run never loaded one.
  Task 30 removed the dedicated server, so a region allocator, a proposal manager, a server-side
  verifier, a heartbeat monitor over a node registry, the multi-factor reliability scorer, the
  spot-check audit lane and the whole shadow-validation coordinator were compiled into every jar
  and executed by nothing but their own tests. What survives is what production reaches:
  `WorldMutationApplier`, `LeaseManager`, `ReliabilityLedger`, `LagHandoffPolicy`, the committee
  primitives (`CommitteeMember`/`MemberBallot`/`VotePersistence`/`CommitteeFailover`), the quorum
  policy, and `shadow/SnapshotDeltaApplier` + `InterferenceProbe`. 21 dedicated test files went with
  them; no live class lost its only coverage (each has its own unit test, and the peer-side ITs
  `ByzantineMeshIT`/`LiveLagHandoffIT`/`WorkerQuorumValidationIT` exercise the shipping batch loop).

- **2026-07-28 — L-52:** `FixedPoint`, `PersistedEntityState.withMotion`/`withMotionAndAge`, and
  `ChunkKey` now own the deterministic helper implementations. `FixedPointTest`, `ChunkKeyTest`, and
  `EntityLaneTypesTest` pin arithmetic, reversible bit layout, and canonical entity bytes.

## Sequencing

The order favours changes that unlock the rest and that carry the least wire-contract risk:

1. **`CanonicalFrame` tag+version helper + `EnumEncodable`** — unblocks the largest cluster (every
   `Encodable` row above) and is wire-byte-identical, so the only verification is the existing
   `fixtures/wire` + `tag_mirror` conformance. Do this first.
2. **`CanonicalNodeIdOrder` constant + `NodeIdList.canonicalCopyOf`** — small, mechanical, removes
   the `Comparator.comparing(NodeId::value)` literal pasted across the coordinator/committee and the
   validator-sort block in `RegionLease`/`RegionCommittee`.
3. **`RedstoneRules` split** — the biggest god class (726 lines) and the one that makes every
   redstone change expensive to reason about. Split after the helpers above exist so the new
   `WireNetwork`/`PistonMotion` files can use them.
4. **Shared test fixtures** — the fixture half of this landed on 2026-08-06 (see Resolved above):
   `EngineFixtures` is now the one builder set and the eight copies are callers. What is still open
   is the assertion scaffolding rather than the values — a `MobSoakHarness` for the "spawn N ghosts,
   step T ticks, assert walkable + root-identical" block, and the snapshot+batch preamble that makes
   `BorderSignalTest` a 290%-hub. Pure-test refactors cannot break the gate, so this stays cheap.
5. **`FlatWorldPalette` extraction** — move the static palette table and whitelist builders out of
   `FlatWorldRules`; keep action dispatch and mutation semantics in the rule class.
