package dev.nodera.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.StateRoot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares client {@link ShadowResult}s against the server's reference root and tracks the outcome
 * (Task 5). A match ticks the {@link ShadowMetrics}; a mismatch records a {@link DivergenceRecord},
 * ticks the mismatch counter, and <b>poisons</b> the region — the coordinator must re-snapshot a
 * poisoned region before relaying further batches to it, so one divergence does not cascade into a
 * storm of dependent false mismatches.
 *
 * @Thread-context confined to the coordinator thread.
 */
public final class DivergenceTracker {

    private final ShadowMetrics metrics = new ShadowMetrics();
    private final List<DivergenceRecord> divergences = new ArrayList<>();
    private final Set<RegionId> poisoned = new HashSet<>();

    /**
     * Compare one client result against the reference root.
     *
     * @param referenceRoot the server's reference root for this batch.
     * @param shadow        the client's reported result.
     * @return {@code true} on match, {@code false} on divergence (which is recorded and poisons the
     *         region).
     */
    public boolean compare(StateRoot referenceRoot, ShadowResult shadow) {
        if (referenceRoot == null) {
            throw new IllegalArgumentException("referenceRoot must not be null");
        }
        if (shadow == null) {
            throw new IllegalArgumentException("shadow must not be null");
        }
        if (referenceRoot.equals(shadow.resultingRoot())) {
            metrics.recordMatch();
            return true;
        }
        metrics.recordMismatch();
        divergences.add(new DivergenceRecord(
                shadow.region(), shadow.epoch(), shadow.baseVersion(),
                referenceRoot, shadow.resultingRoot(), shadow.clientNodeId()));
        poisoned.add(shadow.region());
        return false;
    }

    /** @return the shadow counters this tracker updates. */
    public ShadowMetrics metrics() {
        return metrics;
    }

    /** @return an immutable copy of every recorded divergence, in observation order. */
    public List<DivergenceRecord> divergences() {
        return List.copyOf(divergences);
    }

    /** @return {@code true} if {@code region} is poisoned (awaiting re-snapshot). */
    public boolean isPoisoned(RegionId region) {
        return poisoned.contains(region);
    }

    /** Clear a region's poison flag after it has been re-snapshotted. */
    public void clearPoison(RegionId region) {
        poisoned.remove(region);
    }
}
