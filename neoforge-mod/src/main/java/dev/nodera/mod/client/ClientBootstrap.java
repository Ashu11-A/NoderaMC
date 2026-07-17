package dev.nodera.mod.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Client bootstrap holder — registers client-only listeners/screens. Empty for now (Task 1);
 * later tasks add the client {@code WorkerRuntime}, HUD overlays, and config screen here.
 *
 * <p>Thread context: {@code register} runs on the mod-loading thread on the client; all
 * subsequent work is client render / client tick thread only. This class may be classloaded on
 * the dedicated server ONLY transitively — it must not touch {@code net.minecraft.client.*}
 * at static-init time (those references are isolated in later-added classes).
 */
@ApiStatus.Internal
public final class ClientBootstrap {

    private ClientBootstrap() {
    }

    /** Called from {@link dev.nodera.mod.NoderaClientMod} ({@code Dist.CLIENT} only). */
    public static void register(IEventBus modBus, ModContainer container) {
        // Task 5+: client worker registration. Empty for now.
    }
}
