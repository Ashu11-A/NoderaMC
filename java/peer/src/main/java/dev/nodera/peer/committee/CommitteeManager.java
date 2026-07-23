package dev.nodera.peer.committee;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.consensuscert.CommitteeChangeCertificate;
import dev.nodera.core.consensuscert.CommitteeChangeCertificate.Approval;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionCommittee;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The authority-free committee membership state machine (Task 9). The coordinator (or any member)
 * only <i>nominates</i> the next committee; installing it requires a
 * {@link CommitteeChangeCertificate} whose approvals meet the OLD committee's quorum — so no
 * single party, server included, can rotate members unilaterally. The manager tracks, per region,
 * the currently certified committee plus its members' public keys, validates each change against
 * that state, and can {@linkplain #verifyChain walk} a whole certificate chain from a trusted
 * genesis committee (every epoch bump verifies against its predecessor's keys — Task 9
 * acceptance #4).
 *
 * <p><b>Degraded mode.</b> The peer era runs 4-member committees with a 3-of-4 quorum
 * ({@code NoderaConstants.QUORUM_PEER}); when the population cannot staff 4 members, a drafted
 * committee falls back to the 2-of-3 MVP policy and the region is flagged
 * {@linkplain #isDegraded degraded} — visible, never silent.
 *
 * @Thread-context confined to the owning peer thread; not thread-safe.
 */
public final class CommitteeManager {

    /** A nominated (not yet certified) next committee. */
    public record ChangeProposal(RegionId region, RegionEpoch prevEpoch, RegionCommittee next) {
        public ChangeProposal {
            if (region == null || prevEpoch == null || next == null) {
                throw new IllegalArgumentException("no field of ChangeProposal may be null");
            }
        }

        /** The certificate skeleton this proposal certifies into (no approvals yet). */
        public CommitteeChangeCertificate unsigned() {
            return new CommitteeChangeCertificate(
                    region, prevEpoch, next.epoch(), next, List.of());
        }
    }

    private final SignatureService signatures;
    private final Map<RegionId, RegionCommittee> current = new HashMap<>();
    private final Map<RegionId, Map<NodeId, Bytes>> memberKeys = new HashMap<>();

    public CommitteeManager(SignatureService signatures) {
        if (signatures == null) {
            throw new IllegalArgumentException("signatures must not be null");
        }
        this.signatures = signatures;
    }

    /**
     * Install a trusted starting committee (genesis path: the documented single-signer trust root,
     * L-20). Later changes must be certified.
     */
    public void install(RegionCommittee committee, Map<NodeId, Bytes> keys) {
        requireKeysCover(committee, keys);
        current.put(committee.region(), committee);
        memberKeys.put(committee.region(), Map.copyOf(keys));
    }

    /** The currently certified committee for {@code region}. */
    public Optional<RegionCommittee> committee(RegionId region) {
        return Optional.ofNullable(current.get(region));
    }

    /**
     * True when {@code region}'s committee is below the full peer-era size. Derived from the
     * certified committee itself, never a static flag (L-19): every sizing transition — shrink on
     * loss, growth on population return via {@link #draftResize} — flows through a certified
     * reconfiguration, and this verdict simply reads the certified result.
     */
    public boolean isDegraded(RegionId region) {
        RegionCommittee committee = current.get(region);
        return committee != null && committee.size() < NoderaConstants.QUORUM_PEER_SIZE;
    }

    /**
     * Draft a certified RESIZE of {@code region}'s committee to exactly {@code members} (first
     * member is the primary): the dynamic-sizing path that replaces the static degraded mode —
     * a degraded committee grows back to full strength the moment the population returns, and
     * any shrink below full strength is equally explicit and certified. Quorum of the NEW
     * committee is a strict majority of its size ({@code size/2 + 1}); installing the result
     * still requires the OLD committee's quorum of approvals ({@link #certify}).
     *
     * @throws IllegalArgumentException if fewer than {@code QUORUM_MVP_SIZE} members are given
     *                                  or members repeat.
     */
    public ChangeProposal draftResize(RegionId region, List<NodeId> members) {
        RegionCommittee old = requireCurrent(region);
        if (members == null || members.size() < NoderaConstants.QUORUM_MVP_SIZE) {
            throw new IllegalArgumentException("a committee needs at least "
                    + NoderaConstants.QUORUM_MVP_SIZE + " members");
        }
        if (members.stream().distinct().count() != members.size()) {
            throw new IllegalArgumentException("committee members must be distinct");
        }
        NodeId primary = members.get(0);
        List<NodeId> validators = new ArrayList<>(members.subList(1, members.size()));
        int quorum = members.size() / 2 + 1;
        RegionCommittee next = new RegionCommittee(region,
                new RegionEpoch(old.epoch().value() + 1), primary, validators, quorum);
        return propose(next);
    }

    /** Nominate {@code next} as the region's next committee (epoch must be current + 1). */
    public ChangeProposal propose(RegionCommittee next) {
        RegionCommittee old = requireCurrent(next.region());
        if (next.epoch().value() != old.epoch().value() + 1) {
            throw new IllegalArgumentException("proposal must bump exactly one epoch: "
                    + old.epoch().value() + " -> " + next.epoch().value());
        }
        return new ChangeProposal(next.region(), old.epoch(), next);
    }

    /**
     * Draft a replacement committee after losing {@code lost}: the survivors keep their seats,
     * {@code replacement} takes the vacated one (promoting a validator to primary when the primary
     * died), and the epoch bumps once. Falls back to a degraded 2-of-3 committee when no
     * replacement exists ({@code replacement == null}) and only three members survive.
     */
    public ChangeProposal draftReplacement(RegionId region, NodeId lost, NodeId replacement) {
        RegionCommittee old = requireCurrent(region);
        if (!old.isMember(lost)) {
            throw new IllegalArgumentException(lost + " is not a member of " + region);
        }
        List<NodeId> survivors = new ArrayList<>();
        if (!old.primary().equals(lost)) {
            survivors.add(old.primary());
        }
        for (NodeId v : old.validators()) {
            if (!v.equals(lost)) {
                survivors.add(v);
            }
        }
        if (replacement != null) {
            survivors.add(replacement);
        }
        if (survivors.size() < NoderaConstants.QUORUM_MVP_SIZE) {
            throw new IllegalStateException("cannot staff even a degraded committee for " + region
                    + ": only " + survivors.size() + " members remain");
        }
        NodeId primary = old.primary().equals(lost) ? survivors.get(0) : old.primary();
        List<NodeId> validators = new ArrayList<>(survivors);
        validators.remove(primary);
        boolean full = survivors.size() >= NoderaConstants.QUORUM_PEER_SIZE;
        int quorum = full ? NoderaConstants.QUORUM_PEER_REQUIRED : NoderaConstants.QUORUM_MVP_REQUIRED;
        RegionCommittee next = new RegionCommittee(region,
                new RegionEpoch(old.epoch().value() + 1), primary, validators, quorum);
        return propose(next);
    }

    /** An old-committee member's approval: their signature over the certificate's signed portion. */
    public static Approval approve(ChangeProposal proposal, NodeIdentity member) {
        return new Approval(member.nodeId(), member.sign(proposal.unsigned().signedPortion()));
    }

    /**
     * Certify {@code proposal} with {@code approvals}. Approvals must come from distinct OLD
     * committee members, verify against their registered keys, and meet the old quorum threshold.
     *
     * @throws IllegalStateException if the approvals do not meet the old committee's quorum.
     */
    public CommitteeChangeCertificate certify(ChangeProposal proposal, List<Approval> approvals) {
        RegionCommittee old = requireCurrent(proposal.region());
        CommitteeChangeCertificate cert = new CommitteeChangeCertificate(
                proposal.region(), proposal.prevEpoch(), proposal.next().epoch(),
                proposal.next(), approvals);
        int valid = cert.validApprovals(signatures, old, memberKeys.get(proposal.region()));
        if (valid < old.quorumThreshold()) {
            throw new IllegalStateException("committee change for " + proposal.region()
                    + " has " + valid + " valid old-committee approvals; quorum is "
                    + old.quorumThreshold());
        }
        return cert;
    }

    /**
     * Apply a certified change: validates the certificate against the CURRENT committee (epoch
     * step + approvals against current member keys) and installs the new committee with
     * {@code newKeys}.
     */
    public void apply(CommitteeChangeCertificate certificate, Map<NodeId, Bytes> newKeys) {
        RegionCommittee old = requireCurrent(certificate.region());
        if (certificate.prevEpoch().value() != old.epoch().value()) {
            throw new IllegalStateException("certificate prevEpoch " + certificate.prevEpoch().value()
                    + " does not match current epoch " + old.epoch().value());
        }
        if (!certificate.approvedBy(signatures, old, memberKeys.get(certificate.region()))) {
            throw new IllegalStateException("certificate approvals do not meet the old committee's "
                    + "quorum for " + certificate.region());
        }
        install(certificate.newCommittee(), newKeys);
    }

    /**
     * Walk a certificate chain from a trusted genesis committee (Task 9 acceptance #4): every
     * certificate's approvals must verify against the committee the previous link installed.
     *
     * @param keysByEpoch each epoch's member public keys, genesis included.
     * @return the final committee if the whole chain verifies.
     * @throws IllegalStateException at the first link that fails.
     */
    public static RegionCommittee verifyChain(SignatureService signatures,
                                              RegionCommittee genesis,
                                              Map<Long, Map<NodeId, Bytes>> keysByEpoch,
                                              List<CommitteeChangeCertificate> chain) {
        RegionCommittee committee = genesis;
        for (CommitteeChangeCertificate cert : chain) {
            if (cert.prevEpoch().value() != committee.epoch().value()) {
                throw new IllegalStateException("chain break at epoch " + cert.prevEpoch().value()
                        + ": expected " + committee.epoch().value());
            }
            Map<NodeId, Bytes> keys = keysByEpoch.get(committee.epoch().value());
            if (keys == null || !cert.approvedBy(signatures, committee, keys)) {
                throw new IllegalStateException("approvals at epoch " + cert.newEpoch().value()
                        + " do not verify against the previous committee");
            }
            committee = cert.newCommittee();
        }
        return committee;
    }

    private RegionCommittee requireCurrent(RegionId region) {
        RegionCommittee committee = current.get(region);
        if (committee == null) {
            throw new IllegalStateException("no committee installed for " + region);
        }
        return committee;
    }

    private static void requireKeysCover(RegionCommittee committee, Map<NodeId, Bytes> keys) {
        if (keys == null) {
            throw new IllegalArgumentException("keys must not be null");
        }
        if (!keys.containsKey(committee.primary())) {
            throw new IllegalArgumentException("missing public key for primary " + committee.primary());
        }
        for (NodeId v : committee.validators()) {
            if (!keys.containsKey(v)) {
                throw new IllegalArgumentException("missing public key for validator " + v);
            }
        }
    }
}
