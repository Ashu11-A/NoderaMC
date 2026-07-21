package dev.nodera.mod.client.multiplayer;

import dev.nodera.diagnostics.view.TorrentWorldListView.TorrentWorldEntry;
import dev.nodera.protocol.discovery.TrackerResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter from tracker answers (Task 20 {@link TrackerResponse}) to the Minecraft-free
 * {@link TorrentWorldEntry} the view model consumes (Task 26). Unpacking happens HERE so
 * {@code diagnostics} keeps its {@code core}-only dependency rule. Live tracker querying (dialing
 * the {@code TrackerService} peer per world) arrives with the NeoForge live lane; this adapter is
 * the seam it will feed.
 *
 * <p>Thread-context: stateless static mapping; any thread.
 */
public final class TrackerDataSource {

    private TrackerDataSource() {
    }

    /** Map one tracker answer to a view-model entry, computing the countdown at {@code nowMillis}. */
    public static TorrentWorldEntry toEntry(TrackerResponse response, long nowMillis) {
        long deadline = response.retentionDeadlineEpochMillis();
        long secondsRemaining = deadline <= 0
                ? -1
                : Math.max(0, (deadline - nowMillis) / 1000);
        return new TorrentWorldEntry(
                response.worldName(),
                response.worldPlayerCount(),
                response.storedChunks(),
                response.reliabilityBps(),
                response.health(),
                secondsRemaining,
                ""); // owner unknown from a bare tracker answer; the directory query carries it
    }

    /** Map a batch of tracker answers. */
    public static List<TorrentWorldEntry> toEntries(List<TrackerResponse> responses, long nowMillis) {
        List<TorrentWorldEntry> out = new ArrayList<>(responses.size());
        for (TrackerResponse response : responses) {
            out.add(toEntry(response, nowMillis));
        }
        return out;
    }
}
