package dev.nodera.peer.discovery;

import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PersistedNodeIdentity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Loads and stores a peer's {@link NodeIdentity} on disk so a returning peer keeps its
 * {@code NodeId} across restarts (Task 20; retires LIMITATIONS L-28).
 *
 * <h2>Why this is not in {@code core}</h2>
 *
 * <p>{@code core} is JDK-only and carries no {@code NodeIdentity} persistence path on purpose
 * (the type's Javadoc defers disk encoding to "Task 5's concern"). This store lives in
 * {@code peer-runtime}, which already owns process lifecycle, and reuses
 * {@link PersistedNodeIdentity} (a {@code core} value type) for the actual bytes — so the
 * secret-material format stays a frozen core contract while the file-handling policy stays here.
 *
 * <h2>The private key never crosses back out of {@code core}</h2>
 *
 * <p>The persisted form carries a private key, and {@link NodeIdentity} exposes none. The store
 * therefore deals in {@link PersistedNodeIdentity} for writing (which already holds the key bytes
 * it was given at generation time) and in {@link NodeIdentity} for reading (via
 * {@link PersistedNodeIdentity#restore()}). The only path that produces a writable identity is
 * {@link PersistedNodeIdentity#generate()}, which creates the key pair and its persistable form
 * together — so there is no accessor on {@code NodeIdentity} to extract a key from, and no way to
 * re-persist an identity whose private key the process has lost.
 *
 * <h2>Owner-only permissions</h2>
 *
 * <p>The persisted form contains a private key. On POSIX systems the file is created with mode
 * {@code 600} (owner read/write only); on file systems that do not support POSIX permissions the
 * file is still written (best effort) and the limitation is the OS's, not ours. The temp-then-move
 * write means a crash mid-save never truncates the existing identity — a peer always either keeps
 * its old identity or atomically adopts a new one.
 *
 * <p>Thread-context: methods are safe to call from any thread but perform blocking file IO on the
 * caller's thread; one store per peer, one writer at a time.
 */
public final class PersistentIdentityStore {

    private final Path file;

    /**
     * @param file the identity file (e.g. server {@code <world>/nodera/server-identity.bin},
     *             client game-dir). Its parent directory is created on save if absent.
     * @throws IllegalArgumentException if {@code file} is null.
     * @Thread-context any thread (construction only).
     */
    public PersistentIdentityStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** @return the file this store reads and writes. */
    public Path file() {
        return file;
    }

    /**
     * Load an existing identity, if present.
     *
     * @return the restored identity, or empty if the file does not exist (a first run).
     * @throws UncheckedIOException if the file exists but cannot be read or parsed.
     * @Thread-context any thread; blocking IO on the calling thread.
     */
    public Optional<NodeIdentity> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read identity file " + file, e);
        }
        return Optional.of(PersistedNodeIdentity.decode(
                new dev.nodera.core.crypto.CanonicalReader(raw)).restore());
    }

    /**
     * Persist an identity together with its private key. The argument must come from
     * {@link PersistedNodeIdentity#generate()} (or a {@link #load()} decoded value re-encoded),
     * because {@link NodeIdentity} does not expose its private key — this is the one write path,
     * and it cannot leak a key it was not handed.
     *
     * @param persisted the persistable form (holds the private key).
     * @throws IllegalArgumentException if {@code persisted} is null.
     * @throws UncheckedIOException     if the write fails.
     * @Thread-context any thread; blocking IO on the calling thread.
     */
    public void save(PersistedNodeIdentity persisted) {
        Objects.requireNonNull(persisted, "persisted");
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter(160);
        persisted.encode(w);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(
                    parent,
                    file.getFileName().toString() + ".",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(ownerOnly()));
            Files.write(temp, w.toByteArray());
            restrict(temp);
            try {
                Files.move(temp, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save identity file " + file, e);
        }
    }

    /**
     * Load an existing identity, or generate and persist a fresh one. The common first-run path.
     *
     * @return the identity now on disk.
     * @throws UncheckedIOException if generation succeeds but persistence fails.
     * @Thread-context any thread; blocking IO on the calling thread.
     */
    public NodeIdentity loadOrGenerate() {
        Optional<NodeIdentity> existing = load();
        if (existing.isPresent()) {
            return existing.get();
        }
        PersistedNodeIdentity.Generated fresh = PersistedNodeIdentity.generate();
        save(fresh.persisted());
        return fresh.identity();
    }

    /**
     * POSIX {@code rw-------} — owner-only.
     *
     * @return the permission set.
     * @Thread-context any thread.
     */
    private static Set<PosixFilePermission> ownerOnly() {
        return Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
    }

    /** Apply owner-only permissions where supported; tolerate filesystems that reject it. */
    private static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, ownerOnly());
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem: best effort only. The file is written; access control is the
            // OS/user's responsibility. Not an error worth failing identity persistence over.
        }
    }
}
