package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.simulationmsg.EntityTransferAccept;
import dev.nodera.protocol.simulationmsg.EntityTransferCommit;
import dev.nodera.protocol.simulationmsg.EntityTransferPrepare;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class EntityTransferMessageCodecTest {

    private static final HashService HASHES = new HashService();
    private static final RegionId SOURCE = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionId TARGET = new RegionId(DimensionKey.overworld(), 1, 0);
    private static final RegionEpoch SOURCE_EPOCH = new RegionEpoch(1);
    private static final RegionEpoch TARGET_EPOCH = new RegionEpoch(2);

    @Test
    void prepareAcceptAndCommitRoundTripByteExactly() {
        Fixture fixture = fixture();
        EntityTransferPrepare prepare = new EntityTransferPrepare(
                fixture.descriptor, fixture.sourceDelta, fixture.targetDelta);
        EntityTransferAccept accept = new EntityTransferAccept(
                fixture.descriptor.transferId(), SOURCE,
                fixture.certificate.sourceProof().votes().getFirst());
        EntityTransferCommit commit = new EntityTransferCommit(
                fixture.certificate, fixture.certificate.sourceProof(),
                fixture.sourceDelta, fixture.targetDelta);
        for (NoderaMessage message : List.of(prepare, accept, commit)) {
            byte[] encoded = MessageCodec.encode(message);
            NoderaMessage decoded = MessageCodec.decode(encoded);
            assertThat(decoded).isEqualTo(message);
            assertThat(MessageCodec.encode(decoded)).containsExactly(encoded);
        }
        assertThat(MessageCodec.typeTagOf(prepare))
                .isEqualTo(MessageCodec.TAG_ENTITY_TRANSFER_PREPARE);
        assertThat(MessageCodec.typeTagOf(accept))
                .isEqualTo(MessageCodec.TAG_ENTITY_TRANSFER_ACCEPT);
        assertThat(MessageCodec.typeTagOf(commit))
                .isEqualTo(MessageCodec.TAG_ENTITY_TRANSFER_COMMIT);
    }

    private static Fixture fixture() {
        PersistedEntityState sourceEntity = entity(FixedVec3.ofBlock(127, 5, 1));
        PersistedEntityState targetEntity = entity(FixedVec3.ofBlock(128, 5, 1));
        SnapshotVersion base = SnapshotVersion.INITIAL;
        SnapshotVersion next = base.next();
        RegionDelta sourceDelta = new RegionDelta(
                SOURCE, base, next, List.of(), root(2),
                List.of(new EntityMutation(sourceEntity.id(), sourceEntity, null)), List.of(),
                List.of(new EntityTransferIntent(TARGET, targetEntity)));
        RegionDelta targetDelta = new RegionDelta(
                TARGET, base, next, List.of(), root(4),
                List.of(new EntityMutation(targetEntity.id(), null, targetEntity)), List.of());
        EntityTransferDescriptor descriptor = new EntityTransferDescriptor(
                7, SOURCE, TARGET, SOURCE_EPOCH, TARGET_EPOCH, sourceEntity.id(),
                base, next, root(1), sourceDelta.resultingRoot(),
                StateRoot.of(HASHES.hash(sourceDelta)), base, next, root(3),
                targetDelta.resultingRoot(), StateRoot.of(HASHES.hash(targetDelta)), 50);
        StateRoot approvalRoot = StateRoot.of(HASHES.hash(descriptor));
        QuorumCertificate sourceProof = proof(descriptor, approvalRoot, true);
        QuorumCertificate targetProof = proof(descriptor, approvalRoot, false);
        return new Fixture(descriptor, sourceDelta, targetDelta,
                new EntityTransferCertificate(descriptor, sourceProof, targetProof));
    }

    private static QuorumCertificate proof(
            EntityTransferDescriptor descriptor, StateRoot approvalRoot, boolean source) {
        var members = List.of(NodeIdentity.generate(), NodeIdentity.generate(), NodeIdentity.generate());
        var region = source ? descriptor.sourceRegion() : descriptor.targetRegion();
        var epoch = source ? descriptor.sourceEpoch() : descriptor.targetEpoch();
        var base = source ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion();
        var previous = source ? descriptor.sourcePrevRoot() : descriptor.targetPrevRoot();
        var result = source ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot();
        var transition = source
                ? descriptor.sourceTransitionRoot() : descriptor.targetTransitionRoot();
        List<SignedVote> votes = members.stream().map(member -> {
            SignedVote unsigned = new SignedVote(
                    member.nodeId(), region, epoch, base, approvalRoot,
                    result, transition, VoteDecision.ACCEPT, Bytes.empty());
            return new SignedVote(
                    member.nodeId(), region, epoch, base, approvalRoot,
                    result, transition, VoteDecision.ACCEPT,
                    member.sign(unsigned.signedPortion()));
        }).toList();
        return new QuorumCertificate(region, epoch, base, previous, result, votes);
    }

    private static PersistedEntityState entity(FixedVec3 position) {
        return new PersistedEntityState(
                new NetworkEntityId(9), EntityKind.ITEM, 4, position, FixedVec3.ZERO,
                2, 6_000, Bytes.unsafeWrap(new byte[]{0, 0, 0, 4, 1}));
    }

    private static StateRoot root(int fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) fill);
        return StateRoot.of(Bytes.unsafeWrap(bytes));
    }

    private record Fixture(
            EntityTransferDescriptor descriptor,
            RegionDelta sourceDelta,
            RegionDelta targetDelta,
            EntityTransferCertificate certificate) {
    }
}
