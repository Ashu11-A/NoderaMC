package dev.nodera.coordinator.interference;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.Bytes;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InterferenceBuffer} coalescing: one CAS guard per position per drained delta, and writes
 * that land back on the committed state vanish (nothing to certify).
 */
class InterferenceBufferTest {

    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);
    private final NBlockPos pos = new NBlockPos(5, 70, 5);
    private final NBlockPos sectionPos = new NBlockPos(0, 64, 0);
    private final InterferenceBuffer buffer = new InterferenceBuffer();

    @Test
    void samePositionCoalescesToFirstPrevAndLastNew() {
        buffer.record(region, new RecordedMutation(pos, 0, 3, MutationSource.UNKNOWN));
        buffer.record(region, new RecordedMutation(pos, 3, 7, MutationSource.ENTITY));
        assertThat(buffer.drain(region)).containsExactly(
                new RecordedMutation(sectionPos, 0, 7, MutationSource.ENTITY));
    }

    @Test
    void differentPositionsInSameSectionCoalesce() {
        buffer.record(region, new RecordedMutation(pos, 0, 3, MutationSource.UNKNOWN));
        buffer.record(region, new RecordedMutation(
                new NBlockPos(6, 71, 6), 3, 8, MutationSource.ENTITY));
        assertThat(buffer.drain(region)).containsExactly(
                new RecordedMutation(sectionPos, 0, 8, MutationSource.ENTITY));
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

    @Test
    void ghostUpdatesCoalesceToFirstExpectedAndLatestNew() {
        PersistedEntityState first = ghost(1, 0);
        PersistedEntityState middle = ghost(1, 1);
        PersistedEntityState latest = ghost(1, 2);
        buffer.recordEntity(region, first, middle);
        buffer.recordEntity(region, middle, latest);
        assertThat(buffer.drainEntities(region)).singleElement().satisfies(mutation -> {
            assertThat(mutation.expectedPrevious()).isEqualTo(first);
            assertThat(mutation.newState()).isEqualTo(latest);
        });
    }

    @Test
    void ghostCreateThenRemoveDropsOut() {
        PersistedEntityState ghost = ghost(1, 0);
        buffer.recordEntity(region, null, ghost);
        buffer.recordEntity(region, ghost, null);
        assertThat(buffer.drainEntities(region)).isEmpty();
    }

    @Test
    void entityOnlyRegionIsPendingAndDrainMakesItEmpty() {
        buffer.recordEntity(region, null, ghost(1, 0));
        assertThat(buffer.pendingRegions()).containsExactly(region);
        assertThat(buffer.isEmpty(region)).isFalse();
        buffer.drainEntities(region);
        assertThat(buffer.isEmpty(region)).isTrue();
    }

    @Test
    void mismatchedGhostIdsAreRejected() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                buffer.recordEntity(region, ghost(1, 0), ghost(2, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PersistedEntityState ghost(long id, int age) {
        return new PersistedEntityState(
                new NetworkEntityId(id), EntityKind.GHOST, 54,
                FixedVec3.ofBlock(1, 5, 1), FixedVec3.ZERO,
                age, PersistedEntityState.NEVER_DESPAWN, Bytes.empty());
    }
}
