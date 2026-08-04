package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;

import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Round-trip time to each world's host, measured the way Minecraft measures a server's.
 *
 * <h2>Why this is client-side</h2>
 *
 * <p>There is no latency anywhere in the peer data plane. Trackers and rendezvous endpoints are
 * probed and their RTT is kept, but no per-<i>world</i> figure exists and none is on the wire —
 * {@code NodeCapabilities.latencyMs} is a hardcoded 50 that nothing measures. Threading a real one
 * up from the worker would mean a new field in the STATE JSON, the parser, the view model and every
 * positional constructor along the way.
 *
 * <p>None of that is needed, because the number a player expects on a world row is exactly the
 * number vanilla already computes for a server row: a status handshake against the game endpoint.
 * The join flow already resolves that endpoint and already builds the {@link ServerData} the pinger
 * fills in. So this is vanilla's own pinger, pointed at the routes the Worlds tab is already
 * showing.
 *
 * <p>Only worlds with a live game endpoint can be pinged, and those are exactly the ones the list
 * already marks as joinable now. A world whose host is offline reports no ping rather than a
 * fabricated one — the same "unknown is not zero" rule the player counts follow.
 *
 * @Thread-context client thread; {@link #tick()} must be pumped from the screen's tick and
 *                 {@link #close()} called when it goes away.
 */
public final class WorldPingFeed {

    private final ServerStatusPinger pinger = new ServerStatusPinger();

    /** world id → the {@link ServerData} vanilla is filling in, so its ping can be read back. */
    private final Map<String, ServerData> pinged = new HashMap<>();

    /**
     * Start (or refresh) a ping for every world that has a live endpoint.
     *
     * <p>Idempotent per world: a world already being pinged is left alone, so calling this on the
     * list's refresh cadence does not open a connection per second per world.
     *
     * @param worlds the rows currently on screen.
     * @Thread-context client thread.
     */
    public void refresh(List<TorrentWorldEntry> worlds) {
        if (worlds == null) {
            return;
        }
        for (TorrentWorldEntry world : worlds) {
            String route = world.mcRoute();
            String id = world.worldIdHex();
            if (route == null || route.isBlank() || id == null || id.isBlank()
                    || pinged.containsKey(id)) {
                continue;
            }
            ServerData data = new ServerData(world.name(), route, ServerData.Type.OTHER);
            pinged.put(id, data);
            try {
                pinger.pingServer(data, () -> { }, () -> { });
            } catch (UnknownHostException | RuntimeException unreachable) {
                // A host that cannot even be resolved has no ping, and that is the honest answer.
                // Left in the map so it is not retried every frame; the next screen open retries it.
                data.ping = -1;
            }
        }
    }

    /**
     * The measured round trip for a world, or {@code -1} when there is none.
     *
     * @param worldIdHex the world.
     * @return milliseconds, or {@code -1} for "not measured" — never 0, which would read as instant.
     * @Thread-context client thread.
     */
    public long pingOf(String worldIdHex) {
        ServerData data = worldIdHex == null ? null : pinged.get(worldIdHex);
        if (data == null || data.ping <= 0) {
            return -1;
        }
        return data.ping;
    }

    /**
     * Drive the pinger's I/O.
     *
     * <p>Vanilla's pinger does its work here rather than on a thread of its own, so a screen that
     * forgets to call this measures nothing and reports every world as unknown.
     *
     * @Thread-context client thread.
     */
    public void tick() {
        pinger.tick();
    }

    /**
     * Drop every pending ping.
     *
     * <p>Called when the screen closes. Vanilla's pinger holds open connections until they answer,
     * and a screen that leaves them behind leaks one socket per world per visit.
     *
     * @Thread-context client thread.
     */
    public void close() {
        pinger.removeAll();
        pinged.clear();
    }
}
