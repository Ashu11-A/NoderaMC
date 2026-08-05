package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.distribution.PieceManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * What this node is holding, written down so a restart does not forget it.
 *
 * <h2>Why the blobs alone are not enough</h2>
 *
 * <p>The content store keeps every piece it has ever verified, addressed by hash. That survives a
 * restart on its own — but a hash-addressed blob knows nothing about which world it belongs to,
 * which version of that world, or which other blobs complete it. The manifest is what binds them,
 * and it lived only in memory.
 *
 * <p>So a restarted worker held a complete world and reported {@code piece_count: 0,
 * pieces_held: 0}, which is worse than it sounds: it did not merely display a wrong number, it no
 * longer believed it had anything, so it would not serve a piece and would not announce itself as a
 * seeder. Observed on a phone that had 119 of 119 pieces before its app was restarted.
 *
 * <h2>One file per manifest</h2>
 *
 * <p>Each manifest is written to its own file, named by world, lane and version, using the same
 * canonical encoding the wire uses — so this format has exactly one definition and
 * {@link PieceManifest#decode} re-verifies the root on the way back in. A file that is corrupt,
 * truncated or from a future version costs its own manifest and nothing else: it is skipped with a
 * warning, because refusing to start is a far worse answer to "one file went bad" than coming up
 * holding slightly less.
 *
 * <p>Writes go to a temporary file and are moved into place, so a process that dies mid-write
 * leaves the previous state rather than a half-written manifest.
 *
 * <p>Thread-context: safe from any thread; every method is a self-contained filesystem operation.
 */
final class ManifestIndexStore {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    /** Written alongside the blobs, so moving an archive directory moves its index with it. */
    private static final String DIRECTORY = "manifests";

    private static final String SUFFIX = ".manifest";

    /**
     * Where the blobs live right now, asked each time rather than captured once.
     *
     * <p>The content store can be re-pointed while the node runs (L-58) — the blobs move and the
     * node keeps serving. An index that had captured the old path would go on writing beside
     * content that is no longer there, and come back after a restart describing an empty directory.
     */
    private final java.util.function.Supplier<Path> contentRoot;

    ManifestIndexStore(java.util.function.Supplier<Path> contentRoot) {
        this.contentRoot = contentRoot;
    }

    private Path root() {
        return contentRoot.get().resolveSibling(DIRECTORY);
    }

    /**
     * Record one manifest.
     *
     * <p>The lane comes from the manifest's own region rather than from the caller, so a file can
     * never be filed under a lane its contents disagree with — the same rule the wire path uses to
     * decide which map an incoming manifest belongs in.
     *
     * <p>Failures are logged and swallowed. This is a durability improvement on a path that already
     * worked in memory, and a full disk must not take down the seeding it is trying to protect.
     *
     * @param worldIdHex the world it belongs to.
     * @param manifest   the manifest to write.
     */
    void put(String worldIdHex, PieceManifest manifest) {
        try {
            Path root = root();
            Files.createDirectories(root);
            CanonicalWriter writer = new CanonicalWriter();
            manifest.encode(writer);
            Path target = root.resolve(fileName(worldIdHex, laneOf(manifest), manifest.manifestRoot()));
            Path temporary = Files.createTempFile(root, "manifest", ".tmp");
            Files.write(temporary, writer.toBytes().toArray());
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException degraded) {
            LOG.warn("could not record the manifest for {} v{} — this node will forget it on "
                            + "restart: {}", worldIdHex, manifest.version().value(),
                    degraded.toString());
        }
    }

    /** Forget one manifest, when retention trims it. */
    void remove(String worldIdHex, PieceManifest manifest) {
        try {
            Files.deleteIfExists(
                    root().resolve(fileName(worldIdHex, laneOf(manifest), manifest.manifestRoot())));
        } catch (IOException ignored) {
            // A manifest that outlives its content is re-trimmed on the next pass.
        }
    }

    /** Which map a manifest belongs in: the archive lane files under a synthetic region. */
    private static String laneOf(PieceManifest manifest) {
        return String.valueOf(manifest.region());
    }

    /** Every manifest recorded here, with the world and lane it was filed under. */
    List<Entry> loadAll() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(SUFFIX))
                    .toList()) {
                readOne(file).ifPresent(out::add);
            }
        } catch (IOException unreadable) {
            LOG.warn("could not read the manifest index at {}: {}", root, unreadable.toString());
        }
        return out;
    }

    private java.util.Optional<Entry> readOne(Path file) {
        String name = file.getFileName().toString();
        String[] parts = name.substring(0, name.length() - SUFFIX.length()).split("__");
        if (parts.length != 3) {
            LOG.warn("ignoring an unrecognised manifest file name: {}", name);
            return java.util.Optional.empty();
        }
        try {
            PieceManifest manifest =
                    PieceManifest.decode(new CanonicalReader(Files.readAllBytes(file)));
            return java.util.Optional.of(new Entry(parts[0], manifest));
        } catch (IOException | RuntimeException corrupt) {
            // decode() re-derives the manifest root, so this also catches a tampered file.
            LOG.warn("discarding a corrupt manifest file {}: {}", name, corrupt.toString());
            return java.util.Optional.empty();
        }
    }

    /**
     * The file a manifest is stored under.
     *
     * <h2>Why the name is a root and not a version</h2>
     *
     * <p>It used to end in the region's chain height, which named the file after a number counted on
     * whichever machine happened to produce it. Two peers holding the same content filed it under
     * different names, and one peer holding two different states of a region at the same height
     * filed both under one. The manifest root is what actually identifies the content, so it is what
     * names the file: the same bytes are the same file everywhere, always.
     *
     * <p>Renaming the scheme costs a cache miss and nothing else. This directory is a cache — a file
     * that is corrupt, truncated or unrecognised costs its own manifest and no more — so an install
     * upgrading past this simply re-learns what it holds from the content store.
     */
    private static String fileName(String worldIdHex, String lane, Bytes manifestRoot) {
        return worldIdHex + "__" + encodeLane(lane) + "__" + manifestRoot.toHex() + SUFFIX;
    }

    /** Lane identities contain characters a file name may not; hex keeps the mapping total. */
    private static String encodeLane(String lane) {
        StringBuilder out = new StringBuilder(lane.length() * 2);
        for (byte b : lane.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }

    /** One recorded manifest and the world it belongs to; its lane is its own region. */
    record Entry(String worldIdHex, PieceManifest manifest) {
    }
}
