package dev.nodera.coordinator;

import dev.nodera.consensus.VerificationOutcome;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionPipelineTest {

    private final RegionId region = CoordFixtures.region(0, 0);

    @Test
    void happyPathTransitions() {
        RegionPipeline p = new RegionPipeline(region);
        assertThat(p.state()).isEqualTo(PipelineState.IDLE);
        p.assign(new RegionEpoch(1));
        assertThat(p.state()).isEqualTo(PipelineState.SNAPSHOT_SYNC);
        assertThat(p.epoch().value()).isEqualTo(1);
        p.snapshotSynced();
        assertThat(p.state()).isEqualTo(PipelineState.ACTIVE);
        p.dispatchBatch();
        assertThat(p.state()).isEqualTo(PipelineState.AWAITING_PROPOSAL);
        p.proposalArrived();
        assertThat(p.state()).isEqualTo(PipelineState.AWAITING_VERIFICATION);
        assertThat(p.verified(VerificationOutcome.MATCH)).isEqualTo(PipelineState.COMMIT);
        p.committed(new SnapshotVersion(1));
        assertThat(p.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(p.lastCommittedVersion()).isEqualTo(new SnapshotVersion(1));
    }

    @Test
    void mismatchRoutesToResync() {
        RegionPipeline p = driveToVerification();
        assertThat(p.verified(VerificationOutcome.MISMATCH)).isEqualTo(PipelineState.SNAPSHOT_SYNC);
    }

    @Test
    void timeoutRoutesToResync() {
        RegionPipeline p = driveToVerification();
        assertThat(p.verified(VerificationOutcome.TIMEOUT)).isEqualTo(PipelineState.SNAPSHOT_SYNC);
    }

    @Test
    void staleEpochReturnsToActive() {
        RegionPipeline p = driveToVerification();
        assertThat(p.verified(VerificationOutcome.STALE_EPOCH)).isEqualTo(PipelineState.ACTIVE);
    }

    @Test
    void illegalTransitionThrows() {
        RegionPipeline p = new RegionPipeline(region);
        // Cannot commit before verifying.
        assertThatThrownBy(() -> p.committed(new SnapshotVersion(1)))
                .isInstanceOf(IllegalStateException.class);
        // Cannot dispatch a batch before snapshot sync.
        p.assign(new RegionEpoch(1));
        assertThatThrownBy(p::dispatchBatch).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void revokeFromAnyStateGoesToRevoked() {
        RegionPipeline p = driveToVerification();
        p.revoke();
        assertThat(p.state()).isEqualTo(PipelineState.REVOKED);
        // and can be reassigned from REVOKED.
        p.assign(new RegionEpoch(2));
        assertThat(p.state()).isEqualTo(PipelineState.SNAPSHOT_SYNC);
    }

    @Test
    void crossRegionBarrierCanCommitAndAdvance() {
        RegionPipeline pipeline = activePipeline();
        pipeline.pauseForCrossRegion();
        assertThat(pipeline.state()).isEqualTo(PipelineState.PAUSED_FOR_XR);
        pipeline.crossRegionCommitted(new SnapshotVersion(1));
        assertThat(pipeline.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(pipeline.lastCommittedVersion()).isEqualTo(new SnapshotVersion(1));
    }

    @Test
    void directCommitteeCommitAdvancesExactlyOneVersionWhileActive() {
        RegionPipeline pipeline = activePipeline();

        pipeline.committeeCommitted(new SnapshotVersion(1));

        assertThat(pipeline.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(pipeline.lastCommittedVersion()).isEqualTo(new SnapshotVersion(1));
        assertThatThrownBy(() -> pipeline.committeeCommitted(new SnapshotVersion(3)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void crossRegionBarrierCanAbortWithoutVersionAdvance() {
        RegionPipeline pipeline = activePipeline();
        pipeline.pauseForCrossRegion();
        pipeline.crossRegionAborted();
        assertThat(pipeline.state()).isEqualTo(PipelineState.ACTIVE);
        assertThat(pipeline.lastCommittedVersion()).isEqualTo(SnapshotVersion.INITIAL);
    }

    @Test
    void crossRegionBarrierRejectsIllegalStateAndStaleCommit() {
        RegionPipeline pipeline = new RegionPipeline(region);
        assertThatThrownBy(pipeline::pauseForCrossRegion).isInstanceOf(IllegalStateException.class);
        pipeline = activePipeline();
        pipeline.pauseForCrossRegion();
        RegionPipeline paused = pipeline;
        assertThatThrownBy(() -> paused.crossRegionCommitted(SnapshotVersion.INITIAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RegionPipeline driveToVerification() {
        RegionPipeline p = new RegionPipeline(region);
        p.assign(new RegionEpoch(1));
        p.snapshotSynced();
        p.dispatchBatch();
        p.proposalArrived();
        return p;
    }

    private RegionPipeline activePipeline() {
        RegionPipeline pipeline = new RegionPipeline(region);
        pipeline.assign(new RegionEpoch(1));
        pipeline.snapshotSynced();
        return pipeline;
    }
}
