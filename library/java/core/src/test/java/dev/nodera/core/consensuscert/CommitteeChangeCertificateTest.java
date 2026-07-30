package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionCommittee;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CommitteeChangeCertificate} (Task 9): canonical round-trip, the exactly-one-epoch rule,
 * and approval verification against the OLD committee's keys — the mechanism that stops any single
 * party (server included) from rotating a committee unilaterally.
 */
final class CommitteeChangeCertificateTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private final SignatureService signatures = new SignatureService();

    private final NodeIdentity a = NodeIdentity.generate();
    private final NodeIdentity b = NodeIdentity.generate();
    private final NodeIdentity c = NodeIdentity.generate();
    private final NodeIdentity outsider = NodeIdentity.generate();

    private RegionCommittee oldCommittee() {
        return new RegionCommittee(REGION, RegionEpoch.INITIAL, a.nodeId(),
                List.of(b.nodeId(), c.nodeId()), 2);
    }

    private RegionCommittee newCommittee() {
        return new RegionCommittee(REGION, new RegionEpoch(1), b.nodeId(),
                List.of(c.nodeId(), outsider.nodeId()), 2);
    }

    private Map<NodeId, Bytes> oldKeys() {
        return Map.of(a.nodeId(), a.publicKeyBytes(), b.nodeId(), b.publicKeyBytes(),
                c.nodeId(), c.publicKeyBytes());
    }

    private CommitteeChangeCertificate signedBy(NodeIdentity... approvers) {
        CommitteeChangeCertificate unsigned = new CommitteeChangeCertificate(
                REGION, RegionEpoch.INITIAL, new RegionEpoch(1), newCommittee(), List.of());
        List<CommitteeChangeCertificate.Approval> approvals = java.util.Arrays.stream(approvers)
                .map(id -> new CommitteeChangeCertificate.Approval(
                        id.nodeId(), id.sign(unsigned.signedPortion())))
                .toList();
        return new CommitteeChangeCertificate(
                REGION, RegionEpoch.INITIAL, new RegionEpoch(1), newCommittee(), approvals);
    }

    @Test
    void roundTripsCanonically() {
        CommitteeChangeCertificate cert = signedBy(a, b);
        CanonicalWriter w = new CanonicalWriter();
        cert.encode(w);
        CommitteeChangeCertificate decoded =
                CommitteeChangeCertificate.decode(new CanonicalReader(w.toBytes().toArray()));
        assertThat(decoded).isEqualTo(cert);
    }

    @Test
    void mustBumpExactlyOneEpoch() {
        assertThatThrownBy(() -> new CommitteeChangeCertificate(
                REGION, RegionEpoch.INITIAL, new RegionEpoch(2),
                new RegionCommittee(REGION, new RegionEpoch(2), a.nodeId(),
                        List.of(b.nodeId()), 2),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one epoch");
    }

    @Test
    void quorumOfOldCommitteeApprovalsVerifies() {
        CommitteeChangeCertificate cert = signedBy(a, b);
        assertThat(cert.validApprovals(signatures, oldCommittee(), oldKeys())).isEqualTo(2);
        assertThat(cert.approvedBy(signatures, oldCommittee(), oldKeys())).isTrue();
    }

    @Test
    void outsiderAndForgedApprovalsDoNotCount() {
        // An outsider's (valid) signature is not an old-committee approval …
        CommitteeChangeCertificate withOutsider = signedBy(a, outsider);
        assertThat(withOutsider.validApprovals(signatures, oldCommittee(), oldKeys())).isEqualTo(1);
        assertThat(withOutsider.approvedBy(signatures, oldCommittee(), oldKeys())).isFalse();

        // … and a member entry carrying someone else's signature fails verification.
        CommitteeChangeCertificate real = signedBy(a);
        CommitteeChangeCertificate forged = new CommitteeChangeCertificate(
                REGION, RegionEpoch.INITIAL, new RegionEpoch(1), newCommittee(),
                List.of(new CommitteeChangeCertificate.Approval(
                        b.nodeId(), real.approvals().get(0).signature())));
        assertThat(forged.validApprovals(signatures, oldCommittee(), oldKeys())).isZero();
    }

    @Test
    void duplicateApproverCountsOnce() {
        CommitteeChangeCertificate unsigned = new CommitteeChangeCertificate(
                REGION, RegionEpoch.INITIAL, new RegionEpoch(1), newCommittee(), List.of());
        Bytes sig = a.sign(unsigned.signedPortion());
        CommitteeChangeCertificate doubled = new CommitteeChangeCertificate(
                REGION, RegionEpoch.INITIAL, new RegionEpoch(1), newCommittee(),
                List.of(new CommitteeChangeCertificate.Approval(a.nodeId(), sig),
                        new CommitteeChangeCertificate.Approval(a.nodeId(), sig)));
        assertThat(doubled.validApprovals(signatures, oldCommittee(), oldKeys())).isEqualTo(1);
    }

    @Test
    void approvalsAreCanonicallySorted() {
        CommitteeChangeCertificate cert = signedBy(c, a, b);
        List<UUID> order = cert.approvals().stream().map(x -> x.approver().value()).toList();
        assertThat(order).isSorted();
    }
}
