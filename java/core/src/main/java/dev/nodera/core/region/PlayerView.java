package dev.nodera.core.region;

import dev.nodera.core.NoderaConstants;

/**
 * A player's field of view expressed as a chunk disc — the decentralized unit of region ownership.
 *
 * <p>In Nodera's decentralized model there is no server that decides who owns what; instead each
 * player <b>owns the world they can see</b>. This record captures exactly that: a player standing in
 * chunk {@code (centerChunkX, centerChunkZ)} with a render distance of {@code renderDistanceChunks}
 * loads a circular disc of chunks of that radius around themselves. Every static grid {@link RegionId}
 * whose cell intersects that disc is a region the player <em>activates and is responsible for</em>
 * (see {@link PlayerViewRegionResolver}). A larger render distance ⇒ a larger disc ⇒ more owned
 * regions — the owned area is a circle that grows with the player's view.
 *
 * <p>The static grid identity is deliberately kept (Plan §3.2): the disc decides <em>which</em> stable
 * regions a player claims and <em>where committees form</em> (overlapping discs), but region identity,
 * leases, and epochs are untouched.
 *
 * <p>Thread-context: immutable, safe for any thread.
 */
public record PlayerView(DimensionKey dimension, int centerChunkX, int centerChunkZ,
                         int renderDistanceChunks) {

    public PlayerView {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
        if (renderDistanceChunks < 1) {
            throw new IllegalArgumentException(
                    "renderDistanceChunks must be >= 1, got " + renderDistanceChunks);
        }
    }

    /**
     * Build a view from a player's world position, clamping the render distance to the accepted
     * {@code [MIN, MAX]} range so a hostile or misconfigured client cannot claim an unbounded circle.
     *
     * @param dimension          the player's dimension.
     * @param blockX             world block X of the player.
     * @param blockZ             world block Z of the player.
     * @param renderDistanceChunks the client's render/view distance in chunks (clamped).
     * @return the corresponding chunk-disc view.
     */
    public static PlayerView fromBlock(DimensionKey dimension, int blockX, int blockZ,
                                       int renderDistanceChunks) {
        int clamped = Math.max(NoderaConstants.MIN_RENDER_DISTANCE_CHUNKS,
                Math.min(NoderaConstants.MAX_RENDER_DISTANCE_CHUNKS, renderDistanceChunks));
        return new PlayerView(dimension, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16), clamped);
    }

    /** The static grid region the player is standing in (the disc's center cell). */
    public RegionId centerRegion() {
        return RegionId.fromChunk(dimension, centerChunkX, centerChunkZ);
    }
}
