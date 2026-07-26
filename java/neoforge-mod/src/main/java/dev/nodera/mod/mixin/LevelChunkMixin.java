package dev.nodera.mod.mixin;

import dev.nodera.mod.server.shadow.BlockWriteGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The single write choke point (minecraft Task 2 deliverable 4; the live half of the Task 11
 * interference guard).
 *
 * <p><b>Why an event was not enough.</b> There is no event for "a block changed". NeoForge fires
 * events for the <i>causes</i> it knows — a player placing, a player breaking, a piston moving, a
 * fluid spreading — and a foreign write is by definition the write whose cause nobody enumerated: a
 * mod calling {@code setBlockState} directly, a fake player, a worldgen feature running late, an
 * async executor. Every one of them funnels through {@code LevelChunk.setBlockState}, and nothing
 * else does. Guarding the funnel is the only way to see them all; guarding the causes would mean
 * guarding an open set, and a guard with two entry points is not a guard.
 *
 * <p>The judgement is not here. {@link BlockWriteGuard} translates chunk and state into region and
 * palette ids, and {@code MutationGuard} in the engine decides — so the semantics are unit-tested
 * without a game and this class stays the three lines that version churn can break (A-7).
 *
 * <p>Cancelling returns {@code null}, which is precisely what vanilla's own method returns when a
 * write changes nothing: every caller already handles it. Only STRICT mode ever cancels; the
 * default converts, so the write lands and the region certifies it as an external delta.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"), cancellable = true)
    private void nodera$guardForeignWrites(
            BlockPos pos, BlockState state, boolean isMoving,
            CallbackInfoReturnable<BlockState> cir) {
        if (BlockWriteGuard.blocks((LevelChunk) (Object) this, pos, state)) {
            cir.setReturnValue(null);
        }
    }
}
