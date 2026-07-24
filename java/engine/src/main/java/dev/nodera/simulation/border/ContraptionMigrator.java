package dev.nodera.simulation.border;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The Task 13 contraption-migration DECISION core (headless): given the pending
 * {@link BorderSignal}s per region and the current delegation view, decide what happens to a
 * contraption whose effects cross region borders. The decision logic is a pure function so the
 * mod-side coordinator (and every test) exercises the identical rules:
 *
 * <ul>
 *   <li><b>DEMOTE</b> — some region the group touches is in the vanilla lane: the whole group
 *       demotes to vanilla ({@code DelegabilityPolicy.Reason.CONTRAPTION_CROSSES_VANILLA});
 *       the contraption runs pure vanilla — correct, slower.</li>
 *   <li><b>MIGRATE</b> — every touched region is delegated but primaries differ: the whole
 *       flood-filled group moves under ONE shared primary (the source's), epochs bump, and the
 *       replayed border signal becomes the first item of the merged schedule.</li>
 *   <li><b>INTERNAL</b> — every touched region already shares the source's primary: the signal
 *       is an ordinary halo hand-off, no topology change.</li>
 * </ul>
 *
 * <p>The GROUP is the flood-fill closure of regions connected by pending border signals: a
 * clock in A driving a farm in B driving a sorter in C is ONE contraption even though A never
 * signals C directly.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class ContraptionMigrator {

    private ContraptionMigrator() {
    }

    /** The migration decision for one contraption group (regions in canonical order). */
    public sealed interface Decision {
        /** The group's regions, source included, in canonical (encoded) order. */
        Set<RegionId> group();

        /** Some group region is vanilla: demote everything, cooldown before redelegation. */
        record Demote(Set<RegionId> group, Set<RegionId> vanillaRegions) implements Decision {
        }

        /** All delegated, primaries differ: merge under {@code newPrimary}, bump every epoch. */
        record Migrate(Set<RegionId> group, NodeId newPrimary) implements Decision {
        }

        /** Already single-primary: border signals are plain halo hand-offs. */
        record Internal(Set<RegionId> group) implements Decision {
        }
    }

    /** Blocks per chunk edge (Minecraft constant; the codebase's chunk math uses 16 inline). */
    private static final int CHUNK_SIZE = 16;

    /** The region that owns {@code signal.target()} — border signals always leave the region. */
    public static RegionId targetRegion(RegionId source, BorderSignal signal) {
        return RegionId.fromChunk(
                source.dimension(),
                Math.floorDiv(signal.target().x(), CHUNK_SIZE),
                Math.floorDiv(signal.target().z(), CHUNK_SIZE));
    }

    /**
     * Decide the fate of the contraption group seeded at {@code source}.
     *
     * @param source        the region whose execution emitted fresh border signals.
     * @param pendingByRegion every region's pending border signals (the flood-fill edges); the
     *                      map's absent regions are treated as signal-free.
     * @param primaryOf     the delegation view: the region's primary, or empty when the region
     *                      runs in the vanilla lane.
     */
    public static Decision decide(
            RegionId source,
            Map<RegionId, List<BorderSignal>> pendingByRegion,
            Function<RegionId, Optional<NodeId>> primaryOf) {
        Set<RegionId> group = floodFill(source, pendingByRegion);

        Set<RegionId> vanilla = new TreeSet<>(REGION_ORDER);
        Set<NodeId> primaries = new LinkedHashSet<>();
        for (RegionId region : group) {
            Optional<NodeId> primary = primaryOf.apply(region);
            if (primary.isEmpty()) {
                vanilla.add(region);
            } else {
                primaries.add(primary.get());
            }
        }
        if (!vanilla.isEmpty()) {
            return new Decision.Demote(group, vanilla);
        }
        NodeId sourcePrimary = primaryOf.apply(source).orElseThrow(
                () -> new IllegalStateException("source region must be delegated: " + source));
        if (primaries.size() > 1) {
            return new Decision.Migrate(group, sourcePrimary);
        }
        return new Decision.Internal(group);
    }

    /** Flood-fill: every region reachable from {@code source} through pending border signals. */
    static Set<RegionId> floodFill(
            RegionId source, Map<RegionId, List<BorderSignal>> pendingByRegion) {
        Set<RegionId> group = new TreeSet<>(REGION_ORDER);
        ArrayDeque<RegionId> queue = new ArrayDeque<>();
        group.add(source);
        queue.add(source);
        while (!queue.isEmpty()) {
            RegionId region = queue.removeFirst();
            for (BorderSignal signal : pendingByRegion.getOrDefault(region, List.of())) {
                RegionId target = targetRegion(region, signal);
                if (group.add(target)) {
                    queue.addLast(target);
                }
            }
        }
        return group;
    }

    /** Canonical region order: dimension, then regionX, then regionZ. */
    static final java.util.Comparator<RegionId> REGION_ORDER = java.util.Comparator
            .comparing((RegionId r) -> r.dimension().namespace())
            .thenComparing(r -> r.dimension().path())
            .thenComparingInt(RegionId::regionX)
            .thenComparingInt(RegionId::regionZ);

    /**
     * Group decay bookkeeping (spec: {@code contraption.decayTicks}, default 1200): a group
     * with no cross-border signal for the decay window dissolves and its regions become
     * independently re-assignable again.
     *
     * @Thread-context confined to the coordinator thread.
     */
    public static final class Groups {

        /** Default decay window in region ticks (spec Task.13: {@code decayTicks} 1200). */
        public static final long DEFAULT_DECAY_TICKS = 1200;

        private final long decayTicks;
        private final Map<Set<RegionId>, Long> lastSignalTick = new TreeMap<>(
                java.util.Comparator.comparing(Object::toString));

        public Groups(long decayTicks) {
            if (decayTicks <= 0) {
                throw new IllegalArgumentException("decayTicks must be positive: " + decayTicks);
            }
            this.decayTicks = decayTicks;
        }

        public Groups() {
            this(DEFAULT_DECAY_TICKS);
        }

        /** Record cross-border activity for {@code group} at {@code tick}. */
        public void touch(Set<RegionId> group, long tick) {
            lastSignalTick.put(Set.copyOf(group), tick);
        }

        /** @return whether {@code group} is still active (touched within the decay window). */
        public boolean isActive(Set<RegionId> group, long nowTick) {
            Long last = lastSignalTick.get(group);
            return last != null && nowTick - last < decayTicks;
        }

        /**
         * Dissolve every group whose last signal is older than the decay window and return
         * them (the coordinator re-assigns their regions independently).
         */
        public Set<Set<RegionId>> expire(long nowTick) {
            Set<Set<RegionId>> expired = new LinkedHashSet<>();
            lastSignalTick.entrySet().removeIf(entry -> {
                if (nowTick - entry.getValue() >= decayTicks) {
                    expired.add(entry.getKey());
                    return true;
                }
                return false;
            });
            return expired;
        }

        /** The number of live groups (metrics: RedstoneLaneMetrics group count). */
        public int activeCount() {
            return lastSignalTick.size();
        }
    }
}
