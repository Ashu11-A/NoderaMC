package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decides <b>which piece to fetch next, and from whom</b> — deterministically (Task 19).
 *
 * <h2>Rarest-first, then rendezvous</h2>
 *
 * <p>Pieces are ordered by holder count ascending: the piece fewest peers hold is the one whose
 * loss would end the swarm, so it is replicated first. Ties — which are the common case early on,
 * when everyone holds everything or nothing — break by
 * {@code StableHash(manifestRoot, pieceIndex)}, not by index order. That matters: if ties broke by
 * index, every fetcher joining the same swarm would request piece 0 from the same seeder at the
 * same moment, serialising the swarm on one peer. Hashing the tie spreads fetchers over distinct
 * pieces while staying reproducible.
 *
 * <p>Holder choice for a piece uses the same rendezvous idea, keyed by
 * {@code StableHash(manifestRoot, pieceIndex, nodeId)}, so two fetchers wanting the same piece
 * still prefer different seeders, and a given (piece, holder-set) always resolves the same way.
 *
 * <p>Determinism is a testable property here (acceptance #5), not an accident: no wall clock, no
 * {@code Math.random}, no unordered-map iteration leaks into the order. {@link StableHash} is the
 * project's cross-JVM-stable hash, so two peers on different JDKs agree.
 *
 * <p>Thread-context: stateless static helpers; safe for any thread. Callers pass immutable
 * snapshots of the holder set.
 */
public final class PieceSelector {

    private PieceSelector() {}

    /**
     * Order the wanted pieces rarest-first.
     *
     * @param manifest the manifest being fetched.
     * @param holders  holder set: peer → the piece indexes that peer holds. Never mutated.
     * @param wanted   the piece indexes still needed.
     * @return {@code wanted}, ordered rarest-first with a deterministic tie-break. Pieces no peer
     *         holds sort first (count 0) so the caller can see them as unavailable rather than
     *         silently skipping them.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread.
     */
    public static List<Integer> order(
            PieceManifest manifest,
            Map<NodeId, ? extends Set<Integer>> holders,
            Collection<Integer> wanted) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(holders, "holders");
        Objects.requireNonNull(wanted, "wanted");

        long rootKey = rootKey(manifest.manifestRoot());
        List<Integer> ordered = new ArrayList<>(wanted);
        ordered.sort(Comparator
                .comparingInt((Integer index) -> holderCount(holders, index))
                .thenComparingLong(index -> StableHash.of(rootKey, index))
                // Final tie-break on the index itself: StableHash collisions are astronomically
                // unlikely but a comparator must still be a total order, or sort() may throw.
                .thenComparingInt(index -> index));
        return List.copyOf(ordered);
    }

    /**
     * Choose which peer to ask for one piece.
     *
     * @param manifestRoot the manifest root being fetched.
     * @param index        the piece index.
     * @param holders      holder set: peer → the piece indexes that peer holds.
     * @param exclude      peers to skip (already asked, or known-failed for this piece).
     * @return the chosen holder, or {@code null} if no eligible peer holds the piece.
     * @throws IllegalArgumentException if a required argument is null.
     * @Thread-context any thread.
     */
    public static NodeId chooseHolder(
            Bytes manifestRoot,
            int index,
            Map<NodeId, ? extends Set<Integer>> holders,
            Set<NodeId> exclude) {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(holders, "holders");
        Objects.requireNonNull(exclude, "exclude");

        long rootKey = rootKey(manifestRoot);
        NodeId best = null;
        long bestScore = 0;
        for (Map.Entry<NodeId, ? extends Set<Integer>> e : holders.entrySet()) {
            NodeId peer = e.getKey();
            if (exclude.contains(peer) || !e.getValue().contains(index)) {
                continue;
            }
            long score = StableHash.of(rootKey, index, StableHash.of(peer.value()));
            // Highest score wins; ties break on the node id's own stable hash so the winner never
            // depends on the iteration order of the (unordered) holder map.
            if (best == null || score > bestScore
                    || (score == bestScore && StableHash.of(peer.value())
                            > StableHash.of(best.value()))) {
                best = peer;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * How many peers in the holder set hold a given piece.
     *
     * @param holders holder set.
     * @param index   the piece index.
     * @return the holder count (0 if nobody holds it).
     * @Thread-context any thread.
     */
    public static int holderCount(Map<NodeId, ? extends Set<Integer>> holders, int index) {
        int count = 0;
        for (Set<Integer> held : holders.values()) {
            if (held.contains(index)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Fold a manifest root into a 64-bit key for {@link StableHash}. The root is a SHA-256, so its
     * leading 8 bytes are already uniformly distributed; folding the remainder in keeps the whole
     * root load-bearing rather than truncating it.
     *
     * @param manifestRoot the root.
     * @return a stable 64-bit key.
     * @Thread-context any thread.
     */
    static long rootKey(Bytes manifestRoot) {
        byte[] raw = manifestRoot.toArray();
        long state = 0;
        for (int i = 0; i < raw.length; i += 8) {
            long word = 0;
            for (int b = 0; b < 8 && i + b < raw.length; b++) {
                word = (word << 8) | (raw[i + b] & 0xFFL);
            }
            state = StableHash.mix(state, word);
        }
        return state;
    }
}
