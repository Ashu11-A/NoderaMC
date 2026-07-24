package dev.nodera.mod.server.redstone;

import dev.nodera.core.NoderaConstants;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The Task 13 live suppression registry: in delegated redstone regions the ENGINE is the
 * scheduler — vanilla scheduled ticks for those chunks are fully suppressed at the source
 * ({@code LevelTicksMixin} cancels {@code LevelTicks.schedule}), not tolerated-and-reverted by
 * the interference guard. Once the palette-v3 lane owns a region, anything still arriving
 * through the foreign path is a bug (Task 11 counter becomes a hard assert-zero).
 *
 * <p>Deliberately free of Minecraft types (region math on raw block coordinates) so the
 * registry semantics are unit-testable headless; the mixin does the {@code BlockPos} unwrap.
 *
 * <p><b>Dimension note (MVP):</b> suppression matches on region coordinates only — the flat
 * MVP delegates a single overworld, so a packed {@code (regionX, regionZ)} key is exact. When
 * multi-dimension delegation lands the key grows the dimension, not the call sites.
 *
 * @Thread-context registry mutations from the coordinator thread; lookups from the server
 * thread; both sides use concurrent structures.
 */
public final class RedstoneSuppression {

    private static final Set<Long> SUPPRESSED_REGIONS = ConcurrentHashMap.newKeySet();
    private static final LongAdder SUPPRESSED_TICKS = new LongAdder();

    private RedstoneSuppression() {
    }

    /** Blocks per region edge (8 chunks × 16 blocks). */
    private static final int REGION_SIZE_BLOCKS =
            NoderaConstants.REGION_SIZE_CHUNKS * 16;

    /** Suppress vanilla scheduled ticks for the region covering delegated coordinates. */
    public static void activate(int regionX, int regionZ) {
        SUPPRESSED_REGIONS.add(pack(regionX, regionZ));
    }

    /** Stop suppressing (the region left the delegated redstone lane). */
    public static void deactivate(int regionX, int regionZ) {
        SUPPRESSED_REGIONS.remove(pack(regionX, regionZ));
    }

    /** @return whether a vanilla scheduled tick at block {@code (x, z)} must be cancelled. */
    public static boolean shouldSuppress(int blockX, int blockZ) {
        if (SUPPRESSED_REGIONS.isEmpty()) {
            return false; // the hot path for undelegated worlds: one volatile read
        }
        return SUPPRESSED_REGIONS.contains(pack(
                Math.floorDiv(blockX, REGION_SIZE_BLOCKS),
                Math.floorDiv(blockZ, REGION_SIZE_BLOCKS)));
    }

    /** Count one cancelled vanilla tick (RedstoneLaneMetrics: suppressions are EXPECTED). */
    public static void recordSuppressed() {
        SUPPRESSED_TICKS.increment();
    }

    /** Total vanilla scheduled ticks cancelled since start. */
    public static long suppressedCount() {
        return SUPPRESSED_TICKS.sum();
    }

    /** The number of regions currently suppressed. */
    public static int activeRegions() {
        return SUPPRESSED_REGIONS.size();
    }

    /** Test seam: reset all registry state. */
    public static void reset() {
        SUPPRESSED_REGIONS.clear();
        SUPPRESSED_TICKS.reset();
    }

    private static long pack(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }
}
