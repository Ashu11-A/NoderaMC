package dev.nodera.mod.debug.render;

import dev.nodera.diagnostics.classify.ZoneClassifier;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.state.Health;
import dev.nodera.diagnostics.state.OwnershipState;
import dev.nodera.diagnostics.view.ViewBuilder;
import dev.nodera.mod.debug.Dimensions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player boss bars for the HUD (Task 18 surface: boss bars) — up to three per player:
 * <ul>
 *   <li><b>Zone bar</b> — name = current region + state word; colour = {@link OwnershipState};
 *       progress = the state's fill fraction.</li>
 *   <li><b>Health bar</b> — session health; colour by {@link Health}; progress = quorum fill.</li>
 *   <li><b>Net bar</b> (shown when boss bars are on — {@code /nodera hud bars on}); progress =
 *       normalised throughput against a fixed ceiling.</li>
 * </ul>
 *
 * <p>Each bar is diffed against the last-sent signature (name + colour + progress bucket) and only
 * mutated when something changed, so a 20-tick cadence never spams boss-bar packets. When boss bars
 * are off for a player their players are removed (the bars vanish).
 *
 * <p>Thread-context: driven from the server main thread by {@link dev.nodera.mod.debug.DiagnosticsService}.
 */
public final class BossBarManager {

    /** Fixed 100%-throughput reference for the net-bar progress (256 KiB/s). */
    private static final double MAX_NET_BYTES_PER_SEC = 256.0 * 1024.0;

    private final ConcurrentHashMap<UUID, PlayerBars> bars = new ConcurrentHashMap<>();

    /**
     * Refresh the zone + health + net bars for {@code player} from {@code snap}. The zone bar reflects
     * the {@link OwnershipState} of the region the player is standing in (real geometry, placeholder
     * ownership until Task 6). When {@code bars} is false all three are removed.
     */
    public void update(ServerPlayer player, TelemetrySnapshot snap, boolean barsOn) {
        if (!barsOn) {
            remove(player);
            return;
        }
        PlayerBars pb = bars.computeIfAbsent(player.getUUID(), u -> new PlayerBars(player));
        OwnershipState zone = ZoneClassifier.classify(Dimensions.of(player),
                player.blockPosition().getX(), player.blockPosition().getZ(), snap.regions());
        pb.updateZone(zone);
        pb.updateHealth(snap);
        pb.updateNet(snap);
        pb.updateTps(currentTps(player.server));
    }

    /**
     * The server's live TPS from the vanilla average tick time, capped at the nominal 20
     * (issue #46: the debug TPS surface). On the integrated server this IS the current player's
     * TPS — each Nodera player runs their own server.
     */
    public static double currentTps(net.minecraft.server.MinecraftServer server) {
        double avgNanos = server.getAverageTickTimeNanos();
        if (avgNanos <= 0) {
            return 20.0;
        }
        return Math.min(20.0, 1_000_000_000.0 / avgNanos);
    }

    /** Remove all bars for a player (logout / HUD off). */
    public void remove(ServerPlayer player) {
        PlayerBars pb = bars.remove(player.getUUID());
        if (pb != null) {
            pb.zoneBar.removeAllPlayers();
            pb.healthBar.removeAllPlayers();
            pb.netBar.removeAllPlayers();
            pb.tpsBar.removeAllPlayers();
        }
    }

    /** Tear down every bar (server stopping). */
    public void clear() {
        for (PlayerBars pb : bars.values()) {
            pb.zoneBar.removeAllPlayers();
            pb.healthBar.removeAllPlayers();
            pb.netBar.removeAllPlayers();
            pb.tpsBar.removeAllPlayers();
        }
        bars.clear();
    }

    /** The three bars owned for one player, with their last-sent diff signatures. */
    private static final class PlayerBars {
        final ServerPlayer player;
        final ServerBossEvent zoneBar;
        final ServerBossEvent healthBar;
        final ServerBossEvent netBar;
        final ServerBossEvent tpsBar;
        String zoneKey = "";
        String healthKey = "";
        String netKey = "";
        String tpsKey = "";
        boolean netVisible = false;

