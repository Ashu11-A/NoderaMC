package dev.nodera.mod.dedicated;

import org.jetbrains.annotations.ApiStatus;

/**
 * Dedicated-server bootstrap holder — adds server-side game-event subscriptions to the
 * NeoForge event bus. Empty for now (Task 1); later tasks attach coordinator, vote-collector,
 * and mutation-applier listeners here without touching the {@code @Mod} entrypoint.
 *
 * <p>Thread context: {@code register} runs on the mod-loading thread; subscribed game events
 * later fire on the server main thread.
 */
@ApiStatus.Internal
public final class ServerBootstrap {

    private ServerBootstrap() {
    }

    /** Called from {@link dev.nodera.mod.NoderaMod} only when {@code dist == DEDICATED_SERVER}. */
    public static void register() {
        // Task 5+: NeoForge.EVENT_BUS.register(...). Empty for now.
    }
}
