package dev.nodera.mod.server.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.coordinator.RegionChunkHolds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;

/**
 * Keeps a delegated region's chunks resident (minecraft Task 2 deliverable 6).
 *
 * <p>A delegated region is only as validated as it is <b>loaded</b>. Unloaded chunks cannot be
 * extracted, cannot receive a committed delta, and come back later as a wall of interference nobody
 * caused — so a lane holds tickets on the chunks of every region it owns, and drops them the moment
 * ownership moves. The bookkeeping (which chunks, who holds them, what actually changed) is
 * {@link RegionChunkHolds}, which is Minecraft-free and tested on the ordinary gate; this class is
 * only the part that talks to the chunk source.
 *
 * <p><b>Why a Nodera ticket type rather than forced chunks.</b> {@code setChunkForced} writes the
 * chunk into the level's saved forced-chunk set, so a crash mid-session would leave a world
 * permanently pinning regions that no longer belong to anyone. A ticket is session state: it dies
 * with the process, which is the correct lifetime for "this node currently owns this region".
 *
 * <p>Radius 1 puts the ticket at the block-ticking level: the chunk is fully loaded and simulated,
 * which is what a region whose blocks and entities are being validated needs. Anything looser and
 * the lane would validate a region the game is not running.
 *
 * @Thread-context safe from any thread: the ref-counting is synchronised and the vanilla
 *                 ticket mutation hops to the server thread when it is not already on it.
 */
public final class ChunkTicketService {

    /**
     * The ticket type {@code COMPATIBILITY.md} §5 names: Nodera adds and removes only its own type
     * and never cancels a foreign ticket. Session-scoped, so a crash cannot leave a world pinned.
     */
    private static final TicketType<ChunkPos> NODERA_DELEGATED =
            TicketType.create("nodera_delegated", Comparator.comparingLong(ChunkPos::toLong));

    /** Fully loaded and block-ticking — a region cannot be validated while the game skips it. */
    private static final int TICKET_RADIUS = 1;

    private final RegionChunkHolds holds = new RegionChunkHolds();

    /** Hold every chunk of {@code region}; already-held chunks cost nothing. */
    public void hold(ServerLevel level, RegionId region) {
        apply(level, holds.hold(region));
    }

    /** Drop this region's hold; a chunk another region still holds stays loaded. */
    public void release(ServerLevel level, RegionId region) {
        apply(level, holds.release(region));
    }

    /** Session shutdown: release everything exactly once. */
    public void releaseAll(ServerLevel level) {
        apply(level, holds.releaseAll());
    }

    /** @return how many distinct chunks this service is currently holding. */
    public int heldChunks() {
        return holds.heldChunks();
    }

    private void apply(ServerLevel level, RegionChunkHolds.Delta delta) {
        if (level == null || delta.isEmpty()) {
            return;
        }
        // A ticket may only be added or removed on the server thread. `DistanceManager` and the
        // `TickingTracker` behind it are thread-confined by contract and unsynchronised in fact, so
        // touching them from anywhere else corrupts the lighting/ticking priority queue — and the
        // corruption is not seen where it is caused. A live ownership run crashed the integrated
        // server with `ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 33` inside
        // vanilla's own `TickingTracker.runAllUpdates`, several frames deep in the SERVER thread,
        // because a re-plan released this lane's tickets from `nodera-entity-lane-replan`.
        //
        // The ref-counting above is already decided and synchronised, so only the vanilla call
        // hops. The executor is FIFO, so a hold and a release scheduled in that order still arrive
        // in that order, and a hop cannot invert them.
        MinecraftServer server = level.getServer();
        if (server != null && !server.isSameThread()) {
            server.execute(() -> mutate(level, delta));
            return;
        }
        mutate(level, delta);
    }

    private void mutate(ServerLevel level, RegionChunkHolds.Delta delta) {
        for (RegionChunkHolds.ChunkRef chunk : delta.newlyHeld()) {
            ChunkPos pos = new ChunkPos(chunk.chunkX(), chunk.chunkZ());
            level.getChunkSource().addRegionTicket(NODERA_DELEGATED, pos, TICKET_RADIUS, pos);
        }
        for (RegionChunkHolds.ChunkRef chunk : delta.released()) {
            ChunkPos pos = new ChunkPos(chunk.chunkX(), chunk.chunkZ());
            level.getChunkSource().removeRegionTicket(NODERA_DELEGATED, pos, TICKET_RADIUS, pos);
        }
    }
}
