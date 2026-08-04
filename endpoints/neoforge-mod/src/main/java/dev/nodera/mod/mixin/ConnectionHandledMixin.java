package dev.nodera.mod.mixin;

import dev.nodera.mod.client.multiplayer.SeamlessTakeover;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stop the client re-handling a disconnect it has already decided to survive.
 *
 * <h2>Why this is needed</h2>
 *
 * <p>{@link ClientDisconnectMixin} cancels {@code onDisconnect} so the level is never torn down. But
 * vanilla sets {@code disconnectionHandled} to {@code true} <b>before</b> it calls that method, and
 * {@code MultiPlayerGameMode.tick()} calls {@code handleDisconnection()} on every client tick. So
 * from the tick after the takeover begins, every single tick took the "already handled" branch and
 * logged {@code handleDisconnection() called twice} — hundreds of lines a second, for as long as the
 * player stood in their ghost world.
 *
 * <p>That is not only noise. It is a per-tick trip through the disconnect path of a connection this
 * client has deliberately stopped caring about, and it buries whatever the log was actually trying
 * to say about the takeover.
 *
 * <p>Scoped to a running takeover: outside one, this method is vanilla's own and its warning is a
 * real signal about a connection being handled twice.
 */
@Mixin(Connection.class)
public class ConnectionHandledMixin {

    @Inject(method = "handleDisconnection", at = @At("HEAD"), cancellable = true)
    private void nodera$stopReHandlingASurvivedDisconnect(CallbackInfo ci) {
        try {
            if (SeamlessTakeover.active()) {
                ci.cancel();
            }
        } catch (RuntimeException | LinkageError degraded) {
            // Never break vanilla's disconnect handling because our own state could not be read.
            org.slf4j.LoggerFactory.getLogger("NoderaContinuity")
                    .debug("takeover state unreadable in handleDisconnection: {}", degraded.toString());
        }
    }
}
