package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.storage.io.AtomicFileWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Addresses remembered from previous sessions — bootstrap mechanism #2 (Task 20).
 *
 * <p>This is the mechanism that makes a world outlive its original host. Once a client has been in
 * a world, it knows a set of peers; if the configured bootstrap is gone next time, it redials those
 * instead. Nothing here is trusted: a cached address is a hint about <i>where to knock</i>, and the
 * genesis-hash + certificate check on the other side is what decides whether the answer is the
 * world the client wanted.
 *
 * <p>Persistence is a canonical-encoded file written <b>atomically</b> (temp file + move), because
 * the alternative — truncating the real file and crashing mid-write — leaves a client with no way
 * back into its world at exactly the moment it most needs one.
 *
 * <p>Thread-context: thread-safe; mutating and reading methods synchronise on the store. File IO is
 * performed by the caller's thread inside {@link #save(Path)} / {@link #load(Path)}.
 */
public final class CachedPeerStore {

    /** Default cap on remembered addresses per world. */
    public static final int DEFAULT_MAX_ENTRIES_PER_WORLD = 64;

    /**
     * One remembered peer address.
     *
     * @param genesisHash    the world it was seen in.
     * @param nodeId         the peer.
     * @param route          the transport route ({@code "host:port"} for the socket transport).
     * @param lastSeenMillis when it was last seen.
     * @Thread-context immutable record, safe for any thread.
     */
    public record CachedPeer(Bytes genesisHash, NodeId nodeId, String route, long lastSeenMillis)
            implements Encodable {

        /**
         * Compact constructor.
         *
         * @throws IllegalArgumentException if a reference argument is null or {@code route} is
         *                                  blank (a blank route is not dialable, so remembering it
         *                                  only wastes a slot a usable address could occupy).
         */
        public CachedPeer {
            Objects.requireNonNull(genesisHash, "genesisHash");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(route, "route");
            if (route.isBlank()) {
                throw new IllegalArgumentException("route must not be blank");
            }
        }

        @Override
        public void encode(CanonicalWriter w) {
            w.writeU16(TypeTags.CACHED_PEER).writeU16(ENCODING_VERSION);
            w.writeBytes(genesisHash);
            w.writeU64(nodeId.value().getMostSignificantBits());
            w.writeU64(nodeId.value().getLeastSignificantBits());
            w.writeString(route);
            w.writeU64(lastSeenMillis);
        }

        /**
         * Full-frame decode.
         *
         * @param r the reader positioned at this value's tag.
         * @return the decoded entry.
         * @throws IllegalStateException if the next tag is not {@code CACHED_PEER}.
         * @Thread-context not thread-safe; one reader per decode call.
         */
        public static CachedPeer decode(CanonicalReader r) {
            int tag = r.readU16();
            if (tag != TypeTags.CACHED_PEER) {
                throw new IllegalStateException("expected CACHED_PEER tag, got " + tag);
            }
            r.readVersion(ENCODING_VERSION);
            Bytes genesisHash = r.readBytesValue();
            long msb = r.readU64();
            long lsb = r.readU64();
            String route = r.readString();
            long lastSeen = r.readU64();
            return new CachedPeer(genesisHash, new NodeId(new UUID(msb, lsb)), route, lastSeen);
        }
    }

    private final int maxEntriesPerWorld;
    private final Map<Bytes, LinkedHashMap<NodeId, CachedPeer>> byWorld = new LinkedHashMap<>();

    /** Create a store with the default bound. */
    public CachedPeerStore() {
        this(DEFAULT_MAX_ENTRIES_PER_WORLD);
    }

    /**
     * @param maxEntriesPerWorld cap on remembered addresses per world; must be positive.
     * @throws IllegalArgumentException if the bound is not positive.
     * @Thread-context any thread (construction only).
     */
    public CachedPeerStore(int maxEntriesPerWorld) {
        if (maxEntriesPerWorld <= 0) {
            throw new IllegalArgumentException("maxEntriesPerWorld must be positive");
        }
        this.maxEntriesPerWorld = maxEntriesPerWorld;
    }

