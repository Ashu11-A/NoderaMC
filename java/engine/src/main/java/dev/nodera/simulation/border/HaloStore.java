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
 * <p><b>It still verifies nothing.</b> Exactly as {@link RegionHalo} documents: a slice is bytes a
 * neighbour handed over. This store makes the input deterministic given a set of slices; it does
 * not make the slices trustworthy. Pinning the halo as a committee-signed consensus input is the
 * remaining half of engine L-2, and is why {@code trustedFor} is deliberately narrow at the call
 * site rather than assumed here.
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
