package dev.nodera.core.state;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The index exists to answer "what changed" instead of "are we the same". These tests pin that
 * answer, including the case the old version-counter design got wrong: identical content stamped at
 * different instants is <b>not</b> a change, and re-fetching it is the waste this replaces.
 */
final class RegionChunkIndexTest {

    private static final RegionId REGION = new RegionId(DimensionKey.of("minecraft", "overworld"), 0, 0);
    private static final SnapshotVersion V1 = new SnapshotVersion(1);
    private static final SnapshotVersion V2 = new SnapshotVersion(2);

    private static ChunkColumnState column(int x, int z, int block) {
        return new ChunkColumnState(x, z, new int[]{block, block}, -64, 2, List.of());
    }

    private static Hlc at(long millis) {
        return new Hlc(millis, 0, NodeId.random().value());
    }

    private static RegionChunkIndex index(SnapshotVersion version, List<ChunkStamp> stamps) {
        return RegionChunkIndex.of(REGION, version, stamps);
    }

    @Test
    void twoPeersHoldingTheSameRegionAgreeOnThirtyTwoBytes() {
        List<ChunkStamp> mine = new ArrayList<>();
        List<ChunkStamp> theirs = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                mine.add(ChunkStamp.of(column(x, z, x * 8 + z), at(1000)));
                // Built in the opposite order, by a different node, at a different instant. Same
                // content — which is the only thing the root is allowed to depend on. One peer
                // wrote this terrain and the other received it; if that made their roots differ,
                // every peer would differ from every other peer on every region forever.
                theirs.add(0, ChunkStamp.of(column(x, z, x * 8 + z), at(77_000)));
            }
        }

        assertThat(index(V1, mine).root()).isEqualTo(index(V1, theirs).root());
    }

    @Test
    void oneChangedColumnChangesTheRootAndNamesItself() {
        List<ChunkStamp> before = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            before.add(ChunkStamp.of(column(x, 0, 1), at(1000)));
        }
        List<ChunkStamp> after = new ArrayList<>(before);
        after.set(2, ChunkStamp.of(column(2, 0, 99), at(2000)));

        RegionChunkIndex held = index(V1, before);
        RegionChunkIndex fresh = index(V2, after);

        assertThat(fresh.root()).isNotEqualTo(held.root());
        assertThat(fresh.changedSince(held))
                .as("exactly one column moved, so exactly one column is fetched")
                .hasSize(1)
                .allSatisfy(stamp -> assertThat(stamp.chunkX()).isEqualTo(2));
    }

    @Test
    void identicalContentStampedLaterIsNotAChange() {
        // This is the failure the version counter produced: the host repacked, every version number
        // moved, and every peer re-fetched a world nobody had edited.
        List<ChunkStamp> early = List.of(ChunkStamp.of(column(0, 0, 5), at(1000)));
        List<ChunkStamp> late = List.of(ChunkStamp.of(column(0, 0, 5), at(9999)));

        assertThat(index(V2, late).changedSince(index(V1, early))).isEmpty();
    }

    @Test
    void aColumnTheOtherSideHasNeverSeenCountsAsChanged() {
        RegionChunkIndex held = index(V1, List.of(ChunkStamp.of(column(0, 0, 1), at(1000))));
        RegionChunkIndex fresh = index(V2, List.of(
                ChunkStamp.of(column(0, 0, 1), at(1000)),
                ChunkStamp.of(column(0, 1, 1), at(1000))));

        assertThat(fresh.changedSince(held)).hasSize(1);
    }

    @Test
    void withNothingHeldEverythingIsChanged() {
        RegionChunkIndex fresh = index(V1, List.of(
                ChunkStamp.of(column(0, 0, 1), at(1000)),
                ChunkStamp.of(column(0, 1, 2), at(1000))));

        assertThat(fresh.changedSince(null)).hasSize(2);
    }

    @Test
    void mergingTakesTheNewerVersionOfEachColumnSeparately() {
        // Two owners edited different corners while they could not see each other. A region-level
        // "newest wins" would discard one of them wholesale; per-column merging keeps both.
        RegionChunkIndex ours = index(V2, List.of(
                ChunkStamp.of(column(0, 0, 10), at(5000)),
                ChunkStamp.of(column(1, 0, 1), at(1000))));
        RegionChunkIndex theirs = index(V2, List.of(
                ChunkStamp.of(column(0, 0, 1), at(1000)),
                ChunkStamp.of(column(1, 0, 20), at(6000))));

        RegionChunkIndex merged = ours.mergeWith(theirs);

        assertThat(merged.stampAt(0, 0).contentHash())
                .isEqualTo(ChunkStamp.of(column(0, 0, 10), at(0)).contentHash());
        assertThat(merged.stampAt(1, 0).contentHash())
                .isEqualTo(ChunkStamp.of(column(1, 0, 20), at(0)).contentHash());
    }

    @Test
    void itRoundTripsThroughItsCanonicalEncoding() {
        RegionChunkIndex original = index(V2, List.of(
                ChunkStamp.of(column(0, 0, 1), at(1000)),
                ChunkStamp.of(column(3, 7, 2), at(2000))));

        CanonicalWriter w = new CanonicalWriter();
        original.encode(w);
        RegionChunkIndex decoded = RegionChunkIndex.decode(new CanonicalReader(w.toBytes()));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void aRootThatDoesNotMatchTheStampsIsRefused() {
        List<ChunkStamp> stamps = List.of(ChunkStamp.of(column(0, 0, 1), at(1000)));

        assertThatThrownBy(() -> new RegionChunkIndex(REGION, V1, stamps,
                Bytes.unsafeWrap(new byte[32])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root");
    }

    @Test
    void twoStampsForOneColumnAreRefused() {
        List<ChunkStamp> stamps = List.of(
                ChunkStamp.of(column(0, 0, 1), at(1000)),
                ChunkStamp.of(column(0, 0, 2), at(2000)));

        assertThatThrownBy(() -> RegionChunkIndex.of(REGION, V1, stamps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one stamp per column");
    }
}
