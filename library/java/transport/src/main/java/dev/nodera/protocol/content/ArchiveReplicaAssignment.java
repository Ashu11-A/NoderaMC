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
 * <p><b>Nothing sends this today.</b> The push-side repair lane that produced it — an audit task
 * that found a manifest under-replicated and directed the next-ranked peer to take the missing
 * replicas, answering with an {@link ArchiveReplicaAck} — was deleted on 2026-08-06 (Plan 11 round
 * 2, issue #210) as a closed loop with no production entry point. Repair is now pull-side and
 * implicit: placement is a pure function of the live peer set
 * ({@code dev.nodera.peer.archival.RendezvousArchivePolicy}), so a holder's departure re-ranks the
 * worlds it held and {@code WorldReplicationService}'s next sweep adopts them without anyone being
 * told to. See {@code dev.nodera.peer.archival.package-info} for why the sweep holds the durability
 * property better than the directive did.
 *
 * <p>The record and its wire tag stay: tag 30 is frozen in {@code WireRegistry} so a released peer
 * never re-uses the number, and the codec still round-trips it. Only the sender is gone. Treat this
 * as a reserved shape, not as live protocol — if a push-side repair lane is ever wanted again, this
 * is the word it should speak.
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
