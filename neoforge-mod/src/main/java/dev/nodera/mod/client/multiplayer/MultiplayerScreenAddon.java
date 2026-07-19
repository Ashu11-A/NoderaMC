package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * The Task 26 screen hooks — a NeoForge {@link ScreenEvent.Init.Post} listener instead of a mixin
 * (an event suffices; {@code nodera.mixins.json} stays empty). On {@link JoinMultiplayerScreen} it
 * adds the {@link WorldSearchBox} + {@link TorrentWorldListWidget} pair alongside vanilla's server
 * list; on {@link CreateWorldScreen} it adds the {@link CreateTorrentWorldOption}. Tracker data
 * flows through {@link #setWorldSupplier} — the live tracker query wiring is the NeoForge live
 * lane; until it lands the list renders empty.
 *
 * <p>Thread-context: events fire on the client thread.
 */
public final class MultiplayerScreenAddon {

    private static volatile Supplier<List<TorrentWorldEntry>> worldSupplier = List::of;

    private MultiplayerScreenAddon() {
    }

    /** Install the live tracker feed (player-hosted + friends' + recently-joined worlds). */
    public static void setWorldSupplier(Supplier<List<TorrentWorldEntry>> supplier) {
        worldSupplier = supplier == null ? List::of : supplier;
    }

    /** Registered on the NeoForge event bus by {@code ClientBootstrap}. */
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof JoinMultiplayerScreen screen) {
            var font = Minecraft.getInstance().font;
            TorrentWorldListWidget list = new TorrentWorldListWidget(
                    8, 56, Math.max(200, screen.width / 3), screen.height - 120);
            list.setEntries(worldSupplier.get());
            WorldSearchBox search = new WorldSearchBox(
                    font, 8, 32, Math.max(200, screen.width / 3), 20, list::setSearch);
            event.addListener(search);
            event.addListener(list);
        } else if (event.getScreen() instanceof CreateWorldScreen screen) {
            var font = Minecraft.getInstance().font;
            CreateTorrentWorldOption option = new CreateTorrentWorldOption(
                    font, screen.width - 158, 8, 150);
            event.addListener(option.toggleWidget());
            event.addListener(option.passwordWidget());
        }
    }
}
