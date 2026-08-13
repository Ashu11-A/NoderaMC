package dev.nodera.mod.server.shadow;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.VanillaPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Real chunks to a real {@link RegionSnapshot} (minecraft Task 2 deliverable 2). The counterpart of
 * {@link dev.nodera.storage.SnapshotExtractor}, which turns already-extracted section arrays into
 * digests: this class is what produces those arrays from a live {@link ServerLevel}.
 *
 * <p><b>Every extracted section is exact.</b> A section whose 4096 states all map to one palette id
 * (the overwhelming majority — air above ground, stone below) is stored as that single id;
 * everything else is stored as a dense 4096-entry array. {@link ChunkColumnState} canonicalises
 * both shapes, so two nodes that read the same world encode the same bytes, and an untouched world
 * costs nothing to carry.
 *
 * <p><b>Unsupported blocks become the exclusion list, not an error.</b> A block the palette cannot
 * express is extracted as {@link FlatWorldRules#AIR} and counted. That is deliberate and it is the
 * honest reading of what consensus knows: the validated lane holds the palette's world, and a
 * region full of modded machinery reports a high excluded count rather than a wrong root. Callers
 * read {@link Extraction#excludedBlocks()} to decide whether a region is worth delegating at all.
 *
 * <p><b>Loaded chunks only, and never a blocking read.</b> Extraction never forces chunk generation
 * and never waits for one in flight: a chunk that is not resident <em>right now</em> contributes an
 * all-air column and is counted in {@link Extraction#missingChunks()}. Loading a region's worth of
 * chunks synchronously on the server thread is exactly the stall the lane exists to avoid — and
 * "asking without loading" is not enough on its own, because vanilla's
 * {@code getChunk(…, load = false)} still parks the caller in {@code managedBlock} when a holder's
 * FULL future is pending. See the comment at the read site.
 *
 * @Thread-context server main thread only — it reads live chunk sections.
 */
public final class LiveSnapshotExtractor {

    /** Blocks per section edge. */
    private static final int SECTION_EDGE = 16;

    /** One extraction: the snapshot plus what had to be dropped to produce it. */
    public record Extraction(
            RegionSnapshot snapshot,
            int excludedBlocks,
            int missingChunks,
            int denseSections) {
    }

    private LiveSnapshotExtractor() {
    }

    /**
     * Extract one region.
     *
     * @param level   the level whose dimension must match {@code region}'s.
     * @param region  the region to read.
     * @param version the version to stamp on the snapshot.
     * @param tick    the tick to stamp on the snapshot.
     */
    public static Extraction extract(
            ServerLevel level, RegionId region, SnapshotVersion version, long tick) {
        if (level == null || region == null || version == null) {
            throw new IllegalArgumentException("extraction arguments must not be null");
        }
        int minY = level.getMinBuildHeight();
        int sectionCount = level.getSectionsCount();
        List<ChunkColumnState> columns = new ArrayList<>(
                NoderaConstants.REGION_SIZE_CHUNKS * NoderaConstants.REGION_SIZE_CHUNKS);
        int excluded = 0;
        int missing = 0;
        int dense = 0;
        for (int dx = 0; dx < NoderaConstants.REGION_SIZE_CHUNKS; dx++) {
            for (int dz = 0; dz < NoderaConstants.REGION_SIZE_CHUNKS; dz++) {
                int chunkX = region.originChunkX() + dx;
                int chunkZ = region.originChunkZ() + dz;
                // getChunkNow, NOT getChunk(…, load=false). Both promise "do not generate", but
                // getChunk goes through ServerChunkCache.managedBlock even when load is false: if a
                // holder exists whose FULL future is still pending, the SERVER THREAD parks inside
                // the tick it is running, the tick never ends, and sixty seconds later the vanilla
                // watchdog declares the server crashed and shuts it down. That is what killed seven
                // of the nineteen live scenarios in run 31401507964 — every one of them with
                // LiveSnapshotExtractor.extract on the watchdog's stack. getChunkNow reads the
                // already-completed future or answers null, so a half-loaded region is reported as
                // missing chunks, which is exactly what this class's contract says it does.
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    missing++;
                    columns.add(new ChunkColumnState(
                            chunkX, chunkZ, new int[sectionCount], minY, sectionCount));
                    continue;
                }
                Column column = column(chunk, chunkX, chunkZ, minY, sectionCount);
                excluded += column.excluded();
                dense += column.state().denseSections().size();
                columns.add(column.state());
            }
        }
        return new Extraction(
                new RegionSnapshot(region, version, tick, columns), excluded, missing, dense);
    }

    private record Column(ChunkColumnState state, int excluded) {
    }

    private static Column column(
            LevelChunk chunk, int chunkX, int chunkZ, int minY, int sectionCount) {
        int[] uniform = new int[sectionCount];
        List<ChunkColumnState.DenseSection> dense = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int excluded = 0;
        for (int index = 0; index < sectionCount; index++) {
            if (index >= sections.length || sections[index] == null
                    || sections[index].hasOnlyAir()) {
                uniform[index] = FlatWorldRules.AIR;
                continue;
            }
            LevelChunkSection section = sections[index];
            int[] ids = new int[ChunkColumnState.SECTION_VOLUME];
            boolean uniformSection = true;
            int first = -1;
            int cursor = 0;
            // y·z·x — the section's own storage order, and the order the 5b digest reads.
            for (int y = 0; y < SECTION_EDGE; y++) {
                for (int z = 0; z < SECTION_EDGE; z++) {
                    for (int x = 0; x < SECTION_EDGE; x++) {
                        int id = PaletteMapper.idOf(section.getBlockState(x, y, z));
                        if (id == VanillaPalette.UNSUPPORTED) {
                            id = FlatWorldRules.AIR;
                            excluded++;
                        }
                        ids[cursor++] = id;
                        if (first < 0) {
                            first = id;
                        } else if (id != first) {
                            uniformSection = false;
                        }
                    }
                }
            }
            if (uniformSection) {
                uniform[index] = first;
            } else {
                uniform[index] = FlatWorldRules.AIR;
                dense.add(new ChunkColumnState.DenseSection(index, ids));
            }
        }
        return new Column(
                new ChunkColumnState(chunkX, chunkZ, uniform, minY, sectionCount, dense), excluded);
    }

    /** The world Y of a section index, for callers that report positions. */
    public static int sectionMinY(int minY, int sectionIndex) {
        return minY + sectionIndex * SECTION_EDGE;
    }

    /** Convenience for a caller holding a {@link BlockPos} rather than a region. */
    public static RegionId regionOf(ServerLevel level, BlockPos pos) {
        return RegionId.fromChunk(
                dev.nodera.mod.server.entity.MinecraftEntityAdapters.dimension(level),
                Math.floorDiv(pos.getX(), SECTION_EDGE), Math.floorDiv(pos.getZ(), SECTION_EDGE));
    }
}
