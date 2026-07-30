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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L-18's rotation quarter: deterministic committee rotation by rendezvous hashing over
 * {@code (region, nextEpoch, nodeId)}. Every replica derives the identical next committee from
 * the same population; the epoch input reshuffles seats every rotation so no committee holds a
 * region indefinitely; installing still runs through the certified-change quorum, and an
 * under-quorum rotation can never install.
 */
final class CommitteeRotationTest {

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

    private static List<Member> members(int n) {
        List<Member> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Member(NodeIdentity.generate()));
        }
        return out;
    }

    private CommitteeManager managerWith(List<Member> committee4) {
        CommitteeManager manager = new CommitteeManager(signatures);
        Map<NodeId, Bytes> keys = new HashMap<>();
        for (Member m : committee4) {
            keys.put(m.id(), m.key());
        }
        manager.install(new RegionCommittee(region, RegionEpoch.INITIAL,
                committee4.get(0).id(),
                List.of(committee4.get(1).id(), committee4.get(2).id(), committee4.get(3).id()),
                3), keys);
        return manager;
    }

    @Test
    void everyReplicaDerivesTheIdenticalRotationFromTheSamePopulation() {
        List<Member> pop = members(9);
        List<Member> committee = pop.subList(0, 4);
        List<NodeId> population = pop.stream().map(Member::id).toList();

        CommitteeManager.ChangeProposal first =
                managerWith(committee).draftRotation(region, population, 4);
        CommitteeManager.ChangeProposal second =
                managerWith(committee).draftRotation(region, population, 4);
        assertThat(second.next())
                .as("rotation is a pure function of (region, epoch, population)")
                .isEqualTo(first.next());
        assertThat(first.next().epoch()).isEqualTo(new RegionEpoch(1));
        assertThat(first.next().quorumThreshold()).isEqualTo(3);
        assertThat(first.next().size()).isEqualTo(4);

        // Shuffled population order changes nothing — ranking is by hash, not list position.
        List<NodeId> reversed = new ArrayList<>(population);
        java.util.Collections.reverse(reversed);
        assertThat(managerWith(committee).draftRotation(region, reversed, 4).next())
                .isEqualTo(first.next());
    }

    @Test
    void successiveEpochsReshuffleTheSeatsSoNoCommitteeSitsForever() {
        List<Member> pop = members(10);
        List<Member> committee = pop.subList(0, 4);
        List<NodeId> population = pop.stream().map(Member::id).toList();
        CommitteeManager manager = managerWith(committee);
        Map<NodeId, Bytes> allKeys = new HashMap<>();
        for (Member m : pop) {
            allKeys.put(m.id(), m.key());
        }
        Map<NodeId, Member> byId = new HashMap<>();
        for (Member m : pop) {
            byId.put(m.id(), m);
        }

        Set<Set<NodeId>> distinctCommittees = new HashSet<>();
        for (int round = 0; round < 6; round++) {
            RegionCommittee current = manager.committee(region).orElseThrow();
            CommitteeManager.ChangeProposal rotation =
                    manager.draftRotation(region, population, 4);
            // The OLD committee's quorum approves the rotation (any 3 of its 4 members).
            List<CommitteeChangeCertificate.Approval> approvals = new ArrayList<>();
            approvals.add(CommitteeManager.approve(rotation, byId.get(current.primary()).identity()));
            approvals.add(CommitteeManager.approve(rotation,
                    byId.get(current.validators().get(0)).identity()));
            approvals.add(CommitteeManager.approve(rotation,
                    byId.get(current.validators().get(1)).identity()));
            CommitteeChangeCertificate cert = manager.certify(rotation, approvals);
            manager.apply(cert, allKeys);
            RegionCommittee installed = manager.committee(region).orElseThrow();
            Set<NodeId> seats = new HashSet<>(installed.validators());
            seats.add(installed.primary());
            distinctCommittees.add(seats);
        }
        assertThat(distinctCommittees.size())
                .as("the epoch input reshuffles the seats — tenure is bounded")
                .isGreaterThan(1);
        assertThat(manager.committee(region).orElseThrow().epoch().value()).isEqualTo(6);
    }

    @Test
    void rotationRefusesThinPopulationsAndUnderQuorumApprovalsCannotInstall() {
        List<Member> pop = members(6);
        List<Member> committee = pop.subList(0, 4);
        CommitteeManager manager = managerWith(committee);

        assertThatThrownBy(() -> manager.draftRotation(region,
                List.of(pop.get(0).id(), pop.get(1).id(), pop.get(2).id()), 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct candidates");

        CommitteeManager.ChangeProposal rotation = manager.draftRotation(
                region, pop.stream().map(Member::id).toList(), 4);
        assertThatThrownBy(() -> manager.certify(rotation, List.of(
                CommitteeManager.approve(rotation, committee.get(0).identity()),
                CommitteeManager.approve(rotation, committee.get(1).identity()))))
                .as("2 approvals of a 3-of-4 committee can never install the rotation")
                .isInstanceOf(IllegalStateException.class);
    }
}
