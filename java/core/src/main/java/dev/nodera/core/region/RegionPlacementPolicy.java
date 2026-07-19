package dev.nodera.core.region;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;

/**
 * The seam for deciding which node runs which region (Plan §3.5, Task 6). Declared in {@code core}
 * so the coordinator's concrete {@code RendezvousPlacementPolicy} can be swapped without any caller
 * knowing the scoring maths. Two responsibilities:
 *
 * <ul>
 *   <li>{@link #eligible(NodeCapabilities)} — a hard capability floor (does this node accept worker
 *       duty at all?);</li>
 *   <li>{@link #score(NodeId, RegionId, NodeCapabilities, double)} — a <b>deterministic</b> placement
 *       score; higher wins. The same inputs MUST produce the same score on every JVM (weighted
 *       rendezvous hashing over {@link dev.nodera.core.crypto.StableHash}), so every peer that scores
 *       a candidate set independently agrees on the ordering.</li>
 * </ul>
 *
 * @Thread-context implementations must be pure and deterministic; safe from any thread.
 */
public interface RegionPlacementPolicy {

    /**
     * @param caps the candidate's self-declared capabilities.
     * @return {@code true} if the node clears the hard floor to be considered for any region.
     */
    boolean eligible(NodeCapabilities caps);

    /**
     * Deterministic placement score for {@code node} on {@code region}. Higher scores are preferred;
     * ties are broken deterministically by the implementation (never by hash-map order).
     *
     * @param node        the candidate node.
     * @param region      the region being placed.
     * @param caps        the candidate's capabilities (cores/memory/latency weight in).
     * @param reliability the candidate's current reliability EMA in {@code [0, 1]}; quantised to a
     *                    band before mixing so no floating-point nondeterminism enters the score.
     * @return the placement score; higher wins.
     */
    long score(NodeId node, RegionId region, NodeCapabilities caps, double reliability);
}
