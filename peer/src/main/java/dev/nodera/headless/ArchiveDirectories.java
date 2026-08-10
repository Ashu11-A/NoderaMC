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
     * Open a location already known to name a document tree.
     *
     * <p>This is a separate entry point rather than a branch inside {@link #open(String, Optional)},
     * and the reason is a security boundary rather than tidiness. The {@code NODERA-CONFIG} handler
     * takes this location <b>from the control socket</b>, and every other client-supplied path there
     * goes through {@link ControlPaths#resolve} — normalised, then tested for containment inside a
     * root this node has a reason to touch. A document tree cannot go through that guard, because it
     * is not a path. So the branch that accepts one must be a branch that can never reach
     * {@code java.nio.file} at all: calling a method that decides for itself would hand a
     * socket-supplied string to {@code Path.of} on the other side of the decision, which is an
     * unguarded write primitive with this node's whole content store behind it. That is what
     * CodeQL's {@code java/path-injection} flagged when this branch called {@code open}, and it was
     * right to.
     *
     * @param tree   a persisted {@code content://} tree URI.
     * @param bridge the app-side SAF bridge, or empty when this process has none.
     * @return where the content store should write.
     * @throws IllegalArgumentException if {@code tree} does not name a document tree.
     * @throws StorageException if no bridge can reach it — a worker that silently fell back to a
     *         private directory would report a folder as in use while storing nothing in it, which
     *         is the defect this whole lane exists to remove.
     */
    public static BlobDirectory openDocumentTree(
            String tree, Optional<SafBlobDirectory.Bridge> bridge) {
        if (!isDocumentTree(tree)) {
            throw new IllegalArgumentException("not an Android document tree: " + tree);
        }
        return new SafBlobDirectory(tree, bridge.orElseThrow(() -> new StorageException(
                "the archive location " + tree + " is an Android document tree, but this "
                        + "process has no storage bridge to open it with")));
    }

    /**
     * Open a document tree using whatever bridge this process actually has.
     *
     * @param tree a persisted {@code content://} tree URI.
     * @return where the content store should write.
     */
    public static BlobDirectory openDocumentTree(String tree) {
        return openDocumentTree(tree, SafBlobDirectory.Reflective.installed());
    }

    /**
     * Open the archive location, whichever kind it is.
     *
     * <p>For the startup path, where the location comes from this process's own environment. A
     * caller holding a string a <i>client</i> sent must not use this: it decides with
     * {@link #isDocumentTree} and then calls {@link #openDocumentTree} or {@link ControlPaths}, so
     * that the client cannot choose which of the two this node applies.
     *
     * @param location  the configured location: a directory path, or a {@code content://} tree.
     * @param bridge    the app-side SAF bridge, or empty when this process has none (every desktop
     *                  worker).
     * @return where the content store should write.
     * @throws StorageException if a document tree is named but no bridge can reach it.
     */
    public static BlobDirectory open(String location, Optional<SafBlobDirectory.Bridge> bridge) {
        if (isDocumentTree(location)) {
            return openDocumentTree(location, bridge);
        }
        return new PathBlobDirectory(Path.of(location));
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
