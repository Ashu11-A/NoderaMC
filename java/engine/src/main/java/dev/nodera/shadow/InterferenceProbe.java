package dev.nodera.shadow;

import dev.nodera.core.region.RegionId;
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
 * it is counted separately and the region is re-snapshotted rather than poisoned. The count of
 * changed sections is the interference magnitude proxy (a fuller source classification is the mod
 * side's job once it can see the concrete block diff).
 *
 * @Thread-context confined to the probe's owning thread.
 */
public final class InterferenceProbe {

    private long checks;
    private long interferedChecks;
    private long changedSectionsTotal;

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
        changedSectionsTotal += changed;
        boolean interfered = changed > 0;
        if (interfered) {
            interferedChecks++;
        }
        return new Report(expected.region(), changed, interfered);
    }

    private static int changedSections(RegionSnapshot a, RegionSnapshot b) {
        Map<Long, int[]> byChunk = new HashMap<>(a.chunks().size());
        for (ChunkColumnState col : a.chunks()) {
            byChunk.put(pack(col.chunkX(), col.chunkZ()), col.paletteStateIdsPerSection());
        }
        int changed = 0;
        for (ChunkColumnState col : b.chunks()) {
            int[] before = byChunk.remove(pack(col.chunkX(), col.chunkZ()));
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

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** @return the number of probes run. */
    public long checks() {
        return checks;
    }

    /** @return the number of probes that observed at least one foreign mutation. */
    public long interferedChecks() {
        return interferedChecks;
    }

    /** @return the cumulative changed-section count across every probe. */
    public long changedSectionsTotal() {
        return changedSectionsTotal;
    }

    /**
     * The interference verdict for one probe.
     *
     * @param region          the probed region.
     * @param changedSections how many sections differ from the shadow chain's prediction.
     * @param interfered      {@code true} if {@code changedSections > 0}.
     */
    public record Report(RegionId region, int changedSections, boolean interfered) {
        public Report {
            if (region == null) {
                throw new IllegalArgumentException("region must not be null");
            }
        }
    }
}
