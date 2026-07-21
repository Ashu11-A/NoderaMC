package dev.nodera.core.region;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Turns a {@link PlayerView} (a player's chunk disc) into the set of static grid {@link RegionId}s the
 * player activates and owns — the geometric heart of decentralized field-of-view region ownership.
 *
 * <p>A region is <b>in view</b> iff any chunk of its 8×8 cell lies within the player's circular disc
 * of radius {@code renderDistanceChunks}. Because the test is a true Euclidean disc (not a square),
 * the activated regions trace a rough <em>circle</em> around the player that widens as render distance
 * grows — exactly the requested behaviour. Chunk coordinates are treated as unit cells; the nearest
 * chunk of a region to the player's chunk decides intersection, so a region just clipped by the disc's
 * edge still counts.
 *
 * <p>All arithmetic is integer and sign-correct ({@link Math#floorDiv}) so the same view yields the
 * same region set on every JVM — a determinism requirement, since overlapping players must agree on
 * who covers a shared region without a coordinator.
 *
 * <p>Thread-context: stateless; every method is a pure function, safe from any thread.
 */
public final class PlayerViewRegionResolver {

    private PlayerViewRegionResolver() {
    }

    /**
     * @return every region the player activates, in a deterministic scan order (row-major over the
     *         disc's bounding box), including the center region.
     */
    public static Set<RegionId> activeRegions(PlayerView view) {
        int r = view.renderDistanceChunks();
        int minChunkX = view.centerChunkX() - r;
        int maxChunkX = view.centerChunkX() + r;
        int minChunkZ = view.centerChunkZ() - r;
        int maxChunkZ = view.centerChunkZ() + r;

        int size = dev.nodera.core.NoderaConstants.REGION_SIZE_CHUNKS;
        int minRegionX = Math.floorDiv(minChunkX, size);
        int maxRegionX = Math.floorDiv(maxChunkX, size);
        int minRegionZ = Math.floorDiv(minChunkZ, size);
        int maxRegionZ = Math.floorDiv(maxChunkZ, size);

        // LinkedHashSet: deterministic iteration order (row-major) so callers/tests are stable.
        Set<RegionId> out = new LinkedHashSet<>();
        for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
            for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                RegionId candidate = new RegionId(view.dimension(), rx, rz);
                if (covers(view, candidate)) {
                    out.add(candidate);
                }
            }
        }
        return out;
    }

    /**
     * @return {@code true} iff any chunk of {@code region} lies within the player's disc (same
     *         dimension required). The nearest chunk of the region cell to the player's chunk is
     *         clamped, then Euclidean-distance-tested against the radius.
     */
    public static boolean covers(PlayerView view, RegionId region) {
        if (!view.dimension().equals(region.dimension())) {
            return false;
        }
        int size = dev.nodera.core.NoderaConstants.REGION_SIZE_CHUNKS;
        int minChunkX = region.originChunkX();
        int maxChunkX = minChunkX + size - 1;
        int minChunkZ = region.originChunkZ();
        int maxChunkZ = minChunkZ + size - 1;

        int nearestX = clamp(view.centerChunkX(), minChunkX, maxChunkX);
        int nearestZ = clamp(view.centerChunkZ(), minChunkZ, maxChunkZ);

        long dx = (long) nearestX - view.centerChunkX();
        long dz = (long) nearestZ - view.centerChunkZ();
        long radius = view.renderDistanceChunks();
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * Squared distance (in quarter-chunk² units) from the player's chunk to a region's center — the
     * deterministic ownership metric used to pick the primary among overlapping players (closest
     * player owns; the rest validate). Computed in doubled-chunk integer space so it is exact.
     *
     * @return a non-negative comparable magnitude; the absolute scale is irrelevant, only ordering is.
     */
    public static long centerDistanceSq(PlayerView view, RegionId region) {
        int size = dev.nodera.core.NoderaConstants.REGION_SIZE_CHUNKS;
        // Region center in doubled-chunk units: 2*origin + (size-1). Player chunk center: 2*c + 1.
        long regionCx2 = 2L * region.originChunkX() + (size - 1);
        long regionCz2 = 2L * region.originChunkZ() + (size - 1);
        long playerCx2 = 2L * view.centerChunkX() + 1;
        long playerCz2 = 2L * view.centerChunkZ() + 1;
        long dx = regionCx2 - playerCx2;
        long dz = regionCz2 - playerCz2;
        return dx * dx + dz * dz;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
