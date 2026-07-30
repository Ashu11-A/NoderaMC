package dev.nodera.consensus;

import dev.nodera.core.state.StateRoot;

import dev.nodera.core.identity.NodeId;

/**
 * Evidence that a voter signed two different {@link StateRoot}s for the same {@link ProposalKey}
 * (Task 7 consensus/). Produced by {@link EquivocationDetector#record(NodeId)}; fed to
 * {@code ReliabilityLedger.slash} and stored as the certificate backing a committee-removal
 * action (Plan §6 Phase 3 / Invariant machinery).
 *
 * @param voter           the equivocating validator.
 * @param key             the proposal the voter double-voted on.
 * @param firstRoot       the root of the retained (earlier-stored) claim in the conflicting pair
 *                        this replica convicted on. Storage order equals observation order on a
 *                        single thread; under concurrent observation it is the {@code putIfAbsent}
 *                        winner, not a guaranteed temporal ordering.
 * @param secondRoot      the conflicting root of the same pair (≠ {@code firstRoot}). Any one
 *                        conflicting pair is sufficient evidence; which pair is captured is
 *                        replica-local.
 * @param detectedAtMillis wall-clock detection time; for logging/auditing only — NOT part of
 *                        hashed consensus state.
 *
 * @Thread-context immutable, any thread.
 */
public record EquivocationRecord(
        NodeId voter,
        ProposalKey key,
        StateRoot firstRoot,
        StateRoot secondRoot,
        long detectedAtMillis) {

    /**
     * @throws IllegalArgumentException if any reference argument is null, or if both roots are
     *                                  equal (a non-equivocation).
     */
    public EquivocationRecord {
        if (voter == null) {
            throw new IllegalArgumentException("voter must not be null");
        }
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (firstRoot == null) {
            throw new IllegalArgumentException("firstRoot must not be null");
        }
        if (secondRoot == null) {
            throw new IllegalArgumentException("secondRoot must not be null");
        }
        if (firstRoot.equals(secondRoot)) {
            throw new IllegalArgumentException(
                    "firstRoot and secondRoot must differ; identical roots are not equivocation");
        }
    }
}
