package dev.nodera.mod.client.multiplayer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * The name-filter box on the multiplayer page (Task 26): an {@link EditBox} whose responder
 * forwards the query to the list widget. Filtering itself is view-model logic
 * ({@code TorrentWorldListView.panel(entries, search)}) — this class is only the input surface.
 *
 * <p>Thread-context: client (render) thread only.
 */
public final class WorldSearchBox extends EditBox {

    public WorldSearchBox(Font font, int x, int y, int width, int height, Consumer<String> onQuery) {
        super(font, x, y, width, height, Component.translatable("nodera.multiplayer.search"));
        setHint(Component.translatable("nodera.multiplayer.search.hint"));
        setResponder(onQuery);
    }
}
