package dev.nodera.coordinator;

/**
 * States of a delegated region's {@link RegionPipeline} (Task 6). A region walks
 * {@code IDLE → SNAPSHOT_SYNC → ACTIVE → AWAITING_PROPOSAL → AWAITING_VERIFICATION → COMMIT →
 * ACTIVE …}; a mismatch/timeout routes back through {@code SNAPSHOT_SYNC} (resync); a lease expiry
 * drops it to {@code REVOKED} from anywhere.
 */
public enum PipelineState {
    /** Not delegated (vanilla lane). */
    IDLE,
    /** Streaming the snapshot to the committee. */
    SNAPSHOT_SYNC,
    /** Delegated and idle between batches. */
    ACTIVE,
    /** Batch dispatched to the primary; waiting for its proposal. */
    AWAITING_PROPOSAL,
    /** Proposal received; server re-executing to verify. */
    AWAITING_VERIFICATION,
    /** Verified MATCH; delta being applied by the world writer. */
    COMMIT,
    /** Region is held at a committed boundary for an atomic cross-region operation. */
    PAUSED_FOR_XR,
    /** Lease revoked; back on the vanilla lane pending reassignment. */
    REVOKED
}
