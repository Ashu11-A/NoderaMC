package dev.nodera.mod;

import dev.nodera.mod.common.ModAttachments;
import dev.nodera.mod.common.ModNetworking;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.server.ServerBootstrap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Nodera common entrypoint — loaded on BOTH the dedicated server and the client.
 *
 * <p>Thread context: constructed on the mod-loading thread during bootstrap. Registers the
 * networking registrar, attachment types, config specs, and the server-side game-event
 * subscriptions on <b>every</b> dist.
 *
 * <p>Decentralization (Task 30): the host wiring ({@link ServerBootstrap}) is no longer gated behind
 * {@code Dist.DEDICATED_SERVER}. A player's <i>integrated</i> server can host a world on the network
 * too — it just waits for the pause-menu "Share" action instead of auto-hosting. Which installation
 * hosts is decided by the host role, not by the dist. {@code dist} is retained only for logging /
 * future dist-specific tuning.
 *
 * <p>Dist isolation: client-only code lives under {@link dev.nodera.mod.client} and is loaded
 * only via the {@link NoderaClientMod} {@code Dist.CLIENT} entrypoint — this class must never
 * classload anything under that package.
 */
@Mod(NoderaMod.MOD_ID)
public final class NoderaMod {
    public static final String MOD_ID = "nodera";

    public NoderaMod(IEventBus modBus, ModContainer container, Dist dist) {
        NoderaConfig.register(container);
        ModNetworking.register(modBus);   // registrar "1", the session hand-off payload (Task 4)
        ModAttachments.register(modBus);  // empty DeferredRegister for now
        ServerBootstrap.register();       // host lifecycle on both dists; integrated server waits for "Share"
    }
}
