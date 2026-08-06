package dev.nodera.headless;

import dev.nodera.core.crypto.CanonicalReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

/**
 * A directory of one signed record per world, named by the world id.
 *
 * <h2>The guard is the point</h2>
 *
 * <p>A world id arrives from the network and becomes a <b>path component</b>. Hex-only is the whole
 * check — it makes {@code ..}, separators and absolute paths unrepresentable rather than filtered —
 * and it was written twice, differently. {@code WorldKeyStore} validated the id, required an even
 * length, and then re-resolved and re-checked containment; {@code WorldTombstoneStore} validated the
 * id and stopped. Neither was wrong, but a security guard that exists in two versions is a guard
 * that only one of its copies is keeping up to date. This is the one version, and it does both:
 * refuse anything that is not hex, and then prove the resolved path is still inside the directory,
 * so a reader does not have to trace an id back to its validation to see that the store cannot write
 * outside itself.
 *
 * <h2>Held, not inherited</h2>
 *
 * <p>A store <b>has</b> a directory of hex-named files; it is not one. That is the honest
 * relationship — {@code WorldKeyStore} is a keyring and {@code WorldTombstoneStore} is a record of
 * deletions, and neither is a kind of the other — and it is also what keeps the structural report
 * able to see the calls. {@code DeadCodeAnalysis} resolves a reference by exact method id with no
 * walk up the hierarchy, so an inherited {@code fileFor(...)} call is credited to the subclass and
 * the declaration reads as referenced by nothing. Composition names the owner at the call site, so
 * every method here is visibly used by the code that uses it.
 *
 * <p>What each caller still owns: the suffix, the record type, and what verification means for it.
 * Everything handed to {@link #loadAll} is re-verified on the way in — a file on our own disk is not
 * more trustworthy than a frame off the wire, so an edited record reads as absent rather than as an
 * unverifiable record we hold.
 *
 * @Thread-context safe from any thread; blocking IO on the caller's thread.
 */
final class HexKeyedStore {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    private final Path directory;

    /** @param directory where this store's files live; created on the first save. */
    HexKeyedStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").normalize();
    }

    /**
     * Lower-case a world id and refuse anything that is not an even-length run of hex.
     *
     * <p>Even length because a world id is bytes: an odd run cannot be one, and accepting it would
     * let two spellings of nothing become two different filenames.
     *
     * @param worldIdHex the id as it arrived.
     * @return the normalised id, or {@code null} when it is not a usable world id.
     */
    static String normalise(String worldIdHex) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return null;
        }
        String trimmed = WorldIds.key(worldIdHex);
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return null;
            }
        }
        return trimmed.length() % 2 == 0 ? trimmed : null;
    }

    /**
     * The file holding one world's record.
     *
     * @param worldIdHex the world, in any case and with any surrounding space.
     * @param suffix     the subclass's file extension.
     * @return the resolved path, guaranteed to be inside {@link #directory()}.
     * @throws IllegalArgumentException if the id is not hex, or — belt and braces after the hex
     *                                  check has already made it impossible — if the resolved path
     *                                  would escape the directory.
     */
    Path fileFor(String worldIdHex, String suffix) {
        String name = normalise(worldIdHex);
        if (name == null) {
            throw new IllegalArgumentException("worldId must be hex: " + worldIdHex);
        }
        Path resolved = directory.resolve(name + suffix).normalize();
        if (!resolved.startsWith(directory)) {
            throw new IllegalArgumentException("path escapes " + directory);
        }
        return resolved;
    }

    /** @return every id this store holds a {@code suffix} file for. */
    List<String> idsWith(String suffix) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return out;
        }
        try (Stream<Path> files = Files.list(directory)) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(suffix))
                    .map(name -> name.substring(0, name.length() - suffix.length()))
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list " + directory, e);
        }
        return out;
    }

    /** Best-effort removal; a file we cannot delete is logged, never thrown from a sweep. */
    void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.debug("could not remove {}: {}", file, e.getMessage());
        }
    }

    /**
     * Read and re-verify one record.
     *
     * @param file     the file.
     * @param decode   the record's canonical decoder.
     * @param verifies its signature check.
     * @param what     the record kind, for the log line.
     * @param <T>      the record type.
     * @return the record, or {@code null} when the file is unreadable or does not verify.
     */
    <T> T read(Path file, Function<CanonicalReader, T> decode, Predicate<T> verifies,
                         String what) {
        try {
            T record = decode.apply(new CanonicalReader(Files.readAllBytes(file)));
            if (!verifies.test(record)) {
                LOG.warn("Ignoring {}: the stored {} does not verify", file, what);
                return null;
            }
            return record;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        } catch (RuntimeException malformed) {
            LOG.warn("Ignoring {}: unreadable ({})", file, malformed.getMessage());
            return null;
        }
    }

    /**
     * Every record of one kind still inside a retention window, with the expired ones deleted.
     *
     * @param suffix           the subclass's file extension.
     * @param decode           the record's canonical decoder.
     * @param verifies         its signature check.
     * @param what             the record kind, for the log line.
     * @param issuedAt         when the record was issued, for the retention cut.
     * @param floorEpochMillis records issued before this are deleted rather than returned.
     * @param <T>              the record type.
     * @return the survivors.
     */
    <T> List<T> loadAll(String suffix, Function<CanonicalReader, T> decode,
                                  Predicate<T> verifies, String what, ToLongFunction<T> issuedAt,
                                  long floorEpochMillis) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<T> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files
                    .filter(p -> p.getFileName().toString().endsWith(suffix)).toList()) {
                T record = read(file, decode, verifies, what);
                if (record == null) {
                    continue;
                }
                if (issuedAt.applyAsLong(record) < floorEpochMillis) {
                    delete(file);
                    continue;
                }
                loaded.add(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list " + directory, e);
        }
        return List.copyOf(loaded);
    }
}
