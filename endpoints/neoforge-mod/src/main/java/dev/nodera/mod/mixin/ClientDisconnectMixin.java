package dev.nodera.mod.mixin;

import dev.nodera.mod.client.multiplayer.SeamlessTakeover;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one place a lost host can be caught before it costs the player their world.
 *
 * <h2>Why the interception has to be here and not in an event</h2>
 *
 * <p>Vanilla's {@code onDisconnect} calls {@code Minecraft.disconnect}, and that method tears the
 * level down <b>before</b> it shows a screen — {@code this.level = null} happens inside it, several
 * statements ahead of the screen ever being built. Every NeoForge hook downstream of that therefore
 * runs in a world where the client no longer has a level, which is why the previous implementation
 * could only offer a screen: by the time it was asked, the ghost chunks it would have rendered were
 * already gone.
 *
 * <p>Cancelling at the head keeps the level, the player entity, and the loaded chunks exactly as
 * they were. Nothing is torn down, so nothing has to be rebuilt, so there is nothing to look at
 * while it is.
 *
 * <h2>Why cancelling vanilla here is safe</h2>
 *
 * <p>{@link SeamlessTakeover#begin()} answers {@code false} unless it is certain it can put the
 * player somewhere — a Nodera-joined session, not this process's own world, not a password refusal,
 * and a worker present to open the world from. When it answers {@code false} this method does
 * nothing at all and the player gets the ordinary disconnect screen with the ordinary reason on it,
 * which for every non-Nodera disconnect is exactly what should happen.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientDisconnectMixin {

    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void nodera$takeOverInsteadOfLeaving(DisconnectionDetails details, CallbackInfo ci) {
        try {
            if (SeamlessTakeover.begin()) {
                ci.cancel();
            }
        } catch (RuntimeException | LinkageError degraded) {
            // Never swallow a disconnect because our own code failed: not cancelling means vanilla
            // proceeds, which is the behaviour that existed before this mixin.
            org.slf4j.LoggerFactory.getLogger("NoderaContinuity")
                    .warn("Nodera: could not take over a disconnect ({}) — leaving it to vanilla",
                            degraded.toString());
        }
    }
}
