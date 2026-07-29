package dev.nodera.diagnostics.model;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.diagnostics.model.RegionOwnership.Certification;
import dev.nodera.diagnostics.view.Cell;
import dev.nodera.diagnostics.view.Panel;
import dev.nodera.diagnostics.view.Row;
import dev.nodera.diagnostics.view.ViewBuilder;
import dev.nodera.diagnostics.state.Health;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #47.3 — region rows must say what consensus actually did, and must never let a
 * <b>population shortfall</b> read as a stalled commit.
 *
 * <p>The observed defect was a region panel that looked permanently "unsigned": with two players
 * and no standing workers, most regions are covered by exactly one player, so their committee is a
 * committee of one. That head IS committed (a 1-of-1 quorum) — it simply carries no independent
 * signature, and no amount of waiting will add one. {@link Certification#SOLO} names that; the
 * cure is more peers on the mesh (issue #45), which {@link Certification#PENDING} would have
 * wrongly implied was already arriving.
 */
final class RegionCertificationTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final NodeId A = new NodeId(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private static final NodeId B = new NodeId(UUID.fromString("00000000-0000-0000-0000-00000000000b"));

    private static StateRoot root(int fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) fill);
        return new StateRoot(Bytes.unsafeWrap(raw));
    }

    private static QuorumCertificate certificate(long version, NodeId... voters) {
        List<SignedVote> votes = java.util.Arrays.stream(voters)
                .map(v -> new SignedVote(v, REGION, new RegionEpoch(1), new SnapshotVersion(version),
                        root(0x33), root(0x22), root(0x22), VoteDecision.ACCEPT,
                        Bytes.unsafeWrap(new byte[64])))
                .toList();
        return new QuorumCertificate(REGION, new RegionEpoch(1), new SnapshotVersion(version),
                root(0x11), root(0x22), votes);
    }

    @Test
    void aCommitteeOfOneIsSoloNotPending() {
        assertThat(RegionOwnership.classify(1, new SnapshotVersion(5), certificate(5, A)))
                .isEqualTo(Certification.SOLO);
    }

    @Test
    void aCoSignedHeadIsCertified() {
        assertThat(RegionOwnership.classify(2, new SnapshotVersion(5), certificate(5, A, B)))
                .isEqualTo(Certification.CERTIFIED);
    }

    @Test
    void aCommitteeThatCanCoSignButHasNotYetIsPending() {
        assertThat(RegionOwnership.classify(3, new SnapshotVersion(5), null))
                .isEqualTo(Certification.PENDING);
    }

    @Test
    void aCertificateBehindTheHeadIsPendingNotCertified() {
        // The lane has committed past the newest certificate this node holds: honest = PENDING.
        assertThat(RegionOwnership.classify(3, new SnapshotVersion(9), certificate(5, A, B)))
                .isEqualTo(Certification.PENDING);
    }

    @Test
    void aSingleSignatureOnAMultiMemberCommitteeIsNotCertification() {
        assertThat(RegionOwnership.classify(3, new SnapshotVersion(5), certificate(5, A)))
                .isEqualTo(Certification.PENDING);
    }

    @Test
    void noLeaseIsUnknownRatherThanAClaimAboutConsensus() {
        assertThat(RegionOwnership.classify(0, new SnapshotVersion(5), certificate(5, A, B)))
                .isEqualTo(Certification.UNKNOWN);
    }

    @Test
    void theRegionsPanelRendersSoloWithItsPopulationReasonAndNeverAsPending() {
        RegionOwnership owned = new RegionOwnership(
                List.of(REGION), List.of(), List.of(), 64,
                Map.of(REGION, new RegionOwnership.LeaseInfo(1L, 100L, Certification.SOLO, 1, 1)));
        Panel panel = ViewBuilder.regionsPanel(snapshotWith(owned));

        Row solo = rowNamed(panel, ViewBuilder.LBL_SOLO);
        // The solo row names its own key — the population reason is words in the lang file, not a
        // sentence this view model assembled (MC-GUI-5).
        assertThat(solo.cells().get(1).key()).isEqualTo(ViewBuilder.VAL_SOLO);
        assertThat(solo.cells().get(1).args()).containsExactly(1);
        assertThat(panel.rows().stream().map(r -> r.cells().get(0).key()))
                .doesNotContain(ViewBuilder.LBL_PENDING);
    }

    @Test
    void theRegionsPanelCountsCertifiedAndPendingSeparately() {
        RegionId second = new RegionId(DimensionKey.overworld(), 1, 0);
        RegionOwnership owned = new RegionOwnership(
                List.of(REGION, second), List.of(), List.of(), 128,
                Map.of(REGION, new RegionOwnership.LeaseInfo(1L, 100L, Certification.CERTIFIED, 3, 2),
                        second, new RegionOwnership.LeaseInfo(1L, 100L, Certification.PENDING, 3, 0)));
        Panel panel = ViewBuilder.regionsPanel(snapshotWith(owned));

        Cell certified = rowNamed(panel, ViewBuilder.LBL_CERTIFIED).cells().get(1);
        assertThat(certified.key()).isEqualTo(ViewBuilder.VAL_CERTIFIED);
        assertThat(certified.args()).containsExactly(1);
        Cell pending = rowNamed(panel, ViewBuilder.LBL_PENDING).cells().get(1);
        assertThat(pending.key()).isEqualTo(ViewBuilder.VAL_PENDING);
        assertThat(pending.args()).containsExactly(1);
    }

    @Test
    void aPeerWithNoLaneStillRendersThePlaceholderAndNoCertificationRows() {
        Panel panel = ViewBuilder.regionsPanel(snapshotWith(RegionOwnership.empty()));
        assertThat(panel.rows().stream().map(r -> r.cells().get(0).key()))
                .contains(ViewBuilder.LBL_NOTE)
                .doesNotContain(ViewBuilder.LBL_CERTIFIED, ViewBuilder.LBL_PENDING,
                        ViewBuilder.LBL_SOLO);
    }

    private static Row rowNamed(Panel panel, String labelKey) {
        return panel.rows().stream()
                .filter(r -> r.cells().get(0).key().equals(labelKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + labelKey + "' row in " + panel.rows()));
    }

    private static TelemetrySnapshot snapshotWith(RegionOwnership regions) {
        return new TelemetrySnapshot(1L, A, false,
                new SessionInfo(1L, A, true, 1, "route", List.of()),
                new NetStats(0, 0, 0, 0, 0, 0, 0, 0, Map.of()),
                regions, EntityControl.empty(), new HealthStat(Health.HEALTHY, "ok"));
    }
}
