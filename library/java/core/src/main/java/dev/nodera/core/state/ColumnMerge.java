package dev.nodera.core.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Two peers' versions of one chunk column, reconciled block by block against what they last agreed
 * on.
 *
 * <h2>Why not simply keep the newer column</h2>
 *
 * <p>Whole-column last-writer-wins is what a clock alone can do, and it throws away real work: two
 * players who built in the same chunk while their owners could not see each other both did
 * something, and the one whose clock reads lower loses up to ninety-eight thousand blocks of it for
 * having stopped a second earlier. That was the region-level version comparison's failure repeated
 * at a finer grain — better, and still wrong for the same reason.
 *
 * <p>With the common ancestor in hand the question becomes answerable per position rather than per
 * column. Almost every block in a contested column was touched by nobody; of the few that were,
 * almost none were touched by both. So:
 *
 * <ul>
 *   <li><b>only one side changed it</b> → take that side. Nothing is in conflict, and this is the
 *       overwhelming majority of what a merge does.</li>
 *   <li><b>both changed it to the same thing</b> → take it, and do not count it. Two people placing
 *       the same block in the same place have not disagreed about anything; treating that as a
 *       conflict is how a merge acquires a false conflict rate.</li>
 *   <li><b>both changed it to different things</b> → the column with the newer clock wins the
 *       position, and it is counted. This is the only place recency decides anything, and it decides
 *       the smallest possible thing.</li>
 * </ul>
 *
 * <h2>Where the ancestor comes from</h2>
 *
 * <p>The last content both sides held — in practice the newest region manifest both have. Without
 * one there is no way to tell "I changed this" from "they changed this back", and the honest
 * fallback is the clock: {@link #ofColumns} with a {@code null} ancestor resolves the whole column
 * to whichever side is newer, and says so in its counts.
 *
 * @param result     the merged column.
 * @param tookMine   positions taken from {@code mine} because only it had changed them.
 * @param tookTheirs positions taken from {@code theirs} for the same reason.
 * @param agreed     positions both sides changed identically — not conflicts.
 * @param contested  positions both sides changed differently, resolved by clock.
 * @Thread-context immutable record, safe for any thread.
 */
public record ColumnMerge(ChunkColumnState result, int tookMine, int tookTheirs, int agreed,
                          int contested) {

    /** Blocks per section edge. */
    private static final int SECTION_EDGE = 16;

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code result} is null.
     */
    public ColumnMerge {
        Objects.requireNonNull(result, "result");
    }

    /** @return positions where the two sides genuinely differed, contested or not. */
    public int changedPositions() {
        return tookMine + tookTheirs + agreed + contested;
    }

    /**
     * Merge two versions of one column.
     *
     * @param ancestor   the last content both sides held, or {@code null} when there is none.
     * @param mine       this peer's column.
     * @param theirs     the other peer's column.
     * @param mineNewer  whether {@code mine} carries the newer clock reading — the tie-break for a
     *                   position both sides changed differently. Must be computed the same way on
     *                   both peers, or they converge on different states.
     * @return the merge.
     * @throws IllegalArgumentException if the columns describe different chunks or disagree about
     *                                  their vertical extent.
     * @Thread-context any thread.
     */
    public static ColumnMerge ofColumns(ChunkColumnState ancestor, ChunkColumnState mine,
                                        ChunkColumnState theirs, boolean mineNewer) {
        Objects.requireNonNull(mine, "mine");
        Objects.requireNonNull(theirs, "theirs");
        if (mine.chunkX() != theirs.chunkX() || mine.chunkZ() != theirs.chunkZ()) {
            throw new IllegalArgumentException("cannot merge different columns");
        }
        if (mine.sectionCount() != theirs.sectionCount() || mine.minY() != theirs.minY()) {
            throw new IllegalArgumentException(
                    "cannot merge columns with different vertical extents");
        }
        if (mine.equals(theirs)) {
            // Identical content is not a merge. Saying so first keeps the common case free and
            // stops it being reported as a column full of agreements.
            return new ColumnMerge(mine, 0, 0, 0, 0);
        }
        if (ancestor == null) {
            // No basis for "who changed what". Whole-column recency is the only honest answer, and
            // the counts say that is what happened rather than pretending to a per-block result.
            ChunkColumnState winner = mineNewer ? mine : theirs;
            return new ColumnMerge(winner, mineNewer ? 1 : 0, mineNewer ? 0 : 1, 0,
                    mineNewer ? 0 : 1);
        }
        if (ancestor.sectionCount() != mine.sectionCount() || ancestor.minY() != mine.minY()) {
            throw new IllegalArgumentException(
                    "ancestor does not describe the same column extent");
        }

        int sections = mine.sectionCount();
        int[] uniform = new int[sections];
        List<ChunkColumnState.DenseSection> dense = new ArrayList<>();
        int tookMine = 0;
        int tookTheirs = 0;
        int agreed = 0;
        int contested = 0;

        for (int index = 0; index < sections; index++) {
            if (sameSection(mine, theirs, index)) {
                // Neither side moved relative to the other here; copy whichever, they are equal.
                copySection(mine, index, uniform, dense);
                continue;
            }
            int[] merged = new int[ChunkColumnState.SECTION_VOLUME];
            int cursor = 0;
            for (int y = 0; y < SECTION_EDGE; y++) {
                for (int z = 0; z < SECTION_EDGE; z++) {
                    for (int x = 0; x < SECTION_EDGE; x++) {
                        int base = ancestor.blockAt(index, x, y, z);
                        int a = mine.blockAt(index, x, y, z);
                        int b = theirs.blockAt(index, x, y, z);
                        int chosen;
                        if (a == b) {
                            // Either nobody touched it, or both put the same thing there. Counting
                            // the second as a conflict is how a merge invents disagreement.
                            chosen = a;
                            if (a != base) {
                                agreed++;
                            }
                        } else if (a == base) {
                            chosen = b;
                            tookTheirs++;
                        } else if (b == base) {
                            chosen = a;
                            tookMine++;
                        } else {
                            chosen = mineNewer ? a : b;
                            contested++;
                        }
                        merged[cursor++] = chosen;
                    }
                }
            }
            storeSection(index, merged, uniform, dense);
        }
        return new ColumnMerge(
                new ChunkColumnState(mine.chunkX(), mine.chunkZ(), uniform, mine.minY(), sections,
                        dense),
                tookMine, tookTheirs, agreed, contested);
    }

    /** Whether two columns hold identical content in one section. */
    private static boolean sameSection(ChunkColumnState a, ChunkColumnState b, int index) {
        if (a.paletteStateIdsPerSection()[index] != b.paletteStateIdsPerSection()[index]) {
            return false;
        }
        int[] denseA = denseOf(a, index);
        int[] denseB = denseOf(b, index);
        if (denseA == null && denseB == null) {
            return true;
        }
        if (denseA == null || denseB == null) {
            return false;
        }
        return java.util.Arrays.equals(denseA, denseB);
    }

    private static int[] denseOf(ChunkColumnState column, int index) {
        for (ChunkColumnState.DenseSection section : column.denseSections()) {
            if (section.sectionIndex() == index) {
                return section.blocks();
            }
        }
        return null;
    }

    private static void copySection(ChunkColumnState from, int index, int[] uniform,
                                    List<ChunkColumnState.DenseSection> dense) {
        uniform[index] = from.paletteStateIdsPerSection()[index];
        int[] blocks = denseOf(from, index);
        if (blocks != null) {
            dense.add(new ChunkColumnState.DenseSection(index, blocks));
        }
    }

    /**
     * Store a merged section in whichever shape it deserves.
     *
     * <p>Re-canonicalising rather than always storing dense matters: a section that merged back to
     * a single id must be stored as that id, or two peers whose merges agree on the blocks would
     * still encode different bytes and therefore hash differently — which would leave them
     * "disagreeing" about a column they had just successfully reconciled.
     */
    private static void storeSection(int index, int[] merged, int[] uniform,
                                     List<ChunkColumnState.DenseSection> dense) {
        int first = merged[0];
        for (int id : merged) {
            if (id != first) {
                uniform[index] = 0;
                dense.add(new ChunkColumnState.DenseSection(index, merged));
                return;
            }
        }
        uniform[index] = first;
    }
}
