package dev.nodera.mod.mixin;

import dev.nodera.mod.server.redstone.RedstoneSuppression;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Random-tick suppression in delegated regions (L-1, the third and last mixin the minecraft charter
 * plans).
 *
 * <p><b>Why an event was not enough.</b> There is no event for "the game is about to random-tick
 * this chunk". NeoForge fires per-block events *after* vanilla has already chosen the cells and
 * consumed randomness from the level's RNG, which is precisely the thing that must not happen: the
 * engine owns grass, fire and crops in a delegated region, and letting vanilla also roll for them
 * would produce a world neither side predicted — the region's committed root says one thing and the
 * player's screen shows another. Suppression has to be at the source, before the draws, which is
 * `ServerLevel.tickChunk`.
 *
 * <p>The whole chunk is skipped rather than filtered per block: a region is delegated or it is not,
 * and a per-block filter would still have consumed the level RNG for the blocks it rejected. The
 * counter that a farm soak reads is incremented here, and it is deliberately separate from the
 * scheduled-tick counter — the two lanes answer different questions.
 *
 * <p>Everything else in the chunk tick — ice, snow, lightning, mob spawning — belongs to vanilla
 * and is skipped along with the random ticks only inside a delegated region, where the engine is
 * the authority on what the world does next.
 */
@Mixin(net.minecraft.server.level.ServerLevel.class)
public abstract class ServerLevelRandomTickMixin {

    @Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",
            at = @At("HEAD"), cancellable = true)
    private void nodera$suppressRandomTicksInDelegatedRegions(
            LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        int blockX = chunk.getPos().getMinBlockX();
        int blockZ = chunk.getPos().getMinBlockZ();
        if (RedstoneSuppression.shouldSuppress(blockX, blockZ)) {
            RedstoneSuppression.recordSuppressedRandomTick();
            ci.cancel();
        }
    }
}
