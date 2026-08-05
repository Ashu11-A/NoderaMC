package dev.nodera.core.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The merge contract, stated as tests: <b>disjoint edits both survive, identical edits are not a
 * conflict, and only a position both peers changed differently is decided by a clock.</b>
 *
 * <p>What this replaces is worth keeping in view. Reconciliation used to be a comparison of two
 * counters incremented independently on two machines; the higher number won and every edit in the
 * other copy was discarded, silently. Whole-column recency would be better and still wrong for the
 * same reason — it throws away work nobody disagreed about.
 */
final class ColumnMergeTest {

    private static final int SECTIONS = 2;
    private static final int AIR = 0;

    /** A column of all-air sections, ready to have individual positions set. */
    private static ChunkColumnState blank() {
        return new ChunkColumnState(0, 0, new int[SECTIONS], -64, SECTIONS, List.of());
    }

    private static ChunkColumnState with(ChunkColumnState base, int x, int y, int z, int id) {
        return base.withBlock(0, x, y, z, id);
    }

    @Test
    void twoPeersEditingDifferentBlocksBothKeepTheirWork() {
        // The whole point. Under whole-column recency one of these two builds disappears.
        ChunkColumnState ancestor = blank();
        ChunkColumnState mine = with(ancestor, 1, 1, 1, 7);
        ChunkColumnState theirs = with(ancestor, 2, 2, 2, 9);

        ColumnMerge merge = ColumnMerge.ofColumns(ancestor, mine, theirs, true);

        assertThat(merge.result().blockAt(0, 1, 1, 1)).isEqualTo(7);
        assertThat(merge.result().blockAt(0, 2, 2, 2)).isEqualTo(9);
        assertThat(merge.tookMine()).isEqualTo(1);
        assertThat(merge.tookTheirs()).isEqualTo(1);
        assertThat(merge.contested()).isZero();
    }

    @Test
    void theSameBlockPlacedByBothIsNotAConflict() {
        // "Prioritising the most recent version when replacing locations with identical
        // modifications" — two people who did the same thing have not disagreed about anything, and
        // counting it as a conflict is how a merge acquires a false conflict rate.
        //
        // The columns have to differ somewhere, or there is nothing to merge at all: two byte-equal
        // columns take the identity path before any position is looked at.
        ChunkColumnState ancestor = blank();
        ChunkColumnState mine = with(with(ancestor, 3, 3, 3, 11), 1, 1, 1, 7);
        ChunkColumnState theirs = with(with(ancestor, 3, 3, 3, 11), 2, 2, 2, 9);

        ColumnMerge merge = ColumnMerge.ofColumns(ancestor, mine, theirs, false);

        assertThat(merge.result().blockAt(0, 3, 3, 3)).isEqualTo(11);
        assertThat(merge.agreed()).as("the shared placement, counted as agreement").isEqualTo(1);
        assertThat(merge.contested()).isZero();
        assertThat(merge.tookMine()).isEqualTo(1);
        assertThat(merge.tookTheirs()).isEqualTo(1);
    }

    @Test
    void aPositionBothChangedDifferentlyGoesToTheNewerColumn() {
        ChunkColumnState ancestor = blank();
        ChunkColumnState mine = with(ancestor, 4, 4, 4, 21);
        ChunkColumnState theirs = with(ancestor, 4, 4, 4, 22);

        assertThat(ColumnMerge.ofColumns(ancestor, mine, theirs, true).result().blockAt(0, 4, 4, 4))
                .isEqualTo(21);
        assertThat(ColumnMerge.ofColumns(ancestor, mine, theirs, false).result().blockAt(0, 4, 4, 4))
                .isEqualTo(22);
        assertThat(ColumnMerge.ofColumns(ancestor, mine, theirs, true).contested()).isEqualTo(1);
    }

