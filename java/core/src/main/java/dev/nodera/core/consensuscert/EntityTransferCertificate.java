package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.StateRoot;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Joint source/target committee proof for one Task-12 cross-region entity handoff. Every vote signs
 * the hash of the same {@link EntityTransferDescriptor}; each side independently reaches quorum on
 * its own exact transition root.
 *
 * @Thread-context immutable, any thread.
 */
public record EntityTransferCertificate(
        EntityTransferDescriptor descriptor,
        QuorumCertificate sourceProof,
        QuorumCertificate targetProof
) implements Encodable {

    private static final HashService HASHES = new HashService();

    public EntityTransferCertificate {
        if (descriptor == null || sourceProof == null || targetProof == null) {
            throw new IllegalArgumentException("descriptor and both quorum proofs must not be null");
        }
        validateProof(sourceProof, descriptor, true);
        validateProof(targetProof, descriptor, false);
    }

    /** Canonical root every source and target ACCEPT vote must carry as its batch anchor. */
    public StateRoot approvalRoot() {
        return StateRoot.of(HASHES.hash(descriptor));
    }

    /** Verify both quorum thresholds, memberships, and Ed25519 signatures. */
    public boolean verify(
            Map<NodeId, Bytes> sourceMemberKeys,
            int sourceRequired,
            Map<NodeId, Bytes> targetMemberKeys,
            int targetRequired) {
        return verifyProof(sourceProof, sourceMemberKeys, sourceRequired)
                && verifyProof(targetProof, targetMemberKeys, targetRequired);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ENTITY_TRANSFER_CERT).writeU16(ENCODING_VERSION);
        descriptor.encode(w);
        sourceProof.encode(w);
        targetProof.encode(w);
    }

    /** Decode one complete joint transfer certificate. */
    public static EntityTransferCertificate decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.ENTITY_TRANSFER_CERT) {
            throw new IllegalStateException("expected ENTITY_TRANSFER_CERT tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        return new EntityTransferCertificate(
                EntityTransferDescriptor.decode(r),
                QuorumCertificate.decode(r),
                QuorumCertificate.decode(r));
    }

    private static void validateProof(
            QuorumCertificate proof, EntityTransferDescriptor descriptor, boolean source) {
        var region = source ? descriptor.sourceRegion() : descriptor.targetRegion();
        var epoch = source ? descriptor.sourceEpoch() : descriptor.targetEpoch();
        var base = source ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion();
        var prevRoot = source ? descriptor.sourcePrevRoot() : descriptor.targetPrevRoot();
        var resultingRoot = source
                ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot();
        var transitionRoot = source
                ? descriptor.sourceTransitionRoot() : descriptor.targetTransitionRoot();
        StateRoot approvalRoot = StateRoot.of(HASHES.hash(descriptor));
        if (!proof.region().equals(region) || !proof.epoch().equals(epoch)
                || !proof.version().equals(base) || !proof.prevRoot().equals(prevRoot)
                || !proof.resultingRoot().equals(resultingRoot)) {
            throw new IllegalArgumentException("transfer quorum proof does not match descriptor");
        }
        if (proof.votes().size() < 2) {
            throw new IllegalArgumentException("transfer quorum proof requires at least two votes");
        }
        Set<NodeId> voters = new HashSet<>();
        for (SignedVote vote : proof.votes()) {
            if (!voters.add(vote.voter()) || vote.bodyVersion() < 3
                    || vote.decision() != VoteDecision.ACCEPT
                    || !vote.region().equals(region) || !vote.epoch().equals(epoch)
                    || !vote.baseVersion().equals(base) || !vote.batchRoot().equals(approvalRoot)
                    || !vote.resultingRoot().equals(resultingRoot)
                    || !vote.transitionRoot().equals(transitionRoot)) {
                throw new IllegalArgumentException("transfer vote does not match descriptor");
            }
        }
    }

    private static boolean verifyProof(
            QuorumCertificate proof, Map<NodeId, Bytes> memberKeys, int required) {
        if (memberKeys == null || required < 2 || proof.votes().size() < required) {
            return false;
        }
        SignatureService signatures = new SignatureService();
        int verified = 0;
        for (SignedVote vote : proof.votes()) {
            Bytes key = memberKeys.get(vote.voter());
            if (key == null || !signatures.verify(key, vote.signedPortion(), vote.signature())) {
                return false;
            }
            verified++;
        }
        return verified >= required;
    }
}