        PlayerBars(ServerPlayer player) {
            this.player = player;
            zoneBar = new ServerBossEvent(Component.literal("Nodera zone"),
                    BossBarColor.WHITE, BossBarOverlay.PROGRESS);
            healthBar = new ServerBossEvent(Component.literal("Nodera health"),
                    BossBarColor.GREEN, BossBarOverlay.PROGRESS);
            netBar = new ServerBossEvent(Component.literal("Nodera net"),
                    BossBarColor.PURPLE, BossBarOverlay.PROGRESS);
            tpsBar = new ServerBossEvent(Component.literal("Nodera TPS"),
                    BossBarColor.BLUE, BossBarOverlay.PROGRESS);
            zoneBar.addPlayer(player);
            healthBar.addPlayer(player);
            netBar.addPlayer(player);
            tpsBar.addPlayer(player);
            netVisible = true;
        }

        void updateZone(OwnershipState state) {
            String name = "Zone " + state.name();
            String key = name + "|" + state + "|" + progressOf(state);
            if (!key.equals(zoneKey)) {
                zoneBar.setName(Component.literal(name));
                zoneBar.setColor(Palette.bossBar(state));
                zoneBar.setProgress(progressOf(state));
                zoneKey = key;
            }
        }

        void updateHealth(TelemetrySnapshot snap) {
            Health h = snap.health().state();
            String name = "Health " + h.name();
            float progress = switch (h) {
                case HEALTHY -> 1.0f;
                case DEGRADED -> 0.5f;
                case CRITICAL -> 0.2f;
            };
            String key = name + "|" + h + "|" + progress;
            if (!key.equals(healthKey)) {
                healthBar.setName(Component.literal(name));
                healthBar.setColor(Palette.bossBar(h));
                healthBar.setProgress(progress);
                healthKey = key;
            }
        }

        void updateNet(TelemetrySnapshot snap) {
            double total = snap.net().bytesPerSecTx() + snap.net().bytesPerSecRx();
            // Linear normalisation against a fixed ceiling (the log1p form saturated at ~54 B/s).
            float progress = (float) Math.min(1.0, total / MAX_NET_BYTES_PER_SEC);
            String name = "▲" + ViewBuilder.formatRate(snap.net().bytesPerSecTx())
                    + " ▼" + ViewBuilder.formatRate(snap.net().bytesPerSecRx());
            int bucket = Math.round(progress * 40);
            String key = name + "|" + bucket;
            if (!key.equals(netKey)) {
                netBar.setName(Component.literal(name));
                netBar.setColor(BossBarColor.PURPLE);
                netBar.setProgress(progress);
                netKey = key;
            }
        }

        void updateTps(double tps) {
            // Traffic-light colour: ≥18 healthy, ≥10 struggling, below that critical.
            BossBarColor color = tps >= 18.0 ? BossBarColor.GREEN
                    : tps >= 10.0 ? BossBarColor.YELLOW : BossBarColor.RED;
            float progress = (float) Math.min(1.0, tps / 20.0);
            String name = String.format(java.util.Locale.ROOT, "TPS %.1f", tps);
            int bucket = Math.round(progress * 40);
            String key = name + "|" + color + "|" + bucket;
            if (!key.equals(tpsKey)) {
                tpsBar.setName(Component.literal(name));
                tpsBar.setColor(color);
                tpsBar.setProgress(progress);
                tpsKey = key;
            }
        }

        private static float progressOf(OwnershipState s) {
            return switch (s) {
                case OWNED -> 1.0f;
                case VALIDATING -> 0.66f;
                case REPLICA -> 0.33f;
                case FOREIGN, UNASSIGNED -> 0.0f;
            };
        }
    }
}
