package dev.nodera.mod.mixin;

import dev.nodera.diagnostics.state.Semantic;
import dev.nodera.diagnostics.view.Cell;
import dev.nodera.mod.client.worldlist.WorldRowBadges;
import dev.nodera.mod.debug.render.ComponentRenderer;
import dev.nodera.mod.debug.render.Palette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A world that is shared on Nodera says so on its own row, with how many people are in it.
 *
 * <h2>Why this needs a mixin</h2>
 *
 * <p>Everything required to draw on a row is out of reach from outside the package.
 * {@code WorldListEntry.summary} is package-private, and the entry exposes {@code getLevelName()}
 * but not {@code getLevelId()} — and the level id is the save folder, which is the only route from a
 * row to the {@code nodera-world.dat} that names its world. Row geometry is no better:
 * {@code AbstractSelectionList.getRowTop} is protected, and re-deriving it from the scroll offset
 * means hardcoding an item height this code does not own.
 *
 * <p>There is also a correctness reason rather than only an access one. {@code ScreenEvent.Render}
 * fires <b>outside</b> the list's {@code enableScissor}, so anything drawn from there for a row that
 * has scrolled out of view paints over the header and footer instead of being clipped. Injecting
 * into the row's own render puts the badge inside the same scissor as the row it belongs to.
 *
 * <p>The screen already carries a summary line; this is the per-row half of the same fact.
 */
@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListEntryMixin {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    LevelSummary summary;

    /** Kept clear of the row's right edge, matching the gap vanilla leaves around its own text. */
    private static final int NODERA_ROW_MARGIN = 4;

    @Inject(method = "render", at = @At("RETURN"))
    private void nodera$drawSharedBadge(GuiGraphics graphics, int index, int top, int left,
                                        int width, int height, int mouseX, int mouseY,
                                        boolean hovered, float partialTick, CallbackInfo ci) {
        try {
            Cell cell = WorldRowBadges.badgeFor(this.summary.getLevelId());
            if (cell == null) {
                return;
            }
            Component badge = ComponentRenderer.text(cell);
            var font = Minecraft.getInstance().font;
            Integer colour = Palette.chat(Semantic.WORLD_HEALTHY).getColor();
            // Right-aligned on the row's first line, where vanilla writes the world name on the
            // left — the two cannot meet unless the name is long enough to reach the badge, and
            // vanilla already truncates nothing there, so the badge stays the rightmost thing.
            graphics.drawString(font, badge,
                    left + width - font.width(badge) - NODERA_ROW_MARGIN, top + 1,
                    colour == null ? 0xFFFFFF : colour);
        } catch (RuntimeException | LinkageError degraded) {
            // A row that cannot draw a badge is a row without a badge. Never take the world list
            // down for a decoration: this runs inside vanilla's render, and an exception here is a
            // screen the player cannot use.
            org.slf4j.LoggerFactory.getLogger("NoderaClient")
                    .debug("world-row badge failed: {}", degraded.toString());
        }
    }
}
