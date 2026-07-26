package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.RegionId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which chunks a node must keep resident, and for how long (minecraft Task 2 deliverable 6, the
 * Minecraft-free half of the chunk-ticket service).
 *
 * <p>A delegated region is only as validated as it is <b>loaded</b>: a region whose chunks are
 * unloaded cannot be extracted, cannot receive a committed delta, and re-appears later as a wall of
 * interference that nobody caused. So a lane holds the chunks of every region it owns — and, once
 * the same chunk is wanted by two regions or by two lanes, the hold has to be reference-counted or
 * the first release unloads the world out from under the second holder.
 *
 * <p>This class is the bookkeeping only: which chunk coordinates a region covers, who is holding
 * what, and which holds actually changed on the last call — so the Minecraft side issues exactly one
 * ticket per newly held chunk and exactly one release per genuinely dropped one, instead of
 * re-asserting sixty-four tickets every tick.
 *
 * @Thread-context not thread-safe; confined to the lane thread that owns the holds.
 */
public final class RegionChunkHolds {

    /** chunk key → the regions currently holding it. */
    private final Map<Long, Set<RegionId>> holders = new HashMap<>();

    /** One chunk coordinate pair. */
    public record ChunkRef(int chunkX, int chunkZ) {
    }

    /** What changed on the last {@link #hold} or {@link #release} — the work the game must do. */
    public record Delta(List<ChunkRef> newlyHeld, List<ChunkRef> released) {
        public Delta {
            newlyHeld = List.copyOf(newlyHeld);
            released = List.copyOf(released);
        }

        /** @return true when nothing changed and the caller can skip the game entirely. */
        public boolean isEmpty() {
            return newlyHeld.isEmpty() && released.isEmpty();
        }
    }

    /** @return every chunk covered by {@code region}, in a fixed order. */
    public static List<ChunkRef> chunksOf(RegionId region) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        List<ChunkRef> out = new ArrayList<>(
                NoderaConstants.REGION_SIZE_CHUNKS * NoderaConstants.REGION_SIZE_CHUNKS);
        for (int dx = 0; dx < NoderaConstants.REGION_SIZE_CHUNKS; dx++) {
            for (int dz = 0; dz < NoderaConstants.REGION_SIZE_CHUNKS; dz++) {
                out.add(new ChunkRef(region.originChunkX() + dx, region.originChunkZ() + dz));
            }
        }
        return out;
    }

    /**
     * Take a hold on every chunk of {@code region}.
     *
     * @return the chunks that were not held by anything before this call — the ones the game has to
     *         be told about. Holding the same region twice changes nothing.
     */
    public Delta hold(RegionId region) {
        List<ChunkRef> newlyHeld = new ArrayList<>();
        for (ChunkRef chunk : chunksOf(region)) {
            Set<RegionId> owners = holders.computeIfAbsent(
                    key(chunk), unused -> new LinkedHashSet<>());
            if (owners.isEmpty()) {
                newlyHeld.add(chunk);
            }
            owners.add(region);
        }
        return new Delta(newlyHeld, List.of());
    }

    /**
     * Drop {@code region}'s hold.
     *
     * @return the chunks no region holds any more. A chunk another region still covers — the
     *         overlap two adjacent lanes always produce — is not released.
     */
    public Delta release(RegionId region) {
        List<ChunkRef> released = new ArrayList<>();
        for (ChunkRef chunk : chunksOf(region)) {
            long key = key(chunk);
            Set<RegionId> owners = holders.get(key);
            if (owners == null) {
                continue;
            }
            owners.remove(region);
            if (owners.isEmpty()) {
                holders.remove(key);
                released.add(chunk);
            }
        }
        return new Delta(List.of(), released);
    }

    /** Drop every hold (session shutdown) and report what the game must release. */
    public Delta releaseAll() {
        List<ChunkRef> released = new ArrayList<>(holders.size());
        for (Long key : List.copyOf(holders.keySet())) {
            released.add(new ChunkRef((int) (key >> 32), key.intValue()));
        }
        holders.clear();
        return new Delta(List.of(), released);
    }

    /** @return whether any region currently holds this chunk. */
    public boolean isHeld(int chunkX, int chunkZ) {
        return holders.containsKey(key(new ChunkRef(chunkX, chunkZ)));
    }

    /** @return how many distinct chunks are held right now. */
    public int heldChunks() {
        return holders.size();
    }

    private static long key(ChunkRef chunk) {
        return ((long) chunk.chunkX() << 32) | (chunk.chunkZ() & 0xFFFFFFFFL);
    }
}
