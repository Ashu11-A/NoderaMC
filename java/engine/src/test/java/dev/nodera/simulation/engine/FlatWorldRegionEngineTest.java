package dev.nodera.simulation.engine;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.CanonicalEncoder;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.TestFixtures;
import dev.nodera.simulation.rules.ActionRejection;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FlatWorldRegionEngine} determinism and contract tests — the core of the Phase-0 bet.
 *
 * <p>Thread-context: single test thread.
 */
final class FlatWorldRegionEngineTest {

    private final HashService hashService = new HashService();
    private final FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
            FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint(), hashService);

    @Test
    void executeTwiceYieldsIdenticalRootAndDeltaBytes() {
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(region, 0, 0, 0);
        ActionBatch batch = batch(region, List.of(
                TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.STONE)),
                TestFixtures.envelope(region, 0L, 2L,
                        TestFixtures.place(new NBlockPos(5, 80, 7), FlatWorldRules.GLASS))));

        RegionExecutionResult first = engine.execute(request(region, snapshot, batch));
        RegionExecutionResult second = engine.execute(request(region, snapshot, batch));

        assertThat(second.resultingRoot()).isEqualTo(first.resultingRoot());
        Bytes d1 = CanonicalEncoder.encode(first.delta());
        Bytes d2 = CanonicalEncoder.encode(second.delta());
        assertThat(d2).isEqualTo(d1);
        assertThat(first.stats().actionsApplied()).isEqualTo(2);
        assertThat(first.stats().actionsRejected()).isZero();
    }

    @Test
    void rejectionDeterminismAndInvalidActionDoesNotMutateState() {
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(region, 0, 0, 0);

        ActionEnvelope invalid = TestFixtures.envelope(region, 0L, 1L,
                TestFixtures.place(new NBlockPos(0, 64, 0), 999));
        ActionEnvelope valid = TestFixtures.envelope(region, 0L, 2L,
                TestFixtures.place(new NBlockPos(3, 64, 3), FlatWorldRules.STONE));

        ActionBatch withInvalid = batch(region, List.of(invalid, valid));
        ActionBatch validOnly = batch(region, List.of(valid));

        RegionExecutionResult a1 = engine.execute(request(region, snapshot, withInvalid));
        RegionExecutionResult a2 = engine.execute(request(region, snapshot, withInvalid));
        RegionExecutionResult b = engine.execute(request(region, snapshot, validOnly));

        assertThat(rejectionReasons(a1)).isEqualTo(rejectionReasons(a2));
        assertThat(rejectionReasons(a1)).containsExactly(ActionRejection.Reason.ILLEGAL_BLOCK);
        assertThat(a1.stats().actionsApplied()).isEqualTo(1);
        assertThat(a1.stats().actionsRejected()).isEqualTo(1);

        assertThat(a1.resultingRoot()).isEqualTo(b.resultingRoot());
    }

    @Test
    void crossRegionActionThrows() {
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(region, 0, 0, 0);
        ActionEnvelope cross = TestFixtures.envelope(region, 0L, 1L,
                TestFixtures.place(new NBlockPos(128, 64, 0), FlatWorldRules.STONE));
        ActionBatch batch = batch(region, List.of(cross));

        assertThatThrownBy(() -> engine.execute(request(region, snapshot, batch)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fingerprintMismatchThrows() {
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(region, 0, 0, 0);
        long wrongFp = FlatWorldRules.registryFingerprint() ^ 0xDEADBEEFL;
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0L, 2L, 12345L,
                FlatWorldRules.RULES_VERSION, wrongFp);
        ActionBatch batch = batch(region, List.of());
        RegionExecutionRequest req = new RegionExecutionRequest(ctx, snapshot, batch);

        assertThatThrownBy(() -> engine.execute(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registryFingerprint");
    }

    @Test
    void rulesVersionMismatchThrows() {
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(region, 0, 0, 0);
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0L, 2L, 12345L,
                FlatWorldRules.RULES_VERSION + 1, FlatWorldRules.registryFingerprint());
        ActionBatch batch = batch(region, List.of());
        RegionExecutionRequest req = new RegionExecutionRequest(ctx, snapshot, batch);

        assertThatThrownBy(() -> engine.execute(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rulesVersion");
    }

    @Test
    void actionOutsideBatchTickRangeIsRejected() {
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(region, 0, 0, 0);
        ActionEnvelope late = TestFixtures.envelope(
                region, 3L, 1L,
                TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.STONE));
        ActionBatch batch = batch(region, List.of(late));

        assertThatThrownBy(() -> engine.execute(request(region, snapshot, batch)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("target tick outside batch range");
    }

    @Test
    void contextAndBatchAnchorsMustMatch() {
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(region, 0, 0, 0);
        ActionBatch batch = batch(region, List.of());
        RegionExecutionContext wrong = new RegionExecutionContext(
                TestFixtures.region(1, 0), RegionEpoch.INITIAL, SnapshotVersion.INITIAL,
                0L, 2L, 12345L, FlatWorldRules.RULES_VERSION,
                FlatWorldRules.registryFingerprint());

        assertThatThrownBy(() -> engine.execute(new RegionExecutionRequest(wrong, snapshot, batch)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("regions must match");
    }

    @Test
    void rootReflectsPostStateSnapshot() {
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot s0 = TestFixtures.singleColumnSnapshot(region, 0, 0, 0);
        NBlockPos pos = new NBlockPos(0, 64, 0);

        int[] palette = new int[TestFixtures.DEFAULT_SECTION_COUNT];
        int section = Math.floorDiv(pos.y() - TestFixtures.DEFAULT_MIN_Y, 16);
        palette[section] = FlatWorldRules.STONE;
        RegionSnapshot s1 = new RegionSnapshot(region, SnapshotVersion.INITIAL.next(), 2L,
                List.of(TestFixtures.column(0, 0, palette)));

        StateRoot expectedRoot = StateRoot.of(hashService.hash(s1));

        ActionBatch batch = batch(region, List.of(
                TestFixtures.envelope(region, 0L, 1L, TestFixtures.place(pos, FlatWorldRules.STONE))));
        RegionExecutionResult result = engine.execute(request(region, s0, batch));

        assertThat(result.resultingRoot()).isEqualTo(expectedRoot);
        assertThat(result.resultingRoot()).isNotEqualTo(StateRoot.of(hashService.hash(s0)));
    }

    @Test
    void engineNanosIsZeroAndRejectionsListIsInTheResultStats() {
        RegionId region = TestFixtures.region(0, 0);
        RegionSnapshot snapshot = TestFixtures.singleColumnSnapshot(region, 0, 0, 0);
        ActionBatch batch = batch(region, List.of(
                TestFixtures.envelope(region, 0L, 1L,
                        TestFixtures.place(new NBlockPos(0, 64, 0), FlatWorldRules.STONE))));

        RegionExecutionResult result = engine.execute(request(region, snapshot, batch));

        assertThat(result.stats().engineNanos()).isZero();
        assertThat(result.stats().rejections()).isEmpty();
    }

    private static List<ActionRejection.Reason> rejectionReasons(RegionExecutionResult r) {
        return r.stats().rejections().stream().map(ActionRejection::reason).toList();
    }

    private static ActionBatch batch(RegionId region, List<ActionEnvelope> actions) {
        return new ActionBatch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0L, 2L, actions);
    }

    private static RegionExecutionRequest request(RegionId region, RegionSnapshot snapshot, ActionBatch batch) {
        RegionExecutionContext ctx = new RegionExecutionContext(
                region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0L, 2L, 12345L,
                FlatWorldRules.RULES_VERSION, FlatWorldRules.registryFingerprint());
        return new RegionExecutionRequest(ctx, snapshot, batch);
    }
}
