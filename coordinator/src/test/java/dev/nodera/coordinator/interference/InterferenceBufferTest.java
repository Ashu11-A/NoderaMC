package dev.nodera.coordinator.interference;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InterferenceBuffer} coalescing: one CAS guard per position per drained delta, and writes
 * that land back on the committed state vanish (nothing to certify).
 */
class InterferenceBufferTest {

    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
    private final NBlockPos pos = new NBlockPos(5, 70, 5);
    private final InterferenceBuffer buffer = new InterferenceBuffer();

    @Test
    void samePositionCoalescesToFirstPrevAndLastNew() {
        buffer.record(region, new RecordedMutation(pos, 0, 3, MutationSource.UNKNOWN));
        buffer.record(region, new RecordedMutation(pos, 3, 7, MutationSource.ENTITY));
        assertThat(buffer.drain(region)).containsExactly(
                new RecordedMutation(pos, 0, 7, MutationSource.ENTITY));
    }

    @Test
    void writeThatRestoresOriginalStateDropsOut() {
        buffer.record(region, new RecordedMutation(pos, 0, 3, MutationSource.UNKNOWN));
        buffer.record(region, new RecordedMutation(pos, 3, 0, MutationSource.UNKNOWN));
        assertThat(buffer.drain(region)).isEmpty();
    }

    @Test
    void drainRemovesTheRegion() {
        buffer.record(region, new RecordedMutation(pos, 0, 3, MutationSource.UNKNOWN));
        assertThat(buffer.pendingRegions()).containsExactly(region);
        buffer.drain(region);
        assertThat(buffer.pendingRegions()).isEmpty();
        assertThat(buffer.isEmpty(region)).isTrue();
    }
}
