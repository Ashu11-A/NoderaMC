package dev.nodera.mod.client.worldlist;

import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.Cell;
import dev.nodera.diagnostics.view.PublicWorldBadgeView;
import dev.nodera.diagnostics.view.PublicWorldBadgeView.PublicWorldStatus;
import dev.nodera.mod.debug.render.ComponentRenderer;
import dev.nodera.mod.debug.render.Palette;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Task 31b: marks worlds that are shared to Nodera as <em>public</em> on the single-player world
 * list, showing how many peers are connected. A shared world is public by definition, so its listing
 * carries a badge + a live connected-peer count.
 *
 * <p><b>Data-source seam.</b> On {@link SelectWorldScreen} no world is loaded, so the in-JVM host
 * peer is not running and cannot report which saves are shared. The shared-world status therefore
 * comes from {@link #setStatusSupplier} — fed by the persistent Nodera node (the Task 32 companion
 * daemon), which knows the user's hosted worlds even with no world open, or by a future persisted
 * shared-worlds record. Until that lands the supplier returns empty and no badge renders — the same
 * "compile-clean now, live feed deferred" pattern as {@code MultiplayerScreenAddon.worldSupplier}.
 *
 * <p>This addon draws a screen-level summary of shared worlds (definitely position-safe). Per-row
 * badge placement over each {@code WorldSelectionList} entry needs the entry row geometry, which the
 * vanilla list does not expose across packages; that placement is the {@code runClient} GUI-pass
 * work (L-45), keyed off the same {@link PublicWorldBadgeView} model this class already uses.
 *
 * <p>Thread-context: events fire on the client render thread.
 */
public final class SelectWorldScreenAddon {

    /** Vanilla's title baseline — this line shares it, out at the right edge. */
    private static final int TITLE_BASELINE = 8;

    /** Clearance kept from the screen edge and from the centred title. */
    private static final int RIGHT_MARGIN = 8;

    private static volatile Supplier<List<PublicWorldStatus>> statusSupplier = List::of;

    private SelectWorldScreenAddon() {
    }

    /** Install the shared-world status feed (Task 32 daemon / persisted record). */
    public static void setStatusSupplier(Supplier<List<PublicWorldStatus>> supplier) {
        statusSupplier = supplier == null ? List::of : supplier;
    }

    /** Registered on the NeoForge event bus by {@code ClientBootstrap}. */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof SelectWorldScreen screen)) {
            return;
        }
        List<PublicWorldStatus> shared = statusSupplier.get();
        Cell cell = PublicWorldBadgeView.summaryCell(shared);
        if (cell == null) {
            return; // no shared worlds → nothing to annotate
        }
        // MC-GUI-5: the view model chose a key + count; the words come from the lang file here.
        Component summary = ComponentRenderer.text(cell);
        GuiGraphics graphics = event.getGuiGraphics();
        var font = Minecraft.getInstance().font;
        Integer colour = Palette.chat(Semantic.WORLD_HEALTHY).getColor();
        int rgb = colour == null ? 0xFFFFFF : colour;
        // BELOW the title, not on top of it. `y = 8` is where vanilla centres "Select World", so
        // this drew one line straight through another and both became unreadable.
        //
        // Vanilla's title sits at y=8 with a line height of 9, so the first row that is certainly
        // clear of it is y=20 — still in the header band, above the search box, and clear of the
        // list below.
        int y = TITLE_BASELINE;

        // The one server-like fact this screen can state today without new plumbing: how many
        // people are in those worlds right now. It comes from the same worker STATE the multiplayer
        // tab reads, and it distinguishes "nobody is playing" from "nobody has told me", which the
        // worker reports as -1 and this refuses to render as a zero.
        // ONE line, in the top-right corner.
        //
        // The header band is fully spoken for: vanilla's title is centred at y=8, and the search box
        // occupies y=22..42. Centring under the title — the first attempt at fixing the collision
        // with the title — put this text straight through that box instead. There is no clear
        // centred row up here at all.
        //
        // The right edge beside the title is free, because the title is short and centred. Skipped
        // entirely when the screen is too narrow for both, which is the only case where this could
        // still touch anything.
        Cell online = PublicWorldBadgeView.onlineCell(shared);
        Component line = online == null
                ? summary
                : Component.empty().append(summary)
                        .append(Component.literal("  ")).append(ComponentRenderer.text(online));
        int textWidth = font.width(line);
        int titleRight = screen.width / 2 + font.width(screen.getTitle()) / 2;
        int x = screen.width - textWidth - RIGHT_MARGIN;
        if (x <= titleRight + RIGHT_MARGIN) {
            return; // not enough room beside the title; saying nothing beats saying it twice
        }
        graphics.drawString(font, line, x, y, rgb);
    }
}
