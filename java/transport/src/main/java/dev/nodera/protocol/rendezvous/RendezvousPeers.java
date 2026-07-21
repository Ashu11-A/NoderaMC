package dev.nodera.protocol.rendezvous;

import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * A page of discovered peer records (Task 29, wire tag 37; rendezvous.md §4.3). Each record is
 * independently signed and must be verified end-to-end by the discovering peer — the rendezvous
 * point introduced the peers, it did not vouch for them (rendezvous.md §8.1).
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param nextCursor cursor for the next {@link RendezvousDiscover}, or {@code 0} on the last page.
 * @param records    the records in this page.
 */
public record RendezvousPeers(int nextCursor, List<SignedRecord> records) implements NoderaMessage {

    /**
     * @throws IllegalArgumentException if {@code records} is null or {@code nextCursor} is negative.
     */
    public RendezvousPeers {
        Objects.requireNonNull(records, "records");
        if (nextCursor < 0) {
            throw new IllegalArgumentException("nextCursor must be non-negative");
        }
        records = List.copyOf(records);
    }
}
