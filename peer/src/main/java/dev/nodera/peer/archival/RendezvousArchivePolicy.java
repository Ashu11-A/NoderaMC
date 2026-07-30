package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Rendezvous-hashing placement (Task 21): the deterministic expected-holder computation.
 *
 * <h2>The ranking</h2>
 *
 * <p>Each {@code PARTIAL_ARCHIVE} peer gets a score
 * {@code StableHash(StableHash(manifestRootHex), StableHash(nodeUuid))} — two cross-JVM-stable
 * 64-bit hashes folded into one. The manifest root identifies the content, the node id identifies
 * the peer, so the same {@code (content, peer)} always scores the same on every JVM, and different
 * peers score differently for the same content. The top-R by score are the expected partial holders.
 *
 * <p>Ties (an astronomically unlikely 64-bit collision) break by node id, so the comparator is a
 * total order and {@code sort} is deterministic regardless of input order — the property the
 * placement property test pins.
 *
 * <p>Thread-context: stateless; safe from any thread.
 */
public final class RendezvousArchivePolicy implements ArchivePlacementPolicy {

    private final ReplicationFactors factors;

    /** Create the policy with the spec replication factors. */
    public RendezvousArchivePolicy() {
        this(ReplicationFactors.spec());
    }

    /**
     * @param factors the per-class replication factors.
     * @throws IllegalArgumentException if {@code factors} is null.
     * @Thread-context any thread (construction only).
     */
    public RendezvousArchivePolicy(ReplicationFactors factors) {
        this.factors = Objects.requireNonNull(factors, "factors");
    }

    @Override
    public List<NodeId> expectedHolders(
            Bytes manifestRoot,
            ArchiveObjectClass objectClass,
            List<NodeId> eligible,
            Set<NodeId> fullArchive) {
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(objectClass, "objectClass");
        Objects.requireNonNull(eligible, "eligible");
        Objects.requireNonNull(fullArchive, "fullArchive");

        // De-duplicate eligible while preserving nothing — order must not leak into the result.
        LinkedHashSet<NodeId> all = new LinkedHashSet<>(eligible);
        for (NodeId host : fullArchive) {
            if (!all.contains(host)) {
                throw new IllegalArgumentException(
                        "full-archive host " + host + " is not in the eligible set");
            }
        }

        long rootScore = StableHash.of(manifestRoot.toHex());

        List<NodeId> partial = new ArrayList<>();
        for (NodeId peer : all) {
            if (!fullArchive.contains(peer)) {
                partial.add(peer);
            }
        }

        // Hosts first, sorted by node id for a canonical ordering.
        List<NodeId> hosts = new ArrayList<>(fullArchive);
        hosts.sort(Comparator.comparing(n -> n.value().toString()));

        int r = factors.factor(objectClass, all.size());
        // The host does NOT count toward R: take the top-R PARTIAL peers, then add the host(s) on
        // top, so losing the host still leaves R distributed replicas (rule 0 + rule 3).
        int take = Math.min(r, partial.size());

        partial.sort(Comparator
                .comparingLong((NodeId peer) -> score(rootScore, peer))
                .reversed()
                .thenComparing(n -> n.value().toString()));
        List<NodeId> topPartial = partial.subList(0, take);

        List<NodeId> result = new ArrayList<>(hosts.size() + topPartial.size());
        result.addAll(hosts);
        result.addAll(topPartial);
        return List.copyOf(result);
    }

    /** @return the per-class replication factors in use. */
    public ReplicationFactors factors() {
        return factors;
    }

    private static long score(long rootScore, NodeId peer) {
        return StableHash.of(rootScore, StableHash.of(peer.value()));
    }
}
