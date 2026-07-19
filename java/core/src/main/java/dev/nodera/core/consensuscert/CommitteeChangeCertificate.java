package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionCommittee;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proof that a region's committee changed with its predecessor's consent (Task 9 — committee
 * changes are certified by the OLD committee, so the coordinator only <i>nominates</i>; it cannot
 * install members unilaterally). Carries the {@code prevEpoch → newEpoch} step (always exactly one
 * epoch), the full new committee, and the old-committee {@link Approval}s over
 * {@link #signedPortion()}. A peer syncs committee history by walking the chain: each
 * certificate's approvals must verify against the members of the committee the PREVIOUS
 * certificate installed.
 *
 * <p>Wire form: {@code signedPortion()} || {@code [list Approval(nodeId + bytes)]}. The approvals
 * list is canonical: sorted by approver id, unmodifiable.
 *
 * @Thread-context immutable, any thread.
 */
public record CommitteeChangeCertificate(
        RegionId region,
        RegionEpoch prevEpoch,
        RegionEpoch newEpoch,
        RegionCommittee newCommittee,
        List<Approval> approvals
) implements Encodable {

    /** One old-committee member's signature over {@link #signedPortion()}. */
    public record Approval(NodeId approver, Bytes signature) {
        public Approval {
            if (approver == null) {
                throw new IllegalArgumentException("approver must not be null");
            }
            if (signature == null) {
                throw new IllegalArgumentException("signature must not be null");
            }
        }
    }

    private static final Comparator<Approval> APPROVAL_ORDER =
            Comparator.comparing(a -> a.approver().value());

    /**
     * Compact constructor. Canonicalises the approvals list.
     *
     * @throws IllegalArgumentException if any argument is null, the epoch step is not exactly one,
     *         or the new committee's epoch disagrees with {@code newEpoch}.
     */
    public CommitteeChangeCertificate {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (prevEpoch == null) {
            throw new IllegalArgumentException("prevEpoch must not be null");
        }
        if (newEpoch == null) {
            throw new IllegalArgumentException("newEpoch must not be null");
        }
        if (newCommittee == null) {
            throw new IllegalArgumentException("newCommittee must not be null");
        }
        if (approvals == null) {
            throw new IllegalArgumentException("approvals must not be null");
        }
        if (newEpoch.value() != prevEpoch.value() + 1) {
            throw new IllegalArgumentException("committee change must bump exactly one epoch: "
                    + prevEpoch.value() + " -> " + newEpoch.value());
        }
        if (!newCommittee.epoch().equals(newEpoch)) {
            throw new IllegalArgumentException("newCommittee epoch " + newCommittee.epoch().value()
                    + " must equal newEpoch " + newEpoch.value());
        }
        if (!newCommittee.region().equals(region)) {
            throw new IllegalArgumentException("newCommittee region must equal certificate region");
        }
        List<Approval> sorted = new ArrayList<>(approvals);
        sorted.sort(APPROVAL_ORDER);
        approvals = List.copyOf(sorted);
    }

    /** The canonical bytes every approval signs: everything except the approvals themselves. */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        writeSignedFields(w);
        return w.toBytes();
    }

    private void writeSignedFields(CanonicalWriter w) {
        w.writeU16(TypeTags.COMMITTEE_CHANGE_CERT).writeU16(ENCODING_VERSION);
        region.encode(w);
        prevEpoch.encode(w);
        newEpoch.encode(w);
        newCommittee.encode(w);
    }

    @Override
    public void encode(CanonicalWriter w) {
        writeSignedFields(w);
        w.writeList(approvals, (ww, a) -> {
            a.approver().encode(ww);
            ww.writeBytes(a.signature());
        });
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code COMMITTEE_CHANGE_CERT}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static CommitteeChangeCertificate decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.COMMITTEE_CHANGE_CERT) {
            throw new IllegalStateException("expected COMMITTEE_CHANGE_CERT tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        RegionId region = RegionId.decode(r);
        RegionEpoch prevEpoch = RegionEpoch.decode(r);
        RegionEpoch newEpoch = RegionEpoch.decode(r);
        RegionCommittee newCommittee = RegionCommittee.decode(r);
        List<Approval> approvals = r.readList(rr -> {
            NodeId approver = NodeId.decode(rr);
            Bytes signature = rr.readBytesValue();
            return new Approval(approver, signature);
        });
        return new CommitteeChangeCertificate(region, prevEpoch, newEpoch, newCommittee, approvals);
    }

    /**
     * Count the approvals that (a) come from DISTINCT members of {@code oldCommittee} and
     * (b) verify against that member's public key over {@link #signedPortion()}.
     *
     * @param oldMemberKeys each old-committee member's X.509 public key bytes.
     */
    public int validApprovals(SignatureService signatures, RegionCommittee oldCommittee,
                              Map<NodeId, Bytes> oldMemberKeys) {
        Bytes signed = signedPortion();
        Set<NodeId> counted = new HashSet<>();
        for (Approval approval : approvals) {
            if (!oldCommittee.isMember(approval.approver()) || counted.contains(approval.approver())) {
                continue;
            }
            Bytes key = oldMemberKeys.get(approval.approver());
            if (key != null && signatures.verify(key, signed, approval.signature())) {
                counted.add(approval.approver());
            }
        }
        return counted.size();
    }

    /** True when {@link #validApprovals} meets the OLD committee's quorum threshold. */
    public boolean approvedBy(SignatureService signatures, RegionCommittee oldCommittee,
                              Map<NodeId, Bytes> oldMemberKeys) {
        return validApprovals(signatures, oldCommittee, oldMemberKeys)
                >= oldCommittee.quorumThreshold();
    }
}
