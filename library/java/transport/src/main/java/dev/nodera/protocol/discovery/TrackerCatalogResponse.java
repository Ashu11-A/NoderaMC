package dev.nodera.protocol.discovery;

import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * The tracker's directory listing — every listed world it knows, each a {@link TrackerCatalogEntry} —
 * the reply to a {@link TrackerCatalogQuery}. This is what lets the multiplayer "Worlds" tab browse
 * worlds the client had never heard of (the per-world {@link TrackerResponse} is fetched on select).
 *
 * <p>Thread-context: immutable record (defensively copied), safe for any thread.
 *
 * @param worlds the listed worlds, in the tracker's order.
 */
public record TrackerCatalogResponse(List<TrackerCatalogEntry> worlds) implements NoderaMessage {

    public TrackerCatalogResponse {
        Objects.requireNonNull(worlds, "worlds");
        worlds = List.copyOf(worlds);
    }
}
