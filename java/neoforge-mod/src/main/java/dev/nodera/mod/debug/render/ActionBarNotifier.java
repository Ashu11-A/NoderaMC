package dev.nodera.mod.debug.render;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Zone-crossing + alert toasts via the action bar and chat (Task 18 surface: alerts).
 *
 * <p>{@link dev.nodera.mod.debug.ZoneWatcher} calls these on the region-change edge — entering a
 * region outside the player's control fires the RED {@code ⚠ Entering unmanaged zone} alert;
 * re-entering an owned/validated region fires the GREEN {@code ✔ Entered your zone} notice.
 * Debounced to edges by the watcher; this class just renders + sends.
 *
 * <p>Thread-context: called on the server main thread ({@code PlayerTickEvent.Post}).
 */
public final class ActionBarNotifier {

    private ActionBarNotifier() {}

    /** Action-bar + chat: entering a foreign / unassigned zone (RED, bold). */
    public static void alertForeign(ServerPlayer player, String regionCoord, String owner) {
        actionBar(player, Component.literal("⚠ Entering unmanaged zone (region " + regionCoord + ")")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        MutableComponent line = Component.literal("Region " + regionCoord
                + (owner == null || owner.isEmpty() ? " is unassigned." : " is owned by " + owner + "."))
                .withStyle(ChatFormatting.GRAY);
        player.sendSystemMessage(line);
    }

    /** Action-bar: re-entered an owned / validated zone (GREEN). */
    public static void alertOwned(ServerPlayer player, String regionCoord) {
        actionBar(player, Component.literal("✔ Entered your zone (region " + regionCoord + ")")
                .withStyle(ChatFormatting.GREEN));
    }

    /** Send {@code text} to {@code player}'s action bar. */
    public static void actionBar(ServerPlayer player, Component text) {
        player.connection.send(new ClientboundSetActionBarTextPacket(text));
    }
}
