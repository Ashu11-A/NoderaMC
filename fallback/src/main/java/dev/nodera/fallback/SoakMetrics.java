package dev.nodera.fallback;

import java.util.EnumMap;
import java.util.Map;

/**
 * Counts how batches were routed (Task 8, Phase 4). The headline number is the
 * {@link #committeeCommitRatio()} — the fraction committed by committees without server
 * re-execution. The Phase 4 exit criterion is a sustained {@code > 90%} committee-commit ratio in a
 * spread-out session ({@link #meetsPhase4ExitCriterion()}).
 *
 * @Thread-context confined to the router thread; {@link #snapshot()} returns an immutable copy.
 */
public final class SoakMetrics {

    /** The Phase 4 exit threshold: &gt;90% of validated-action batches commit without the server. */
    public static final double PHASE4_COMMITTEE_RATIO = 0.90;

    private final Map<RoutingReason, Long> byReason = new EnumMap<>(RoutingReason.class);
    private long committeeBatches;
    private long fallbackBatches;

    /** Record one routed batch. */
    public void record(RoutingDecision decision) {
        byReason.merge(decision.reason(), 1L, Long::sum);
        if (decision.isCommittee()) {
            committeeBatches++;
        } else {
            fallbackBatches++;
        }
    }

    public long committeeBatches() {
        return committeeBatches;
    }

    public long fallbackBatches() {
        return fallbackBatches;
    }

    public long total() {
        return committeeBatches + fallbackBatches;
    }

    /** @return count of batches routed for {@code reason}. */
    public long countReason(RoutingReason reason) {
        return byReason.getOrDefault(reason, 0L);
    }

    /** @return committee-committed / total (0 if no batches yet). */
    public double committeeCommitRatio() {
        long t = total();
        return t == 0 ? 0.0 : (double) committeeBatches / t;
    }

    /** @return {@code true} if the committee-commit ratio clears the Phase 4 exit threshold. */
    public boolean meetsPhase4ExitCriterion() {
        return committeeCommitRatio() > PHASE4_COMMITTEE_RATIO;
    }

    /** @return an immutable snapshot of the counters. */
    public Snapshot snapshot() {
        return new Snapshot(committeeBatches, fallbackBatches, committeeCommitRatio());
    }

    /**
     * @param committeeBatches batches committee-committed.
     * @param fallbackBatches  batches server-executed.
     * @param committeeRatio   committee-committed / total.
     */
    public record Snapshot(long committeeBatches, long fallbackBatches, double committeeRatio) {
    }
}
