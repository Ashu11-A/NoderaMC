package dev.nodera.simulation.border;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.InteractBlockAction;
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
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.RedstoneRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13 increment 8 (L-26): the border contract — the engine NEVER mutates halo state. A
 * wire walk or piston motion that would cross the owned boundary is collected as a
 * {@link BorderSignal} (deterministic, canonical order, replica-identical) and the refused
 * effect fails closed; the contraption-migration lane consumes the signals.
 *
 * <p>Region (0,0) owns blocks {@code x,z in [0,128)}; {@code x=128} is the halo.
 */
final class BorderSignalTest {

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

    @Test
    void wireWalkReachingTheBorderEmitsAWireSignalAndNeverWritesTheHalo() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(126, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(127, 64, 0), FlatWorldRules.WIRE_0)));

        RegionExecutionResult result = executeTicks(base, actions, 1);
        assertThat(result.stats().actionsRejected()).isZero();
        assertThat(result.borderSignals())
                .as("the component walk that touched x=128 is a SIGNAL, not a halo write")
                .anySatisfy(signal -> {
                    assertThat(signal.kind()).isEqualTo(BorderSignal.Kind.WIRE);
                    assertThat(signal.target()).isEqualTo(new NBlockPos(128, 64, 0));
                });
        // The refused continuation is invisible to the root: every mutation stays owned.
        assertThat(result.delta().blockMutations())
                .allSatisfy(m -> {
                    assertThat(m.pos().x()).isLessThan(128);
                    assertThat(m.pos().z()).isLessThan(128);
                });
    }

    @Test
    void pistonPushingIntoTheBorderFailsClosedAndEmitsAPistonSignal() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(126, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(127, 64, 0),
                        FlatWorldRules.PISTON_RETRACTED_BASE + 3)));

        RegionExecutionResult result = executeTicks(base, actions, 2);
        assertThat(result.borderSignals())
                .anySatisfy(signal -> {
                    assertThat(signal.kind()).isEqualTo(BorderSignal.Kind.PISTON);
                    assertThat(signal.origin()).isEqualTo(new NBlockPos(127, 64, 0));
                    assertThat(signal.target()).isEqualTo(new NBlockPos(128, 64, 0));
                });
        RegionSnapshot advanced = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, result.delta(), 2L);
        int baseId = blockAt(advanced, new NBlockPos(127, 64, 0));
        assertThat(RedstoneRules.pistonIsExtended(baseId))
                .as("the refused motion fails CLOSED — the piston stays retracted")
                .isFalse();
    }

    @Test
    void borderSignalsAreReplicaIdentical() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(126, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(127, 64, 0), FlatWorldRules.WIRE_0)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(127, 64, 1),
                        FlatWorldRules.PISTON_RETRACTED_BASE + 3)));

        RegionExecutionResult first = executeTicks(base, actions, 2);
        RegionExecutionResult second = executeTicks(base, actions, 2);
        assertThat(second.borderSignals())
                .as("border signals are a deterministic consequence of the batch")
                .isEqualTo(first.borderSignals());
        assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());
    }

    @Test
    void interiorRedstoneActivityEmitsNoBorderSignals() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(60, 64, 60), FlatWorldRules.LEVER_OFF)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(61, 64, 60), FlatWorldRules.WIRE_0)));
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                new InteractBlockAction(new NBlockPos(60, 64, 60))));

        assertThat(executeTicks(base, actions, 2).borderSignals()).isEmpty();
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
}
