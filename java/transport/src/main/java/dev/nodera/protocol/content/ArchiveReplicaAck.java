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
 * <p>The ack is a claim, not proof: the coordinator does not trust it blindly. It re-runs the audit
 * (reading the {@code ArchiveInventory}, which the assignee refreshes by gossiping an
 * {@code InventoryAdvertisement}) and confirms the manifest is back at its replication factor. A
 * lying assignee that acks without actually fetching is caught by the next audit — and penalised by
 * Task 22's reliability scoring. State, as always, self-verifies; peer claims do not.
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
