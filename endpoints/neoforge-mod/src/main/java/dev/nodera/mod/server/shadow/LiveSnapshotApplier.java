package dev.nodera.mod.server.shadow;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.ChunkColumnState;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.rules.FlatWorldRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.Optional;

/**
 * A received {@link ChunkColumnState} written back into a live {@link ServerLevel} — the inverse of
 * {@link LiveSnapshotExtractor}, and the link that did not exist.
 *
 * <h2>What was missing</h2>
 *
 * <p>The content plane was complete in one direction only. A region could be extracted, split into
 * pieces, hashed, announced, requested, verified and reassembled — and then nothing anywhere could
 * put it into a world. There was no {@code PalettedContainer}, {@code ProtoChunk} or
 * {@code ChunkSerializer} write path in the mod at all; the only way a block ever changed was one
 * {@code BlockPos} at a time through {@code ServerEntityWorldView}, driven by a delta. A peer could
 * therefore prove it held a region's bytes and still not be able to stand in it, which is why the
 * only way to receive a world was to unpack an archive and re-open the save.
 *
 * <h2>Two rules that are easy to get wrong and expensive to miss</h2>
 *
 * <ol>
 *   <li><b>Server thread only.</b> {@link BlockWriteGuard} routes an off-thread write into a
 *       delegated region to {@code verdictChecked}, which throws {@code AsyncWriteException} — by
 *       design, and out of the tick loop.</li>
 *   <li><b>Inside the applier scope.</b> Writes are made through the same {@code Consumer<Runnable>}
 *       seam {@code ServerEntityWorldView} uses. Without it every one of the up to 98,304 blocks in
 *       a column classifies as a <i>foreign</i> write, lands in the interference buffer, and is
 *       proposed straight back to the committee as this node's own edit.</li>
 * </ol>
 *
 * <h2>Why it writes sections rather than blocks</h2>
 *
 * <p>{@code level.setBlock} runs neighbour updates, block-entity teardown, shape updates and a
 * client packet per position. For arriving terrain none of that is wanted — the state is already
 * settled, and re-running redstone on a region as it materialises is both wrong and ruinous. So a
 * section's states are written directly and the column is re-lit and re-sent once, which is what
 * vanilla itself does when a chunk arrives over the network.
 *
 * @Thread-context server main thread only.
 */
public final class LiveSnapshotApplier {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaChunkApply");

    /** Blocks per section edge. */
    private static final int SECTION_EDGE = 16;

    private LiveSnapshotApplier() {
    }

    /**
     * What one apply did.
     *
     * @param columnsWritten columns whose content differed and were written.
     * @param columnsSkipped columns already byte-identical to what arrived — the common case, and
     *                       what makes applying a whole region nearly free when one chunk changed.
     * @param sectionsWritten sections actually rewritten.
     * @param unmappable     palette ids this build has no block for; written as air and counted,
     *                       never guessed at.
     */
    public record Result(int columnsWritten, int columnsSkipped, int sectionsWritten,
                         int unmappable) {

        Result plus(Result other) {
            return new Result(columnsWritten + other.columnsWritten,
                    columnsSkipped + other.columnsSkipped,
                    sectionsWritten + other.sectionsWritten,
                    unmappable + other.unmappable);
        }

        /** @return whether anything at all was written. */
        public boolean wroteAnything() {
            return columnsWritten > 0;
        }
    }

    private static final Result NOTHING = new Result(0, 0, 0, 0);

    /**
     * Write a whole region snapshot into the level.
     *
     * @param level        the level; its dimension must match the snapshot's region.
     * @param snapshot     the state to write.
     * @param applierScope the seam that marks these writes as the applier's rather than foreign;
     *                     {@code null} runs them unmarked, which is only safe when the region is not
     *                     delegated.
     * @return what was written.
     * @Thread-context server main thread.
     */
    public static Result apply(ServerLevel level, RegionSnapshot snapshot,
                               java.util.function.Consumer<Runnable> applierScope) {
        if (level == null || snapshot == null) {
            throw new IllegalArgumentException("apply arguments must not be null");
        }
        Result total = NOTHING;
        for (ChunkColumnState column : snapshot.chunks()) {
            total = total.plus(applyColumn(level, column, applierScope));
        }
        LOG.debug("applied {} to {}: {} column(s) written, {} already held, {} section(s), {} "
                        + "unmappable", snapshot.region(), level.dimension().location(),
                total.columnsWritten(), total.columnsSkipped(), total.sectionsWritten(),
                total.unmappable());
        return total;
    }

    /**
     * Write one chunk column.
     *
     * <p>Skips fast when the level already holds this content: extracting the live column and
     * comparing is far cheaper than rewriting 24 sections, and a region arriving because one of its
     * chunks changed is 63 skips and one write.
     *
     * @param level        the level.
     * @param column       the column to write.
     * @param applierScope the applier seam, or {@code null}.
     * @return what was written.
     * @Thread-context server main thread.
     */
    public static Result applyColumn(ServerLevel level, ChunkColumnState column,
                                     java.util.function.Consumer<Runnable> applierScope) {
        if (level == null || column == null) {
            throw new IllegalArgumentException("applyColumn arguments must not be null");
        }
        // Force-load: unlike extraction, applying to a chunk that is not resident is not something
        // that can be skipped — dropping it would leave the region partly written and its root
        // wrong, which is worse than the load.
        LevelChunk chunk = level.getChunkSource()
                .getChunk(column.chunkX(), column.chunkZ(), ChunkStatus.FULL, true) instanceof
                LevelChunk loaded ? loaded : null;
        if (chunk == null) {
            LOG.warn("could not load chunk {},{} to apply it", column.chunkX(), column.chunkZ());
            return NOTHING;
        }
        if (holdsAlready(chunk, column)) {
            return new Result(0, 1, 0, 0);
        }
        Result[] written = {NOTHING};
        Runnable body = () -> written[0] = write(level, chunk, column);
        if (applierScope != null) {
            applierScope.accept(body);
        } else {
            body.run();
        }
        return written[0];
    }

