package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16 / L-14: cross-dimension travel is generalized region transfer. An engine-owned entity
 * standing in a {@code NETHER_PORTAL} cell emits an {@code EntityTransferIntent} whose target
 * region lives in the nether — same pipeline, same certificates, no new protocol — with the
 * vanilla 8:1 coordinate scale in pure fixed-point math; GHOSTs never engine-portal.
 */
final class PortalRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        return EngineFixtures.executeTicks(engine, region, base, actions, tickCount, 424242L);
    }

    private static PersistedEntityState mobAt(RegionId r, int seq, double x, double y, double z) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                EntityKind.MOB, SpawnRules.ZOMBIE_TYPE_ID,
                FixedVec3.fromExternal(x, y, z), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN,
                MobCombatRules.vitalsPayload(
                        MobCombatRules.ZOMBIE_MAX_HEALTH, MobCombatRules.ZOMBIE_MAX_HEALTH));
    }

    @Test
    void aMobInThePortalTransfersToTheNetherAtTheEightToOneScale() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        PersistedEntityState mob = mobAt(region, 2, 80.5, 64.5, 80.5);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(mob));
        List<ActionEnvelope> portal = List.of(TestFixtures.envelope(region, 0L, 1L,
                TestFixtures.place(new NBlockPos(80, 64, 80), FlatWorldRules.NETHER_PORTAL)));

        RegionExecutionResult first = executeTicks(base, portal, 1);
        RegionExecutionResult second = executeTicks(base, portal, 1);
        assertThat(second.resultingRoot())
                .as("the portal hand-off is replica-identical")
                .isEqualTo(first.resultingRoot());

        assertThat(first.delta().transferIntents()).hasSize(1);
        EntityTransferIntent intent = first.delta().transferIntents().get(0);
        assertThat(intent.targetRegion().dimension())
                .as("the target region lives in the nether — same transfer pipeline")
                .isEqualTo(PortalRules.NETHER);
        assertThat(intent.targetState().pos().blockX())
                .as("overworld x=80 scales 8:1 to nether x=10")
                .isEqualTo(10);
        assertThat(intent.targetState().pos().blockZ()).isEqualTo(10);
        assertThat(intent.targetState().pos().blockY())
                .as("Y is preserved across dimensions")
                .isEqualTo(64);
    }

    @Test
    void ghostsNeverEnginePortal() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        PersistedEntityState ghost = new PersistedEntityState(
                NetworkEntityId.allocate(region, SnapshotVersion.INITIAL, 2),
                EntityKind.GHOST, SpawnRules.ZOMBIE_TYPE_ID,
                FixedVec3.fromExternal(80.5, 64.5, 80.5), FixedVec3.ZERO,
                0, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(ghost));
        RegionExecutionResult result = executeTicks(base,
                List.of(TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(80, 64, 80),
                                FlatWorldRules.NETHER_PORTAL))), 1);
        assertThat(result.delta().transferIntents())
                .as("a vanilla-authoritative ghost stays put")
                .isEmpty();
    }
}
