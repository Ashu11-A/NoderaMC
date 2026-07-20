package dev.nodera.transport.rendezvous;

import dev.nodera.core.identity.NodeId;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-{@code (peer, messageClass)} path policy for the rendezvous transport (Task 29; rendezvous.md
 * §4.7). Prefers {@link Path#DIRECT} over {@link Path#PUNCHED} over {@link Path#RELAYED}, with
 * transparent demotion on failure and re-promotion on success. Bulk traffic
 * ({@code StreamChunk}-shaped) strongly prefers a non-relayed path to spare relay bandwidth — the
 * same policy Task 10 had assigned to the never-built libp2p module, now owned here.
 *
 * <p>Thread-context: guarded internally; safe from any thread.
 */
public final class TransportSelector {

    /** A usable path to a peer, in descending preference order (lower ordinal = preferred). */
    public enum Path {
        /** A direct local-network or public connection. */
        DIRECT,
        /** A hole-punched direct connection upgraded from a relay circuit. */
        PUNCHED,
        /** An end-to-end-encrypted relay circuit — the always-available fallback. */
        RELAYED
    }

    /** The kind of traffic being sent; bulk avoids the relay unless it is the only path. */
    public enum MessageClass {
        /** Small control/consensus frames — any path is acceptable. */
        CONTROL,
        /** Large bulk transfers — a relayed path is a last resort. */
        BULK
    }

    private final Map<NodeId, Set<Path>> demoted = new HashMap<>();

    /**
     * Choose the best available path to {@code peer} for {@code messageClass}.
     *
     * <p>Returns the most-preferred available path that is not currently demoted; if every available
     * path has been demoted, the peer's demotions are cleared and the most-preferred available path
     * is retried (a stuck peer must still be reachable). For {@link MessageClass#BULK}, a relayed
     * path is chosen only when it is the sole option.
     *
     * @param peer         the destination peer.
     * @param messageClass the traffic class.
     * @param available    the paths currently believed usable (non-empty).
     * @return the chosen path.
     * @throws IllegalArgumentException if {@code available} is empty.
     * @Thread-context any thread.
     */
    public synchronized Path select(NodeId peer, MessageClass messageClass, Set<Path> available) {
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(messageClass, "messageClass");
        if (available.isEmpty()) {
            throw new IllegalArgumentException("no available paths to " + peer);
        }
        Set<Path> down = demoted.getOrDefault(peer, EnumSet.noneOf(Path.class));

        Path best = bestOf(available, down, messageClass);
        if (best != null) {
            return best;
        }
        // Every path is demoted: forget this peer's demotions and retry from scratch.
        demoted.remove(peer);
        Path retry = bestOf(available, EnumSet.noneOf(Path.class), messageClass);
        return retry != null ? retry : firstByPreference(available);
    }

    /**
     * Record that a path to a peer failed, so the next {@link #select} avoids it.
     *
     * @param peer the peer.
     * @param path the failed path.
     * @Thread-context any thread.
     */
    public synchronized void recordFailure(NodeId peer, Path path) {
        demoted.computeIfAbsent(peer, p -> EnumSet.noneOf(Path.class)).add(path);
    }

    /**
     * Record that a path to a peer succeeded, clearing any demotion for it.
     *
     * @param peer the peer.
     * @param path the path that worked.
     * @Thread-context any thread.
     */
    public synchronized void recordSuccess(NodeId peer, Path path) {
        Set<Path> down = demoted.get(peer);
        if (down != null) {
            down.remove(path);
            if (down.isEmpty()) {
                demoted.remove(peer);
            }
        }
    }

    private static Path bestOf(Set<Path> available, Set<Path> down, MessageClass messageClass) {
        for (Path path : Path.values()) { // enum declaration order = preference order
            if (!available.contains(path) || down.contains(path)) {
                continue;
            }
            if (messageClass == MessageClass.BULK && path == Path.RELAYED && hasNonRelayed(available, down)) {
                continue; // bulk avoids the relay while any non-relayed path is up
            }
            return path;
        }
        return null;
    }

    private static boolean hasNonRelayed(Set<Path> available, Set<Path> down) {
        for (Path path : new Path[] {Path.DIRECT, Path.PUNCHED}) {
            if (available.contains(path) && !down.contains(path)) {
                return true;
            }
        }
        return false;
    }

    private static Path firstByPreference(Set<Path> available) {
        for (Path path : Path.values()) {
            if (available.contains(path)) {
                return path;
            }
        }
        throw new IllegalStateException("unreachable: available was checked non-empty");
    }
}
