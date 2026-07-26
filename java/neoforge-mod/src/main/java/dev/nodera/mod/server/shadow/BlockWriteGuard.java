package dev.nodera.mod.server.shadow;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.coordinator.interference.MutationGuard;
import dev.nodera.mod.server.entity.MinecraftEntityAdapters;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.VanillaPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The single write choke point's Java side (minecraft Task 2 deliverable 4). Every block write in
 * the game passes {@code LevelChunk.setBlockState}, so the mixin that guards it is the only place a
 * foreign write can be seen without enumerating the infinite set of things that write blocks —
 * which is exactly why there is one choke point and not two: <i>a guard with two entry points is
 * not a guard</i>.
 *
 * <p>The judgement itself belongs to {@link MutationGuard} in the engine, which already knows the
 * three answers (the applier's own write passes, a foreign write converts into a certified external
 * delta, and in STRICT mode it is refused outright) and is unit-tested there. This class only
 * translates: chunk and {@link BlockState} in, region and palette ids out.
 *
 * <p><b>Inert until a lane installs.</b> The guard is null on every server that is not validating
 * anything, so the hot path is one null check and a return — the cost of the choke point on a
 * vanilla world is a field read per block write.
 *
 * <p><b>Unsupported reads as air, both sides.</b> A block the palette cannot express is not
 * consensus state, so it is compared as air — the same reading {@link LiveSnapshotExtractor} takes.
 * That makes "a modded machine replaced a stone" a real consensus change (stone → air) rather than
 * an invisible one, while "a modded block replaced another modded block" stays correctly invisible.
 *
 * <p><b>Off-thread writes are refused loudly.</b> A write arriving from any thread other than the
 * server's is handed to {@code MutationGuard.verdictChecked}, which throws the documented
 * {@code AsyncWriteException} naming the legal path ({@code AsyncActionGate.submit}) instead of
 * silently blocking or converting something the offending mod could never debug (L-25).
 *
 * @Thread-context any thread; main-thread writes are classified, off-thread writes are rejected.
 */
public final class BlockWriteGuard {

    private static volatile MutationGuard guard;

    private BlockWriteGuard() {
    }

    /** Install the lane's guard; {@code null} makes the choke point inert again. */
    public static void install(MutationGuard installed) {
        guard = installed;
    }

    /** @return the installed guard, or null when nothing is validating. */
    public static MutationGuard guard() {
        return guard;
    }

    /**
     * Classify one vanilla block write.
     *
     * @return {@code true} when the write must be cancelled (STRICT mode, foreign write). A
     *         converted write returns {@code false}: it lands in the world and the guard has already
     *         recorded it for the tick-end committer.
     */
    public static boolean blocks(LevelChunk chunk, BlockPos pos, BlockState newState) {
        MutationGuard active = guard;
        if (active == null || chunk == null || pos == null || newState == null) {
            return false;
        }
        try {
            if (!(chunk.getLevel() instanceof ServerLevel level)) {
                return false;
            }
            int previous = paletteId(chunk.getBlockState(pos));
            int next = paletteId(newState);
            if (previous == next) {
                return false; // no consensus change: nothing for the guard to see
            }
            RegionId region = RegionId.fromChunk(
                    MinecraftEntityAdapters.dimension(level), chunk.getPos().x, chunk.getPos().z);
            NBlockPos where = new NBlockPos(pos.getX(), pos.getY(), pos.getZ());
            // L-25: an off-thread write into a delegated region is the case the documented
            // rejection exists for, and it is the ONLY case that may throw. A main-thread write
            // with no phase marker is ordinary vanilla plumbing — rejecting it would turn every
            // unlabelled vanilla path into a crash, which is why the checked entry point is
            // reached by thread rather than by source.
            MutationGuard.Verdict verdict = level.getServer().isSameThread()
                    ? active.verdict(region, where, previous, next)
                    : active.verdictChecked(region, where, previous, next);
            return verdict == MutationGuard.Verdict.BLOCK;
        } catch (MutationGuard.AsyncWriteException documented) {
            // The whole point of this exception is that the offending mod sees it and can act on
            // it. Degrading it into a warning would recreate the silent-corruption case it exists
            // to replace.
            throw documented;
        } catch (RuntimeException | LinkageError degraded) {
            // The choke point sits on the hottest path in the game. A fault here degrades the
            // guard, never the world: the write proceeds unguarded and the region's interference
            // counters simply miss it.
            org.slf4j.LoggerFactory.getLogger("NoderaBlockGuard")
                    .warn("Nodera: block write guard failed (write allowed): {}",
                            degraded.toString());
            return false;
        }
    }

    private static int paletteId(BlockState state) {
        int id = PaletteMapper.idOf(state);
        return id == VanillaPalette.UNSUPPORTED ? FlatWorldRules.AIR : id;
    }
}
