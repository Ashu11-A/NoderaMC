package dev.nodera.diagnostics.model;

import dev.nodera.core.region.RegionId;

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

    /** Per-region lease snapshot. */
    public record LeaseInfo(long epoch, long leaseExpiryTick) {}

    /** Compact constructor copies the lists/map into immutable containers. */
    public RegionOwnership {
        primary = primary == null ? List.of() : List.copyOf(primary);
        validator = validator == null ? List.of() : List.copyOf(validator);
        replica = replica == null ? List.of() : List.copyOf(replica);
        leases = leases == null ? Map.of() : Map.copyOf(leases);
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
