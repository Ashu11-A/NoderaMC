package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.content.ContentChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rule 10's "hash-validate before use" lives here. The tests that matter are the negative ones: a
 * rejected piece must leave the reassembler <b>bit-identical</b> to before, or a corrupt seeder
 * could advance progress (or worse, poison a byte) simply by being loud.
 *
 * <p>Thread-context: single test thread.
 */
final class PieceReassemblerTest {

    private static RegionSnapshotSplitter.Layout layout() {
        RegionSnapshot snapshot = DistFixtures.variedSnapshot(
                DistFixtures.region(4, 4), new SnapshotVersion(2L), 20L);
        return RegionSnapshotSplitter.split(snapshot, 512);
    }

    private static Bytes pieceBytes(RegionSnapshotSplitter.Layout layout, int index) {
        Piece p = layout.manifest().piece(index);
        return new Bytes(layout.blob().toArray(), (int) p.offset(), (int) p.length());
    }

    @Test
    void acceptsVerifiedPiecesAndReassemblesToTheCommittedStateRoot() {
        RegionSnapshotSplitter.Layout layout = layout();
        PieceReassembler r = new PieceReassembler(layout.manifest());

        for (int i = 0; i < layout.manifest().pieceCount(); i++) {
            assertThat(r.accept(new ContentChunk(
                    layout.manifest().manifestRoot(), i, pieceBytes(layout, i)))).isTrue();
        }

        assertThat(r.isComplete()).isTrue();
        assertThat(r.assemble()).isEqualTo(layout.blob());
        // The whole point: pieces from anywhere reassemble into the state the committee committed.
        assertThat(r.assembledRoot())
                .isEqualTo(StateRoot.of(DistFixtures.hashes().hash(layout.snapshot())))
                .isEqualTo(layout.manifest().regionRoot());
    }

    @Test
    void aCorruptedPieceIsRejectedAndChangesNothing() {
        RegionSnapshotSplitter.Layout layout = layout();
        PieceReassembler r = new PieceReassembler(layout.manifest());

        Bytes good = pieceBytes(layout, 3);
        assertThat(r.accept(new ContentChunk(layout.manifest().manifestRoot(), 3,
                DistFixtures.corrupt(good)))).isFalse();

        assertThat(r.hasPiece(3)).isFalse();
        assertThat(r.verifiedCount()).isZero();
        assertThat(r.missing()).contains(3);

        // ...and the honest bytes still land afterwards: rejection is not a poison pill.
        assertThat(r.accept(new ContentChunk(layout.manifest().manifestRoot(), 3, good))).isTrue();
        assertThat(r.hasPiece(3)).isTrue();
    }

    @Test
    void rejectsChunksForAnotherManifestAndOutOfRangeIndexes() {
        RegionSnapshotSplitter.Layout layout = layout();
        PieceReassembler r = new PieceReassembler(layout.manifest());

        Bytes good = pieceBytes(layout, 0);
        Bytes foreignRoot = DistFixtures.corrupt(layout.manifest().manifestRoot());

        assertThat(r.accept(new ContentChunk(foreignRoot, 0, good))).isFalse();
        assertThat(r.accept(new ContentChunk(layout.manifest().manifestRoot(), 9999, good)))
                .isFalse();
        assertThat(r.verifiedCount()).isZero();
    }

    @Test
    void locallyCachedBytesAreVerifiedTooSoACorruptCacheCannotBecomeACorruptWorld() {
        RegionSnapshotSplitter.Layout layout = layout();
        PieceReassembler r = new PieceReassembler(layout.manifest());

        assertThat(r.restore(0, DistFixtures.corrupt(pieceBytes(layout, 0)))).isFalse();
        assertThat(r.restore(0, pieceBytes(layout, 0))).isTrue();
    }

    @Test
    void refusesToAssembleWhilePiecesAreMissing() {
        RegionSnapshotSplitter.Layout layout = layout();
        PieceReassembler r = new PieceReassembler(layout.manifest());
        r.accept(new ContentChunk(layout.manifest().manifestRoot(), 0, pieceBytes(layout, 0)));

        assertThatThrownBy(r::assemble)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still missing");
    }

    @Test
    void tracksProgressPreciselyAcrossPartialDelivery() {
        RegionSnapshotSplitter.Layout layout = layout();
        int total = layout.manifest().pieceCount();
        PieceReassembler r = new PieceReassembler(layout.manifest());

        for (int i = 0; i < total; i += 2) {
            r.accept(new ContentChunk(layout.manifest().manifestRoot(), i, pieceBytes(layout, i)));
        }

        int expected = (total + 1) / 2;
        assertThat(r.verifiedCount()).isEqualTo(expected);
        assertThat(r.missing()).hasSize(total - expected);
        assertThat(r.isComplete()).isFalse();
    }
}
