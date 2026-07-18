package dev.nodera.shadow;

/**
 * Shadow-lane counters (Task 5): match/mismatch/batch/resync counts and the snapshot + batch bytes
 * streamed, exposed via {@code /nodera status}. The Phase 1 gate reads these — hours of play with
 * {@code mismatches == 0}, and the bandwidth figures feed the Phase 1 bandwidth budget recorded in
 * {@code Plan.md}.
 *
 * @Thread-context confined to the coordinator thread; {@link #stats()} returns an immutable copy.
 */
public final class ShadowMetrics {

    private long matches;
    private long mismatches;
    private long batches;
    private long resyncs;
    private long snapshotBytesOut;
    private long batchBytesOut;

    /** Record a client root matching the reference root. */
    public void recordMatch() {
        matches++;
    }

    /** Record a client root diverging from the reference root. */
    public void recordMismatch() {
        mismatches++;
    }

    /** Record one batch relayed, adding its encoded size to the batch-lane byte counter. */
    public void recordBatch(long encodedBytes) {
        batches++;
        batchBytesOut += encodedBytes;
    }

    /** Record one snapshot streamed (assignment or resync), adding its encoded size. */
    public void recordSnapshotBytes(long encodedBytes) {
        snapshotBytesOut += encodedBytes;
    }

    /** Record a worker asking to re-snapshot. */
    public void recordResync() {
        resyncs++;
    }

    /** @return an immutable snapshot of every counter. */
    public Stats stats() {
        return new Stats(matches, mismatches, batches, resyncs, snapshotBytesOut, batchBytesOut);
    }

    /**
     * Immutable copy of the shadow counters.
     *
     * @param matches          client roots matching the reference.
     * @param mismatches       client roots diverging (the Phase 1 gate requires zero).
     * @param batches          batches relayed.
     * @param resyncs          worker re-snapshot requests served.
     * @param snapshotBytesOut total encoded bytes streamed on the snapshot lane.
     * @param batchBytesOut    total encoded bytes streamed on the batch lane.
     */
    public record Stats(
            long matches,
            long mismatches,
            long batches,
            long resyncs,
            long snapshotBytesOut,
            long batchBytesOut
    ) {
        /** @return {@code true} when no divergence has been observed. */
        public boolean clean() {
            return mismatches == 0;
        }

        /** @return total bytes streamed across both lanes. */
        public long totalBytesOut() {
            return snapshotBytesOut + batchBytesOut;
        }
    }
}