    /**
     * Whether the live chunk already holds exactly this content.
     *
     * <p>Compared through {@link LiveSnapshotExtractor}'s own reading of the level, so "the same"
     * means the same thing here as it does everywhere else in the lane — including for blocks the
     * palette cannot express, which both sides read as air.
     */
    private static boolean holdsAlready(LevelChunk chunk, ChunkColumnState column) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < column.sectionCount(); index++) {
            final int at = index;
            int uniform = column.paletteStateIdsPerSection()[index];
            boolean dense = column.denseSections().stream()
                    .anyMatch(s -> s.sectionIndex() == at);
            boolean live = index < sections.length && sections[index] != null
                    && !sections[index].hasOnlyAir();
            if (!dense && uniform == FlatWorldRules.AIR && !live) {
                continue; // both sides say empty
            }
            if (!live) {
                return false; // the level has nothing where the column has content
            }
            for (int y = 0; y < SECTION_EDGE; y++) {
                for (int z = 0; z < SECTION_EDGE; z++) {
                    for (int x = 0; x < SECTION_EDGE; x++) {
                        if (PaletteMapper.idOf(sections[index].getBlockState(x, y, z))
                                != column.blockAt(index, x, y, z)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static Result write(ServerLevel level, LevelChunk chunk, ChunkColumnState column) {
        LevelChunkSection[] sections = chunk.getSections();
        int sectionsWritten = 0;
        int unmappable = 0;
        for (int index = 0; index < column.sectionCount() && index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null) {
                continue;
            }
            boolean touched = false;
            // y·z·x, the same order the extractor reads in, so a round trip is the identity.
            for (int y = 0; y < SECTION_EDGE; y++) {
                for (int z = 0; z < SECTION_EDGE; z++) {
                    for (int x = 0; x < SECTION_EDGE; x++) {
                        int wanted = column.blockAt(index, x, y, z);
                        if (PaletteMapper.idOf(section.getBlockState(x, y, z)) == wanted) {
                            continue;
                        }
                        Optional<BlockState> state = PaletteMapper.stateOf(wanted);
                        if (state.isEmpty()) {
                            // A palette id this build has no block for. Air is what the extractor
                            // would have read it back as anyway, so this keeps the round trip
                            // consistent instead of inventing a block nobody agreed on.
                            unmappable++;
                            section.setBlockState(x, y, z, net.minecraft.world.level.block.Blocks.AIR
                                    .defaultBlockState(), false);
                            touched = true;
                            continue;
                        }
                        section.setBlockState(x, y, z, state.get(), false);
                        touched = true;
                    }
                }
            }
            if (touched) {
                sectionsWritten++;
            }
        }
        if (sectionsWritten == 0) {
            return new Result(0, 1, 0, unmappable);
        }
        chunk.setUnsaved(true);
        // Heightmaps and light are derived from the states just replaced; leaving them stale is
        // what makes a materialised region render black and spawn mobs in daylight.
        net.minecraft.world.level.levelgen.Heightmap.primeHeightmaps(
                chunk, java.util.EnumSet.allOf(
                        net.minecraft.world.level.levelgen.Heightmap.Types.class));
        LevelLightEngine light = level.getLightEngine();
        for (int index = 0; index < column.sectionCount(); index++) {
            light.updateSectionStatus(
                    net.minecraft.core.SectionPos.of(chunk.getPos(), index + sectionMinIndex(level)),
                    false);
        }
        light.setLightEnabled(chunk.getPos(), true);
        resend(level, chunk);
        return new Result(1, 0, sectionsWritten, unmappable);
    }

    /** The section index of the level's lowest section — what a column's index 0 corresponds to. */
    private static int sectionMinIndex(ServerLevel level) {
        return level.getMinSection();
    }

    /**
     * Push the rewritten chunk to everyone watching it.
     *
     * <p>Without this the server holds the arrived terrain and every client keeps rendering what it
     * had — the state is correct and invisible, which in a game is indistinguishable from broken.
     */
    private static void resend(ServerLevel level, LevelChunk chunk) {
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet =
                new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                        chunk, level.getLightEngine(), null, null);
        for (net.minecraft.server.level.ServerPlayer player
                : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) {
            player.connection.send(packet);
        }
    }

    /**
     * The region a column belongs to — for callers holding columns rather than snapshots.
     *
     * @param level  the level, for its dimension.
     * @param column the column.
     * @return the region.
     */
    public static RegionId regionOf(ServerLevel level, ChunkColumnState column) {
        return RegionId.fromChunk(
                dev.nodera.mod.server.entity.MinecraftEntityAdapters.dimension(level),
                Math.floorDiv(column.chunkX(), NoderaConstants.REGION_SIZE_CHUNKS)
                        * NoderaConstants.REGION_SIZE_CHUNKS,
                Math.floorDiv(column.chunkZ(), NoderaConstants.REGION_SIZE_CHUNKS)
                        * NoderaConstants.REGION_SIZE_CHUNKS);
    }

    /** Convenience: the block position of a column-local coordinate. */
    public static BlockPos posOf(ChunkColumnState column, int sectionIndex, int x, int y, int z) {
        return new BlockPos(column.chunkX() * SECTION_EDGE + x,
                column.minY() + sectionIndex * SECTION_EDGE + y,
                column.chunkZ() * SECTION_EDGE + z);
    }
}
