package dev.nodera.simulation.border;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The consuming half of the halo exchange (engine L-2): the newest slice each neighbour has handed
 * this node, assembled into the {@link RegionHalo} a region executes against.
 *
 * <p><b>Newest-wins, never backwards.</b> A slice carries the source region's snapshot version, so
 * a late-arriving older slice is dropped rather than applied: without that, delivery order would
 * decide what a region reads across its border, and two replicas receiving the same two slices in
 * different orders would execute on different worlds. Same-version re-delivery is idempotent.
 *
 * <p><b>Provenance is decided before a slice gets here.</b> This store holds slices a strict
 * majority of the SOURCE region's committee has signed — the caller verifies the
 * {@code HaloEndorsement} quorum and only then hands the slice over, so what is stored is what a
 * neighbour committee agreed it published, not what one peer claimed. What this store adds is
 * determinism given that set: newest-wins ordering, and {@link #pinnedHaloFor} so a member
 * executes against the exact slices a proposal names rather than its own accumulated view.
 *
 * @Thread-context thread-safe; slices arrive on transport threads and are read on execution
 *                 threads.
 */
public final class HaloStore {

    /** target region → (source region → its newest slice). */
    private final Map<RegionId, Map<RegionId, RegionHalo.Slice>> byTarget = new ConcurrentHashMap<>();

    /**
     * Take one neighbour slice for {@code target}.
     *
     * @param target the region that will read these columns; must not be null.
     * @param slice  the neighbour contribution; must not be null.
     * @return {@code true} when the slice was stored (new, or newer than what was held),
     *         {@code false} when it was dropped as stale or as a self-slice.
     */
    public boolean accept(RegionId target, RegionHalo.Slice slice) {
        if (target == null || slice == null) {
            throw new IllegalArgumentException("target and slice must not be null");
        }
        if (target.equals(slice.source())) {
            // A region does not halo itself: its own state is in its snapshot, and accepting this
            // would let a peer redefine owned cells through the border door.
            return false;
        }
        Map<RegionId, RegionHalo.Slice> bySource =
                byTarget.computeIfAbsent(target, r -> new ConcurrentHashMap<>());
        RegionHalo.Slice stored = bySource.merge(slice.source(), slice,
                (current, incoming) -> incoming.version().value() >= current.version().value()
                        ? incoming : current);
        return stored == slice;
    }

    /**
     * The halo {@code target} should execute against right now.
     *
     * @return a halo over every slice held for the region; empty (every border read AIR, the
     *         pre-exchange behaviour) when no neighbour has delivered anything.
     */
    public RegionHalo haloFor(RegionId target) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        Map<RegionId, RegionHalo.Slice> bySource = byTarget.get(target);
        if (bySource == null || bySource.isEmpty()) {
            return new RegionHalo(target);
        }
        return new RegionHalo(target, new ArrayList<>(bySource.values()));
    }

    /**
     * The exact halo named by {@code pins} — the second half of engine L-2.
     *
     * <p>A committee member does not execute against "whatever slices it happens to hold": it
     * executes against the slices the proposal <em>names</em>. Holding one slice more than the
     * primary is otherwise indistinguishable from a genuine divergence, and costs the region its
     * round for no fault of anyone's. So the pinned set is assembled exactly: every named
     * (source, version) must be held at that version, and nothing unnamed is included.
     *
     * @param target the region that will execute; must not be null.
     * @param pins   source region → the version the proposer executed against; must not be null.
     * @return the pinned halo, or {@code null} when this node does not hold every named slice at
     *         the named version — the clean failure that replaces a silent divergence.
     */
    public RegionHalo pinnedHaloFor(RegionId target, Map<RegionId, SnapshotVersion> pins) {
        if (target == null || pins == null) {
            throw new IllegalArgumentException("target and pins must not be null");
        }
        if (pins.isEmpty()) {
            return new RegionHalo(target);
        }
        Map<RegionId, RegionHalo.Slice> bySource = byTarget.get(target);
        if (bySource == null) {
            return null;
        }
        List<RegionHalo.Slice> pinned = new ArrayList<>(pins.size());
        for (Map.Entry<RegionId, SnapshotVersion> pin : pins.entrySet()) {
            RegionHalo.Slice held = bySource.get(pin.getKey());
            if (held == null || !held.version().equals(pin.getValue())) {
                return null;
            }
            pinned.add(held);
        }
        return new RegionHalo(target, pinned);
    }

    /**
     * What this node would pin if it proposed for {@code target} right now.
     *
     * @param target the region; must not be null.
     * @return source region → held version, in no particular order (the caller canonicalises).
     */
    public Map<RegionId, SnapshotVersion> pinsFor(RegionId target) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        Map<RegionId, RegionHalo.Slice> bySource = byTarget.get(target);
        if (bySource == null) {
            return Map.of();
        }
        Map<RegionId, SnapshotVersion> pins = new java.util.LinkedHashMap<>();
        for (Map.Entry<RegionId, RegionHalo.Slice> e : bySource.entrySet()) {
            pins.put(e.getKey(), e.getValue().version());
        }
        return Map.copyOf(pins);
    }

    /** @return the version held for one (target, source) pair, empty when nothing is held. */
    public Optional<SnapshotVersion> versionOf(RegionId target, RegionId source) {
        if (target == null || source == null) {
            return Optional.empty();
        }
        Map<RegionId, RegionHalo.Slice> bySource = byTarget.get(target);
        RegionHalo.Slice slice = bySource == null ? null : bySource.get(source);
        return slice == null ? Optional.empty() : Optional.of(slice.version());
    }

    /** @return the regions this store holds any slice for. */
    public List<RegionId> targets() {
        return List.copyOf(byTarget.keySet());
    }

    /** Drop everything held for one region (deactivation: a revoked replica reads nothing). */
    public void forget(RegionId target) {
        if (target != null) {
            byTarget.remove(target);
        }
    }
}
