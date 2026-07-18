package dev.nodera.consensus;

/**
 * The outcome of the server verifying one client {@code RegionProposal} against its own recompute
 * (Task 6, Phase 2). In Phase 2 the server re-executes 100% of batches; committee voting (Task 7)
 * later replaces most re-execution with quorum, but the outcome vocabulary is shared.
 *
 * <ul>
 *   <li>{@link #MATCH} — client root equals the server's reference root; the delta commits.</li>
 *   <li>{@link #MISMATCH} — roots differ; reject, penalise the proposer's reliability, resync.</li>
 *   <li>{@link #TIMEOUT} — no proposal (or no server result) within the verify budget; treat as a
 *       mismatch-lite (no penalty), resync, and execute server-side.</li>
 *   <li>{@link #STALE_EPOCH} — the proposal carries an epoch older than the region's current lease
 *       epoch; drop it (a reassignment already superseded it).</li>
 * </ul>
 */
public enum VerificationOutcome {
    /** Client root matched the server reference root; commit. */
    MATCH,
    /** Roots differed; reject + penalise + resync. */
    MISMATCH,
    /** No proposal/result within the budget; mismatch-lite, no penalty. */
    TIMEOUT,
    /** Proposal epoch older than the current lease epoch; drop. */
    STALE_EPOCH
}
