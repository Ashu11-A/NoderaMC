package dev.nodera.mod.mixin;

import dev.nodera.mod.server.redstone.RedstoneSuppression;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Task 13 live suppression (L-26): in delegated redstone regions the Nodera engine is THE
 * scheduler — the hashed scheduled-tick queue in the region root is the only tick truth.
 * Vanilla scheduled ticks (block AND fluid — {@code LevelTicks} is the shared container) for
 * suppressed chunks are cancelled at the source, before they ever enter the vanilla queue,
 * so the Task 11 interference counter for the SCHEDULED source becomes a hard assert-zero:
 * anything still arriving through the foreign path is a bug, not noise to tolerate.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin<T> {

    @Inject(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V",
            at = @At("HEAD"), cancellable = true)
    private void nodera$suppressVanillaTicksInDelegatedRegions(
            ScheduledTick<T> tick, CallbackInfo ci) {
        if (RedstoneSuppression.shouldSuppress(tick.pos().getX(), tick.pos().getZ())) {
            RedstoneSuppression.recordSuppressed();
            ci.cancel();
        }
    }
}
