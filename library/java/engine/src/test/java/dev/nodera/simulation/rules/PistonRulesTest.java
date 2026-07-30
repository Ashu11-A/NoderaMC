package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
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
 * Task 13 increment 7 (L-26): pistons — the {@code BlockEventEntry} two-phase consumer. Motion
 * is scheduled as a hashed pending event and fired next tick with a fire-time power re-check;
 * every failure mode (push limit, immovable block, region border) fails CLOSED and every
 * assertion runs through the full engine path, so it is also a root assertion.
 */
final class PistonRulesTest {

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

    private static RegionSnapshot advance(
            RegionSnapshot base, RegionExecutionResult result, long tick) {
        return dev.nodera.shadow.SnapshotDeltaApplier.apply(base, result.delta(), tick);
    }

    /** Powered east-facing piston at (1,64,0) with {@code stones} in its push line. */
    private List<ActionEnvelope> poweredPistonWithLine(int stones) {
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
        for (int x = 0; x < stones; x++) {
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(2 + x, 64, 0), FlatWorldRules.STONE)));
        }
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(1, 64, 0),
                        FlatWorldRules.PISTON_RETRACTED_BASE + 3)));
        return actions;
    }

    @Test
    void poweredPistonExtendsAndPushesTheLine() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = poweredPistonWithLine(2);

        RegionExecutionResult result = executeTicks(base, actions, 1);
        RegionSnapshot advanced = advance(base, result, 1L);
        assertThat(result.stats().actionsRejected()).isZero();
        assertThat(blockAt(advanced, new NBlockPos(1, 64, 0)))
                .as("the base commits to its extended state")
                .isEqualTo(FlatWorldRules.PISTON_EXTENDED_BASE + 3);
        assertThat(blockAt(advanced, new NBlockPos(2, 64, 0)))
                .as("the head occupies the front cell")
                .isEqualTo(FlatWorldRules.PISTON_HEAD_BASE + 3);
        assertThat(blockAt(advanced, new NBlockPos(3, 64, 0))).isEqualTo(FlatWorldRules.STONE);
        assertThat(blockAt(advanced, new NBlockPos(4, 64, 0)))
                .as("the whole line shifted one cell east")
                .isEqualTo(FlatWorldRules.STONE);

        // Replica determinism: the motion is driven by hashed pending events.
        assertThat(executeTicks(base, actions, 1).resultingRoot())
                .isEqualTo(result.resultingRoot());
    }

    @Test
    void depoweredPistonRetractsItsHeadWithoutPullingBlocks() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot extended = advance(
                base, executeTicks(base, poweredPistonWithLine(1), 1), 1L);
        RegionSnapshot rebased = new RegionSnapshot(region, SnapshotVersion.INITIAL,
                0, extended.chunks(), extended.entities(),
                extended.scheduledTicks(), extended.blockEvents(), extended.bodyVersion());

        RegionExecutionResult broken = executeTicks(rebased, List.of(
                TestFixtures.envelope(region, 0L, 50L,
                        new BreakBlockAction(new NBlockPos(0, 64, 0)))), 1);
        RegionSnapshot retracted = advance(rebased, broken, 1L);
        assertThat(blockAt(retracted, new NBlockPos(1, 64, 0)))
                .as("losing power retracts the base")
                .isEqualTo(FlatWorldRules.PISTON_RETRACTED_BASE + 3);
        assertThat(blockAt(retracted, new NBlockPos(2, 64, 0)))
                .as("the head cell empties")
                .isEqualTo(FlatWorldRules.AIR);
        assertThat(blockAt(retracted, new NBlockPos(3, 64, 0)))
                .as("the non-sticky MVP pulls nothing back")
                .isEqualTo(FlatWorldRules.STONE);
    }

    @Test
    void pushLimitOfTwelveFailsClosed() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionSnapshot atLimit = advance(
                base, executeTicks(base, poweredPistonWithLine(12), 1), 1L);
        assertThat(blockAt(atLimit, new NBlockPos(1, 64, 0)))
                .as("exactly 12 blocks still move")
                .isEqualTo(FlatWorldRules.PISTON_EXTENDED_BASE + 3);

        RegionSnapshot overLimit = advance(
                base, executeTicks(base, poweredPistonWithLine(13), 1), 1L);
        assertThat(blockAt(overLimit, new NBlockPos(1, 64, 0)))
                .as("a 13-block line refuses to move — the piston stays retracted")
                .isEqualTo(FlatWorldRules.PISTON_RETRACTED_BASE + 3);
        assertThat(blockAt(overLimit, new NBlockPos(2, 64, 0)))
                .isEqualTo(FlatWorldRules.STONE);
    }

    @Test
    void redstoneComponentsInTheLineAreImmovable() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(2, 64, 0), FlatWorldRules.STONE)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(3, 64, 0), FlatWorldRules.LEVER_OFF)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(1, 64, 0),
                        FlatWorldRules.PISTON_RETRACTED_BASE + 3)));

        RegionSnapshot advanced = advance(base, executeTicks(base, actions, 1), 1L);
        assertThat(blockAt(advanced, new NBlockPos(1, 64, 0)))
                .as("a redstone component in the line blocks the motion entirely")
                .isEqualTo(FlatWorldRules.PISTON_RETRACTED_BASE + 3);
        assertThat(blockAt(advanced, new NBlockPos(3, 64, 0)))
                .isEqualTo(FlatWorldRules.LEVER_OFF);
    }

    @Test
    void pistonMotionSurvivesADeltaBoundaryMidFlight() {
        // Power arrives through a repeater whose flip fires in the LAST tick of the batch:
        // the piston's motion event is enqueued AFTER that tick's block-event drain, so the
        // pending BlockEventEntry must cross the delta boundary as hashed state and fire in
        // the NEXT batch.
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.LEVER_OFF)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(1, 64, 0), FlatWorldRules.WIRE_0)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(2, 64, 0), FlatWorldRules.REPEATER_EAST_OFF)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(3, 64, 0), FlatWorldRules.WIRE_0)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(4, 64, 0),
                        FlatWorldRules.PISTON_RETRACTED_BASE + 3)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                new dev.nodera.core.action.InteractBlockAction(new NBlockPos(0, 64, 0))));

        RegionExecutionResult placed = executeTicks(base, actions, 1);
        RegionSnapshot mid = advance(base, placed, 1L);
        assertThat(mid.blockEvents())
                .as("the pending motion IS the snapshot state at the boundary")
                .hasSize(1);
        assertThat(blockAt(mid, new NBlockPos(4, 64, 0)))
                .isEqualTo(FlatWorldRules.PISTON_RETRACTED_BASE + 3);

        RegionSnapshot rebased = new RegionSnapshot(region, SnapshotVersion.INITIAL,
                0, mid.chunks(), mid.entities(),
                mid.scheduledTicks(), mid.blockEvents(), mid.bodyVersion());
        RegionSnapshot fired = advance(rebased, executeTicks(rebased, List.of(), 1), 1L);
        assertThat(blockAt(fired, new NBlockPos(4, 64, 0)))
                .as("the carried event fires in the next batch")
                .isEqualTo(FlatWorldRules.PISTON_EXTENDED_BASE + 3);
        assertThat(fired.blockEvents()).isEmpty();
    }
}
