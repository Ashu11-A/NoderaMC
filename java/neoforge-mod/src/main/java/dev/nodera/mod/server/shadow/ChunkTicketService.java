package dev.nodera.mod.server.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.coordinator.RegionChunkHolds;
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
 * @Thread-context server main thread only.
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
