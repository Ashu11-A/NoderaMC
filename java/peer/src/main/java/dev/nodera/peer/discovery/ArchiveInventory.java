package dev.nodera.peer.discovery;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.content.PieceBitmap;
import dev.nodera.protocol.discovery.InventoryAdvertisement;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The swarm's "who has what" (Task 20): {@code manifestRoot → (holder → piece bitmap)}.
 *
 * <p>Fed by {@link InventoryAdvertisement} gossip and Task 19's {@code ContentAvailability}. Read
 * by the tracker (to answer "who seeds this?"), by Task 19's downloader (to compute rarest-first
 * order), and by Task 21's placement audit (expected holders vs actual).
 *
 * <h2>Piece-level, not manifest-level</h2>
 *
 * <p>Holdings are stored as bitmaps rather than a boolean "has it". Manifest granularity would make
 * partial seeders inexpressible, and partial seeders are the entire point: the &lt;5%-per-peer rule
 * (Task 21) means the common case is many peers each holding a slice, none holding the whole.
 *
 * <h2>Bounded, because this is remote input</h2>
 *
 * <p>Everything here is asserted by other peers. The manifest count is capped with
 * least-recently-updated eviction, so a peer advertising 100 000 fabricated manifests costs the
 * receiver a fixed ceiling rather than its heap (acceptance #4).
 *
 * <p>Thread-context: thread-safe; all methods synchronise on the inventory.
 */
public final class ArchiveInventory {

    /** Default cap on tracked manifests ({@code inventory.maxManifests}). */
    public static final int DEFAULT_MAX_MANIFESTS = 4096;

    /** Default cap on holders remembered per manifest. */
    public static final int DEFAULT_MAX_HOLDERS_PER_MANIFEST = 256;

    /** Per-manifest holdings plus the world it belongs to. */
    private static final class Entry {
        private final Bytes genesisHash;
        private final Map<NodeId, Bytes> holders = new LinkedHashMap<>();

        Entry(Bytes genesisHash) {
            this.genesisHash = genesisHash;
        }
    }

    private final int maxManifests;
    private final int maxHoldersPerManifest;

    /** Access-ordered so eviction removes the least-recently-updated manifest. */
    private final LinkedHashMap<Bytes, Entry> manifests =
            new LinkedHashMap<>(16, 0.75f, true);

    /** Create an inventory with default bounds. */
    public ArchiveInventory() {
        this(DEFAULT_MAX_MANIFESTS, DEFAULT_MAX_HOLDERS_PER_MANIFEST);
    }

    /**
     * @param maxManifests          cap on tracked manifests; must be positive.
     * @param maxHoldersPerManifest cap on holders per manifest; must be positive.
     * @throws IllegalArgumentException if a bound is not positive.
     * @Thread-context any thread (construction only).
     */
    public ArchiveInventory(int maxManifests, int maxHoldersPerManifest) {
        if (maxManifests <= 0) {
            throw new IllegalArgumentException("maxManifests must be positive");
        }
        if (maxHoldersPerManifest <= 0) {
            throw new IllegalArgumentException("maxHoldersPerManifest must be positive");
        }
        this.maxManifests = maxManifests;
        this.maxHoldersPerManifest = maxHoldersPerManifest;
    }

    /**
     * Absorb one peer's advertisement. A later advertisement from the same peer <b>replaces</b> its
     * previous holdings for each named manifest — a peer that evicted pieces (Task 22) must be able
     * to shrink its claim, not only grow it.
     *
     * @param advertisement the gossiped holdings.
     * @throws IllegalArgumentException if {@code advertisement} is null.
     * @Thread-context any thread.
     */
    public synchronized void absorb(InventoryAdvertisement advertisement) {
        Objects.requireNonNull(advertisement, "advertisement");
        for (ManifestHolding holding : advertisement.holdings()) {
            record(advertisement.genesisHash(), holding.manifestRoot(),
                    advertisement.holder(), holding.pieceBitmap());
        }
    }

    /**
     * Record one peer's holdings of one manifest.
     *
     * @param genesisHash  the world the manifest belongs to.
     * @param manifestRoot the manifest.
     * @param holder       the holding peer.
     * @param pieceBitmap  its held pieces, packed per {@link PieceBitmap}.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread.
     */
    public synchronized void record(
            Bytes genesisHash, Bytes manifestRoot, NodeId holder, Bytes pieceBitmap) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(manifestRoot, "manifestRoot");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(pieceBitmap, "pieceBitmap");

        Entry entry = manifests.computeIfAbsent(manifestRoot, k -> new Entry(genesisHash));
        if (pieceBitmap.isEmpty()) {
            // An empty bitmap is a claim of "I hold nothing", which is a retraction, not an entry.
            entry.holders.remove(holder);
        } else {
            entry.holders.put(holder, pieceBitmap);
            while (entry.holders.size() > maxHoldersPerManifest) {
                NodeId eldest = entry.holders.keySet().iterator().next();
                entry.holders.remove(eldest);
            }
        }
        if (entry.holders.isEmpty()) {
            manifests.remove(manifestRoot);
            return;
        }
        while (manifests.size() > maxManifests) {
            Bytes eldest = manifests.keySet().iterator().next();
            manifests.remove(eldest);
        }
    }

    /**
     * Drop a peer entirely — it left the network, so its claims are void everywhere.
     *
     * @param holder the departed peer.
     * @Thread-context any thread.
     */
    public synchronized void forgetHolder(NodeId holder) {
        Objects.requireNonNull(holder, "holder");
        List<Bytes> emptied = new ArrayList<>();
        for (Map.Entry<Bytes, Entry> e : manifests.entrySet()) {
            e.getValue().holders.remove(holder);
            if (e.getValue().holders.isEmpty()) {
                emptied.add(e.getKey());
            }
        }
        emptied.forEach(manifests::remove);
    }

    /**
     * @param manifestRoot the manifest.
     * @return its holders, sorted by node id.
     * @Thread-context any thread.
     */
    public synchronized List<NodeId> holdersOf(Bytes manifestRoot) {
        Entry entry = manifests.get(manifestRoot);
        if (entry == null) {
            return List.of();
        }
        List<NodeId> out = new ArrayList<>(entry.holders.keySet());
        out.sort(Comparator.comparing(n -> n.value().toString()));
        return List.copyOf(out);
    }

    /**
     * The holder set in the shape Task 19's {@code PieceSelector} consumes.
     *
     * @param manifestRoot the manifest.
     * @return peer → held piece indexes.
     * @Thread-context any thread.
     */
    public synchronized Map<NodeId, Set<Integer>> holderSet(Bytes manifestRoot) {
        Entry entry = manifests.get(manifestRoot);
        if (entry == null) {
            return Map.of();
        }
        Map<NodeId, Set<Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<NodeId, Bytes> e : entry.holders.entrySet()) {
            BitSet bits = PieceBitmap.unpack(e.getValue());
            Set<Integer> held = new LinkedHashSet<>();
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                held.add(i);
            }
            out.put(e.getKey(), held);
        }
        return Map.copyOf(out);
    }

    /**
     * @param manifestRoot the manifest.
     * @param holder       the peer.
     * @return that peer's claimed bitmap, or an empty bitmap if it claims nothing.
     * @Thread-context any thread.
     */
    public synchronized Bytes bitmapOf(Bytes manifestRoot, NodeId holder) {
        Entry entry = manifests.get(manifestRoot);
        if (entry == null) {
            return PieceBitmap.empty();
        }
        Bytes bitmap = entry.holders.get(holder);
        return bitmap == null ? PieceBitmap.empty() : bitmap;
    }

    /**
     * Every manifest known for a world, with its seeders — the tracker's index.
     *
     * @param genesisHash the world.
     * @return manifest root → seeders, sorted by manifest root hex.
     * @Thread-context any thread.
     */
    public synchronized Map<Bytes, List<NodeId>> manifestsOfWorld(Bytes genesisHash) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        List<Map.Entry<Bytes, Entry>> matching = new ArrayList<>();
        for (Map.Entry<Bytes, Entry> e : manifests.entrySet()) {
            if (e.getValue().genesisHash.equals(genesisHash)) {
                matching.add(e);
            }
        }
        matching.sort(Comparator.comparing(e -> e.getKey().toHex()));
        Map<Bytes, List<NodeId>> out = new LinkedHashMap<>();
        for (Map.Entry<Bytes, Entry> e : matching) {
            List<NodeId> holders = new ArrayList<>(e.getValue().holders.keySet());
            holders.sort(Comparator.comparing(n -> n.value().toString()));
            out.put(e.getKey(), List.copyOf(holders));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * How many distinct pieces in a world have at least one holder — the tracker's
     * {@code storedChunks} counter.
     *
     * @param genesisHash the world.
     * @return the count of distinct held pieces across the world's manifests.
     * @Thread-context any thread.
     */
    public synchronized long storedPieces(Bytes genesisHash) {
        long total = 0;
        for (Entry entry : manifests.values()) {
            if (!entry.genesisHash.equals(genesisHash)) {
                continue;
            }
            BitSet union = new BitSet();
            for (Bytes bitmap : entry.holders.values()) {
                union.or(PieceBitmap.unpack(bitmap));
            }
            total += union.cardinality();
        }
        return total;
    }

    /**
     * @param genesisHash the world.
     * @return every peer holding any piece of any manifest in that world.
     * @Thread-context any thread.
     */
    public synchronized Set<NodeId> seedersOfWorld(Bytes genesisHash) {
        Set<NodeId> out = new LinkedHashSet<>();
        for (Entry entry : manifests.values()) {
            if (entry.genesisHash.equals(genesisHash)) {
                out.addAll(entry.holders.keySet());
            }
        }
        return Set.copyOf(out);
    }

    /** @return how many manifests are currently tracked. */
    public synchronized int trackedManifests() {
        return manifests.size();
    }

    /** @return the configured manifest bound. */
    public int maxManifests() {
        return maxManifests;
    }
}
