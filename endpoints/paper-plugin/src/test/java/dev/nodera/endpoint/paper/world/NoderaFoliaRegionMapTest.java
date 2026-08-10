package dev.nodera.endpoint.paper.world;

import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which Nodera regions one thread writes — the question the plugin could not previously ask. */
final class NoderaFoliaRegionMapTest {

    private static final DimensionKey OVERWORLD = DimensionKey.overworld();
    private static final DimensionKey NETHER = DimensionKey.of("minecraft", "the_nether");

    /** Grid exponent 4 is Folia's own default: a section is 16 chunks, so two regions per axis. */
    private static final int DEFAULT_EXPONENT = 4;

    private final AtomicInteger probes = new AtomicInteger();

    @Test
    void oneTickThreadMeansEveryPairShares() {
        NoderaFoliaRegionMap map = NoderaFoliaRegionMap.singleThreaded();

        assertThat(map.regionised()).isFalse();
        assertThat(map.shareExecutionThread(region(0, 0), region(9, 9))).isTrue();
        assertThat(map.describe()).contains("one tick thread");
    }

    @Test
    void regionsInOneFoliaSectionShareWithoutAskingThePlatform() {
        NoderaFoliaRegionMap map = NoderaFoliaRegionMap.regionised(DEFAULT_EXPONENT, probe(false));

        // Origin chunks 0 and 8 both fall in section 0 at exponent 4 (16 chunks per section).
        assertThat(map.shareExecutionThread(region(0, 0), region(1, 1))).isTrue();
        // ALIGN-1 is the whole answer here: a probe that says "no" must never be consulted, or the
        // common case would depend on a runtime call that can only be made from a region thread.
        assertThat(probes).hasValue(0);
    }

    @Test
    void aWiderSpanIsThePlatformsAnswerAndAnUnaskableOneIsARefusal() {
        // Origin chunks 0 and 16 fall in sections 0 and 1, so ALIGN-1 cannot answer and the
        // platform is asked — which is what the probe counter below proves.
        RegionId source = region(0, 0);
        RegionId target = region(2, 0);

        assertThat(NoderaFoliaRegionMap.regionised(DEFAULT_EXPONENT, probe(true))
                .shareExecutionThread(source, target)).isTrue();
        assertThat(NoderaFoliaRegionMap.regionised(DEFAULT_EXPONENT, probe(false))
                .shareExecutionThread(source, target)).isFalse();
        assertThat(NoderaFoliaRegionMap.regionised(
                        DEFAULT_EXPONENT, NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE)
                .shareExecutionThread(source, target)).isFalse();
        assertThat(probes).hasValue(2);
    }

    @Test
    void twoDimensionsAreNeverOneThread() {
        NoderaFoliaRegionMap map = NoderaFoliaRegionMap.regionised(DEFAULT_EXPONENT, probe(true));

        assertThat(map.shareExecutionThread(
                new RegionId(OVERWORLD, 0, 0), new RegionId(NETHER, 0, 0))).isFalse();
        assertThat(probes).hasValue(0);
    }

    @Test
    void aSplittingGridExponentIsRefusedWithTheAlignOneMessage() {
        assertThatThrownBy(() -> NoderaFoliaRegionMap.regionised(2, probe(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threaded-regions.grid-exponent")
                .hasMessageContaining("config/paper-global.yml");
    }

    @Test
    void describeStatesWhatWasResolvedRatherThanLeavingItToBeInferred() {
        assertThat(NoderaFoliaRegionMap.regionised(DEFAULT_EXPONENT, probe(true)).describe())
                .contains("cross-region commit:")
                .contains("grid-exponent 4")
                .contains("4 Nodera regions per Folia section");
        assertThat(NoderaFoliaRegionMap.regionised(
                        DEFAULT_EXPONENT, NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE)
                .describe()).contains("refused");
    }

    @Test
    void anAbsentSeamResolvesToTheRefusalRatherThanAGuess() {
        assertThat(FoliaOwnershipProbe.resolve(null, null))
                .isSameAs(NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE);
        // A plain object has no isOwnedByCurrentRegion(World,int,int); nothing is invented for it.
        assertThat(FoliaOwnershipProbe.resolve(List.of(), "world"))
                .isSameAs(NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE);
    }

    private NoderaFoliaRegionMap.ExecutionOwnership probe(boolean answer) {
        return (chunkX, chunkZ) -> {
            probes.incrementAndGet();
            return Optional.of(answer);
        };
    }

    private static RegionId region(int x, int z) {
        return new RegionId(OVERWORLD, x, z);
    }
}
