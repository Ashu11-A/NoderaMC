package dev.nodera.coordinator.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.StateRoot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-process source/target committee approval provider used by hosts and deterministic tests. */
public final class JointTransferApprover
        implements EntityTransferCoordinator.TransferApprovalProvider {

    private static final HashService HASHES = new HashService();

    private final List<NodeIdentity> sourceMembers;
    private final List<NodeIdentity> targetMembers;
    private final int sourceRequired;
    private final int targetRequired;
    private final Map<NodeId, Bytes> sourceKeys;
    private final Map<NodeId, Bytes> targetKeys;

    public JointTransferApprover(
            List<NodeIdentity> sourceMembers,
            int sourceRequired,
            List<NodeIdentity> targetMembers,
            int targetRequired) {
        this.sourceMembers = members(sourceMembers, sourceRequired, "source");
        this.targetMembers = members(targetMembers, targetRequired, "target");
        this.sourceRequired = sourceRequired;
        this.targetRequired = targetRequired;
        this.sourceKeys = keys(this.sourceMembers);
        this.targetKeys = keys(this.targetMembers);
    }

    /** Standard Task-12 delegated-to-delegated profile: independent 2-of-3 committees. */
    public static JointTransferApprover mvp(
            List<NodeIdentity> sourceMembers, List<NodeIdentity> targetMembers) {
        return new JointTransferApprover(sourceMembers, 2, targetMembers, 2);
    }

    @Override
    public EntityTransferCertificate approve(
            EntityTransferDescriptor descriptor,
            RegionDelta sourceDelta,
            RegionDelta targetDelta) {
        if (!descriptor.sourceTransitionRoot().equals(StateRoot.of(HASHES.hash(sourceDelta)))
                || !descriptor.targetTransitionRoot().equals(StateRoot.of(HASHES.hash(targetDelta)))) {
            throw new IllegalArgumentException("transfer deltas do not match approval descriptor");
        }
        StateRoot approvalRoot = StateRoot.of(HASHES.hash(descriptor));
        QuorumCertificate sourceProof = proof(descriptor, approvalRoot, sourceMembers, true);
        QuorumCertificate targetProof = proof(descriptor, approvalRoot, targetMembers, false);
        EntityTransferCertificate certificate =
                new EntityTransferCertificate(descriptor, sourceProof, targetProof);
        if (!verify(certificate)) {
            throw new IllegalStateException("locally produced transfer certificate did not verify");
        }
        return certificate;
    }

    @Override
    public boolean verify(EntityTransferCertificate certificate) {
        return certificate.verify(sourceKeys, sourceRequired, targetKeys, targetRequired);
    }

    private static QuorumCertificate proof(
            EntityTransferDescriptor descriptor,
            StateRoot approvalRoot,
            List<NodeIdentity> members,
            boolean source) {
        var region = source ? descriptor.sourceRegion() : descriptor.targetRegion();
        var epoch = source ? descriptor.sourceEpoch() : descriptor.targetEpoch();
        var base = source ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion();
        var prevRoot = source ? descriptor.sourcePrevRoot() : descriptor.targetPrevRoot();
        var resultingRoot = source
                ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot();
        var transitionRoot = source
                ? descriptor.sourceTransitionRoot() : descriptor.targetTransitionRoot();
        List<SignedVote> votes = members.stream().map(identity -> {
            SignedVote unsigned = new SignedVote(
                    identity.nodeId(), region, epoch, base, approvalRoot,
                    resultingRoot, transitionRoot, VoteDecision.ACCEPT, Bytes.empty());
            return new SignedVote(
                    identity.nodeId(), region, epoch, base, approvalRoot,
                    resultingRoot, transitionRoot, VoteDecision.ACCEPT,
                    identity.sign(unsigned.signedPortion()));
        }).toList();
        return new QuorumCertificate(region, epoch, base, prevRoot, resultingRoot, votes);
    }

    private static List<NodeIdentity> members(
            List<NodeIdentity> members, int required, String side) {
        if (members == null || required < 2 || members.size() < required) {
            throw new IllegalArgumentException(side + " committee cannot reach transfer quorum");
        }
        Map<NodeId, NodeIdentity> unique = new LinkedHashMap<>();
        for (NodeIdentity member : members) {
            if (member == null || unique.putIfAbsent(member.nodeId(), member) != null) {
                throw new IllegalArgumentException(side + " committee contains null/duplicate member");
            }
        }
        return List.copyOf(unique.values());
    }

    private static Map<NodeId, Bytes> keys(List<NodeIdentity> members) {
        Map<NodeId, Bytes> keys = new LinkedHashMap<>();
        for (NodeIdentity member : members) {
            keys.put(member.nodeId(), member.publicKeyBytes());
        }
        return Map.copyOf(keys);
    }
}
