package dev.nodera.simulation.entity;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.MovePlayerAction;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16 / L-12: player movement is a committee-validated action. A legal step moves the
 * committed root presence; a speed/teleport cheat and a wall clip die in validation on every
 * honest replica; a border step becomes the dupe-proof cross-region transfer carrying the whole
 * player payload.
 */
final class MovementRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 313131L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    private static NodeId actor() {
        return TestFixtures.envelope(TestFixtures.region(0, 0), 0L, 1L,
                new PickupItemAction(NetworkEntityId.allocate(
                        TestFixtures.region(0, 0), SnapshotVersion.INITIAL, 9))).actor();
    }

    private static PersistedEntityState playerAt(RegionId r, NodeId owner, double x, double y,
                                                 double z) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, 1),
                EntityKind.PLAYER, PlayerRules.PLAYER_TYPE_ID,
                FixedVec3.fromExternal(x, y, z), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN,
                PlayerRules.emptyInventoryPayload(owner));
    }

    @Test
    void aLegalStepMovesTheCommittedPresenceDeterministically() {
        NodeId owner = actor();
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(playerAt(region, owner, 64.5, 64.5, 64.5)));
        List<ActionEnvelope> step = List.of(TestFixtures.envelope(region, 0L, 1L,
                new MovePlayerAction(FixedVec3.fromExternal(65.25, 64.5, 64.5))));

        RegionExecutionResult first = executeTicks(base, step, 1);
        RegionExecutionResult second = executeTicks(base, step, 1);
        assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 1L);
        assertThat(PlayerRules.findPlayer(settled.entities(), owner).pos().blockX())
                .as("the committed presence moved")
                .isEqualTo(65);
    }

    @Test
    void speedCheatsAndWallClipsDieInValidation() {
        NodeId owner = actor();
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(playerAt(region, owner, 64.5, 64.5, 64.5)));

        // Teleport: 10 blocks in one action.
        RegionSnapshot afterTeleport = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base,
                executeTicks(base, List.of(TestFixtures.envelope(region, 0L, 1L,
                        new MovePlayerAction(FixedVec3.fromExternal(74.5, 64.5, 64.5)))), 1)
                        .delta(),
                1L);
        assertThat(PlayerRules.findPlayer(afterTeleport.entities(), owner).pos().blockX())
                .as("a 10-block 'step' is rejected — the presence never moved")
                .isEqualTo(64);

        // Wall clip: destination cell is stone.
        RegionSnapshot walled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base,
                executeTicks(base, List.of(TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(65, 64, 64), FlatWorldRules.STONE))), 1)
                        .delta(),
                1L);
        RegionSnapshot afterClip = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                walled,
                new FlatWorldRegionEngine(FlatWorldRules.RULES_VERSION,
                        FlatWorldRules.registryFingerprint(), hashes)
                        .execute(new RegionExecutionRequest(
                                new RegionExecutionContext(region, RegionEpoch.INITIAL,
                                        walled.version(), 1, 2, 313131L,
                                        FlatWorldRules.RULES_VERSION,
                                        FlatWorldRules.registryFingerprint()),
                                walled,
                                new ActionBatch(region, RegionEpoch.INITIAL, walled.version(),
                                        1, 2, List.of(TestFixtures.envelope(region, 1L, 2L,
                                        new MovePlayerAction(
                                                FixedVec3.fromExternal(65.5, 64.5, 64.5)))))))
                        .delta(),
                2L);
        assertThat(PlayerRules.findPlayer(afterClip.entities(), owner).pos().blockX())
                .as("clipping into stone is rejected")
                .isEqualTo(64);
    }

    @Test
    void aBorderStepBecomesTheDupeProofCrossRegionTransfer() {
        NodeId owner = actor();
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        // Region (0,0) owns x in [0,128); stand at the east edge and step over it.
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(playerAt(region, owner, 127.6, 64.5, 64.5)));
        RegionExecutionResult result = executeTicks(base,
                List.of(TestFixtures.envelope(region, 0L, 1L,
                        new MovePlayerAction(FixedVec3.fromExternal(128.4, 64.5, 64.5)))), 1);

        assertThat(result.delta().transferIntents())
                .as("the border step rides the transfer pipeline")
                .hasSize(1);
        assertThat(result.delta().transferIntents().get(0).targetRegion().originChunkX())
                .isEqualTo(8);
        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, result.delta(), 1L);
        assertThat(PlayerRules.findPlayer(settled.entities(), owner))
                .as("dupe-proof: the presence left the source exactly once")
                .isNull();
    }
}
