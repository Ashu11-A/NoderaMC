package dev.nodera.committee;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpotCheckAuditorTest {

    private final RegionId region = CommFixtures.region(0, 0);

    private RegionExecutionRequest request() {
        RegionSnapshot base = CommFixtures.fullUniformSnapshot(region, 0);
        ActionBatch batch = CommFixtures.batch(region, RegionEpoch.INITIAL, SnapshotVersion.INITIAL, 0, 1,
                List.of(CommFixtures.place(region, 1, 0, 5, 70, 5, 1)));
        return CommFixtures.request(base, batch);
    }

    @Test
    void samplingIsDeterministicAndSelective() {
        SpotCheckAuditor auditor = new SpotCheckAuditor(CommFixtures.engine(), 0xABCDEFL);
        // Fresh committee reliability 0.5 → N=4 (~25% sampled). Both a hit and a miss must exist,
        // and the decision must be reproducible.
        boolean anyHit = false;
        boolean anyMiss = false;
        for (long v = 0; v < 40; v++) {
            boolean first = auditor.shouldAudit(region, v, 0.5);
            boolean second = auditor.shouldAudit(region, v, 0.5);
            assertThat(first).isEqualTo(second); // deterministic
            anyHit |= first;
            anyMiss |= !first;
        }
        assertThat(anyHit).isTrue();
        assertThat(anyMiss).isTrue();
    }

    @Test
    void auditAgreesWithHonestCommit() {
        SpotCheckAuditor auditor = new SpotCheckAuditor(CommFixtures.engine(), 1L);
        StateRoot honest = CommFixtures.engine().execute(request()).resultingRoot();
        SpotCheckAuditor.AuditResult r = auditor.audit(request(), honest);
        assertThat(r.agrees()).isTrue();
        assertThat(r.disputed()).isFalse();
    }

    @Test
    void auditDisputesAColludedWrongRoot() {
        SpotCheckAuditor auditor = new SpotCheckAuditor(CommFixtures.engine(), 1L);
        // A fully-colluding committee committed a bogus root; the server re-execution disagrees.
        SpotCheckAuditor.AuditResult r = auditor.audit(request(), StateRoot.zero());
        assertThat(r.disputed()).isTrue();
        assertThat(r.serverRoot()).isNotEqualTo(StateRoot.zero());
    }
}
