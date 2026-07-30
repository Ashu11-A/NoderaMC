package dev.nodera.mod.client.multiplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;


/**
 * Task 31c screen hooks (redesign of the Task 26 addon). Instead of adding widgets <em>beside</em>
 * vanilla's server list, this now <b>replaces</b> {@link JoinMultiplayerScreen} entirely with the
 * Nodera-only {@link NoderaMultiplayerScreen} (Worlds / Trackers / Rendezvous tabs) — "keep only the
 * Nodera system". Vanilla Direct-Connect / Add-Server are gone from the player's path (the vanilla
 * class is not modified — it is simply swapped out at open time). On {@link CreateWorldScreen} the
 * Task 26 torrent-hosting toggle + password is still added.
 *
 * <p>Uses a {@link ScreenEvent.Init.Post} listener (no mixin; {@code nodera.mixins.json} stays
 * empty). The world feed is installed straight on {@link NoderaMultiplayerScreen} by
 * {@code ClientBootstrap}.
 *
 * <p>Thread-context: events fire on the client thread.
 */
public final class MultiplayerScreenAddon {

    private MultiplayerScreenAddon() {
    }

    /** Registered on the NeoForge event bus by {@code ClientBootstrap}. */
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof JoinMultiplayerScreen) {
            // Replace vanilla's multiplayer screen with the Nodera-only surface. The new screen is not
            // a JoinMultiplayerScreen, so this listener does not re-fire for it (no loop). Parent is
            // resolved to the title screen on close. (The create-world integration lives in
            // dev.nodera.mod.client.create.CreateWorldNoderaAddon.)
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new NoderaMultiplayerScreen(null));
        }
    }
}
