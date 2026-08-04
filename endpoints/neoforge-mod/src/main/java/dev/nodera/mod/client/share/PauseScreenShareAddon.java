package dev.nodera.mod.client.share;

import dev.nodera.mod.common.NoderaPeerService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Replaces vanilla's "Open to LAN" button in the in-game pause menu with Nodera's
 * <b>"Open to Nodera"</b> (Task 31a — the redesign of Task 30b, which merely <em>added</em> a second
 * button beside the vanilla one). The action is unchanged — it opens {@link ShareWorldScreen}, the
 * analogue of vanilla's {@code ShareToLanScreen} — but the button now <em>takes the vanilla LAN
 * button's slot</em> so the player sees exactly one share affordance, the Nodera one.
 *
 * <p>Mechanism (no mixin, matching the {@code client.multiplayer} convention): on
 * {@link ScreenEvent.Init.Post} for a {@link PauseScreen}, find the vanilla LAN button among the
 * screen's listeners (its message is {@code menu.shareToLan}), capture its bounds, remove it, and add
 * the Nodera button at the same bounds. If the LAN button is absent (some states hide it), fall back
 * to a computed slot below the button column so the affordance never disappears.
 *
 * <p>The button appears only when the client is hosting a local integrated server
 * ({@link Minecraft#hasSingleplayerServer()}) — exactly where vanilla shows "Open to LAN". Its label
 * reflects whether the world is already shared.
 *
 * <p>Thread-context: fires on the client render thread.
 */
public final class PauseScreenShareAddon {

    /** Vanilla translation key for the pause-menu "Open to LAN" button. */
    private static final Component LAN_BUTTON = Component.translatable("menu.shareToLan");

    private static final int WIDTH = 204;

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
        // Sharing an unshared world makes you its author; changing a shared world's settings is
        // the author's alone. `hasSingleplayerServer()` above answers neither question — it asks
        // whether the server is in this JVM, which is TRUE for a player who has just recovered
        // somebody else's world onto their own machine. Those players were being handed that
        // world's share options, password field and delete button.
        if (!WorldOwnershipView.mayShare(sharing)) {
            return;
        }
        Component label = Component.translatable(
                sharing ? "nodera.open.button.sharing" : "nodera.open.button");

        // Prefer the vanilla LAN button's slot: find it, capture its bounds, remove it.
        Button lan = findLanButton(event);
        int x;
        int y;
        int width;
        int height;
        if (lan != null) {
            x = lan.getX();
            y = lan.getY();
            width = lan.getWidth();
            height = lan.getHeight();
            event.removeListener(lan);
        } else {
            // No LAN button to take the slot of — which is the ORDINARY case for a shared world,
            // not an edge one: vanilla only offers "Open to LAN" while the world is unpublished, and
            // sharing to Nodera publishes it. So this branch runs every time the button says
            // "Sharing options…", and it used to place itself at a hardcoded
            // `height / 4 + 128` — exactly where "Save and Quit to Title" sits. The two were drawn
            // on top of each other.
            //
            // Placed under the lowest widget actually on the screen instead. That cannot collide
            // with a layout it does not have to know about, and it survives other mods adding rows
            // of their own — which this menu already has several of.
            width = WIDTH;
            height = 20;
            x = screen.width / 2 - width / 2;
            y = belowLowestButton(event, screen);
        }

        Button share = Button.builder(label, b -> mc.setScreen(new ShareWorldScreen(screen)))
                .bounds(x, y, width, height)
                .build();
        event.addListener(share);
    }

    /**
     * A row below every button already on the screen.
     *
     * <p>Measured rather than assumed. The pause menu's layout is vanilla's, plus whatever other
     * mods have added to it, and a constant offset into it is a guess that was already wrong.
     *
     * @param event  the init event whose listeners are the current layout.
     * @param screen the pause screen, for the fallback when it has no buttons at all.
     * @return the y to place the Nodera button at.
     */
    private static int belowLowestButton(ScreenEvent.Init.Post event, PauseScreen screen) {
        int lowest = Integer.MIN_VALUE;
        int height = 20;
        for (GuiEventListener listener : event.getScreen().children()) {
            if (listener instanceof Button button) {
                lowest = Math.max(lowest, button.getY());
                height = button.getHeight();
            }
        }
        if (lowest == Integer.MIN_VALUE) {
            return screen.height / 4 + 128;
        }
        // One row's worth of gap, the same 4px vanilla leaves between its own rows.
        return lowest + height + 4;
    }

    /** Locate the vanilla "Open to LAN" button among the screen's listeners, or {@code null}. */
    private static Button findLanButton(ScreenEvent.Init.Post event) {
        for (GuiEventListener listener : event.getScreen().children()) {
            if (listener instanceof Button button && isLanButton(button)) {
                return button;
            }
        }
        return null;
    }

    private static boolean isLanButton(Button button) {
        return LAN_BUTTON.equals(button.getMessage());
    }
}
