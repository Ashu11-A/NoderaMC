package dev.nodera.mod.client.create;

import dev.nodera.endpoint.share.PendingCreateShare;
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
            // Below every widget already on the screen, measured rather than assumed.
            //
            // It used to sit at y=6, which is inside the create screen's TabNavigationBar band
            // (y 0..24). The tabs are centred within at most 400px, so on any window narrower than
            // ~716px — which is every ordinary GUI scale — this button was drawn straight over the
            // "World"/"More" tabs. The same class of mistake as the pause menu's hardcoded slot.
            int width = 150;
            int height = 20;
            Button nodera = Button.builder(label(), b ->
                            Minecraft.getInstance().setScreen(new NoderaCreateOptionsScreen(screen)))
                    .bounds(screen.width - width - 8, belowLowestWidget(event, screen),
                            width, height)
                    .build();
            event.addListener(nodera);
        } else if (event.getScreen() instanceof SelectWorldScreen
                || event.getScreen() instanceof TitleScreen
                || event.getScreen() instanceof JoinMultiplayerScreen) {
            // The player left the create flow without creating — disarm the parked share.
            PendingCreateShare.clear();
        }
    }

    /**
     * A row clear of every widget currently on the screen.
     *
     * <p>Measured, because this screen's layout is vanilla's plus whatever other mods added, and a
     * constant offset into it is a guess — one that was already wrong.
     */
    private static int belowLowestWidget(ScreenEvent.Init.Post event, CreateWorldScreen screen) {
        int lowest = Integer.MIN_VALUE;
        int height = 20;
        for (net.minecraft.client.gui.components.events.GuiEventListener listener
                : event.getScreen().children()) {
            if (listener instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
                lowest = Math.max(lowest, widget.getY());
                height = widget.getHeight();
            }
        }
        return lowest == Integer.MIN_VALUE ? 6 : lowest + height + 4;
    }

    private static Component label() {
        return Component.translatable(PendingCreateShare.peek().isPresent()
                ? "nodera.create.button.shared" : "nodera.create.button.private");
    }
}
