package dev.nodera.shadow;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotDeltaApplierTest {

    private RegionExecutionResult run(RegionSnapshot base, ActionEnvelope... actions) {
        RegionId region = base.region();
        ActionBatch batch = EngineFixtures.batch(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, List.of(actions));
        // Deliberately NOT EngineFixtures.contextFor: byte-identical, but this suite is the one
        // that proves SessionParams derives the context a shadow replica will derive.
        RegionExecutionRequest req = new RegionExecutionRequest(
                SessionParams.flatWorld(EngineFixtures.WORLD_SEED).contextFor(batch), base, batch);
        return EngineFixtures.engine().execute(req);
    }

    @Test
    void appliedDeltaReHashesToEngineRoot() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 0); // AIR
        RegionExecutionResult res = run(base,
                EngineFixtures.place(region, EngineFixtures.node(1L), 1, 0, 5, 70, 5, 1),
                EngineFixtures.place(region, EngineFixtures.node(1L), 2, 0, 20, 100, 40, 4),
                EngineFixtures.brk(region, EngineFixtures.node(1L), 3, 0, 5, 70, 5));

        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, res.delta(), 1L);

        // The delta faithfully transports the state transition: re-hashing the applied snapshot
        // reproduces the engine's own truth root.
        assertThat(advanced.version()).isEqualTo(base.version().next());
        assertThat(EngineFixtures.rootOf(advanced)).isEqualTo(res.resultingRoot());
    }

    @Test
    void emptyBatchIsIdentity() {
        RegionId region = EngineFixtures.region(-2, 3); // negative-coordinate region
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 1);
        RegionExecutionResult res = run(base);
        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, res.delta(), 1L);
        assertThat(res.delta().isEmpty()).isTrue();
        assertThat(EngineFixtures.rootOf(advanced)).isEqualTo(res.resultingRoot());
    }

    @Test
    void twoMutationsInOneSectionDoNotFalseAbort() {
        // Two positions in the SAME 16-block section both capture the pre-batch section value; a
        // naive interleaved CAS would abort on the second. The two-pass applier validates all guards
        // against the pre-delta state first, so it applies cleanly.
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 0); // AIR
        RegionExecutionResult res = run(base,
                EngineFixtures.place(region, EngineFixtures.node(1L), 1, 0, 5, 70, 5, 1),   // chunk (0,0), section 8
                EngineFixtures.place(region, EngineFixtures.node(1L), 2, 0, 6, 70, 6, 2));  // same chunk + section

        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, res.delta(), 1L);
        assertThat(EngineFixtures.rootOf(advanced)).isEqualTo(res.resultingRoot());
    }

    @Test
    void sectionDeltaUsesExecutionOrderNotCanonicalPositionOrder() {
        // Task 13 densification: two placements in one section are now TWO per-block mutations
        // (the pre-densification section-paint model coalesced them to one), in execution order
        // — and the applied snapshot still reproduces the engine root exactly.
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult res = run(base,
                EngineFixtures.place(region, EngineFixtures.node(1L), 1, 0, 6, 70, 6, 2),
                EngineFixtures.place(region, EngineFixtures.node(1L), 2, 0, 5, 70, 5, 1));
        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, res.delta(), 1L);
        assertThat(res.delta().blockMutations()).hasSize(2);
        assertThat(res.delta().blockMutations())
                .extracting(m -> m.pos())
                .containsExactlyInAnyOrder(
                        new dev.nodera.core.state.NBlockPos(6, 70, 6),
                        new dev.nodera.core.state.NBlockPos(5, 70, 5));
        assertThat(EngineFixtures.rootOf(advanced)).isEqualTo(res.resultingRoot());
    }

    @Test
    void legacyBlockOnlyReplayPreservesVersionOneRootBytes() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot current = EngineFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot base = new RegionSnapshot(
                region, SnapshotVersion.INITIAL, 0, current.chunks(), List.of(), 1);
        RegionSnapshot expected = new RegionSnapshot(
                region, SnapshotVersion.INITIAL.next(), 1, current.chunks(), List.of(), 1);
        RegionDelta delta = new RegionDelta(
                region, SnapshotVersion.INITIAL, SnapshotVersion.INITIAL.next(), List.of(),
                EngineFixtures.rootOf(expected), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 1);
        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, delta, 1);
        assertThat(advanced).isEqualTo(expected);
        assertThat(advanced.bodyVersion()).isEqualTo(1);
    }

    @Test
    void casMismatchThrowsReplicaDrift() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot air = EngineFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult res = run(air, EngineFixtures.place(region, EngineFixtures.node(1L), 1, 0, 5, 70, 5, 1)); // expects prev AIR

        // A replica that already holds STONE where the delta expects AIR has drifted.
        RegionSnapshot stone = EngineFixtures.fullUniformSnapshot(region, 1);
        assertThatThrownBy(() -> SnapshotDeltaApplier.apply(stone, res.delta(), 1L))
                .isInstanceOf(ReplicaDriftException.class);
    }

    @Test
    void versionMismatchRejected() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult res = run(base, EngineFixtures.place(region, EngineFixtures.node(1L), 1, 0, 5, 70, 5, 1));
        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, res.delta(), 1L); // now at v1

        // Applying the same v0-based delta to a v1 replica is a version mismatch, not silent drift.
        assertThatThrownBy(() -> SnapshotDeltaApplier.apply(advanced, res.delta(), 2L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entityMutationAdvancesReplicaAndReHashesToDeltaRoot() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 0);
        PersistedEntityState entity = item(1, 5);
        RegionSnapshot expected = new RegionSnapshot(
                region, base.version().next(), 1, base.chunks(), List.of(entity));
        RegionDelta delta = new RegionDelta(
                region, base.version(), base.version().next(), List.of(),
                EngineFixtures.rootOf(expected), List.of(new EntityMutation(entity.id(), null, entity)), List.of());
        RegionSnapshot advanced = SnapshotDeltaApplier.apply(base, delta, 1);
        assertThat(advanced).isEqualTo(expected);
        assertThat(EngineFixtures.rootOf(advanced)).isEqualTo(delta.resultingRoot());
    }

    @Test
    void entityCasMismatchThrowsEntityReplicaDrift() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot blocks = EngineFixtures.fullUniformSnapshot(region, 0);
        PersistedEntityState expected = item(1, 5);
        PersistedEntityState actual = item(1, 6);
        RegionSnapshot base = new RegionSnapshot(
                region, blocks.version(), 0, blocks.chunks(), List.of(actual));
        RegionDelta delta = new RegionDelta(
                region, base.version(), base.version().next(), List.of(), StateRoot.zero(),
                List.of(new EntityMutation(expected.id(), expected, null)), List.of());
        assertThatThrownBy(() -> SnapshotDeltaApplier.apply(base, delta, 1))
                .isInstanceOf(EntityReplicaDriftException.class);
    }

    @Test
    void resultingRootMismatchIsRejectedBeforePublishingReplica() {
        RegionId region = EngineFixtures.region(0, 0);
        RegionSnapshot base = EngineFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult result = run(
                base, EngineFixtures.place(region, EngineFixtures.node(1L), 1, 0, 5, 70, 5, 1));
        RegionDelta tampered = new RegionDelta(
                result.delta().region(), result.delta().baseVersion(),
                result.delta().resultingVersion(), result.delta().blockMutations(),
                StateRoot.zero(), result.delta().entityMutations(),
                result.delta().inventoryCredits(), result.delta().transferIntents());

        assertThatThrownBy(() -> SnapshotDeltaApplier.apply(base, tampered, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("root mismatch");
    }

    private static PersistedEntityState item(long id, int age) {
        return new PersistedEntityState(
                new NetworkEntityId(id), EntityKind.ITEM, 42,
                FixedVec3.ofBlock(2, 5, 2), FixedVec3.ZERO,
                age, 6_000, ItemEntityRules.payload(42, 3));
    }
}
