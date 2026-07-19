package dev.nodera.protocol.membership;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A liveness / application keep-alive exchanged directly between peers (Phase 6 P2P continuity).
 *
 * <p>Each peer periodically sends a {@code SessionKeepAlive} to every other member over the
 * direct peer mesh. It serves two purposes: it drives the heartbeat failure detector (a member
 * from which no keep-alive arrives within the failure window is declared down), and it is the
 * observable signal that two players are <b>still connected to each other</b> even after the
 * bootstrap peer they originally connected through has gone offline. The monotonically
 * increasing {@code seq} lets a receiver measure loss/latency and de-duplicate.
 *
 * <p>The advisory {@code regionProgress} entries report the sender's last applied tick for each
 * region and assignment. They are operational lag signals only, never consensus truth. The list is
 * canonicalised on construction — sorted by region dimension and signed coordinates — and rejects
 * duplicate regions so every keep-alive has one byte-stable representation.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param from           the sender's stable id.
 * @param seq            monotonically increasing per-sender sequence number.
 * @param regionProgress advisory per-region progress, canonically sorted by region id.
 */
public record SessionKeepAlive(NodeId from, long seq, List<RegionProgress> regionProgress)
        implements NoderaMessage {

    private static final Comparator<RegionProgress> BY_REGION =
            Comparator.comparing((RegionProgress p) -> p.region().dimension().namespace())
                    .thenComparing(p -> p.region().dimension().path())
                    .thenComparingInt(p -> p.region().regionX())
                    .thenComparingInt(p -> p.region().regionZ());

    /**
     * Legacy convenience constructor. A caller that has no regional view reports empty progress.
     *
     * @param from the sender's stable id.
     * @param seq  monotonically increasing per-sender sequence number.
     */
    public SessionKeepAlive(NodeId from, long seq) {
        this(from, seq, List.of());
    }

    /**
     * Compact constructor: validates and canonicalises the progress list.
     *
     * @throws IllegalArgumentException if {@code from}, the list, or an entry is null, or if two
     *                                  entries report the same region.
     */
    public SessionKeepAlive {
        if (from == null) {
            throw new IllegalArgumentException("from must not be null");
        }
        if (regionProgress == null) {
            throw new IllegalArgumentException("regionProgress must not be null");
        }

        List<RegionProgress> sorted = new ArrayList<>(regionProgress.size());
        for (RegionProgress progress : regionProgress) {
            if (progress == null) {
                throw new IllegalArgumentException("regionProgress entry must not be null");
            }
            sorted.add(progress);
        }
        sorted.sort(BY_REGION);
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).region().equals(sorted.get(i).region())) {
                throw new IllegalArgumentException(
                        "duplicate region progress: " + sorted.get(i).region());
            }
        }
        regionProgress = List.copyOf(sorted);
    }
}
