package dev.nodera.mod.debug;

import dev.nodera.core.region.DimensionKey;
import net.minecraft.server.level.ServerPlayer;

/**
 * The single Minecraft → core adapter for a player's dimension (Task 18). Maps the vanilla
 * {@code ResourceLocation} of the player's level to a {@link DimensionKey} so the HUD surfaces
 * (boss bars, zone watcher, commands) share one copy of the layering bridge instead of three.
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class Dimensions {

    private Dimensions() {}

    /** @return the {@link DimensionKey} of the level {@code player} is standing in. */
    public static DimensionKey of(ServerPlayer player) {
        var loc = player.level().dimension().location();
        return DimensionKey.of(loc.getNamespace(), loc.getPath());
    }
}
