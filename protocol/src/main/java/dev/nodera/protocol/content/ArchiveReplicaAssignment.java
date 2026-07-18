package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * "Re-replicate these pieces onto {@code assignee}" — a repair coordinator's directive (Task 21).
 *
 * <p>Sent when an {@code ArchiveAuditTask} finds a manifest under-replicated (a holder died or
 * evicted pieces). The {@code assignee} is the next-ranked peer by rendezvous placement; it pulls
 * each listed piece by hash from any current holder (Task 19's {@code ContentRequest}), verifies,
 * and answers with an {@link ArchiveReplicaAck}.
 *
 * <p>{@code pieceIndexes} is canonicalised — de-duplicated and sorted ascending — so the same
 * directive always encodes identically, and an audit that re-runs produces a byte-identical
 * assignment if nothing changed.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param manifestRoot the blob whose pieces are under-replicated.
 * @param assignee     the peer chosen to take the missing replicas.
 * @param pieceIndexes the pieces it should acquire, de-duplicated and ascending.
 */
public record ArchiveReplicaAssignment(
        Bytes manifestRoot, NodeId assignee, List<Integer> pieceIndexes) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null, the index list is empty, or any
     *                                  index is negative.
     */
    public ArchiveReplicaAssignment {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(assignee, "assignee");
        Objects.requireNonNull(pieceIndexes, "pieceIndexes");
        if (pieceIndexes.isEmpty()) {
            throw new IllegalArgumentException("pieceIndexes must not be empty");
        }
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer i : pieceIndexes) {
            Objects.requireNonNull(i, "pieceIndex");
            if (i < 0) {
                throw new IllegalArgumentException("piece index must be non-negative: " + i);
            }
            sorted.add(i);
        }
        pieceIndexes = List.copyOf(new ArrayList<>(sorted));
    }

    @Override
    public String toString() {
        return "ArchiveReplicaAssignment[" + manifestRoot.toShortHex(6) + " -> " + assignee
                + " x" + pieceIndexes.size() + "]";
    }
}
