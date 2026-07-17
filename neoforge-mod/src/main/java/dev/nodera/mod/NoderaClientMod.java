package dev.nodera.mod;

import dev.nodera.mod.client.ClientBootstrap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Nodera client-only entrypoint — loaded only on clients ({@code Dist.CLIENT}).
 *
 * <p>Thread context: constructed on the mod-loading thread during client bootstrap. All
 * {@code net.minecraft.client.*} screens/overlays belong under {@link dev.nodera.mod.client}
 * and are reachable only through here, so a dedicated server never classloads them. Empty for
 * now (Task 1); later tasks add client worker wiring.
 */
@Mod(value = NoderaMod.MOD_ID, dist = Dist.CLIENT)
public final class NoderaClientMod {
    public NoderaClientMod(IEventBus modBus, ModContainer container) {
        ClientBootstrap.register(modBus, container);   // empty for now
    }
}
