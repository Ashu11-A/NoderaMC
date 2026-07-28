# Engine — Refactoring Register

<!-- AI-AGENT-INSTRUCTION: This register is advisory, not a contract. It is regenerated from
     build/jscpd/jscpd-report.json plus a manual read of java/core, java/engine, java/testing.
     Every row names a concrete refactor plan; none of them are blockers for a commit. A
     frozen-contract type (core/crypto, core Encodable tags) must keep its wire bytes identical
     when a "dedup via shared util" refactor lands — re-run fixtures/wire + the Rust tag_mirror
     conformance in the same commit as any such change. -->

Source: the `jscpd` duplicate run on 2026-07-28 (`build/jscpd/jscpd-report.json`) plus a manual
read-through of the three engine-category modules (`java/core`, `java/engine`, `java/testing`).
`% duplicated` follows the sweep formula `Σ duplicated line-runs / file total lines × 100`; a value
above 100 means the file participates in more clone-runs than it has lines — a duplication *hub*,
usually a small `Encodable` record whose tag/version guard and `decode` boilerplate recurs across
dozens of siblings.

Scope: only the engine category's three modules. `build/`, `target/`, and generated code are
excluded. Sorted by `% duplicated` descending; `—` marks a manual candidate jscpd did not flag.

| File | Lines | % duplicated | Duplicated-with | Refactor plan |
|---|---:|---:|---|---|
| `java/engine/src/test/.../border/BorderSignalTest.java` | 151 | 290.7 | most engine rule/IT tests | Extract a shared `RegionFixture`/`FakeRegion`-based snapshot+batch builder for the assertion scaffolding every rule test re-pastes (the 8-line "build snapshot, run engine, assert root" preamble). |
| `java/core/.../state/BlockEventEntry.java` | 56 | 244.6 | BlockMutation, ChunkColumnState, ContainerEntry, all Encodables | The tag+version guard + `decode` boilerplate: extract `CanonicalFrame.expectTag(reader, tag)` / `CanonicalFrame.writeTag(writer, tag)` helpers used by every `Encodable` (wire bytes unchanged). |
| `java/engine/src/test/.../committee/CommFixtures.java` | 112 | 231.2 | CoordFixtures, ActionForwardIT, CrossRegionFluidTest, … | Promote to `testing` module as the shared committee/coordinator fixture; six engine tests each paste their own copy. |
| `java/core/.../action/AttackEntityAction.java` | 66 | 201.5 | every GameAction permit, ActionEnvelope | The `encode`/`encodeBody`/`decode`/`decodeBody` 4-method skeleton shared by all permits — fold into a `SealedActionCodec` once the tag/version helper above exists. |
| `java/core/.../action/ActionEnvelope.java` | 105 | 196.2 | ActionBatch, every action, CachedPeerStore, … | Same `Encodable` boilerplate; the `signedPortion()`/`writeSignedFields` split is genuine, keep that, dedup the rest via the frame helper. |
| `java/core/.../consensuscert/VoteDecision.java` | 55 | 147.3 | BlockMutation, CommitteeChangeCertificate, PeerRole, … | Tag+version guard (single `u8` ordinal body) — `Encodable`-enum helper. |
| `java/engine/src/main/.../consensus/ProposalKey.java` | 49 | 132.7 | ActionEnvelope, ActionRejection, PeerAddress, PeerEntry | Small record triple-paste; a `RecordKey`/`Pair`-style base would remove it. Low value alone, folds into the frame helper. |
| `java/engine/src/test/.../entity/MobAiRulesTest.java` | 142 | 152.8 | BorderSignalTest, EntityLaneSoakIT, GravityFireRulesTest, MobCombatTest | Soak-test harness (the "spawn N ghosts, step T ticks, assert walkable + root-identical" block) — extract a `MobSoakHarness`. |
| `java/core/.../identity/PeerRole.java` | 81 | 91.4 | NodeCapabilities, RegionReplicaRole, VoteDecision, WorldHealth | The `enum implements Encodable` `encode`/`decode` pair — one shared `EnumEncodable` helper covers all four enums. |
| `java/core/.../identity/PersistedNodeIdentity.java` | 164 | 90.9 | ActionEnvelope, DimensionKey, EncryptedPiece, ManifestSeeders, PersistedWorldKey | Tag+version guard + UUID-pair + bytes-triple; the `Encodable` frame helper covers most. |
| `java/engine/src/main/.../committee/CommitteeFailover.java` | 74 | 89.2 | LagHandoffPolicy, LeaseManager, NodeRegistry, ProposalManager, RegionAllocator | `Comparator.comparing(NodeId::value)` canonical-order literal and the `TreeMap`/`HashMap` field trio recur — a `CanonicalNodeIdOrder` constant (already inlined in 6 classes) removes it. |
| `java/engine/src/test/.../coordinator/CoordFixtures.java` | 108 | 136.1 | CommFixtures, CommitteeCollapseIT, ContainerRulesTest, … | Share with `CommFixtures` (same consolidation). |
| `java/core/.../action/ActionBatch.java` | 87 | 88.5 | ActionEnvelope, EntityTransferDescriptor, GenesisManifest, QuorumCertificate | `Encodable` frame helper. |
| `java/core/.../action/BreakBlockAction.java` | 58 | 82.8 | AttackEntityAction, BlockChangedEvent, InteractBlockAction, PlaceBlockAction | Sealed-action codec consolidation. |
| `java/core/.../region/RegionCommittee.java` | 140 | 68.6 | NodeCapabilities, RegionLease, RegionProgress | The "defensive-copy + UUID-sort validators" compact-constructor block is shared with `RegionLease` — extract `NodeIdList.canonicalCopyOf`. |
| `java/core/.../region/RegionLease.java` | 127 | 67.7 | NodeCapabilities, RegionCommittee | Same as above (`NodeIdList.canonicalCopyOf`). |
| `java/engine/src/main/.../entity/PortalRules.java` | 73 | 63.0 | MobAiRules, ProjectileRules, RailRules | The fixed-point scale + `transferEntity` cross-border hand-off block recurs across the four entity rules — see L-52 / the shared entity-update helper. |
| `java/engine/src/main/.../simulation/rules/RedstoneRules.java` | 726 | 5.9 | EntityRuleSet, FlatWorldRules | — God class. Split into `WireNetwork`, `PistonMotion`, `ObserverTiming`, and `ComparatorDaylight` helpers; `recomputeNetwork` and `firePistonEvent` are each ~80 lines and the only callers of the family-aware piston helpers. |
| `java/engine/src/main/.../simulation/rules/FlatWorldRules.java` | 539 | 10.2 | EntityRuleSet | — Extract the 100-row `PALETTE` table + `buildPlaceable`/`buildWhitelist` into a `FlatWorldPalette` type; `validate`/`apply` action-dispatch stays. |
| `java/engine/src/main/.../simulation/MutableRegionState.java` | 559 | 3.3 | SnapshotDeltaApplier, RegionHalo, InMemoryWorldView | — Five responsibilities (blocks / entities / scheduled ticks / block events / containers / border signals). The misplaced `worldTime()` javadoc (sitting where the `bindOperators` doc belongs) is a symptom. Extract a `ScheduledState` and `ContainerState` helper owned by the region state. |
| `java/engine/src/main/.../coordinator/entity/EntityTransferCoordinator.java` | 581 | 11.9 | EntityTransferCoordinatorTest, EntityTransferCrashRecoveryIT | — Split the live `transfer()` path from the `restorePending()`/recovery path; the `finish()` step-machine is shared but the validation surfaces are not. |
| `java/engine/src/main/.../coordinator/WorldMutationApplier.java` | 300 | 9.7 | (none, manual) | — `applyAll` runs three near-identical verify+apply passes (block / entity / credit targets). Extract a per-target `TargetStrategy` so the two-pass loop reads once. |
| `multiplyFixed` / `withMotion` / `packChunk` (L-52) | — | — | ItemEntityRules, ProjectileRules, TntRules, RailRules, MobAiRules; MutableRegionState, InMemoryWorldView, SnapshotDeltaApplier, InterferenceProbe, RegionHalo, WorldMutationApplier | — Extract `dev.nodera.simulation.FixedPoint` (the `Math.multiplyHigh` Q32.32 idiom), an entity `EntityUpdate.with(entity,pos,vel)` helper, and a `ChunkKey.pack/unpack` util. Determinism-maintainability win (tracked as L-52). |
| `Encodable` tag+version guard across ~40 core types | — | — | every `Encodable` in `core` | — `CanonicalReader.expectTag(tag)` + `CanonicalWriter.writeFrame(tag)` so the `writeU16(tag).writeU16(ENCODING_VERSION)` / `readU16`-compare-`readVersion` triplet is one call. Wire bytes identical. |
| `instanceof` action-dispatch chains | — | — | FlatWorldRules, EntityRuleSet, BorderClassifier, PackDelegatingRuleSet | — Constrained: a type-pattern `switch` compiles to `SwitchBootstraps` which Android ART does not implement (mobile M-8). Keep the chains, but factor the "resolve target position / id" step into one `Actions.targetBlockIdOf(action)` so the four copies agree. |