    /**
     * Remember a peer address.
     *
     * @param peer the address to remember; replaces any earlier entry for the same peer/world.
     * @throws IllegalArgumentException if {@code peer} is null.
     * @Thread-context any thread.
     */
    public synchronized void remember(CachedPeer peer) {
        Objects.requireNonNull(peer, "peer");
        LinkedHashMap<NodeId, CachedPeer> world =
                byWorld.computeIfAbsent(peer.genesisHash(), k -> new LinkedHashMap<>());
        world.put(peer.nodeId(), peer);
        // Evict the least-recently-SEEN entry, not the least-recently-inserted: the point of the
        // cache is to keep the addresses most likely to still answer.
        while (world.size() > maxEntriesPerWorld) {
            NodeId oldest = world.values().stream()
                    .min(Comparator.comparingLong(CachedPeer::lastSeenMillis))
                    .map(CachedPeer::nodeId)
                    .orElseThrow();
            world.remove(oldest);
        }
    }

    /**
     * Remembered addresses for a world, most-recently-seen first — the order to redial in.
     *
     * @param genesisHash the world.
     * @return the addresses.
     * @Thread-context any thread.
     */
    public synchronized List<CachedPeer> forWorld(Bytes genesisHash) {
        LinkedHashMap<NodeId, CachedPeer> world = byWorld.get(genesisHash);
        if (world == null) {
            return List.of();
        }
        List<CachedPeer> out = new ArrayList<>(world.values());
        out.sort(Comparator.comparingLong(CachedPeer::lastSeenMillis).reversed()
                .thenComparing(p -> p.nodeId().value().toString()));
        return List.copyOf(out);
    }

    /**
     * @param genesisHash the world.
     * @param nodeId      the peer to forget.
     * @Thread-context any thread.
     */
    public synchronized void forget(Bytes genesisHash, NodeId nodeId) {
        LinkedHashMap<NodeId, CachedPeer> world = byWorld.get(genesisHash);
        if (world != null) {
            world.remove(nodeId);
            if (world.isEmpty()) {
                byWorld.remove(genesisHash);
            }
        }
    }

    /** @return every remembered entry across all worlds, in canonical order. */
    public synchronized List<CachedPeer> all() {
        List<CachedPeer> out = new ArrayList<>();
        for (LinkedHashMap<NodeId, CachedPeer> world : byWorld.values()) {
            out.addAll(world.values());
        }
        out.sort(Comparator.comparing((CachedPeer p) -> p.genesisHash().toHex())
                .thenComparing(p -> p.nodeId().value().toString()));
        return List.copyOf(out);
    }

    /** @return how many addresses are remembered in total. */
    public synchronized int size() {
        return byWorld.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * Write the store to disk atomically.
     *
     * @param file the destination file (its parent directory is created if absent).
     * @throws UncheckedIOException if the write fails.
     * @Thread-context any thread; performs blocking IO on the calling thread.
     */
    public void save(Path file) {
        Objects.requireNonNull(file, "file");
        List<CachedPeer> entries = all();
        CanonicalWriter w = new CanonicalWriter(64 + entries.size() * 96);
        w.writeU32(entries.size());
        for (CachedPeer p : entries) {
            p.encode(w);
        }
        try {
            // Atomic where the filesystem supports it; a crash mid-save then leaves the previous
            // (still usable) file rather than a truncated one.
            AtomicFileWriter.write(file, w.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save cached peer store to " + file, e);
        }
    }

    /**
     * Read a store from disk.
     *
     * @param file the source file.
     * @return the loaded store, or an empty one if the file does not exist (a first run is normal,
     *         not an error).
     * @throws UncheckedIOException if the file exists but cannot be read.
     * @Thread-context any thread; performs blocking IO on the calling thread.
     */
    public static CachedPeerStore load(Path file) {
        Objects.requireNonNull(file, "file");
        CachedPeerStore store = new CachedPeerStore();
        if (!Files.exists(file)) {
            return store;
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read cached peer store " + file, e);
        }
        CanonicalReader r = new CanonicalReader(raw);
        long count = r.readU32();
        for (long i = 0; i < count; i++) {
            store.remember(CachedPeer.decode(r));
        }
        return store;
    }
}
