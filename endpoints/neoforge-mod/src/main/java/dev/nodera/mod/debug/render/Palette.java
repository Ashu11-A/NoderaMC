package dev.nodera.mod.debug.render;

import dev.nodera.diagnostics.state.Health;
import dev.nodera.diagnostics.state.OwnershipState;
import dev.nodera.diagnostics.state.Semantic;
import net.minecraft.ChatFormatting;
import net.minecraft.world.BossEvent.BossBarColor;

/**
 * The single place that maps {@link Semantic}/{@link OwnershipState}/{@link Health} policy to
 * concrete Minecraft colour (Task 18 — "colour is policy, not decoration").
 *
 * <p>Every renderer ({@link ComponentRenderer}, {@link TabListRenderer}, {@link BossBarManager},
 * {@link ActionBarNotifier}) resolves colour through here, so the colour spec in {@code Task.18.md}
 * lives in exactly one spot. A unit test asserts this is total over {@link Semantic} (acceptance #1).
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class Palette {

    private Palette() {}

    /** @return the {@link ChatFormatting} for {@code semantic}. */
    public static ChatFormatting chat(Semantic semantic) {
        return switch (semantic) {
            case OWNED, HEALTHY, WORLD_HEALTHY -> ChatFormatting.GREEN;
            case VALIDATING, RX -> ChatFormatting.AQUA;
            case REPLICA -> ChatFormatting.BLUE;
            // A torrent world that lost data is RED, unlike the session DEGRADED's YELLOW
            // (Task 26) — sharing the row would silently recolour the Task 18 HUD.
            case FOREIGN, CRITICAL, WORLD_DEGRADED -> ChatFormatting.RED;
            case UNASSIGNED, WORLD_DEAD -> ChatFormatting.GRAY;
            case GATEWAY, SELF -> ChatFormatting.GOLD;
            case DEGRADED -> ChatFormatting.YELLOW;
            case TX -> ChatFormatting.LIGHT_PURPLE;
            case HEADING, NEUTRAL -> ChatFormatting.WHITE;
            case SECONDARY -> ChatFormatting.DARK_GRAY;
        };
    }

    /** @return the {@link BossBarColor} for an {@link OwnershipState} (the zone bar). */
    public static BossBarColor bossBar(OwnershipState state) {
        return switch (state) {
            case OWNED -> BossBarColor.GREEN;
            case VALIDATING -> BossBarColor.BLUE;
            case REPLICA -> BossBarColor.PURPLE;
            case FOREIGN -> BossBarColor.RED;
            case UNASSIGNED -> BossBarColor.WHITE;
        };
    }

    /** @return the {@link BossBarColor} for a {@link Health} (the health bar). */
    public static BossBarColor bossBar(Health health) {
        return switch (health) {
            case HEALTHY -> BossBarColor.GREEN;
            case DEGRADED -> BossBarColor.YELLOW;
            case CRITICAL -> BossBarColor.RED;
        };
    }
}
