package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.CustodyClass;
import dev.nodera.core.region.RegionId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Turns {@code custody: FULL} from a claim into something falsifiable (server task 3, L-62).
 *
 * <p>Before this class an endpoint advertising {@code FULL} was simply believed, so a node that had
 * silently lost half its world still read as a complete replica to the swarm. The audit samples one
 * region at random out of the regions the claim covers, asks the subject for that region's head
 * plus an inclusion proof, and verifies the answer against the root the subject itself advertised.
 * Everything it needs is the advertised root and the answer — the auditor never has to hold the
 * world it is checking.
 *
 * <p><b>A failure downgrades, it does not evict.</b> The outcome of a failed check is
 * {@link CustodyClass#VIEW} plus a reason naming the region, and the subject keeps playing: the
 * world stays available and only the claim is withdrawn. Anything harsher would make a transient
 * loss into an outage, which is exactly the trade this project refuses everywhere else.
 *
 * <p>A subject claiming {@link CustodyClass#VIEW} is not audited: {@code VIEW} promises nothing, so
 * there is nothing to falsify, and the outcome is the claim unchanged.
 *
 * <p>Thread-context: immutable and safe from any thread; the {@link Responder} it is handed decides
 * its own threading.
 */
public final class CustodyAudit {

    /**
     * The audited node's side of a spot-check: given a region, produce the head it claims and the
     * inclusion proof for it.
     *
     * <p>An honest node answers from its own {@link CustodyDigest}. A node that has lost the region
     * returns {@link Optional#empty()} — and so does an honest node that is asked about a region it
     * never claimed, which is why the auditor only ever samples regions the claim covers.
     */
    @FunctionalInterface
    public interface Responder {

        /**
         * @param region the sampled region.
         * @return the proof, or empty when the node cannot answer for that region.
         * @Thread-context implementation-defined; the audit calls it on the auditing thread.
         */
        Optional<CustodyDigest.Proof> proofFor(RegionId region);
    }

    /**
     * What one spot-check concluded.
     *
     * @param subject   the audited node.
     * @param claimed   the custody class the subject advertised.
     * @param effective the class the swarm should use now — equal to {@code claimed} when the check
     *                  passed, {@link CustodyClass#VIEW} when it failed.
     * @param sampled   the region that was sampled, absent when nothing was audited.
     * @param reason    a human-readable sentence for the log; names the region on a failure.
     */
    public record Outcome(
            NodeId subject,
            CustodyClass claimed,
            CustodyClass effective,
            Optional<RegionId> sampled,
            String reason) {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if an argument is null.
         */
        public Outcome {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(claimed, "claimed");
            Objects.requireNonNull(effective, "effective");
            Objects.requireNonNull(sampled, "sampled");
            Objects.requireNonNull(reason, "reason");
        }

        /** @return true when the claim was withdrawn. */
        public boolean downgraded() {
            return effective != claimed;
        }
    }

    private final Random sampler;
    private final HashService hasher = new HashService();

    /**
     * @param sampler the source of the random region choice. Seeded in tests; an audit that always
     *                sampled the same region would be a checkbox rather than a check.
     * @throws IllegalArgumentException if {@code sampler} is null.
     * @Thread-context any thread (construction only).
     */
    public CustodyAudit(Random sampler) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    /**
     * Spot-check one node's custody claim.
     *
     * @param subject        the node being audited.
     * @param claimed        the custody class it advertised.
     * @param advertisedRoot the {@link CustodyDigest} root it advertised.
     * @param claimedRegions every region the claim covers — for {@code FULL}, the whole world's
     *                       region set as the auditor knows it, NOT a set the subject supplied
     *                       (a liar would simply omit what it lost).
     * @param responder      the subject's answering side.
     * @return the outcome; {@link CustodyClass#VIEW} on any missing, mismatched, or unprovable
     *         answer.
     * @throws IllegalArgumentException if a reference argument is null.
     * @Thread-context any thread.
     */
    public Outcome spotCheck(
            NodeId subject,
            CustodyClass claimed,
            Bytes advertisedRoot,
            List<RegionId> claimedRegions,
            Responder responder) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(advertisedRoot, "advertisedRoot");
        Objects.requireNonNull(claimedRegions, "claimedRegions");
        Objects.requireNonNull(responder, "responder");

        if (claimed != CustodyClass.FULL) {
            return new Outcome(subject, claimed, claimed, Optional.empty(),
                    "custody VIEW claims nothing, so there is nothing to spot-check");
        }
        if (claimedRegions.isEmpty()) {
            return new Outcome(subject, claimed, CustodyClass.VIEW, Optional.empty(),
                    "custody FULL over an empty region set is a claim about no world; downgraded to VIEW");
        }

        RegionId sampled = claimedRegions.get(sampler.nextInt(claimedRegions.size()));
        Optional<CustodyDigest.Proof> answer = responder.proofFor(sampled);
        if (answer.isEmpty()) {
            return downgrade(subject, sampled,
                    "advertised custody FULL but could not answer for " + sampled);
        }
        CustodyDigest.Proof proof = answer.get();
        if (!proof.region().equals(sampled)) {
            return downgrade(subject, sampled,
                    "answered for " + proof.region() + " when asked about " + sampled);
        }
        if (!CustodyDigest.verify(advertisedRoot, proof, hasher)) {
            return downgrade(subject, sampled,
                    "proof for " + sampled + " does not reconstruct its advertised custody root");
        }
        return new Outcome(subject, claimed, claimed, Optional.of(sampled),
                "custody FULL verified against a random spot-check of " + sampled);
    }

    private static Outcome downgrade(NodeId subject, RegionId sampled, String reason) {
        // The world stays available: only the claim is withdrawn, never the node.
        return new Outcome(subject, CustodyClass.FULL, CustodyClass.VIEW, Optional.of(sampled),
                reason + " — downgraded to VIEW, the node keeps serving what it has");
    }
}
