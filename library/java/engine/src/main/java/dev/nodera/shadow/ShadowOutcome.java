package dev.nodera.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

/**
 * The result of handing one {@code ActionBatch} to a {@link ShadowWorker} (Task 5): either the
 * worker computed a {@link ShadowResult}, or it detected it is out of sync and needs a re-snapshot
 * before it can execute (it never guesses).
 *
 * @Thread-context immutable, any thread.
 */
public sealed interface ShadowOutcome permits ShadowOutcome.Computed, ShadowOutcome.Resync {

    /** The worker executed the batch and produced a root-only {@link ShadowResult}. */
    record Computed(ShadowResult result) implements ShadowOutcome {
        public Computed {
            if (result == null) {
                throw new IllegalArgumentException("result must not be null");
            }
        }
    }

    /**
     * The worker cannot execute this batch from its current replica and must re-snapshot.
     *
     * @param region       the region needing a fresh snapshot.
     * @param needsVersion the base version the batch required.
     * @param reason       why the worker is out of sync.
     */
    record Resync(RegionId region, SnapshotVersion needsVersion, Reason reason) implements ShadowOutcome {
        public Resync {
            if (region == null) {
                throw new IllegalArgumentException("region must not be null");
            }
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
        }
    }

    /** Why a worker asked to re-snapshot. */
    enum Reason {
        /** No replica is held for the region (never assigned, or evicted). */
        NO_REPLICA,
        /** The held replica's version does not match the batch's base version. */
        VERSION_MISMATCH
    }
}
