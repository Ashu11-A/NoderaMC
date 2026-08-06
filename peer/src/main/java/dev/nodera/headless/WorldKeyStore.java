package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.storage.PersistedWorldKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import static dev.nodera.headless.WorldIds.shortId;

/**
 * The private keys of the worlds this peer created — one file per world, owner-readable only.
 *
 * <h2>What holding a key here means</h2>
 *
 * <p>A world's key pair is minted once, on the machine of the player who first shared that world,
 * and the private half never leaves it. So the presence of a file in this directory <b>is</b> the
 * claim "I administer this world": it is what lets this node sign a
 * {@link dev.nodera.storage.WorldAdminProof} and what the companion app reads to separate the worlds
 * you run from the worlds you merely keep alive for other people.
 *
 * <p>Which is also why nothing here is ever produced from network input. Keys are generated locally
 * ({@link #loadOrGenerate}) or read back from disk. A peer cannot be told it owns a world.
 *
 * <h2>One file per world, not one file for all of them</h2>
 *
 * <p>A single keyring would mean every share rewrites every key, so one bad write costs the
 * administration of every world at once. Per-world files make that blast radius one world, and make
 * "stop administering this world" a deletion rather than a rewrite.
 *
 * @Thread-context safe from any thread; blocking IO on the caller's thread. The in-memory cache is
 *                 concurrent and is only ever populated from disk or from a fresh generation.
 */
public final class WorldKeyStore {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    private static final String SUFFIX = ".worldkey";

    private final Path directory;
    private final ConcurrentHashMap<String, PersistedWorldKey> cache = new ConcurrentHashMap<>();

    /**
     * @param directory where the per-world key files live (created on first save).
     * @throws NullPointerException if {@code directory} is null.
     */
    public WorldKeyStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").normalize();
    }

    /** @return the directory this store reads and writes. */
    public Path directory() {
        return directory;
    }

    /**
     * Load the key for a world this node already administers.
     *
     * @param worldIdHex the world.
     * @return its key pair, or empty when this node does not administer it.
     * @Thread-context any thread; blocking IO on a cache miss.
     */
    public Optional<PersistedWorldKey> load(String worldIdHex) {
        String key = normalise(worldIdHex);
        if (key == null) {
            return Optional.empty();
        }
        PersistedWorldKey cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Path file = fileFor(key);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            PersistedWorldKey loaded =
                    PersistedWorldKey.decode(new CanonicalReader(Files.readAllBytes(file)));
            cache.put(key, loaded);
            return Optional.of(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read world key " + file, e);
        } catch (RuntimeException malformed) {
            // A key file we cannot parse is NOT treated as "no key": deleting or overwriting it
            // would destroy the only copy of an administrator's authority over a world. Report it
            // and leave the file alone; the world reads as unowned until an operator looks.
            LOG.warn("World key {} is unreadable ({}) — this world will not show as administered "
                    + "until the file is repaired or removed", file, malformed.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Load this world's key, or mint one and persist it.
     *
     * <p>The generating call is the moment a peer becomes a world's administrator, so it is
     * deliberately narrow: the worker calls it when it mints a world identity — the point at which
     * it is, by construction, the world's author.
     *
     * @param worldIdHex the world.
     * @return the key pair now on disk.
     * @throws IllegalArgumentException if the world id is blank or not hex.
     * @throws UncheckedIOException     if the key is generated but cannot be saved.
     * @Thread-context any thread; blocking IO.
     */
    public PersistedWorldKey loadOrGenerate(String worldIdHex) {
        String key = normalise(worldIdHex);
        if (key == null) {
            throw new IllegalArgumentException("missing worldId");
        }
        Optional<PersistedWorldKey> existing = load(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        // computeIfAbsent, so two threads sharing a world at once cannot mint two different keys —
        // the loser of that race would silently overwrite the winner's authority.
        return cache.computeIfAbsent(key, hex -> {
            PersistedWorldKey fresh = PersistedWorldKey.generate(Bytes.fromHex(hex));
            save(fresh);
            LOG.info("Minted the administrator key for world {} — this node now administers it",
                    shortId(hex));
            return fresh;
        });
    }

    /**
     * @param worldIdHex the world.
     * @return whether this node holds that world's private key.
     */
    public boolean administers(String worldIdHex) {
        return load(worldIdHex).isPresent();
    }

    /** @return every world this node administers, hex-encoded. */
    public List<String> administeredWorlds() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return out;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(SUFFIX))
                    .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list world keys in " + directory, e);
        }
        return out;
    }

    private void save(PersistedWorldKey key) {
        CanonicalWriter w = new CanonicalWriter(256);
        key.encode(w);
        LocalFiles.writeAtomically(fileFor(normalise(key.worldId().toHex())), w.toByteArray());
    }

    /**
     * The key file for an ALREADY-{@link #normalise(String) normalised} id.
     *
     * <p>Hex validation is what makes traversal impossible, but this resolves and re-checks
     * containment anyway: the guard and the file access then sit in the same method, so no future
     * caller can reach the resolve without it, and a reader does not have to trace the id back to
     * its validation to see that the store cannot write outside its own directory.
     */
    private Path fileFor(String worldIdHex) {
        Path resolved = directory.resolve(worldIdHex + SUFFIX).normalize();
        if (!resolved.startsWith(directory)) {
            throw new IllegalArgumentException("world key path escapes " + directory);
        }
        return resolved;
    }

    /**
     * Lower-case the world id and refuse anything that is not hex.
     *
     * <p>The id becomes a filename, so this is also the path-traversal guard: a caller-supplied
     * {@code ../../} can never reach {@link #fileFor} because it is not hex.
     *
     * @return the normalised id, or {@code null} when it is not a usable world id.
     */
    private static String normalise(String worldIdHex) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return null;
        }
        String trimmed = worldIdHex.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return null;
            }
        }
        return trimmed.length() % 2 == 0 ? trimmed : null;
    }

}
