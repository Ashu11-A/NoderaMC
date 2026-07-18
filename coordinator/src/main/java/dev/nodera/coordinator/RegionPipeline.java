package dev.nodera.coordinator;

import dev.nodera.consensus.VerificationOutcome;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

import java.util.EnumSet;
import java.util.Set;

/**
 * The per-region, single-threaded state machine that sequences one delegated region's
 * assign → sync → execute → propose → verify → commit loop (Task 6). Every transition asserts its
 * legal source state and throws {@link IllegalStateException} otherwise, so an out-of-order message
 * (a late proposal, a duplicate commit) can never advance the machine into an inconsistent state.
 * Runs on the server main thread; not thread-safe.
 */
public final class RegionPipeline {

    private final RegionId region;
    private PipelineState state = PipelineState.IDLE;
    private RegionEpoch epoch = RegionEpoch.INITIAL;
    private SnapshotVersion lastCommittedVersion = SnapshotVersion.INITIAL;

    public RegionPipeline(RegionId region) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        this.region = region;
    }

    public RegionId region() {
        return region;
    }

    public PipelineState state() {
        return state;
    }

    public RegionEpoch epoch() {
        return epoch;
    }

    public SnapshotVersion lastCommittedVersion() {
        return lastCommittedVersion;
    }

    /** Begin delegation under {@code epoch}: IDLE|REVOKED → SNAPSHOT_SYNC. */
    public void assign(RegionEpoch epoch) {
        require(EnumSet.of(PipelineState.IDLE, PipelineState.REVOKED), "assign");
        this.epoch = epoch;
        state = PipelineState.SNAPSHOT_SYNC;
    }

    /** Committee acknowledged the snapshot: SNAPSHOT_SYNC → ACTIVE. */
    public void snapshotSynced() {
        require(EnumSet.of(PipelineState.SNAPSHOT_SYNC), "snapshotSynced");
        state = PipelineState.ACTIVE;
    }

    /** Batch dispatched to the primary: ACTIVE → AWAITING_PROPOSAL. */
    public void dispatchBatch() {
        require(EnumSet.of(PipelineState.ACTIVE), "dispatchBatch");
        state = PipelineState.AWAITING_PROPOSAL;
    }

    /** Primary's proposal arrived: AWAITING_PROPOSAL → AWAITING_VERIFICATION. */
    public void proposalArrived() {
        require(EnumSet.of(PipelineState.AWAITING_PROPOSAL), "proposalArrived");
        state = PipelineState.AWAITING_VERIFICATION;
    }

    /**
     * Fold the verification outcome: MATCH → COMMIT; MISMATCH/TIMEOUT → SNAPSHOT_SYNC (resync);
     * STALE_EPOCH → back to ACTIVE (the stale proposal is simply dropped).
     *
     * @return the resulting state.
     */
    public PipelineState verified(VerificationOutcome outcome) {
        require(EnumSet.of(PipelineState.AWAITING_VERIFICATION), "verified");
        state = switch (outcome) {
            case MATCH -> PipelineState.COMMIT;
            case MISMATCH, TIMEOUT -> PipelineState.SNAPSHOT_SYNC;
            case STALE_EPOCH -> PipelineState.ACTIVE;
        };
        return state;
    }

    /** Commit applied at {@code version}: COMMIT → ACTIVE, advancing the committed version. */
    public void committed(SnapshotVersion version) {
        require(EnumSet.of(PipelineState.COMMIT), "committed");
        this.lastCommittedVersion = version;
        state = PipelineState.ACTIVE;
    }

    /** Revoke the lease from any state: → REVOKED. */
    public void revoke() {
        state = PipelineState.REVOKED;
    }

    private void require(Set<PipelineState> legal, String op) {
        if (!legal.contains(state)) {
            throw new IllegalStateException(
                    "illegal pipeline transition '" + op + "' from " + state + " for " + region);
        }
    }
}
