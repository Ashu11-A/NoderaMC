package dev.nodera.protocol.content;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * "I now hold these pieces" — an assignee's answer to an {@link ArchiveReplicaAssignment} (Task 21).
 *
 * <p><b>Nothing sends this today</b>, because nothing sends the assignment it answers. The
 * push-side repair lane — audit task, repair service, and the inventory cache that told the
 * coordinator whether the ack was honest — was deleted on 2026-08-06 (Plan 11 round 2, issue #210);
 * repair is now the pull-side placement sweep described on {@link ArchiveReplicaAssignment}.
 *
 * <p>The design point survives the deletion and is worth keeping: an ack was always a claim, not
 * proof. A coordinator would have re-run its own audit rather than believe it, because state
 * self-verifies and peer claims do not. The sweep inherits that stance — a peer is an expected
 * holder because placement says so, never because it said it was.
 *
 * <p>The record and its wire tag stay: tag 31 is frozen in {@code WireRegistry} and the codec still
 * round-trips it. Treat this as a reserved shape, not as live protocol.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param manifestRoot the blob whose pieces were acquired.
 * @param assignee     the peer reporting.
 * @param pieceIndexes the pieces it now holds, de-duplicated and ascending.
 */
public record ArchiveReplicaAck(
        Bytes manifestRoot, NodeId assignee, List<Integer> pieceIndexes) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null, the index list is empty, or any
     *                                  index is negative.
     */
    public ArchiveReplicaAck {
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
        return "ArchiveReplicaAck[" + assignee + " " + manifestRoot.toShortHex(6)
                + " x" + pieceIndexes.size() + "]";
    }
}
