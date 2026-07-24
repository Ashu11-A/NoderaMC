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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 14 (L-6): the daylight sensor — a redstone source whose output is the member-agreed world
 * time crossed with committed sky light. Noon under open sky powers a wire; night or a roofed
 * sensor stays dark; replicas agree.
 */
final class DaylightSensorTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult executeAt(RegionSnapshot base, long worldTime,
                                            List<ActionEnvelope> actions) {
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, 31337L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), worldTime);
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, actions);
        return engine.execute(new RegionExecutionRequest(ctx, base, batch));
    }

    private static List<ActionEnvelope> sensorAndWire() {
        return List.of(
                TestFixtures.envelope(TestFixtures.region(0, 0), 0L, 1L,
                        TestFixtures.place(new NBlockPos(64, 64, 64), FlatWorldRules.DAYLIGHT_SENSOR)),
                TestFixtures.envelope(TestFixtures.region(0, 0), 0L, 2L,
                        TestFixtures.place(new NBlockPos(65, 64, 64), FlatWorldRules.WIRE_0)));
    }

    private static int wirePowerAfter(RegionSnapshot settled) {
        return RedstoneRules.wirePower(
                new dev.nodera.simulation.MutableRegionState(settled, dev.nodera.core.region.RegionBounds.of(
                        TestFixtures.region(0, 0))).getBlock(new NBlockPos(65, 64, 64)));
    }

    @Test
    void noonUnderOpenSkyPowersTheWire() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionExecutionResult first = executeAt(base, 6_000L, sensorAndWire());
        RegionExecutionResult second = executeAt(base, 6_000L, sensorAndWire());
        assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());
        assertThat(wirePowerAfter(dev.nodera.shadow.SnapshotDeltaApplier.apply(base, first.delta(), 1L)))
                .as("noon + open sky ⇒ the sensor emits 15 into the adjacent wire")
                .isEqualTo(15);
    }

    @Test
    void nightLeavesTheWireDark() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        RegionExecutionResult result = executeAt(base, 18_000L, sensorAndWire());
        assertThat(wirePowerAfter(dev.nodera.shadow.SnapshotDeltaApplier.apply(base, result.delta(), 1L)))
                .as("midnight ⇒ the sensor is dark")
                .isZero();
    }

    @Test
    void roofedSensorStaysDarkEvenAtNoon() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, FlatWorldRules.AIR);
        // Roof first, then sensor + wire: the wire's placement triggers the network recompute, at
        // which point the sensor reads the already-blocked sky (sky/time reactivity as a discrete
        // event is the documented follow-on; today the sensor reads sky at recompute time).
        List<ActionEnvelope> roofed = List.of(
                TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(64, 65, 64), FlatWorldRules.STONE)),
                TestFixtures.envelope(region, 0L, 2L,
                        TestFixtures.place(new NBlockPos(64, 64, 64), FlatWorldRules.DAYLIGHT_SENSOR)),
                TestFixtures.envelope(region, 0L, 3L,
                        TestFixtures.place(new NBlockPos(65, 64, 64), FlatWorldRules.WIRE_0)));
        RegionExecutionResult result = executeAt(base, 6_000L, roofed);
        assertThat(wirePowerAfter(dev.nodera.shadow.SnapshotDeltaApplier.apply(base, result.delta(), 1L)))
                .as("an opaque block above the sensor blocks the sky even at noon")
                .isZero();
    }
}
