package dev.nodera.headless;

import dev.nodera.storage.StorageException;
import dev.nodera.storage.fs.BlobDirectory;
import dev.nodera.storage.fs.PathBlobDirectory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Turns "where this node keeps its archive" — one string, from an environment variable or from the
 * app's {@code storage.peer_worlds_dir} setting — into somewhere the content store can write.
 *
 * <p>Almost always that is a directory path and nothing here is doing anything: the worker gets the
 * same {@link PathBlobDirectory} it has always had. The exception is Android, where a folder the
 * user picks with the system file manager is a {@code content://} tree URI and not a path at all
 * (frontend M-1). That case is the only reason this class exists, and keeping the decision in one
 * place is what keeps {@code PeerNode} and the {@code NODERA-CONFIG} handler from each having their
 * own opinion about what a location string means.
 *
 * <p><b>Only the archive lane is routed this way.</b> The node identity, the world registry, the
 * key store and RocksDB stay on the filesystem, in app-private storage. That is deliberate: a
 * signing key in a folder the user browses — and that any app with the same grant can read — is a
 * security regression, not a feature. The exit criterion for M-1 names a world archive, and a world
 * archive is content-addressed public data that peers verify by hash. Those are not the same
 * bytes and they do not deserve the same treatment.
 */
public final class ArchiveDirectories {

    /** What Android hands back from the folder picker. */
    private static final String CONTENT_SCHEME = "content://";

    private ArchiveDirectories() {
    }

    /**
     * @param location an archive location as configured.
     * @return whether it names an Android document tree rather than a filesystem directory.
     */
    public static boolean isDocumentTree(String location) {
        return location != null && location.startsWith(CONTENT_SCHEME);
    }

    /**
     * Open the archive location.
     *
     * @param location  the configured location: a directory path, or a {@code content://} tree.
     * @param bridge    the app-side SAF bridge, or empty when this process has none (every desktop
     *                  worker).
     * @return where the content store should write.
     * @throws StorageException if a document tree is named but no bridge can reach it — a worker
     *         that silently fell back to a private directory would report a folder as in use while
     *         storing nothing in it, which is the defect this whole lane exists to remove.
     */
    public static BlobDirectory open(String location, Optional<SafBlobDirectory.Bridge> bridge) {
        if (!isDocumentTree(location)) {
            return new PathBlobDirectory(Path.of(location));
        }
        return new SafBlobDirectory(location, bridge.orElseThrow(() -> new StorageException(
                "the archive location " + location + " is an Android document tree, but this "
                        + "process has no storage bridge to open it with")));
    }

    /**
     * Open the archive location using whatever bridge this process actually has.
     *
     * @param location the configured location.
     * @return where the content store should write.
     */
    public static BlobDirectory open(String location) {
        return open(location, SafBlobDirectory.Reflective.installed());
    }
}
