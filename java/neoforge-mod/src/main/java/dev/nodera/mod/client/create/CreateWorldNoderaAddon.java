package dev.nodera.mod.client.create;

import dev.nodera.mod.common.PendingCreateShare;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Create-world integration (Task 5d redesign): instead of floating a toggle + password box over
 * the vanilla tabs, the create-world screen gets one clearly-labelled <b>"Nodera: Private /
 * Shared"</b> button (top-right, clear of the tab bar) that opens the dedicated
 * {@link NoderaCreateOptionsScreen}. The chosen options park in {@link PendingCreateShare}; the
 * freshly created world consumes them on first start and goes on the network immediately —
 * create-time sharing and pause-menu share-later are one code path ({@code NoderaHost.activate}).
 *
 * <p>Backing out of world creation (returning to the world-select / title / multiplayer surface)
 * clears the parked options, so a cancelled create can never share the next world the player
 * happens to load.
 *
 * <p>Thread-context: fires on the client render thread.
 */
public final class CreateWorldNoderaAddon {

    private CreateWorldNoderaAddon() {
    }

    /** Registered on the NeoForge event bus by {@code ClientBootstrap}. */
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof CreateWorldScreen screen) {
            Button nodera = Button.builder(label(), b ->
                            Minecraft.getInstance().setScreen(new NoderaCreateOptionsScreen(screen)))
                    .bounds(screen.width - 158, 6, 150, 20)
                    .build();
            event.addListener(nodera);
        } else if (event.getScreen() instanceof SelectWorldScreen
                || event.getScreen() instanceof TitleScreen
                || event.getScreen() instanceof JoinMultiplayerScreen) {
            // The player left the create flow without creating — disarm the parked share.
            PendingCreateShare.clear();
        }
    }

    private static Component label() {
        return Component.translatable(PendingCreateShare.peek().isPresent()
                ? "nodera.create.button.shared" : "nodera.create.button.private");
    }
}
