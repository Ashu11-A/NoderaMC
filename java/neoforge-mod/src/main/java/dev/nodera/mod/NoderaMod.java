package dev.nodera.mod;

import dev.nodera.mod.common.ModAttachments;
import dev.nodera.mod.common.ModNetworking;
import dev.nodera.mod.common.NoderaConfig;
import dev.nodera.mod.dedicated.ServerBootstrap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Nodera common entrypoint — loaded on BOTH the dedicated server and the client.
 *
 * <p>Thread context: constructed on the mod-loading thread during bootstrap. Registers the
 * networking registrar, attachment types, and config specs; on a dedicated server it also
 * wires the server-side game-event subscriptions. No gameplay behaviour yet (Task 1).
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
        ModNetworking.register(modBus);   // registrar "1", zero payloads for now (Task 4 fills it)
        ModAttachments.register(modBus);  // empty DeferredRegister for now
        if (dist == Dist.DEDICATED_SERVER) {
            ServerBootstrap.register();   // empty game-event subscriptions for now
        }
    }
}
