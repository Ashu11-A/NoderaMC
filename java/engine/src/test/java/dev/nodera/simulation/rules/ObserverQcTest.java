package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14 (L-5): observers + quasi-connectivity. The observer watches its facing cell and
 * pulses 15 out its BACK for one tick (rise +1, fall +2 — both edges live in the hashed
 * queue); the piston additionally reads power through the cell above it (QC), and BUD
 * staleness emerges from update-driven re-evaluation rather than being simulated.
 */
final class ObserverQcTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeTicks(
            RegionSnapshot base, List<ActionEnvelope> actions, int tickCount) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, tickCount, 12345L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    private static int blockAt(RegionSnapshot snapshot, NBlockPos pos) {
        for (var col : snapshot.chunks()) {
            if (col.chunkX() == Math.floorDiv(pos.x(), 16)
                    && col.chunkZ() == Math.floorDiv(pos.z(), 16)) {
                int section = Math.floorDiv(pos.y() - col.minY(), 16);
                return col.blockAt(section,
                        Math.floorMod(pos.x(), 16),
                        Math.floorMod(pos.y() - col.minY(), 16),
                        Math.floorMod(pos.z(), 16));
            }
        }
        return -1;
    }

    /** Observer at (5,64,0) watching EAST (6,64,0), wire on its back at (4,64,0). */
    private List<ActionEnvelope> observerRig() {
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(5, 64, 0), FlatWorldRules.OBSERVER_EAST_OFF)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(4, 64, 0), FlatWorldRules.WIRE_0)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(4, 64, 1), FlatWorldRules.WIRE_0)));
        // The observed change: a block appears in the watched cell.
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(6, 64, 0), FlatWorldRules.STONE)));
        return actions;
    }

    @Test
    void observedChangeRaisesTheBackPulseOneTickLater() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot mid = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, observerRig(), 1).delta(), 1L);
        assertThat(blockAt(mid, new NBlockPos(5, 64, 0)))
                .as("the pulse rises one tick after the observed change")
                .isEqualTo(FlatWorldRules.OBSERVER_EAST_ON);
        assertThat(blockAt(mid, new NBlockPos(4, 64, 0)))
                .as("the back wire reads the pulse")
                .isEqualTo(RedstoneRules.wireWithPower(15));
    }

    @Test
    void thePulseFallsOnItsOwnOneTickAfterRising() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot after = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, observerRig(), 3).delta(), 3L);
        assertThat(blockAt(after, new NBlockPos(5, 64, 0)))
                .as("the pulse is transient state — it falls without further input")
                .isEqualTo(FlatWorldRules.OBSERVER_EAST_OFF);
        assertThat(blockAt(after, new NBlockPos(4, 64, 0)))
                .isEqualTo(FlatWorldRules.WIRE_0);
        // Replica determinism for the two-edge pulse.
        assertThat(executeTicks(base, observerRig(), 3).resultingRoot())
                .isEqualTo(executeTicks(base, observerRig(), 3).resultingRoot());
    }

    @Test
    void observerNeverPowersItsSides() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot mid = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, observerRig(), 1).delta(), 1L);
        assertThat(blockAt(mid, new NBlockPos(5, 64, 1)))
                .as("side cell reads nothing — the pulse leaves through the back only")
                .isEqualTo(FlatWorldRules.AIR);
        // The side wire at (4,64,1) is powered only THROUGH the back wire's hop (14), never
        // directly by the observer (which would read 15).
        assertThat(blockAt(mid, new NBlockPos(4, 64, 1)))
                .isEqualTo(RedstoneRules.wireWithPower(14));
    }

    @Test
    void quasiConnectivityPowersAPistonThroughTheCellAboveIt() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        // Power reaches ONLY the cell above the piston: a redstone block two cells up powers
        // (10,65,10) as-if-a-block-were-there; the piston itself has no direct neighbor power.
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(10, 66, 10), FlatWorldRules.REDSTONE_BLOCK)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(10, 64, 10),
                        FlatWorldRules.PISTON_RETRACTED_BASE + 3)));

        RegionSnapshot advanced = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, executeTicks(base, actions, 2).delta(), 2L);
        assertThat(blockAt(advanced, new NBlockPos(10, 64, 10)))
                .as("QC: the piston extends although only the cell above it is powered")
                .isEqualTo(FlatWorldRules.PISTON_EXTENDED_BASE + 3);
    }

    @Test
    void observerOnStatesAreNeverPlaceable() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult result = executeTicks(base, List.of(
                TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(3, 64, 3),
                                FlatWorldRules.OBSERVER_WEST_ON))), 1);
        assertThat(result.stats().rejections())
                .extracting(ActionRejection::reason)
                .containsExactly(ActionRejection.Reason.ILLEGAL_BLOCK);
    }
}
