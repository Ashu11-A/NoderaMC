package dev.nodera.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkKey;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Measures <b>foreign mutations</b> — state changes that reached a shadow region outside the captured
 * player actions (random ticks, fluids, fire, mobs, fake players, another mod, cross-border vanilla
 * mechanics) — by comparing the shadow chain's expected snapshot against a freshly re-extracted one
 * (Task 5). This sizes "Hole A" before Task 11 builds the interference guard, and it feeds the
 * {@code INTERFERENCE_REVOKE_RATE} default.
 *
 * <p><b>Not a divergence.</b> Interference is engine-vs-world disagreement, never engine-vs-engine;
 * it is counted separately and the region is re-snapshotted rather than poisoned.
 *
 * <p><b>Two magnitudes, one probe.</b> {@link Report#changedSections()} is the coarse count that
 * predates the live extractor: it compares the per-section palette entries, so a whole section
 * reads as one unit of interference. {@link Report#changedBlocks()} is the exact one — it descends
 * into the dense sections a real chunk extraction produces and counts individual blocks. The coarse
 * number is kept because every historical measurement is expressed in it; the exact number is what
 * a live rate should be sized from, because "one section changed" is the same reading whether a mob
 * broke one block or a fire consumed four thousand.
 *
 * @Thread-context confined to the probe's owning thread.
 */
public final class InterferenceProbe {

    private long checks;
    private long interferedChecks;
    private long changedSectionsTotal;
    private long changedBlocksTotal;

    /**
     * Compare the expected shadow-chain snapshot against a re-extracted one.
     *
     * @param expected     the snapshot the shadow chain predicts.
     * @param reExtracted  the snapshot freshly read from the live world.
     * @return a per-check report.
     * @throws IllegalArgumentException if the snapshots are null or for different regions.
     */
    public Report probe(RegionSnapshot expected, RegionSnapshot reExtracted) {
        if (expected == null || reExtracted == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (!expected.region().equals(reExtracted.region())) {
            throw new IllegalArgumentException("interference probe across different regions");
        }
        checks++;
        int changed = changedSections(expected, reExtracted);
        int changedBlocks = changedBlocks(expected, reExtracted);
        changedSectionsTotal += changed;
        changedBlocksTotal += changedBlocks;
        boolean interfered = changed > 0 || changedBlocks > 0;
        if (interfered) {
            interferedChecks++;
        }
        return new Report(expected.region(), changed, changedBlocks, interfered);
    }

    private static int changedSections(RegionSnapshot a, RegionSnapshot b) {
        Map<Long, int[]> byChunk = new HashMap<>(a.chunks().size());
        for (ChunkColumnState col : a.chunks()) {
            byChunk.put(ChunkKey.pack(col.chunkX(), col.chunkZ()), col.paletteStateIdsPerSection());
        }
        int changed = 0;
        for (ChunkColumnState col : b.chunks()) {
            int[] before = byChunk.remove(ChunkKey.pack(col.chunkX(), col.chunkZ()));
            int[] after = col.paletteStateIdsPerSection();
            if (before == null) {
                changed += after.length; // a chunk that appeared is wholly foreign
                continue;
            }
            int n = Math.min(before.length, after.length);
            for (int i = 0; i < n; i++) {
                if (before[i] != after[i]) {
                    changed++;
                }
            }
            changed += Math.abs(before.length - after.length);
        }
        for (int[] before : byChunk.values()) {
            changed += before.length; // a chunk that vanished is wholly foreign
        }
        return changed;
    }

    /**
     * The exact count: how many individual blocks differ. A section stored as a single palette id
     * is expanded lazily — comparing a uniform section against a dense one costs one pass over the
     * dense side, and comparing two uniform sections costs one integer comparison, so the whole
     * region is walked without ever materialising an all-air world.
     */
    private static int changedBlocks(RegionSnapshot a, RegionSnapshot b) {
        Map<Long, ChunkColumnState> byChunk = new HashMap<>(a.chunks().size());
        for (ChunkColumnState col : a.chunks()) {
            byChunk.put(ChunkKey.pack(col.chunkX(), col.chunkZ()), col);
        }
        int changed = 0;
        for (ChunkColumnState after : b.chunks()) {
            ChunkColumnState before = byChunk.remove(ChunkKey.pack(after.chunkX(), after.chunkZ()));
            if (before == null) {
                changed += nonAirBlocks(after); // a chunk that appeared is wholly foreign
                continue;
            }
            changed += columnDifference(before, after);
        }
        for (ChunkColumnState before : byChunk.values()) {
            changed += nonAirBlocks(before); // a chunk that vanished is wholly foreign
        }
        return changed;
    }

    private static int columnDifference(ChunkColumnState before, ChunkColumnState after) {
        Map<Integer, int[]> denseBefore = dense(before);
        Map<Integer, int[]> denseAfter = dense(after);
        int[] uniformBefore = before.paletteStateIdsPerSection();
        int[] uniformAfter = after.paletteStateIdsPerSection();
        int sections = Math.max(uniformBefore.length, uniformAfter.length);
        int changed = 0;
        for (int index = 0; index < sections; index++) {
            int[] denseA = denseBefore.get(index);
            int[] denseB = denseAfter.get(index);
            int flatA = index < uniformBefore.length ? uniformBefore[index] : 0;
            int flatB = index < uniformAfter.length ? uniformAfter[index] : 0;
            if (denseA == null && denseB == null) {
                if (flatA != flatB) {
                    changed += ChunkColumnState.SECTION_VOLUME;
                }
                continue;
            }
            for (int cell = 0; cell < ChunkColumnState.SECTION_VOLUME; cell++) {
                int idA = denseA == null ? flatA : denseA[cell];
                int idB = denseB == null ? flatB : denseB[cell];
                if (idA != idB) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static Map<Integer, int[]> dense(ChunkColumnState column) {
        if (column.denseSections().isEmpty()) {
            return Map.of();
        }
        Map<Integer, int[]> out = new HashMap<>(column.denseSections().size());
        for (ChunkColumnState.DenseSection section : column.denseSections()) {
            out.put(section.sectionIndex(), section.blocks());
        }
        return out;
    }

    private static int nonAirBlocks(ChunkColumnState column) {
        Map<Integer, int[]> dense = dense(column);
        int[] uniform = column.paletteStateIdsPerSection();
        int count = 0;
        for (int index = 0; index < uniform.length; index++) {
            int[] blocks = dense.get(index);
            if (blocks == null) {
                if (uniform[index] != 0) {
                    count += ChunkColumnState.SECTION_VOLUME;
                }
                continue;
            }
            for (int id : blocks) {
                if (id != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /** @return the number of probes run. */
    public long checks() {
        return checks;
    }

    /** @return the number of probes that observed at least one foreign mutation. */
    public long interferedChecks() {
        return interferedChecks;
    }

    /** @return the cumulative changed-section count across every probe (the coarse magnitude). */
    public long changedSectionsTotal() {
        return changedSectionsTotal;
    }

    /** @return the cumulative exact block-difference count across every probe. */
    public long changedBlocksTotal() {
        return changedBlocksTotal;
    }

    /**
     * The interference verdict for one probe.
     *
     * @param region          the probed region.
     * @param changedSections how many sections differ from the shadow chain's prediction.
     * @param changedBlocks   how many individual blocks differ (exact; descends into dense sections).
     * @param interfered      {@code true} if either count is positive.
     */
    public record Report(
            RegionId region, int changedSections, int changedBlocks, boolean interfered) {
        public Report {
            if (region == null) {
                throw new IllegalArgumentException("region must not be null");
            }
        }
    }
}
