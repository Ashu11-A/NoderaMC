package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.PieceMapView;
import dev.nodera.diagnostics.view.PieceMapView.PieceMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * Task 31d: the per-world piece-map screen, reached from the "View pieces" button on the Nodera
 * multiplayer screen. Renders a {@link PieceMapWidget} (the torrent-chunk grid) for the selected
 * world; the grid data is pulled live from {@code mapSupplier} each frame (fed by the Task 32
 * daemon's inventory / the local content store).
 *
 * <p>Thread-context: client render thread.
 */
public final class PieceMapScreen extends Screen {

    private final Screen parent;
    private final Supplier<PieceMap> mapSupplier;

    public PieceMapScreen(Screen parent, Supplier<PieceMap> mapSupplier) {
        super(Component.translatable(PieceMapView.TITLE));
        this.parent = parent;
        this.mapSupplier = mapSupplier;
    }

    @Override
    protected void init() {
        int margin = 20;
        PieceMapWidget grid = new PieceMapWidget(
                margin, 40, this.width - 2 * margin, this.height - 90, mapSupplier);
        addRenderableWidget(grid);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 30, 200, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
