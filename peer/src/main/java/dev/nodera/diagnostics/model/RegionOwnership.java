package dev.nodera.diagnostics.model;

import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

import java.util.List;
import java.util.Map;

/**
 * The regions a peer participates in, by role (Task 18).
 *
 * <p>Placeholder until Task 6's {@code LeaseManager} supplies a real
 * {@link dev.nodera.diagnostics.source.RegionOwnershipProvider}; the empty instance renders the
 * {@code UNASSIGNED} state (the zone geometry is real, the ownership is not — LIMITATIONS L-31).
 *
 * @param primary    regions this peer is primary of (renders {@code OWNED}).
 * @param validator  regions this peer validates (renders {@code VALIDATING}).
 * @param replica    regions this peer holds a replica of (renders {@code REPLICA}).
 * @param ownedChunks total chunks across the primary set (0 while placeholder).
 * @param leases     per-region epoch + lease-expiry tick (empty while placeholder).
 * @Thread-context immutable record, any thread.
 */
public record RegionOwnership(
        List<RegionId> primary,
        List<RegionId> validator,
        List<RegionId> replica,
        int ownedChunks,
        Map<RegionId, LeaseInfo> leases) {

    /**
     * How far a region's current head has got through consensus (issue #47.3).
     *
     * <p>The panel used to say nothing at all about certification, which read to players as a
     * permanent "unsigned". These four values are the honest distinction the issue asks for:
     * a region that <i>cannot</i> be co-signed at this population ({@link #SOLO}) is a different
     * fact from one that simply has not been co-signed yet ({@link #PENDING}).
     */
    public enum Certification {
        /** A quorum certificate with ≥2 distinct voters covers the replica's current head. */
        CERTIFIED,
        /** The committee can co-sign (≥2 members) but the current head carries no certificate yet. */
        PENDING,
        /**
         * The committee is this node alone: the head is self-signed and no independent
         * re-execution exists at this population. A population shortfall, not a stall — the
         * cure is more peers on the mesh (issue #45), not more time.
         */
        SOLO,
        /** No lease held here — certification is another node's to report. */
        UNKNOWN
    }

    /**
     * Per-region lease snapshot.
     *
     * @param epoch           the lease epoch.
     * @param leaseExpiryTick the tick the lease expires at.
     * @param certification   how far the region's head has got through consensus.
     * @param committeeSize   primary + validators on the lease (1 = solo).
     * @param signatures      distinct voters on the head's certificate (0 = none).
     */
    public record LeaseInfo(long epoch, long leaseExpiryTick, Certification certification,
                            int committeeSize, int signatures) {

        /** Compact constructor: a null certification reads as {@link Certification#UNKNOWN}. */
        public LeaseInfo {
            certification = certification == null ? Certification.UNKNOWN : certification;
        }

        /** A lease with no certification information (pre-#47.3 shape). */
        public LeaseInfo(long epoch, long leaseExpiryTick) {
            this(epoch, leaseExpiryTick, Certification.UNKNOWN, 0, 0);
        }
    }

    /**
     * Classify one region's certification state from the facts a validation lane already holds.
     *
     * <p>Pure so the honest-status rule is pinned headlessly rather than only observed in game.
     *
     * @param committeeSize primary + validators on the region's lease; {@code <1} means no lease.
     * @param head          the replica's current snapshot version, or {@code null} if unknown.
     * @param certificate   the most recent quorum certificate for the region, or {@code null}.
     * @return the certification state to render.
     */
    public static Certification classify(int committeeSize, SnapshotVersion head,
                                         QuorumCertificate certificate) {
        if (committeeSize < 1) {
            return Certification.UNKNOWN;
        }
        if (committeeSize == 1) {
            return Certification.SOLO;
        }
        if (certificate == null || head == null) {
            return Certification.PENDING;
        }
        boolean coversHead = certificate.version().value() >= head.value();
        boolean independent = certificate.votes().stream()
                .map(v -> v.voter().value()).distinct().count() >= 2;
        return coversHead && independent ? Certification.CERTIFIED : Certification.PENDING;
    }

    /** Compact constructor copies the lists/map into immutable containers. */
    public RegionOwnership {
        primary = primary == null ? List.of() : List.copyOf(primary);
        validator = validator == null ? List.of() : List.copyOf(validator);
        replica = replica == null ? List.of() : List.copyOf(replica);
        leases = leases == null ? Map.of() : Map.copyOf(leases);
    }

    /** @return how many leases are in {@code state}. */
    public int countCertification(Certification state) {
        int n = 0;
        for (LeaseInfo info : leases.values()) {
            if (info.certification() == state) {
                n++;
            }
        }
        return n;
    }

    /** @return {@code true} if no region is delegated to this peer (the placeholder state). */
    public boolean isEmpty() {
        return primary.isEmpty() && validator.isEmpty() && replica.isEmpty();
    }

    /** The empty placeholder instance (Task 18 staging — owned by Task 6). */
    public static RegionOwnership empty() {
        return new RegionOwnership(List.of(), List.of(), List.of(), 0, Map.of());
    }
}
