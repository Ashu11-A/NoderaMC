package dev.nodera.mod.client.title;

import dev.nodera.mod.client.multiplayer.NoderaMultiplayerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Makes Nodera integral to the title screen (Task 31): vanilla's <b>Minecraft Realms</b> button —
 * a paid central-server product that has no meaning on a decentralized network — is removed, and
 * its slot becomes the <b>Nodera Network</b> button opening the {@link NoderaMultiplayerScreen}.
 * The vanilla Multiplayer button stays (its screen is swapped to the Nodera surface by
 * {@code MultiplayerScreenAddon}), so both affordances lead to the same Nodera-native place.
 *
 * <p>Mechanism (no mixin, same convention as the other screen addons): on
 * {@link ScreenEvent.Init.Post} for a {@link TitleScreen}, find the Realms button by its
 * {@code menu.online} message, capture its bounds, remove it, and add the Nodera button there. If
 * the button is absent the addon adds nothing — never a floating widget.
 *
 * <p>Thread-context: fires on the client render thread.
 */
public final class TitleScreenAddon {

    /** Vanilla translation key for the title-screen "Minecraft Realms" button. */
    private static final Component REALMS_BUTTON = Component.translatable("menu.online");

    private TitleScreenAddon() {
    }

    /** Registered on the NeoForge event bus by {@code ClientBootstrap}. */
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen screen)) {
            return;
        }
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof Button button && REALMS_BUTTON.equals(button.getMessage())) {
                Button nodera = Button.builder(
                                Component.translatable("nodera.title.network"),
                                b -> Minecraft.getInstance().setScreen(
                                        new NoderaMultiplayerScreen(screen)))
                        .bounds(button.getX(), button.getY(), button.getWidth(), button.getHeight())
                        .build();
                event.removeListener(button);
                event.addListener(nodera);
                return;
            }
        }
    }
}
