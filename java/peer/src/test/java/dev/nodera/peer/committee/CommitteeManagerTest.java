package dev.nodera.peer.committee;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.CommitteeChangeCertificate;
import dev.nodera.core.consensuscert.CommitteeChangeCertificate.Approval;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionCommittee;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CommitteeManager} (Task 9): the authority-free membership state machine. The coordinator
 * only nominates; installing a change needs old-committee approvals at quorum (no single party —
 * server included — rotates members unilaterally), a lost member is replaced under a bumped epoch,
 * a too-small population degrades loudly to 2-of-3, and a full chain verifies link-by-link.
 */
final class CommitteeManagerTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    private final SignatureService signatures = new SignatureService();
    private final NodeIdentity a = NodeIdentity.generate();
    private final NodeIdentity b = NodeIdentity.generate();
    private final NodeIdentity c = NodeIdentity.generate();
    private final NodeIdentity d = NodeIdentity.generate();
    private final NodeIdentity outsider = NodeIdentity.generate();

    private RegionCommittee peer4() {
        // 4-member peer-era committee, 3-of-4 quorum.
        return new RegionCommittee(REGION, RegionEpoch.INITIAL, a.nodeId(),
                List.of(b.nodeId(), c.nodeId(), d.nodeId()),
                NoderaConstants.QUORUM_PEER_REQUIRED);
    }

    private Map<NodeId, Bytes> keys(NodeIdentity... ids) {
        Map<NodeId, Bytes> map = new HashMap<>();
        for (NodeIdentity id : ids) {
            map.put(id.nodeId(), id.publicKeyBytes());
        }
        return map;
    }

    private CommitteeManager withPeer4() {
        CommitteeManager manager = new CommitteeManager(signatures);
        manager.install(peer4(), keys(a, b, c, d));
        return manager;
    }

    @Test
    void installRequiresKeysForEveryMember() {
        CommitteeManager manager = new CommitteeManager(signatures);
        assertThatThrownBy(() -> manager.install(peer4(), keys(a, b, c))) // missing d
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing public key");
    }

    @Test
    void changeRequiresOldCommitteeQuorum() {
        CommitteeManager manager = withPeer4();
        RegionCommittee next = new RegionCommittee(REGION, RegionEpoch.INITIAL.bump(),
                b.nodeId(), List.of(c.nodeId(), d.nodeId(), outsider.nodeId()),
                NoderaConstants.QUORUM_PEER_REQUIRED);
        CommitteeManager.ChangeProposal proposal = manager.propose(next);

        // Only two of three needed approvals (a quorum of 3-of-4) ⇒ rejected.
        List<Approval> twoApprovals = List.of(
                CommitteeManager.approve(proposal, a),
                CommitteeManager.approve(proposal, b));
        assertThatThrownBy(() -> manager.certify(proposal, twoApprovals))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");

        // Three valid old-committee approvals ⇒ certified.
        CommitteeChangeCertificate cert = manager.certify(proposal, List.of(
                CommitteeManager.approve(proposal, a),
                CommitteeManager.approve(proposal, b),
                CommitteeManager.approve(proposal, c)));
        assertThat(cert.newEpoch().value()).isEqualTo(1L);

        // Apply installs the new committee with the new key set.
        manager.apply(cert, keys(b, c, d, outsider));
        assertThat(manager.committee(REGION)).contains(cert.newCommittee());
    }

    @Test
    void outsiderSignatureAndForgedApprovalDoNotCertify() {
        CommitteeManager manager = withPeer4();
        RegionCommittee next = new RegionCommittee(REGION, RegionEpoch.INITIAL.bump(),
                b.nodeId(), List.of(c.nodeId(), d.nodeId(), outsider.nodeId()),
                NoderaConstants.QUORUM_PEER_REQUIRED);
        CommitteeManager.ChangeProposal proposal = manager.propose(next);

        // The outsider's valid signature is not an old-committee approval.
        assertThatThrownBy(() -> manager.certify(proposal, List.of(
                CommitteeManager.approve(proposal, a),
                CommitteeManager.approve(proposal, b),
                CommitteeManager.approve(proposal, outsider))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum");
    }

    @Test
    void lostPrimaryReplacedUnderBumpedEpochWithPopulationIntact() {
        CommitteeManager manager = withPeer4();
        NodeIdentity replacement = NodeIdentity.generate();

        // Primary `a` dies: a validator is promoted to primary, replacement drafted, epoch +1.
        CommitteeManager.ChangeProposal proposal =
                manager.draftReplacement(REGION, a.nodeId(), replacement.nodeId());
        RegionCommittee next = proposal.next();
        assertThat(next.epoch().value()).isEqualTo(1L);
        assertThat(next.primary()).isNotEqualTo(a.nodeId());
        assertThat(next.isMember(replacement.nodeId())).isTrue();
        assertThat(next.size()).isEqualTo(4); // still full peer-era size

        CommitteeChangeCertificate cert = manager.certify(proposal, List.of(
                CommitteeManager.approve(proposal, b),
                CommitteeManager.approve(proposal, c),
                CommitteeManager.approve(proposal, d)));
        manager.apply(cert, keys(b, c, d, replacement));
        assertThat(manager.committee(REGION)).contains(next);
        assertThat(manager.isDegraded(REGION)).isFalse();
    }

    @Test
    void smallPopulationDegradesToTwoOfThreeLoudly() {
        CommitteeManager manager = withPeer4();
        // Lose `a` with no replacement drafted: 3 survive ⇒ degraded 2-of-3 committee.
        CommitteeManager.ChangeProposal proposal =
                manager.draftReplacement(REGION, a.nodeId(), null);
        RegionCommittee next = proposal.next();
        assertThat(next.size()).isEqualTo(3);
        assertThat(next.quorumThreshold()).isEqualTo(NoderaConstants.QUORUM_MVP_REQUIRED);

        CommitteeChangeCertificate cert = manager.certify(proposal, List.of(
                CommitteeManager.approve(proposal, b),
                CommitteeManager.approve(proposal, c),
                CommitteeManager.approve(proposal, d)));
        manager.apply(cert, keys(b, c, d));
        assertThat(manager.isDegraded(REGION)).isTrue();
    }

    @Test
    void cannotStaffEvenADegradedCommittee() {
        // Genesis already degraded (3 members): losing one with no replacement has nowhere to go.
        RegionCommittee degraded = new RegionCommittee(REGION, RegionEpoch.INITIAL, a.nodeId(),
                List.of(b.nodeId(), c.nodeId()), NoderaConstants.QUORUM_MVP_REQUIRED);
        CommitteeManager manager = new CommitteeManager(signatures);
        manager.install(degraded, keys(a, b, c));
        assertThatThrownBy(() -> manager.draftReplacement(REGION, a.nodeId(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot staff");
    }

    @Test
    void chainVerifiesLinkByLinkAgainstPredecessorKeys() {
        CommitteeManager manager = withPeer4();

        // Epoch 0 → 1: full committee, primary rotates to b.
        RegionCommittee epoch1 = new RegionCommittee(REGION, new RegionEpoch(1), b.nodeId(),
                List.of(c.nodeId(), d.nodeId(), a.nodeId()), NoderaConstants.QUORUM_PEER_REQUIRED);
        CommitteeManager.ChangeProposal p1 = manager.propose(epoch1);
        CommitteeChangeCertificate c1 = manager.certify(p1, List.of(
                CommitteeManager.approve(p1, a),
                CommitteeManager.approve(p1, c),
                CommitteeManager.approve(p1, d)));

        // Epoch 1 → 2: draft outsider as a new member (now trusted from epoch 1's key set).
        NodeIdentity e = NodeIdentity.generate();
        manager.apply(c1, keys(b, c, d, a));
        RegionCommittee epoch2 = new RegionCommittee(REGION, new RegionEpoch(2), c.nodeId(),
                List.of(d.nodeId(), a.nodeId(), e.nodeId()), NoderaConstants.QUORUM_PEER_REQUIRED);
        CommitteeManager.ChangeProposal p2 = manager.propose(epoch2);
        CommitteeChangeCertificate c2 = manager.certify(p2, List.of(
                CommitteeManager.approve(p2, b),
                CommitteeManager.approve(p2, d),
                CommitteeManager.approve(p2, a)));

        Map<Long, Map<NodeId, Bytes>> keysByEpoch = new HashMap<>();
        keysByEpoch.put(0L, keys(a, b, c, d));     // genesis committee keys
        keysByEpoch.put(1L, keys(b, c, d, a));     // epoch-1 committee keys
        RegionCommittee resolved = CommitteeManager.verifyChain(
                signatures, peer4(), keysByEpoch, List.of(c1, c2));
        assertThat(resolved).isEqualTo(epoch2);

        // A stale link (prevEpoch does not match the committee the previous link installed)
        // fails at the offending link.
        RegionCommittee futureGenesis = new RegionCommittee(REGION, new RegionEpoch(5),
                a.nodeId(), List.of(b.nodeId(), c.nodeId(), d.nodeId()),
                NoderaConstants.QUORUM_PEER_REQUIRED);
        assertThatThrownBy(() -> CommitteeManager.verifyChain(
                signatures, futureGenesis, keysByEpoch, List.of(c1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chain break");
    }

    @Test
    void proposalMustStepExactlyOneEpoch() {
        CommitteeManager manager = withPeer4();
        RegionCommittee twoSteps = new RegionCommittee(REGION, new RegionEpoch(2),
                b.nodeId(), List.of(c.nodeId(), d.nodeId(), a.nodeId()),
                NoderaConstants.QUORUM_PEER_REQUIRED);
        assertThatThrownBy(() -> manager.propose(twoSteps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one epoch");
    }
}
