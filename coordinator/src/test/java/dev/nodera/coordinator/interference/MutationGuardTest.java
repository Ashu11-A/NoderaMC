package dev.nodera.coordinator.interference;

import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.DimensionKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MutationGuard} classification (Task 11 acceptance #1's verdict half): the vanilla lane is
 * untouched, the applier is the one legal writer, STRICT cancels, CONVERT records.
 */
class MutationGuardTest {

    private final RegionId delegated = new RegionId(DimensionKey.overworld(), 0, 0);
    private final RegionId vanilla = new RegionId(DimensionKey.overworld(), 9, 9);
    private final NBlockPos pos = new NBlockPos(5, 70, 5);

    private final InterferenceBuffer buffer = new InterferenceBuffer();
    private final InterferenceStats stats = new InterferenceStats(100);

    private MutationGuard guard(MutationGuard.Mode mode) {
        return new MutationGuard(delegated::equals, mode, buffer, stats);
    }

    @Test
    void nonDelegatedRegionAlwaysPasses() {
        MutationGuard guard = guard(MutationGuard.Mode.STRICT);
        assertThat(guard.verdict(vanilla, pos, 0, 5)).isEqualTo(MutationGuard.Verdict.PASS);
        assertThat(buffer.isEmpty(vanilla)).isTrue();
        assertThat(stats.ratePerWindow(vanilla)).isZero();
        assertThat(guard.blockedWrites()).isZero();
    }

    @Test
    void applierScopePassesAndCountsSanityTotal() {
        MutationGuard guard = guard(MutationGuard.Mode.STRICT);
        guard.applierScope(() -> {
            assertThat(guard.verdict(delegated, pos, 0, 5)).isEqualTo(MutationGuard.Verdict.PASS);
            assertThat(guard.verdict(delegated, new NBlockPos(6, 70, 6), 0, 5))
                    .isEqualTo(MutationGuard.Verdict.PASS);
        });
        // Outside the scope again: same write is foreign.
        assertThat(guard.verdict(delegated, pos, 0, 5)).isEqualTo(MutationGuard.Verdict.BLOCK);
        assertThat(guard.applierWrites()).isEqualTo(2); // must equal the applied-mutation total
        assertThat(guard.blockedWrites()).isEqualTo(1);
    }

    @Test
    void strictBlocksForeignWriteAndRecordsNothing() {
        MutationGuard guard = guard(MutationGuard.Mode.STRICT);
        assertThat(guard.verdict(delegated, pos, 0, 5)).isEqualTo(MutationGuard.Verdict.BLOCK);
        assertThat(buffer.isEmpty(delegated)).isTrue();
        assertThat(stats.ratePerWindow(delegated)).isEqualTo(1); // observed, still counted
    }

    @Test
    void convertRecordsWithInnermostSourceMarker() {
        MutationGuard guard = guard(MutationGuard.Mode.CONVERT);
        guard.withSource(MutationSource.ENTITY, () ->
                guard.withSource(MutationSource.NEIGHBOR, () ->
                        assertThat(guard.verdict(delegated, pos, 0, 5))
                                .isEqualTo(MutationGuard.Verdict.CONVERT)));
        List<RecordedMutation> drained = buffer.drain(delegated);
        assertThat(drained).containsExactly(
                new RecordedMutation(pos, 0, 5, MutationSource.NEIGHBOR));
        assertThat(stats.totalFor(delegated, MutationSource.NEIGHBOR)).isEqualTo(1);
        assertThat(guard.convertedWrites()).isEqualTo(1);
    }

    @Test
    void sourceDefaultsToUnknownOutsideAnyMarker() {
        MutationGuard guard = guard(MutationGuard.Mode.CONVERT);
        guard.verdict(delegated, pos, 0, 5);
        assertThat(buffer.drain(delegated))
                .containsExactly(new RecordedMutation(pos, 0, 5, MutationSource.UNKNOWN));
    }
}
