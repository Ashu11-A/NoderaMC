package dev.nodera.mod.client.share;

import dev.nodera.mod.common.NoderaPeerService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds a "Share to Nodera" button to the in-game pause menu (Task 30b), the analogue of vanilla's
 * "Open to LAN". Uses the same {@link ScreenEvent.Init.Post} + {@code event.addListener(...)} pattern
 * the {@code client.multiplayer} addon established — no mixin.
 *
 * <p>The button appears only when the client is hosting a local integrated server
 * ({@link Minecraft#hasSingleplayerServer()}) — exactly the situation where vanilla shows "Open to
 * LAN". On a client connected to someone else's world there is nothing local to share, so the button
 * is absent. Its label reflects whether the world is already shared.
 *
 * <p>Thread-context: fires on the client render thread.
 */
public final class PauseScreenShareAddon {

    private PauseScreenShareAddon() {
    }

    /** Registered on the NeoForge event bus by {@code ClientBootstrap}. */
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen screen)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.hasSingleplayerServer()) {
            return; // nothing local to share (connected to a remote world)
        }
        boolean sharing = NoderaPeerService.get().isHosting();
        Component label = Component.translatable(
                sharing ? "nodera.share.button.sharing" : "nodera.share.button");
        // Slot it below the vanilla pause-menu button column (coordinates are GUI-pass-tunable).
        int width = 204;
        int x = screen.width / 2 - width / 2;
        int y = screen.height / 4 + 128;
        Button share = Button.builder(label, b -> mc.setScreen(new ShareWorldScreen(screen)))
                .bounds(x, y, width, 20)
                .build();
        event.addListener(share);
    }
}
