package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EntityTransferCertificateTest {

    private static final RegionId SOURCE = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionId TARGET = new RegionId(DimensionKey.overworld(), 1, 0);
    private static final RegionEpoch SOURCE_EPOCH = new RegionEpoch(3);
    private static final RegionEpoch TARGET_EPOCH = new RegionEpoch(4);
    private static final List<NodeIdentity> SOURCE_MEMBERS = members();
    private static final List<NodeIdentity> TARGET_MEMBERS = members();

    @Test
    void jointCertificateRoundTripsAndVerifiesBothCommittees() {
        EntityTransferCertificate certificate = certificate(descriptor());
        CanonicalWriter w = new CanonicalWriter();
        certificate.encode(w);
        EntityTransferCertificate decoded =
                EntityTransferCertificate.decode(new CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(certificate);
        assertThat(decoded.verify(keys(SOURCE_MEMBERS), 2, keys(TARGET_MEMBERS), 2)).isTrue();
        assertThat(decoded.verify(Map.of(), 2, keys(TARGET_MEMBERS), 2)).isFalse();
    }

    @Test
    void tamperingDescriptorInvalidatesExistingProofs() {
        EntityTransferCertificate signed = certificate(descriptor());
        EntityTransferDescriptor tampered = new EntityTransferDescriptor(
                signed.descriptor().transferId(), SOURCE, TARGET, SOURCE_EPOCH, TARGET_EPOCH,
                signed.descriptor().entityId(), new SnapshotVersion(0), new SnapshotVersion(1),
                root(1), StateRoot.zero(), root(3), new SnapshotVersion(3), new SnapshotVersion(4),
                root(2), root(4), root(5), 20);
        assertThatThrownBy(() -> new EntityTransferCertificate(
                tampered, signed.sourceProof(), signed.targetProof()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proof");
    }

    @Test
    void proofRequiresTwoDistinctMatchingAcceptVotes() {
        EntityTransferDescriptor descriptor = descriptor();
        StateRoot approvalRoot = StateRoot.of(new HashService().hash(descriptor));
        SignedVote lone = vote(SOURCE_MEMBERS.getFirst(), descriptor, approvalRoot, true);
        QuorumCertificate loneProof = new QuorumCertificate(
                SOURCE, SOURCE_EPOCH, descriptor.sourceBaseVersion(),
                descriptor.sourcePrevRoot(), descriptor.sourceResultingRoot(), List.of(lone));
        assertThatThrownBy(() -> new EntityTransferCertificate(
                descriptor, loneProof, proof(descriptor, approvalRoot, TARGET_MEMBERS, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    void descriptorRejectsSameRegionAndNonAdjacentVersions() {
        assertThatThrownBy(() -> descriptor(SOURCE, new SnapshotVersion(1), new SnapshotVersion(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regions");
        assertThatThrownBy(() -> descriptor(TARGET, new SnapshotVersion(2), new SnapshotVersion(4)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(() -> descriptor(TARGET, new SnapshotVersion(1), new SnapshotVersion(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");
    }

    @Test
    void wrongTagIsRejected() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(999).writeU16(1);
        assertThatThrownBy(() -> EntityTransferCertificate.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class);
    }

    private static EntityTransferCertificate certificate(EntityTransferDescriptor descriptor) {
        StateRoot approvalRoot = StateRoot.of(new HashService().hash(descriptor));
        return new EntityTransferCertificate(
                descriptor,
                proof(descriptor, approvalRoot, SOURCE_MEMBERS, true),
                proof(descriptor, approvalRoot, TARGET_MEMBERS, false));
    }

    private static QuorumCertificate proof(
            EntityTransferDescriptor descriptor,
            StateRoot approvalRoot,
            List<NodeIdentity> members,
            boolean source) {
        List<SignedVote> votes = members.stream()
                .map(member -> vote(member, descriptor, approvalRoot, source))
                .toList();
        return new QuorumCertificate(
                source ? descriptor.sourceRegion() : descriptor.targetRegion(),
                source ? descriptor.sourceEpoch() : descriptor.targetEpoch(),
                source ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion(),
                source ? descriptor.sourcePrevRoot() : descriptor.targetPrevRoot(),
                source ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot(),
                votes);
    }

    private static SignedVote vote(
            NodeIdentity member,
            EntityTransferDescriptor descriptor,
            StateRoot approvalRoot,
            boolean source) {
        var region = source ? descriptor.sourceRegion() : descriptor.targetRegion();
        var epoch = source ? descriptor.sourceEpoch() : descriptor.targetEpoch();
        var base = source ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion();
        var result = source ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot();
        var transition = source
                ? descriptor.sourceTransitionRoot() : descriptor.targetTransitionRoot();
        SignedVote unsigned = new SignedVote(
                member.nodeId(), region, epoch, base, approvalRoot,
                result, transition, VoteDecision.ACCEPT, Bytes.empty());
        return new SignedVote(
                member.nodeId(), region, epoch, base, approvalRoot,
                result, transition, VoteDecision.ACCEPT,
                member.sign(unsigned.signedPortion()));
    }

    private static EntityTransferDescriptor descriptor() {
        return descriptor(TARGET, new SnapshotVersion(1), new SnapshotVersion(4));
    }

    private static EntityTransferDescriptor descriptor(
            RegionId target, SnapshotVersion sourceResult, SnapshotVersion targetResult) {
        return new EntityTransferDescriptor(
                7, SOURCE, target, SOURCE_EPOCH, TARGET_EPOCH, new NetworkEntityId(2),
                new SnapshotVersion(0), sourceResult, root(1), root(2), root(3),
                new SnapshotVersion(3), targetResult, root(4), root(5), root(6), 20);
    }

    private static List<NodeIdentity> members() {
        return List.of(NodeIdentity.generate(), NodeIdentity.generate(), NodeIdentity.generate());
    }

    private static Map<NodeId, Bytes> keys(List<NodeIdentity> members) {
        return Map.of(
                members.get(0).nodeId(), members.get(0).publicKeyBytes(),
                members.get(1).nodeId(), members.get(1).publicKeyBytes(),
                members.get(2).nodeId(), members.get(2).publicKeyBytes());
    }

    private static StateRoot root(int fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) fill);
        return StateRoot.of(Bytes.unsafeWrap(bytes));
    }
}
