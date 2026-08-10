package dev.nodera.endpoint.paper.world;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.storage.TransferStore;
import dev.nodera.storage.event.InMemoryTransferStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Stage 1 of L-64: a delta whose regions are written by different Folia threads is refused with a
 * named error, and NOTHING is written.
 *
 * <p>The assertions are positive on the message, not merely that something threw. A refusal whose
 * text does not tell the caller what to do instead is the "loud and correct" half without the
 * "beats fast and racy" half.
 */
final class CrossRegionCommitTest {

    private static final DimensionKey OVERWORLD = DimensionKey.overworld();
    private static final int DEFAULT_EXPONENT = 4;

    private final RegionId source = new RegionId(OVERWORLD, 0, 0);
    private final RegionId target = new RegionId(OVERWORLD, 2, 0);
    private final TransferStore transfers = new InMemoryTransferStore();

    @Test
    void aSpanAcrossTwoFoliaThreadsIsRefusedWithZeroDurableStages() {
        CrossRegionCommit commit = CrossRegionCommit.refusing(
                NoderaFoliaRegionMap.regionised(
                        DEFAULT_EXPONENT, NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE),
                transfers);

        CrossRegionRefusedException refusal = catchThrowableOfType(
                CrossRegionRefusedException.class,
                () -> commit.requireJointCriticalSection(4_242L, source, target));

        assertThat(refusal).isNotNull();
        assertThat(refusal.transferId()).isEqualTo(4_242L);
        assertThat(refusal.source()).isEqualTo(source);
        assertThat(refusal.target()).isEqualTo(target);
        assertThat(refusal.getMessage())
                .startsWith(CrossRegionRefusedException.CODE)
                .contains("transfer 4242")
                .contains(source.toString())
                .contains(target.toString())
                .contains("different Folia region threads")
                .contains("no joint critical section")
                .contains("NOTHING was written and no transfer stage was journalled")
                .contains("resync the two regions from their certified heads")
                .contains("L-64");

        // The claim in the message, checked against the store the refusal was holding.
        assertThat(transfers.all()).isEmpty();
        assertThat(transfers.recoverable()).isEmpty();
    }

    @Test
    void aSpanInsideOneFoliaSectionIsNotRefused() {
        CrossRegionCommit commit = CrossRegionCommit.refusing(
                NoderaFoliaRegionMap.regionised(
                        DEFAULT_EXPONENT, NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE),
                transfers);

        // ALIGN-1: regions (0,0) and (1,1) nest in the same 16-chunk section, so one thread writes
        // both and there is a critical section to commit in. This is the common case by design.
        commit.requireJointCriticalSection(1L, source, new RegionId(OVERWORLD, 1, 1));
        assertThat(transfers.all()).isEmpty();
    }

    @Test
    void oneTickThreadRefusesNothing() {
        CrossRegionCommit commit = CrossRegionCommit.refusing(
                NoderaFoliaRegionMap.singleThreaded(), transfers);

        commit.requireJointCriticalSection(1L, source, target);
        assertThat(commit.jointTransferAvailable()).isFalse();
    }

    @Test
    void stageOneNeverPretendsToOfferTheJointPath() {
        CrossRegionCommit commit = CrossRegionCommit.refusing(
                NoderaFoliaRegionMap.singleThreaded(), transfers);

        assertThatThrownBy(() -> commit.commit(
                1L, source, target, null, 0L, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CrossRegionRefusedException.class)
                .hasMessageContaining("no joint-transfer path is configured");
        assertThat(transfers.all()).isEmpty();
    }
}
