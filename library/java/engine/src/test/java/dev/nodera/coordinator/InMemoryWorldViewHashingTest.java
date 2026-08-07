package dev.nodera.coordinator;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkStampBook;
import dev.nodera.core.state.Hlc;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionChunkIndex;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.testkit.engine.EngineFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The view caches two things a commit used to recompute from scratch every time: the region's
 * consensus root, and its per-column stamps.
 *
 * <p>A cache on a consensus root is only as good as its invalidation, so these tests are mostly
 * about the ways it must <b>not</b> answer: after a write, after a rollback, and for inputs it was
 * not computed for. The correctness bar is "indistinguishable from recomputing", and the failure
 * mode of getting it wrong is two peers disagreeing about committed state without anything throwing.
 */
final class InMemoryWorldViewHashingTest {

    private final RegionId region = EngineFixtures.region(0, 0);

    private InMemoryWorldView loaded() {
        InMemoryWorldView world = new InMemoryWorldView();
        world.load(EngineFixtures.fullUniformSnapshot(region, 0));
        return world;
    }

    private static StateRoot recomputed(InMemoryWorldView world, RegionId region,
                                        SnapshotVersion version, long tick) {
        return StateRoot.of(EngineFixtures.hashes().hash(world.reExtract(region, version, tick)));
    }

    @Test
    void theCachedRootIsTheRootThatHashingWouldHaveProduced() {
        InMemoryWorldView world = loaded();

        StateRoot cached = world.regionRoot(region, SnapshotVersion.INITIAL, 7L);

        assertThat(cached).isEqualTo(recomputed(world, region, SnapshotVersion.INITIAL, 7L));
        assertThat(world.regionRoot(region, SnapshotVersion.INITIAL, 7L))
                .as("asking twice for an unchanged region is the case worth making free")
                .isEqualTo(cached);
    }

    @Test
    void aWriteInvalidatesTheRoot() {
        InMemoryWorldView world = loaded();
        StateRoot before = world.regionRoot(region, SnapshotVersion.INITIAL, 0L);

        world.setBlock(region, new NBlockPos(5, 70, 5), 1);

        StateRoot after = world.regionRoot(region, SnapshotVersion.INITIAL, 0L);
        assertThat(after).isNotEqualTo(before);
        assertThat(after).isEqualTo(recomputed(world, region, SnapshotVersion.INITIAL, 0L));
    }

    @Test
    void aDifferentVersionOrTickIsADifferentRoot() {
        // The version and tick are encoded into the snapshot frame, so they are inputs to the hash
        // and not merely labels. A cache keyed on the region alone would answer a root that was
        // never committed at that version.
        InMemoryWorldView world = loaded();
        StateRoot atZero = world.regionRoot(region, SnapshotVersion.INITIAL, 0L);

        assertThat(world.regionRoot(region, SnapshotVersion.INITIAL, 1L)).isNotEqualTo(atZero);
        assertThat(world.regionRoot(region, SnapshotVersion.INITIAL.next(), 0L))
                .isNotEqualTo(atZero);
        assertThat(world.regionRoot(region, SnapshotVersion.INITIAL, 0L))
                .as("and the original key still answers its own value")
                .isEqualTo(atZero);
    }

    @Test
    void aRolledBackScopeDoesNotLeaveAnAnswerableRootBehind() {
        InMemoryWorldView world = loaded();
        StateRoot original = world.regionRoot(region, SnapshotVersion.INITIAL, 0L);

        try (MutableWorldView.MutationScope scope = world.beginMutation()) {
            world.setBlock(region, new NBlockPos(5, 70, 5), 1);
            world.regionRoot(region, SnapshotVersion.INITIAL, 0L); // cache the mutated root
            // no commit()
        }

        StateRoot afterRollback = world.regionRoot(region, SnapshotVersion.INITIAL, 0L);
        assertThat(afterRollback)
                .as("the roll-back put the old state back, so the old root is the true one")
                .isEqualTo(original)
                .isEqualTo(recomputed(world, region, SnapshotVersion.INITIAL, 0L));
    }

    @Test
    void theIndexIsTheOneAFullEncodeWouldHaveProduced() {
        InMemoryWorldView world = loaded();
        world.setBlock(region, new NBlockPos(5, 70, 5), 1);
        world.setBlock(region, new NBlockPos(40, 100, 40), 4);

        RegionChunkIndex cached = world.chunkIndex(region, null);

        // Built the slow way, straight off the re-extracted columns.
        java.util.List<dev.nodera.core.state.ChunkStamp> fromScratch = new java.util.ArrayList<>();
        for (dev.nodera.core.state.ChunkColumnState column
                : world.reExtract(region, SnapshotVersion.INITIAL, 0L).chunks()) {
            fromScratch.add(dev.nodera.core.state.ChunkStamp.of(column, Hlc.ZERO));
        }

        assertThat(cached.root()).isEqualTo(RegionChunkIndex.of(region, fromScratch).root());
    }

    @Test
    void anEditedColumnIsTheOnlyOneTheIndexReportsAsChanged() {
        InMemoryWorldView world = loaded();
        RegionChunkIndex before = world.chunkIndex(region, null);

        world.setBlock(region, new NBlockPos(5, 70, 5), 1);

        assertThat(world.chunkIndex(region, null).changedSince(before))
                .as("one block moved, so one of sixty-four columns did")
                .hasSize(1);
    }

    @Test
    void anAppliedWriteStampsTheColumnJustLikeAForeignOneDoes() {
        // The reason stamping lives in setBlock rather than at the write guard: the guard never sees
        // a WorldMutationApplier write, so a validator applying somebody else's certified commit
        // would report the column as never written and lose a merge for content it holds correctly.
        InMemoryWorldView world = loaded();
        ChunkStampBook book = new ChunkStampBook(NodeId.random());
        world.stampBook(book);

        world.setBlock(region, new NBlockPos(5, 70, 5), 1);

        assertThat(book.size()).isEqualTo(1);
        assertThat(world.chunkIndex(region, book).stampAt(0, 0).stamp())
                .as("the column carries the reading it was written at, not the floor")
                .isNotEqualTo(Hlc.ZERO);
    }

    @Test
    void entityWritesMoveTheRootToo() {
        // Entities are in the snapshot frame, so a cache that only watched blocks would answer a
        // root that no longer described the region.
        InMemoryWorldView world = loaded();
        RegionSnapshot base = world.reExtract(region, SnapshotVersion.INITIAL, 0L);
        StateRoot before = world.regionRoot(region, SnapshotVersion.INITIAL, 0L);

        world.setEntity(region, new dev.nodera.core.state.PersistedEntityState(
                new dev.nodera.core.state.NetworkEntityId(1L),
                dev.nodera.core.state.EntityKind.ITEM, 42,
                dev.nodera.core.state.FixedVec3.ofBlock(2, 5, 2),
                dev.nodera.core.state.FixedVec3.ZERO, 0, 6_000,
                dev.nodera.simulation.entity.ItemEntityRules.payload(42, 1)));

        assertThat(world.regionRoot(region, SnapshotVersion.INITIAL, 0L))
                .isNotEqualTo(before)
                .isEqualTo(recomputed(world, region, SnapshotVersion.INITIAL, 0L));
        assertThat(base.entities()).isEmpty();
    }
}
