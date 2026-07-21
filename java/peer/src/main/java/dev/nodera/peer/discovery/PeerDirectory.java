package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.membership.PeerEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * "Who have I seen, where, and when" — the per-world peer index the tracker answers from (Task 20).
 *
 * <p>Fed by membership gossip (which stays the liveness spine; this only indexes it). Entries are
 * keyed by {@code (genesisHash, nodeId)} because a network may host several torrent worlds at once
 * and the same peer may be in more than one of them.
 *
 * <h2>Time is passed in, never read</h2>
 *
 * <p>Every method that records or evaluates liveness takes an explicit {@code nowMillis}. The class
 * never calls a clock itself, which keeps staleness behaviour exactly testable — "a peer goes stale
 * after N ms" is asserted by arithmetic rather than by sleeping.
 *
 * <h2>Bounded</h2>
 *
 * <p>Directory contents arrive from remote peers, so the entry count is capped per world and
 * eviction is least-recently-seen first. A peer that gossips thousands of fabricated members can
 * cost the receiver a bounded amount of memory and nothing more.
 *
 * <p>Thread-context: thread-safe; all mutating and reading methods synchronise on the directory.
 */
public final class PeerDirectory {

    /** Default cap on remembered peers per world. */
    public static final int DEFAULT_MAX_PEERS_PER_WORLD = 1024;

    /** Default staleness window: a peer unseen for this long is not reported as online. */
    public static final long DEFAULT_STALE_AFTER_MILLIS = 60_000L;

    /**
     * One remembered peer.
     *
     * @param entry        the peer's wire description (id, route, capabilities, bootstrap flag).
     * @param lastSeenMillis when it was last observed.
     * @Thread-context immutable record, safe for any thread.
     */
    public record Known(PeerEntry entry, long lastSeenMillis) {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if {@code entry} is null.
         */
        public Known {
            Objects.requireNonNull(entry, "entry");
        }

        /** @return the peer's id. */
        public NodeId nodeId() {
            return entry.nodeId();
        }

        /** @return the peer's declared capabilities. */
        public NodeCapabilities capabilities() {
            return entry.capabilities();
        }
    }

    private final int maxPeersPerWorld;
    private final long staleAfterMillis;

    /** genesisHash → (nodeId → Known), access-ordered so eviction is least-recently-seen. */
    private final Map<Bytes, LinkedHashMap<NodeId, Known>> worlds = new LinkedHashMap<>();

    /** Create a directory with default bounds. */
    public PeerDirectory() {
        this(DEFAULT_MAX_PEERS_PER_WORLD, DEFAULT_STALE_AFTER_MILLIS);
    }

    /**
     * @param maxPeersPerWorld cap on remembered peers per world; must be positive.
     * @param staleAfterMillis how long a peer stays "online" after its last sighting; must be
     *                         positive.
     * @throws IllegalArgumentException if a bound is not positive.
     * @Thread-context any thread (construction only).
     */
    public PeerDirectory(int maxPeersPerWorld, long staleAfterMillis) {
        if (maxPeersPerWorld <= 0) {
            throw new IllegalArgumentException("maxPeersPerWorld must be positive");
        }
        if (staleAfterMillis <= 0) {
            throw new IllegalArgumentException("staleAfterMillis must be positive");
        }
        this.maxPeersPerWorld = maxPeersPerWorld;
        this.staleAfterMillis = staleAfterMillis;
    }

    /**
     * Record a sighting of a peer in a world.
     *
     * @param genesisHash the world.
     * @param entry       the peer's wire description.
     * @param nowMillis   the observation time.
     * @throws IllegalArgumentException if a reference argument is null.
     * @Thread-context any thread.
     */
    public synchronized void seen(Bytes genesisHash, PeerEntry entry, long nowMillis) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(entry, "entry");
        LinkedHashMap<NodeId, Known> world = worlds.computeIfAbsent(
                genesisHash, k -> new LinkedHashMap<>(16, 0.75f, true));
        world.put(entry.nodeId(), new Known(entry, nowMillis));
        // The map is access-ordered, so its eldest entry is the least-recently-seen peer.
        while (world.size() > maxPeersPerWorld) {
            NodeId eldest = world.keySet().iterator().next();
            world.remove(eldest);
        }
    }

    /**
     * Forget a peer that explicitly left.
     *
     * @param genesisHash the world.
     * @param nodeId      the departing peer.
     * @Thread-context any thread.
     */
    public synchronized void forget(Bytes genesisHash, NodeId nodeId) {
        LinkedHashMap<NodeId, Known> world = worlds.get(genesisHash);
        if (world != null) {
            world.remove(nodeId);
            if (world.isEmpty()) {
                worlds.remove(genesisHash);
            }
        }
    }

    /**
     * @param genesisHash the world.
     * @param nodeId      the peer.
     * @return the remembered entry, present even if stale (callers that care ask
     *         {@link #online(Bytes, long)} instead).
     * @Thread-context any thread.
     */
    public synchronized Optional<Known> lookup(Bytes genesisHash, NodeId nodeId) {
        LinkedHashMap<NodeId, Known> world = worlds.get(genesisHash);
        return world == null ? Optional.empty() : Optional.ofNullable(world.get(nodeId));
    }

    /**
     * Peers currently considered online in a world, sorted by node id so two directories with the
     * same knowledge answer identically.
     *
     * @param genesisHash the world.
     * @param nowMillis   the evaluation time.
     * @return the online peers.
     * @Thread-context any thread.
     */
    public synchronized List<Known> online(Bytes genesisHash, long nowMillis) {
        LinkedHashMap<NodeId, Known> world = worlds.get(genesisHash);
        if (world == null) {
            return List.of();
        }
        List<Known> out = new ArrayList<>();
        for (Known k : world.values()) {
            if (nowMillis - k.lastSeenMillis() <= staleAfterMillis) {
                out.add(k);
            }
        }
        out.sort(Comparator.comparing(k -> k.nodeId().value().toString()));
        return List.copyOf(out);
    }

    /**
     * Every peer remembered for a world, online or not — the source for
     * {@link CachedPeerStore} snapshots, which deliberately want stale addresses too (a peer that
     * has been offline for a week is still the best redial candidate a client has).
     *
     * @param genesisHash the world.
     * @return the remembered peers, sorted by node id.
     * @Thread-context any thread.
     */
    public synchronized List<Known> all(Bytes genesisHash) {
        LinkedHashMap<NodeId, Known> world = worlds.get(genesisHash);
        if (world == null) {
            return List.of();
        }
        List<Known> out = new ArrayList<>(world.values());
        out.sort(Comparator.comparing(k -> k.nodeId().value().toString()));
        return List.copyOf(out);
    }

    /**
     * @return the genesis hashes of every world this directory knows peers in.
     * @Thread-context any thread.
     */
    public synchronized List<Bytes> worlds() {
        return List.copyOf(worlds.keySet());
    }

    /**
     * @param genesisHash the world.
     * @return how many peers are remembered for it (online or not).
     * @Thread-context any thread.
     */
    public synchronized int size(Bytes genesisHash) {
        LinkedHashMap<NodeId, Known> world = worlds.get(genesisHash);
        return world == null ? 0 : world.size();
    }
}
