package dev.nodera.peer.committee;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.CommitteeChangeCertificate;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L-19's exit: dynamic committee sizing through certified reconfiguration replaces the static
 * degraded mode. A degraded (2-of-3) committee grows back to full 3-of-4 strength via a
 * {@link CommitteeChangeCertificate} approved by the OLD committee's quorum; the degraded verdict
 * is derived from the certified committee, never a flag; and an under-quorum approval set can
 * never install a resize.
 */
final class CertifiedResizeTest {

    private final SignatureService signatures = new SignatureService();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

    private record Member(NodeIdentity identity) {
        NodeId id() {
            return identity.nodeId();
        }

        Bytes key() {
            return identity.publicKeyBytes();
        }
    }

    @Test
    void degradedCommitteeGrowsBackToFullStrengthViaCertifiedResize() {
        Member a = new Member(NodeIdentity.generate());
        Member b = new Member(NodeIdentity.generate());
        Member c = new Member(NodeIdentity.generate());
        Member d = new Member(NodeIdentity.generate());

        CommitteeManager manager = new CommitteeManager(signatures);
        RegionCommittee degraded = new RegionCommittee(
                region, RegionEpoch.INITIAL, a.id(), List.of(b.id(), c.id()), 2);
        manager.install(degraded, Map.of(a.id(), a.key(), b.id(), b.key(), c.id(), c.key()));
        assertThat(manager.isDegraded(region)).as("2-of-3 is below full strength").isTrue();

        // Population returned: certified resize to the full 4-member committee (3-of-4).
        CommitteeManager.ChangeProposal grow =
                manager.draftResize(region, List.of(a.id(), b.id(), c.id(), d.id()));
        assertThat(grow.next().quorumThreshold()).isEqualTo(3);
        CommitteeChangeCertificate cert = manager.certify(grow, List.of(
                CommitteeManager.approve(grow, a.identity()),
                CommitteeManager.approve(grow, b.identity())));
        manager.apply(cert, Map.of(
                a.id(), a.key(), b.id(), b.key(), c.id(), c.key(), d.id(), d.key()));

        assertThat(manager.isDegraded(region))
                .as("the certified resize retires the degraded mode — no flag involved")
                .isFalse();
        assertThat(manager.committee(region).orElseThrow().size()).isEqualTo(4);
        assertThat(manager.committee(region).orElseThrow().epoch())
                .isEqualTo(new RegionEpoch(1));
    }

    @Test
    void underQuorumApprovalsCannotInstallAResize() {
        Member a = new Member(NodeIdentity.generate());
        Member b = new Member(NodeIdentity.generate());
        Member c = new Member(NodeIdentity.generate());
        Member d = new Member(NodeIdentity.generate());

        CommitteeManager manager = new CommitteeManager(signatures);
        manager.install(new RegionCommittee(
                        region, RegionEpoch.INITIAL, a.id(), List.of(b.id(), c.id()), 2),
                Map.of(a.id(), a.key(), b.id(), b.key(), c.id(), c.key()));

        CommitteeManager.ChangeProposal grow =
                manager.draftResize(region, List.of(a.id(), b.id(), c.id(), d.id()));
        assertThatThrownBy(() -> manager.certify(grow,
                List.of(CommitteeManager.approve(grow, a.identity()))))
                .as("one approval cannot meet the old 2-of-3 quorum")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");
    }

    @Test
    void resizeRejectsDuplicateOrTooFewMembers() {
        Member a = new Member(NodeIdentity.generate());
        Member b = new Member(NodeIdentity.generate());
        Member c = new Member(NodeIdentity.generate());
        CommitteeManager manager = new CommitteeManager(signatures);
        manager.install(new RegionCommittee(
                        region, RegionEpoch.INITIAL, a.id(), List.of(b.id(), c.id()), 2),
                Map.of(a.id(), a.key(), b.id(), b.key(), c.id(), c.key()));

        assertThatThrownBy(() -> manager.draftResize(region, List.of(a.id(), b.id())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.draftResize(region, List.of(a.id(), a.id(), b.id())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct");
    }
}
