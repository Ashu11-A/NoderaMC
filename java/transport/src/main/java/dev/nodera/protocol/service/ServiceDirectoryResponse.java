package dev.nodera.protocol.service;

import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * A tracker's directory answer (wire tag
 * {@value dev.nodera.protocol.codec.MessageCodec#TAG_SERVICE_DIRECTORY_RESPONSE}).
 *
 * <p>Rows arrive sorted by descending composite score then ascending service id, so a peer that
 * ignores scoring entirely still gets a sensible order — but the order is a <i>hint</i>. A peer
 * verifies each row's signature and recomputes each composite from its components; merging answers
 * from several trackers is a union, never an arbitration.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param entries the rows, best first.
 */
public record ServiceDirectoryResponse(List<ServiceDirectoryEntry> entries)
        implements NoderaMessage {

    /**
     * Compact constructor: validates and defensive-copies the rows.
     *
     * @throws IllegalArgumentException if {@code entries} is null.
     */
    public ServiceDirectoryResponse {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }
}
