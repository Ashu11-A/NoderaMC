package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.Bytes;
import dev.nodera.core.action.InteractBlockAction;
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
import dev.nodera.simulation.rules.ActionRejection.Reason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 13 increment 4 (L-26): the static redstone signal graph through the FULL engine path —
 * validation, per-block application, deterministic network settling, delta emission, root
 * hashing. Wire power is block state, so every assertion below is also a root assertion.
 */
final class RedstoneGraphTest {

    private final HashService hashes = new HashService();
    private final RegionId region = TestFixtures.region(0, 0);
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashes);

    private RegionExecutionResult execute(RegionSnapshot base, List<ActionEnvelope> actions) {
        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, actions);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, 1, 12345L,
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

    private List<ActionEnvelope> wireLineWithLever(int wires) {
        List<ActionEnvelope> actions = new ArrayList<>();
        long seq = 1;
        actions.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.LEVER_OFF)));
        for (int x = 1; x <= wires; x++) {
            actions.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(x, 64, 0), FlatWorldRules.WIRE_0)));
        }
        return actions;
    }

    @Test
    void leverToggleSettlesTheWireLineWithDecayAndIdenticalRootsAcrossReplicas() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> actions = wireLineWithLever(5);
        actions.add(TestFixtures.envelope(region, 0L, 99L,
                new InteractBlockAction(new NBlockPos(0, 64, 0))));

        RegionExecutionResult first = execute(base, actions);
        RegionExecutionResult second = execute(base, actions);

        assertThat(first.stats().actionsRejected()).isZero();
        assertThat(second.resultingRoot())
                .as("two replicas settle the network to the identical root")
                .isEqualTo(first.resultingRoot());

        RegionSnapshot advanced = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, first.delta(), 1L);
        assertThat(blockAt(advanced, new NBlockPos(0, 64, 0)))
                .isEqualTo(FlatWorldRules.LEVER_ON);
        // Wire adjacent to the source carries 15; each further hop decays by 1.
        for (int x = 1; x <= 5; x++) {
            assertThat(blockAt(advanced, new NBlockPos(x, 64, 0)))
                    .as("wire at x=%d", x)
                    .isEqualTo(RedstoneRules.wireWithPower(15 - (x - 1)));
        }
    }

    @Test
    void breakingTheSourceDepowersTheNetwork() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> build = new ArrayList<>();
        long seq = 1;
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
        for (int x = 1; x <= 3; x++) {
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(x, 64, 0), FlatWorldRules.WIRE_0)));
        }
        RegionSnapshot powered = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, execute(base, build).delta(), 1L);
        assertThat(blockAt(powered, new NBlockPos(1, 64, 0)))
                .isEqualTo(RedstoneRules.wireWithPower(15));

        RegionSnapshot rebased = new RegionSnapshot(region, SnapshotVersion.INITIAL,
                0, powered.chunks(), powered.entities());
        RegionExecutionResult broken = execute(rebased, List.of(
                TestFixtures.envelope(region, 0L, 50L,
                        new dev.nodera.core.action.BreakBlockAction(new NBlockPos(0, 64, 0)))));
        RegionSnapshot depowered = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                rebased, broken.delta(), 1L);
        for (int x = 1; x <= 3; x++) {
            assertThat(blockAt(depowered, new NBlockPos(x, 64, 0)))
                    .as("wire at x=%d depowered", x)
                    .isEqualTo(FlatWorldRules.WIRE_0);
        }
    }

    @Test
    void strongestOfTwoSourcesWinsPerWire() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> build = new ArrayList<>();
        long seq = 1;
        // Sources at both ends of a 5-wire line: every wire settles at max(from-left, from-right).
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));
        for (int x = 1; x <= 5; x++) {
            build.add(TestFixtures.envelope(region, 0L, seq++,
                    TestFixtures.place(new NBlockPos(x, 64, 0), FlatWorldRules.WIRE_0)));
        }
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(6, 64, 0), FlatWorldRules.REDSTONE_BLOCK)));

        RegionSnapshot settled = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, execute(base, build).delta(), 1L);
        // Symmetric line: both ends adjacent to a source read 15, the middle reads 13.
        assertThat(blockAt(settled, new NBlockPos(1, 64, 0)))
                .isEqualTo(RedstoneRules.wireWithPower(15));
        assertThat(blockAt(settled, new NBlockPos(3, 64, 0)))
                .isEqualTo(RedstoneRules.wireWithPower(13));
        assertThat(blockAt(settled, new NBlockPos(5, 64, 0)))
                .isEqualTo(RedstoneRules.wireWithPower(15));
    }

    @Test
    void networkComputedStatesAreNeverPlaceable() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult poweredWire = execute(base, List.of(
                TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(1, 64, 0),
                                RedstoneRules.wireWithPower(5)))));
        RegionExecutionResult leverOn = execute(base, List.of(
                TestFixtures.envelope(region, 0L, 2L,
                        TestFixtures.place(new NBlockPos(1, 64, 0), FlatWorldRules.LEVER_ON))));
        assertThat(poweredWire.stats().rejections())
                .extracting(ActionRejection::reason).containsExactly(Reason.ILLEGAL_BLOCK);
        assertThat(leverOn.stats().rejections())
                .extracting(ActionRejection::reason).containsExactly(Reason.ILLEGAL_BLOCK);
    }

    @Test
    void interactingWithANonInteractableBlockIsRejectedDeterministically() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        RegionExecutionResult result = execute(base, List.of(
                TestFixtures.envelope(region, 0L, 1L,
                        new InteractBlockAction(new NBlockPos(1, 64, 0)))));
        assertThat(result.stats().rejections())
                .extracting(ActionRejection::reason).containsExactly(Reason.ILLEGAL_BLOCK);
    }

    @Test
    void torchIsExtinguishedByAPoweredSupportThroughTheTickQueue() {
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> build = new ArrayList<>();
        long seq = 1;
        // Redstone block at y=64, torch standing on it at y=65: input powered -> the torch
        // must go out — one region tick later, THROUGH the hashed scheduled-tick queue.
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(2, 64, 2), FlatWorldRules.REDSTONE_BLOCK)));
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(2, 65, 2), FlatWorldRules.TORCH_ON)));

        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, 2, build);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, 2, 12345L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        RegionExecutionResult result = engine.execute(
                new RegionExecutionRequest(ctx, base, batch));
        RegionSnapshot advanced = dev.nodera.shadow.SnapshotDeltaApplier.apply(
                base, result.delta(), 2L);

        assertThat(blockAt(advanced, new NBlockPos(2, 65, 2)))
                .as("powered support extinguishes the torch after its scheduled delay")
                .isEqualTo(FlatWorldRules.TORCH_OFF);
        // Replica determinism for the timed path.
        assertThat(engine.execute(new RegionExecutionRequest(ctx, base, batch)).resultingRoot())
                .isEqualTo(result.resultingRoot());
    }

    @Test
    void torchClockOscillatesDeterministicallyAcrossReplicas() {
        // THE acceptance-1 device: a torch whose own output wire powers its support — the
        // classic torch clock. Every batch of ticks must yield identical roots on every
        // replica, and the schedule (the clock's phase) lives in the hashed queue.
        RegionSnapshot base = TestFixtures.fullUniformSnapshot(region, 0);
        List<ActionEnvelope> build = new ArrayList<>();
        long seq = 1;
        // Support wire at (2,64,2); torch on it at (2,65,2); wire from the torch's level
        // feeding back down to the support: torch ON powers wire, wire powers support,
        // support extinguishes torch, wire depowers, torch relights — oscillation.
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(2, 64, 2), FlatWorldRules.WIRE_0)));
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(2, 65, 3), FlatWorldRules.WIRE_0)));
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(2, 64, 3), FlatWorldRules.WIRE_0)));
        build.add(TestFixtures.envelope(region, 0L, seq++,
                TestFixtures.place(new NBlockPos(2, 65, 2), FlatWorldRules.TORCH_ON)));

        ActionBatch batch = new ActionBatch(
                region, RegionEpoch.INITIAL, base.version(), 0, 10, build);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, base.version(), 0, 10, 12345L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());

        RegionExecutionResult first = engine.execute(new RegionExecutionRequest(ctx, base, batch));
        RegionExecutionResult second = engine.execute(new RegionExecutionRequest(ctx, base, batch));
        assertThat(second.resultingRoot())
                .as("10 ticks of a torch clock settle to the identical root on every replica")
                .isEqualTo(first.resultingRoot());
        Bytes d1 = dev.nodera.core.crypto.CanonicalEncoder.encode(first.delta());
        Bytes d2 = dev.nodera.core.crypto.CanonicalEncoder.encode(second.delta());
        assertThat(d2).isEqualTo(d1);
    }
}
