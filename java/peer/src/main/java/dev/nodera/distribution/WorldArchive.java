package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.ContentId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The canonical <b>world archive</b> — a whole Minecraft save folder packed into one deterministic
 * blob, so the existing piece plane ({@link PieceManifest}/{@link PieceSplitter}/
 * {@link ContentTransferService}) can move a playable world between peers byte-exactly. This is the
 * world-continuity lane's payload: the durability answer to "the host closed the game" is that the
 * save's actual bytes already live on the peer network, fetchable and re-openable by any joiner.
 *
 * <p>This is deliberately <i>not</i> the validated-state lane: the engine's region snapshots commit
 * canonical simulation state (Task 1), while the archive carries the save files themselves
 * (level.dat, region/*.mca, the world's signed identity + genesis records). The two meet
 * later, when the 5b extractor replaces coarse digests; until then the archive is what makes a
 * shared world durably playable without its author.
 *
 * <h2>Determinism</h2>
 *
 * <p>Entries are sorted by their {@code /}-separated relative path and carry no timestamps, modes,
 * or ordering freedom: the same file set always packs to the same bytes, so the archive's
 * {@link ContentId} is a stable identity for "this exact world state".
 *
 * <h2>Safety</h2>
 *
 * <p>{@link #unpackInto} refuses absolute paths and any {@code ..} traversal segment — an archive
 * fetched from an untrusted swarm must never write outside its destination root. (Piece hashes
 * make corruption detectable; this guard makes a <i>maliciously crafted</i> archive inert.)
 *
 * <p>Thread-context: stateless static helpers; safe for any thread.
 */
public final class WorldArchive {

    /** Format magic + version, first u64 of every archive blob ({@code "NARCHIV1"} in ASCII). */
    static final long MAGIC = 0x4E41524348495631L;

    /** The synthetic region every world-archive manifest is filed under (not a simulated region). */
    public static final RegionId ARCHIVE_REGION =
            new RegionId(DimensionKey.of("nodera", "world_archive"), 0, 0);

    /** Piece size for archive blobs — save files are MBs, so pieces are bigger than region deltas. */
    public static final int ARCHIVE_PIECE_BYTES = 256 * 1024;

    private static final HashService HASHES = new HashService();

    private WorldArchive() {
    }

    /**
     * Pack a file map into the canonical archive blob.
     *
     * @param files relative {@code /}-separated path → file bytes; sorted internally.
     * @return the canonical archive bytes.
     * @throws IllegalArgumentException if a path is null, empty, absolute, or contains {@code ..}.
     * @Thread-context any thread.
     */
    public static byte[] pack(Map<String, byte[]> files) {
        TreeMap<String, byte[]> sorted = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            sorted.put(checkedPath(e.getKey()), e.getValue());
        }
        CanonicalWriter w = new CanonicalWriter();
        w.writeU64(MAGIC);
        w.writeList(sorted.entrySet(), (ww, e) -> {
            ww.writeString(e.getKey());
            ww.writeBytes(e.getValue());
        });
        return w.toByteArray();
    }

    /**
     * Unpack an archive blob back into its file map.
     *
     * @param blob the archive bytes.
     * @return relative path → file bytes, in packed (sorted) order.
     * @throws IllegalStateException if the magic/layout is malformed.
     * @throws IllegalArgumentException if an entry path is unsafe.
     * @Thread-context any thread.
     */
    public static SequencedMap<String, byte[]> unpack(byte[] blob) {
        CanonicalReader r = new CanonicalReader(blob);
        if (r.readU64() != MAGIC) {
            throw new IllegalStateException("not a Nodera world archive (bad magic)");
        }
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        List<Map.Entry<String, Bytes>> entries = r.readList(rr ->
                Map.entry(rr.readString(), rr.readBytesValue()));
        if (r.available() != 0) {
            throw new IllegalStateException("world archive has trailing bytes");
        }
        for (Map.Entry<String, Bytes> e : entries) {
            files.put(checkedPath(e.getKey()), e.getValue().toArray());
        }
        return files;
    }

    /**
     * Pack a directory tree (the save folder) into the canonical archive blob.
     *
     * @param root   the directory to pack.
     * @param filter which files to include, judged on the {@code /}-separated relative path.
     * @return the canonical archive bytes.
     * @throws UncheckedIOException on any read failure.
     * @Thread-context any thread.
     */
    public static byte[] packDirectory(Path root, Predicate<String> filter) {
        TreeMap<String, byte[]> files = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!filter.test(rel)) {
                    continue;
                }
                files.put(rel, Files.readAllBytes(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("packing world archive from " + root, e);
        }
        return pack(files);
    }

    /**
     * Unpack an archive blob into a directory tree, creating parents as needed.
     *
     * @param blob the archive bytes.
     * @param root the destination directory (created if absent).
     * @throws UncheckedIOException on any write failure.
     * @throws IllegalArgumentException if an entry path escapes {@code root}.
     * @Thread-context any thread.
     */
    public static void unpackInto(byte[] blob, Path root) {
        SequencedMap<String, byte[]> files = unpack(blob);
        try {
            Files.createDirectories(root);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Path target = root.resolve(e.getKey()).normalize();
                if (!target.startsWith(root.normalize())) {
                    throw new IllegalArgumentException(
                            "archive entry escapes destination: " + e.getKey());
                }
                Files.createDirectories(target.getParent());
                Files.write(target, e.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("unpacking world archive into " + root, e);
        }
    }

    /**
     * The default save-folder filter: what a joiner needs to re-open the world, nothing volatile
     * and <b>nothing secret</b>. Includes {@code level.dat}, all dimension data
     * ({@code region/entities/poi/data/DIM*}), and the world's public Nodera records — the signed
     * {@code nodera-world.dat} identity and the certified {@code nodera-genesis.dat}, both at the
     * save root — so a fetched world re-opens already shared and keeps its worldId. Excludes the
     * whole {@code nodera/} runtime subtree: {@code nodera/server-identity.bin} is the host's
     * <b>private signing key</b> and must never enter the swarm; {@code nodera/spool} is this
     * lane's own archive spool (archives must not nest); {@code nodera/entity-lane} is per-host
     * RocksDB state a rehoster bootstraps fresh.
     *
     * @param relativePath the {@code /}-separated save-relative path.
     * @return whether the file belongs in the archive.
     * @Thread-context any thread; pure function.
     */
    public static boolean defaultSaveFilter(String relativePath) {
        if (relativePath.equals("session.lock") || relativePath.endsWith(".tmp")
                || relativePath.startsWith("nodera/")) {
            return false;
        }
        return relativePath.equals("level.dat")
                || relativePath.equals("level.dat_old")
                || relativePath.equals("nodera-world.dat")
                || relativePath.equals("nodera-genesis.dat")
                || relativePath.startsWith("region/")
                || relativePath.startsWith("entities/")
                || relativePath.startsWith("poi/")
                || relativePath.startsWith("data/")
                || relativePath.startsWith("DIM-1/")
                || relativePath.startsWith("DIM1/")
                || relativePath.startsWith("dimensions/")
                // Player state IS world state: a rehosted world without playerdata/ resets every
                // returning player to spawn with an empty inventory (observed live) — positions,
                // inventories, advancements, and stats must travel with the world.
                || relativePath.startsWith("playerdata/")
                || relativePath.startsWith("advancements/")
                || relativePath.startsWith("stats/")
                || relativePath.startsWith("serverconfig/");
    }

    /**
     * Build the piece manifest for one archive snapshot of a world.
     *
     * <p>{@code regionRoot} is SHA-256 over the archive bytes: self-checking content identity, not
     * a committed simulation root (the archive lane is durability, not validation — the manifest's
     * own Javadoc's certified-checkpoint binding arrives with the 5b extractor).
     *
     * @param version the archive snapshot version (monotonic per world; freshness ordering).
     * @param blob    the canonical archive bytes.
     * @return the manifest, with {@link #ARCHIVE_REGION} as its region.
     * @Thread-context any thread.
     */
    public static PieceManifest manifestFor(long version, byte[] blob) {
        List<Piece> pieces = PieceSplitter.splitFixed(blob, ARCHIVE_PIECE_BYTES);
        return PieceManifest.of(
                ARCHIVE_REGION,
                new SnapshotVersion(version),
                version,
                StateRoot.of(HASHES.sha256(blob)),
                ContentId.of(HASHES, blob),
                blob.length,
                pieces);
    }

    /** Reject null/empty/absolute/traversing entry paths (see class Javadoc, "Safety"). */
    private static String checkedPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("archive entry path must not be empty");
        }
        if (path.startsWith("/") || path.contains("\\") || path.matches(".*(^|/)\\.\\.(/|$).*")) {
            throw new IllegalArgumentException("unsafe archive entry path: " + path);
        }
        return path;
    }

    /** All-files variant of {@link #packDirectory(Path, Predicate)} using the default filter. */
    public static byte[] packSaveDirectory(Path saveRoot) {
        return packDirectory(saveRoot, WorldArchive::defaultSaveFilter);
    }

    /** Convenience: the archive blob's stable content identity. */
    public static ContentId contentIdOf(byte[] blob) {
        return ContentId.of(HASHES, blob);
    }

    /** Convenience: split + manifest + return both, for callers that need the piece list too. */
    public record Prepared(PieceManifest manifest, Bytes blob) {
        public static Prepared of(long version, byte[] blob) {
            return new Prepared(manifestFor(version, blob), Bytes.unsafeWrap(blob));
        }
    }

    /** The archive entries a fetched blob contains, as an ordered list of paths (diagnostics). */
    public static List<String> entryPaths(byte[] blob) {
        return new ArrayList<>(unpack(blob).keySet());
    }
}
