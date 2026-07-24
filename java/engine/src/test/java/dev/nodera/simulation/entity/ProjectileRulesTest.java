package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionBounds;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 15 (L-9): deterministic projectiles. Arrows fly a fixed-point arc — gravity pulls them
 * down each tick, drag bleeds speed, an opaque wall stops them dead — and every replica traces
 * the identical trajectory from the root alone.
 */
final class ProjectileRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 31337L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    private static PersistedEntityState arrow(RegionId r, int seq, double x, double y, double z,
                                              double vx, double vy, double vz, int despawnTick) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                EntityKind.PROJECTILE, ProjectileRules.ARROW_TYPE_ID,
                FixedVec3.fromExternal(x, y, z), FixedVec3.fromExternal(vx, vy, vz),
                0, despawnTick, Bytes.empty());
    }

    private static PersistedEntityState soleProjectile(RegionSnapshot snapshot) {
        List<PersistedEntityState> shots = snapshot.entities().stream()
                .filter(e -> e.kind() == EntityKind.PROJECTILE).toList();
        assertThat(shots).hasSize(1);
        return shots.get(0);
    }

    @Test
    void arrowFliesAnArcWithIdenticalRootsAcrossReplicas() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        PersistedEntityState arrow = arrow(region, 1, 64.5, 64.5, 64.5, 0.5, 0.3, 0.0,
                ProjectileRules.LIFETIME_TICKS);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(arrow));

        RegionExecutionResult first = executeTicks(base, List.of(), 60);
        RegionExecutionResult second = executeTicks(base, List.of(), 60);
        assertThat(second.resultingRoot())
                .as("the fixed-point arc settles to one trajectory root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 60L);
        PersistedEntityState flown = soleProjectile(settled);
        assertThat(flown.pos().blockX())
                .as("the arrow advanced downrange")
                .isGreaterThan(64);
        assertThat(flown.vel().y())
                .as("gravity has bent the arc downward by now")
                .isLessThan(0L);
    }

    @Test
    void gravityActsBeforeDragEachTick() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        MutableRegionState state = new MutableRegionState(air, RegionBounds.of(region));
        PersistedEntityState arrow = arrow(region, 1, 64.5, 64.5, 64.5, 1.0, 1.0, 0.0,
                ProjectileRules.LIFETIME_TICKS);
        state.createEntity(arrow);

        ProjectileRules.tick(state, 0L, new DeterministicRandom(1L));
        PersistedEntityState stepped = state.entity(arrow.id());

        assertThat(stepped.pos().x())
                .as("position advances by the new velocity")
                .isGreaterThan(arrow.pos().x());
        assertThat(stepped.vel().y())
                .as("gravity (applied before drag) reduces the vertical speed")
                .isLessThan(arrow.vel().y());
    }

    @Test
    void arrowSticksToAnOpaqueWallAndDoesNotPassThrough() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        PersistedEntityState arrow = arrow(region, 1, 60.5, 64.5, 64.5, 1.0, 0.0, 0.0,
                ProjectileRules.LIFETIME_TICKS);
        RegionSnapshot base = new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(arrow));
        // A stone wall the arrow must strike.
        List<ActionEnvelope> wall = new ArrayList<>();
        long seq = 1;
        for (int x = 63; x <= 66; x++) {
            wall.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(x, 64, 64), FlatWorldRules.STONE)));
        }

        RegionExecutionResult first = executeTicks(base, wall, 20);
        RegionExecutionResult second = executeTicks(base, wall, 20);
        assertThat(second.resultingRoot())
                .as("the stick settles to one root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 20L);
        PersistedEntityState stuck = soleProjectile(settled);
        assertThat(stuck.vel())
                .as("the arrow is embedded in the wall — velocity killed")
                .isEqualTo(FixedVec3.ZERO);
        assertThat(stuck.pos().blockX())
                .as("the arrow never crossed into the wall")
                .isLessThan(63);
        assertThat(stuck.pos().blockY())
                .as("the arrow rests at its strike height")
                .isEqualTo(64);
    }

    @Test
    void arrowDespawnsAtItsLifetimeHorizon() {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        MutableRegionState state = new MutableRegionState(air, RegionBounds.of(region));
        PersistedEntityState arrow = arrow(region, 1, 64.5, 64.5, 64.5, 0.5, 0.0, 0.0, 50);
        state.createEntity(arrow);

        ProjectileRules.tick(state, 49L, new DeterministicRandom(1L));
        assertThat(state.entity(arrow.id())).as("one tick early: still in flight").isNotNull();
        ProjectileRules.tick(state, 50L, new DeterministicRandom(1L));
        assertThat(state.entity(arrow.id())).as("the lifetime horizon removes the shot").isNull();
    }
}
