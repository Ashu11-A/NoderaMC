package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.discovery.TrackerRoutesResponse;

import java.util.List;
import java.util.Optional;

/**
 * The read half of a tracker client — the two queries a content lane makes and the endpoint list it
 * reports them over.
 *
 * <p>This seam exists for a specific reason recorded in the network register (L-85). Seeder
 * resolution has to merge two answers that may disagree or arrive alone: the seeder index from
 * {@link #query} — whose {@code PeerEntry}s already carry a dial route — and the separate route
 * query from {@link #routes}. The defect the register names was that the first answer's route was
 * discarded and the code relied on the second, so a tracker that does not answer {@link #routes}
 * left every seeder known and unroutable. That merge is pure decision logic over two responses, but
 * it could not be exercised headlessly while the only implementation was {@link TrackerClient} — a
 * final class that opens sockets. Narrowing the dependency to this interface is what lets a test
 * hand in a tracker that answers one query and not the other.
 *
 * <p>It is deliberately read-only: announcing is a write with a cadence and an identity behind it,
 * and no content lane needs it. Widening this interface to cover announces would re-couple the two.
 *
 * <p>Thread-context: implementations must be thread-safe; callers query from arbitrary threads.
 */
public interface TrackerLookup extends AutoCloseable {

    /** @return the configured endpoints, in order; empty means "no tracker to ask". */
    List<TrackerClient.Endpoint> endpoints();

    /**
     * @param genesisHash the world.
     * @return the merged seeder/peer answer, or empty when no endpoint answered.
     */
    Optional<TrackerResponse> query(Bytes genesisHash);

    /**
     * @param genesisHash the world.
     * @return the merged route answer; peers empty when no endpoint knows the world (or when the
     *         tracker does not implement the query at all, which is the case this seam exists for).
     */
    TrackerRoutesResponse routes(Bytes genesisHash);

    @Override
    void close();
}
