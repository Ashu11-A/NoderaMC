package dev.nodera.mod.debug;

import dev.nodera.diagnostics.DiagnosticsCollector;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.mod.debug.render.BossBarManager;
import dev.nodera.mod.debug.render.TabListRenderer;
import dev.nodera.peer.PeerRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-side diagnostics driver for the HUD (Task 18) — owns the sample cadence and renders the
 * latest {@link TelemetrySnapshot} to every surface, per player, per their HUD preferences.
 *
 * <p>On {@code ServerTickEvent.Post} (every {@code sampleTicks}, default 20) it samples the
 * {@link DiagnosticsCollector} into a fresh immutable snapshot, then for each online player:
 * pushes the tab-list packet (if enabled) and refreshes the boss bars (if enabled). Zone alerts are
 * edge-driven by {@link ZoneWatcher} on {@code PlayerTickEvent.Post}. Everything is diffed or
 * edge-throttled so the cadence never causes a packet storm or TPS regression.
 *
 * <p>Thread-context: all callbacks run on the server main thread; prefs are concurrent maps so
 * command threads can mutate them safely.
 */
public final class DiagnosticsService {

    /** Default sample cadence (ticks) — once per second. */
    public static final int DEFAULT_SAMPLE_TICKS = 20;

    /** Per-player HUD preferences. All default on; the net bar rides on {@code bars}. */
    public record HudPref(boolean tab, boolean bars, boolean alerts) {
        static HudPref defaults() {
            return new HudPref(true, true, true);
        }
    }

    /** HUD surface selectable by {@code /nodera hud}. */
    public enum Surface { TAB, BARS, ALERTS, ALL }

    private final PeerRuntime serverRuntime;
    private final DiagnosticsCollector collector;
    private volatile int sampleTicks;
    private final BossBarManager bossBars = new BossBarManager();
    private final ZoneWatcher zoneWatcher = new ZoneWatcher(this);
    private final Map<UUID, HudPref> prefs = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> tabShown = new ConcurrentHashMap<>();

    private long tickSeq;
    private volatile TelemetrySnapshot latest;

    public DiagnosticsService(PeerRuntime serverRuntime, DiagnosticsCollector collector) {
        this(serverRuntime, collector, DEFAULT_SAMPLE_TICKS);
    }

    public DiagnosticsService(PeerRuntime serverRuntime, DiagnosticsCollector collector, int sampleTicks) {
        this.serverRuntime = serverRuntime;
        this.collector = collector;
        this.sampleTicks = Math.max(1, sampleTicks);
    }

    /** Tune the sample cadence at runtime ({@code /nodera debug sample-rate}). */
    public void setSampleTicks(int sampleTicks) {
        this.sampleTicks = Math.max(1, sampleTicks);
    }

    /** @return the latest sampled snapshot (null before the first sample). */
    public TelemetrySnapshot latest() {
        return latest;
    }

    /**
     * @return the latest snapshot, or — if the periodic sampler has not ticked yet — a one-shot
     *         sample taken now. Returns {@code null} only if the server peer is genuinely offline.
     *         Lets commands ({@code /nodera zone} etc.) respond instantly during the first second
     *         of server boot instead of reporting "diagnostics offline".
     */
    public TelemetrySnapshot snapshotOrSample() {
        TelemetrySnapshot s = latest;
        if (s != null) {
            return s;
        }
        if (serverRuntime == null) {
            return null;
        }
        latest = collector.sample(tickSeq, System.nanoTime(),
                serverRuntime.nodeId(), serverRuntime.isBootstrap());
        return latest;
    }

    /** @return the HUD preference for {@code player} (defaults if unset). */
    public HudPref pref(UUID player) {
        return prefs.getOrDefault(player, HudPref.defaults());
    }

    /** Set a single surface preference for {@code player}. */
    public void setPref(UUID player, Surface surface, boolean on) {
        HudPref cur = prefs.getOrDefault(player, HudPref.defaults());
        HudPref next = switch (surface) {
            case TAB -> new HudPref(on, cur.bars, cur.alerts);
            case BARS -> new HudPref(cur.tab, on, cur.alerts);
            case ALERTS -> new HudPref(cur.tab, cur.bars, on);
            case ALL -> new HudPref(on, on, on);
        };
        prefs.put(player, next);
    }

    /** {@code ServerTickEvent.Post} hook: sample + render. */
    public void onServerTickPost(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (++tickSeq % sampleTicks != 0 || serverRuntime == null) {
            return;
        }
        latest = collector.sample(tickSeq, System.nanoTime(),
                serverRuntime.nodeId(), serverRuntime.isBootstrap());
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            HudPref pref = pref(id);
            if (pref.tab()) {
                player.connection.send(TabListRenderer.render(latest));
                tabShown.put(id, Boolean.TRUE);
            } else if (tabShown.remove(id) == Boolean.TRUE) {
                // Tab just turned off: clear the stale header/footer once (the client pins the last
                // packet until overwrite/disconnect).
                player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
            }
            if (pref.bars()) {
                bossBars.update(player, latest, true);
            } else {
                bossBars.remove(player);
            }
        }
    }

    /** {@code PlayerTickEvent.Post} hook: zone-edge alerts. */
    public void onPlayerTickPost(net.minecraft.world.entity.player.Player player) {
        if (player.level().isClientSide()) {
            return;
        }
        zoneWatcher.onPlayerTick((ServerPlayer) player);
    }

    /** {@code PlayerEvent.PlayerLoggedOutEvent} hook: drop that player's bars + trackers. */
    public void onPlayerLoggedOut(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            bossBars.remove(player);
            zoneWatcher.remove(player.getUUID());
            tabShown.remove(player.getUUID());
        }
    }

    /** {@code ServerStoppingEvent} hook: clear all boss bars. */
    public void onServerStopping() {
        bossBars.clear();
    }
}
