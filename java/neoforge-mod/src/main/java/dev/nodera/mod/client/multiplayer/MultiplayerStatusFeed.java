package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.RendezvousStatusView.PathKind;
import dev.nodera.diagnostics.view.RendezvousStatusView.RendezvousEndpointStatus;
import dev.nodera.diagnostics.view.TrackerStatusView.TrackerEndpointStatus;
import dev.nodera.mod.common.NoderaConfig;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Task 31/33 fix: the live feed behind the multiplayer screen's Trackers/Rendezvous tabs. Previously
 * the tabs always read empty suppliers and showed "No trackers configured" even when
 * {@code nodera-client.toml} was correct — the bug was that no one built rows from the configured
 * endpoints. This reads {@link NoderaConfig#CLIENT_TRACKER_ENDPOINTS}/{@code CLIENT_RENDEZVOUS_ENDPOINTS},
 * probes each with a short TCP connect on a background cadence, and exposes cached
 * {@link TrackerEndpointStatus}/{@link RendezvousEndpointStatus} rows the screen's suppliers return —
 * never blocking the render thread.
 *
 * <p>Thread-context: {@link #start} runs at client setup; the refresh runs on a daemon; the getters
 * are read on the render thread (cached, volatile).
 */
public final class MultiplayerStatusFeed {

    private static final int PROBE_TIMEOUT_MS = 800;

    private static volatile List<TrackerEndpointStatus> trackers = List.of();
    private static volatile List<RendezvousEndpointStatus> rendezvous = List.of();
    private static volatile long[] trackerLastOkMs = new long[0];
    private static volatile long[] rendezvousLastOkMs = new long[0];
    private static ScheduledExecutorService scheduler;

    private MultiplayerStatusFeed() {
    }

    /** Snapshot of configured tracker endpoints + reachability (for the screen supplier). */
    public static List<TrackerEndpointStatus> trackers() {
        return trackers;
    }

    /** Snapshot of configured rendezvous endpoints + reachability (for the screen supplier). */
    public static List<RendezvousEndpointStatus> rendezvous() {
        return rendezvous;
    }

    /** Start the background reachability refresh. Idempotent; builds the first snapshot immediately. */
    public static synchronized void start() {
        if (scheduler != null) {
            return;
        }
        List<String> trk = strings(NoderaConfig.CLIENT_TRACKER_ENDPOINTS.get());
        List<String> rdv = strings(NoderaConfig.CLIENT_RENDEZVOUS_ENDPOINTS.get());
        trackerLastOkMs = new long[trk.size()];
        rendezvousLastOkMs = new long[rdv.size()];
        refresh(trk, rdv); // immediate first snapshot so the tab is populated at once
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-multiplayer-status");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> refresh(trk, rdv), 5, 5, TimeUnit.SECONDS);
    }

    private static void refresh(List<String> trk, List<String> rdv) {
        long now = System.currentTimeMillis();
        List<TrackerEndpointStatus> t = new ArrayList<>(trk.size());
        for (int i = 0; i < trk.size(); i++) {
            boolean up = reachable(trk.get(i));
            if (up) {
                trackerLastOkMs[i] = now;
            }
            long since = trackerLastOkMs[i] == 0 ? -1 : (now - trackerLastOkMs[i]) / 1000;
            t.add(new TrackerEndpointStatus(trk.get(i), up, -1, up ? 0 : since));
        }
        List<RendezvousEndpointStatus> r = new ArrayList<>(rdv.size());
        for (int i = 0; i < rdv.size(); i++) {
            boolean up = reachable(rdv.get(i));
            r.add(new RendezvousEndpointStatus(rdv.get(i), up, -1, 0,
                    up ? PathKind.DIRECT : PathKind.NONE));
        }
        trackers = List.copyOf(t);
        rendezvous = List.copyOf(r);
    }

    /** A cheap liveness check: can we open a TCP connection to {@code host:port}? */
    private static boolean reachable(String endpoint) {
        int colon = endpoint.lastIndexOf(':');
        if (colon <= 0 || colon == endpoint.length() - 1) {
            return false;
        }
        try {
            String host = endpoint.substring(0, colon);
            int port = Integer.parseInt(endpoint.substring(colon + 1));
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> strings(List<? extends String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }
}