## Sequencing

The order favours changes that unlock the rest and that carry the least wire-contract risk:

1. **`CanonicalFrame` tag+version helper + `EnumEncodable`** — unblocks the largest cluster (every
   `Encodable` row above) and is wire-byte-identical, so the only verification is the existing
   `fixtures/wire` + `tag_mirror` conformance. Do this first.
2. **`FixedPoint` + `EntityUpdate` + `ChunkKey` shared utils (L-52)** — removes a real determinism
   hazard (four copies of the multiply idiom) and is covered by a new `@Property` plus the existing
   entity-rule roots. Highest safety value per line touched.
3. **`CanonicalNodeIdOrder` constant + `NodeIdList.canonicalCopyOf`** — small, mechanical, removes
   the `Comparator.comparing(NodeId::value)` literal pasted across the coordinator/committee and the
   validator-sort block in `RegionLease`/`RegionCommittee`.
4. **`RedstoneRules` split** — the biggest god class (726 lines) and the one that makes every
   redstone change expensive to reason about. Split after the helpers above exist so the new
   `WireNetwork`/`PistonMotion` files can use them.
5. **Shared test fixtures (`RegionFixture`, `MobSoakHarness`, consolidate `CommFixtures`/
   `CoordFixtures`)** — the test duplication is the largest absolute volume (BorderSignalTest alone
   is a 290%-hub) and pure-test refactors cannot break the gate; do it once the production helpers
   land so the fixtures adopt them.