    @Test
    void bothPeersMergingTheSamePairReachTheSameColumn() {
        // Run on both machines, each with its own side as "mine". They must land in the same place,
        // or a merge that both peers believe succeeded leaves them holding different regions.
        ChunkColumnState ancestor = blank();
        ChunkColumnState a = with(with(ancestor, 1, 1, 1, 7), 5, 5, 5, 30);
        ChunkColumnState b = with(with(ancestor, 2, 2, 2, 9), 5, 5, 5, 31);

        // `a` is the newer side; both peers compute that identically from the clock readings.
        ColumnMerge onA = ColumnMerge.ofColumns(ancestor, a, b, true);
        ColumnMerge onB = ColumnMerge.ofColumns(ancestor, b, a, false);

        assertThat(onA.result()).isEqualTo(onB.result());
        assertThat(onA.contested()).isEqualTo(onB.contested()).isEqualTo(1);
    }

    @Test
    void aBlockOneSideRemovedStaysRemoved() {
        // Breaking is an edit like any other: ancestor has it, one side does not, nobody else
        // touched it. Treating "changed to air" as "unchanged" would make blocks un-mineable.
        ChunkColumnState ancestor = with(blank(), 6, 6, 6, 42);
        ChunkColumnState mine = with(ancestor, 6, 6, 6, AIR);
        ChunkColumnState theirs = ancestor;

        ColumnMerge merge = ColumnMerge.ofColumns(ancestor, mine, theirs, false);

        assertThat(merge.result().blockAt(0, 6, 6, 6)).isEqualTo(AIR);
        assertThat(merge.tookMine()).isEqualTo(1);
    }

    @Test
    void identicalColumnsAreNotAMergeAtAll() {
        ChunkColumnState column = with(blank(), 1, 1, 1, 5);

        ColumnMerge merge = ColumnMerge.ofColumns(blank(), column, column, true);

        assertThat(merge.result()).isEqualTo(column);
        assertThat(merge.changedPositions()).isZero();
    }

    @Test
    void withNoAncestorTheWholeColumnResolvesByClockAndSaysSo() {
        // The honest degradation: without a common ancestor there is no way to tell "I changed
        // this" from "they changed it back", so per-block reasoning would be guessing.
        ChunkColumnState mine = with(blank(), 1, 1, 1, 7);
        ChunkColumnState theirs = with(blank(), 2, 2, 2, 9);

        ColumnMerge merge = ColumnMerge.ofColumns(null, mine, theirs, true);

        assertThat(merge.result()).isEqualTo(mine);
        assertThat(merge.contested()).isZero();
        assertThat(ColumnMerge.ofColumns(null, mine, theirs, false).contested())
                .as("losing a whole column is reported as a conflict, because it is one")
                .isEqualTo(1);
    }

    @Test
    void aMergedSectionThatCameBackUniformIsStoredUniform() {
        // Two peers whose edits cancel out must not end up encoding the same blocks differently —
        // a dense section holding one repeated id hashes differently from the uniform form, so they
        // would "disagree" about a column they had just successfully reconciled.
        ChunkColumnState ancestor = blank();
        ChunkColumnState mine = with(ancestor, 1, 1, 1, 3);
        ChunkColumnState theirs = with(ancestor, 1, 1, 1, 3);

        ColumnMerge merge = ColumnMerge.ofColumns(ancestor, mine, theirs, true);

        assertThat(merge.result()).isEqualTo(mine);
        assertThat(ChunkStamp.of(merge.result(), Hlc.ZERO).contentHash())
                .isEqualTo(ChunkStamp.of(mine, Hlc.ZERO).contentHash());
    }

    @Test
    void columnsForDifferentChunksAreRefused() {
        ChunkColumnState mine = blank();
        ChunkColumnState elsewhere =
                new ChunkColumnState(1, 0, new int[SECTIONS], -64, SECTIONS, List.of());

        assertThatThrownBy(() -> ColumnMerge.ofColumns(null, mine, elsewhere, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different columns");
    }
}
