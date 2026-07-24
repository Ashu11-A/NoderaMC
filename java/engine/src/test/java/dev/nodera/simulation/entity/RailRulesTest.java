package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
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
 * Task 15 (L-9): deterministic minecarts. A cart follows a rail graph by inferring its heading
 * from connectivity — a closed loop circulates forever, a dead-end stops it dead, a powered rail
 * holds it at top speed — and every replica traces the identical lap from the root alone.
 */
final class RailRulesTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private static final int Y = 64;

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 31337L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    private static PersistedEntityState cart(RegionId r, int seq, double x, double z,
                                             long vx, long vz) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(r, SnapshotVersion.INITIAL, seq),
                EntityKind.MINECART, RailRules.MINECART_TYPE_ID,
                FixedVec3.fromExternal(x, Y + 0.5, z), new FixedVec3(vx, 0L, vz),
                0, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
    }

    private RegionSnapshot airWorldWith(PersistedEntityState... entities) {
        RegionSnapshot air = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        return new RegionSnapshot(region, SnapshotVersion.INITIAL, 0L,
                air.chunks(), List.of(entities));
    }

    private static void place(List<ActionEnvelope> out, long[] seq, int x, int z, int id) {
        out.add(TestFixtures.envelope(region(0), 0L, seq[0]++,
                TestFixtures.place(new NBlockPos(x, Y, z), id)));
    }

    private static RegionId region(int ignored) {
        return TestFixtures.region(0, 0);
    }

    /** A square rail ring: powered north edge, plain south/east/west edges. */
    private List<ActionEnvelope> poweredLoop(int min, int max) {
        List<ActionEnvelope> out = new ArrayList<>();
        long[] seq = {1};
        for (int x = min; x <= max; x++) {
            place(out, seq, x, min, FlatWorldRules.POWERED_RAIL); // north edge (powered)
            place(out, seq, x, max, FlatWorldRules.RAIL);          // south edge
        }
        for (int z = min + 1; z < max; z++) {
            place(out, seq, min, z, FlatWorldRules.RAIL);          // west edge
            place(out, seq, max, z, FlatWorldRules.RAIL);          // east edge
        }
        return out;
    }

    @Test
    void cartCirculatesAPoweredLoopWithIdenticalRootsAcrossReplicas() {
        RegionSnapshot base = airWorldWith(cart(region, 1, 62.5, 60.5,
                RailRules.MAX_SPEED, 0L));
        List<ActionEnvelope> loop = poweredLoop(60, 66);

        RegionExecutionResult first = executeTicks(base, loop, 200);
        RegionExecutionResult second = executeTicks(base, loop, 200);
        assertThat(second.resultingRoot())
                .as("the cart traces one lap root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 200L);
        MutableRegionState view = new MutableRegionState(settled, RegionBounds.of(region));
        PersistedEntityState minecart = view.entities().stream()
                .filter(e -> e.kind() == EntityKind.MINECART).findFirst().orElseThrow();
        NBlockPos on = new NBlockPos(minecart.pos().blockX(), Y, minecart.pos().blockZ());
        assertThat(RailRules.isRail(view.getBlock(on)))
                .as("the cart is still riding the loop after 200 ticks")
                .isTrue();
        assertThat(minecart.vel())
                .as("the loop's powered rail keeps the cart moving")
                .isNotEqualTo(FixedVec3.ZERO);
    }

    @Test
    void cartStopsAtADeadEndAndRests() {
        RegionSnapshot base = airWorldWith(cart(region, 1, 60.5, 64.5,
                RailRules.MAX_SPEED, 0L));
        List<ActionEnvelope> track = new ArrayList<>();
        long[] seq = {1};
        for (int x = 60; x <= 64; x++) {
            place(track, seq, x, 64, FlatWorldRules.RAIL); // east-going stub, terminus at x=64
        }

        RegionExecutionResult first = executeTicks(base, track, 30);
        RegionExecutionResult second = executeTicks(base, track, 30);
        assertThat(second.resultingRoot())
                .as("the dead-end stop settles to one root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 30L);
        MutableRegionState view = new MutableRegionState(settled, RegionBounds.of(region));
        PersistedEntityState minecart = view.entities().stream()
                .filter(e -> e.kind() == EntityKind.MINECART).findFirst().orElseThrow();
        assertThat(minecart.vel())
                .as("the cart stopped at the rail head")
                .isEqualTo(FixedVec3.ZERO);
        assertThat(minecart.pos().blockX())
                .as("it rests at the terminus and never reversed")
                .isEqualTo(64);
    }

    @Test
    void poweredRailHoldsTheCartAtTopSpeed() {
        RegionSnapshot base = airWorldWith(cart(region, 1, 60.5, 64.5,
                RailRules.MAX_SPEED, 0L));
        List<ActionEnvelope> track = new ArrayList<>();
        long[] seq = {1};
        for (int x = 60; x <= 70; x++) {
            place(track, seq, x, 64, FlatWorldRules.POWERED_RAIL);
        }

        RegionExecutionResult first = executeTicks(base, track, 20);
        RegionExecutionResult second = executeTicks(base, track, 20);
        assertThat(second.resultingRoot())
                .as("the boost settles to one root on every replica")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 20L);
        PersistedEntityState minecart = settled.entities().stream()
                .filter(e -> e.kind() == EntityKind.MINECART).findFirst().orElseThrow();
        assertThat(minecart.vel().x())
                .as("a powered rail pins the cart to MAX_SPEED every tick")
                .isEqualTo(RailRules.MAX_SPEED);
        assertThat(minecart.pos().blockX())
                .as("the cart advanced down the powered track")
                .isGreaterThan(60);
    }
}
