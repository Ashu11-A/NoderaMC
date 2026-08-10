package dev.nodera.headless;

import dev.nodera.storage.fs.BlobDirectory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The worker's blob directory when the archive location is an Android {@code content://} tree
 * (frontend M-1).
 *
 * <h2>The problem this closes</h2>
 *
 * <p>Android 11+ withholds raw {@code java.io.File} access to shared storage no matter what the
 * user granted: a folder chosen with the system file manager comes back as a {@code content://}
 * tree URI and there is no path behind it. The companion app could already open the picker, persist
 * the grant and prove with a real write whether the folder was usable — and for anything outside
 * app-specific storage the honest answer was always "picked but unusable", because the worker
 * writes with {@code java.nio.file}. This is the other half: a {@link BlobDirectory} that writes
 * through the Storage Access Framework, so the same {@code FsContentStore} — the same budget, the
 * same pins, the same hash checks — can keep its archive in the folder the user actually chose.
 *
 * <h2>Why it is reflective</h2>
 *
 * <p>{@code :peer} must not compile against {@code android.*}; it is the same jar that runs on a
 * desktop. And the Kotlin side cannot compile against {@code :peer} either — the worker ships as a
 * dexed <i>asset</i> that {@code NoderaWorker} loads with a {@link ClassLoader} whose parent is the
 * app's, so classes flow one way only: the worker can see {@code dev.nodera.app}, the app cannot
 * see {@code dev.nodera.headless}. A shared interface therefore cannot exist. What crosses the
 * boundary is a fixed set of static methods taking nothing but {@code String}, {@code byte[]} and
 * {@code long} — no shared types at all — reached by name through {@link Reflective}.
 *
 * <p>R8 cannot see a call made by name, so {@code dev.nodera.app.NoderaSafBlobs} needs a
 * {@code -keep} rule; {@code scripts/android-apk.sh} adds one and then asserts it landed, because a
 * missing keep is invisible in a green build and shows up only as a {@code NoSuchMethodError} on a
 * device.
 *
 * <h2>Atomicity is weaker here, and saying so is the point</h2>
 *
 * <p>{@link dev.nodera.storage.fs.PathBlobDirectory} replaces a blob with a same-directory
 * temporary file and {@code ATOMIC_MOVE}: a reader sees the old bytes or the new bytes, never a
 * prefix. <b>The Storage Access Framework has no atomic move.</b> The bridge writes
 * {@code <name>.tmp}, flushes and syncs the descriptor, then renames the document — and a rename
 * through a content provider is not guaranteed atomic by the platform, only ordered. So a process
 * killed between the sync and the rename leaves a stray {@code .tmp} (harmless: {@link #list} only
 * reports blobs whose name is a 64-character hash), and one killed <i>during</i> the rename leaves
 * an outcome the platform does not define.
 *
 * <p>What makes that acceptable rather than a corruption bug is that the store is content
 * addressed: a blob's name is its hash and {@code FsContentStore.get} re-hashes every read, so a
 * truncated blob is reported as corruption and refetched — never returned. It is a weaker
 * durability guarantee, not a weaker correctness one, and it is the strongest guarantee the
 * platform offers for a folder the user picked.
 *
 * @Thread-context confined to the owning store; not thread-safe.
 */
public final class SafBlobDirectory implements BlobDirectory {

    /** The class the app installs, reached by name across the class-loader boundary. */
    static final String BRIDGE_CLASS = "dev.nodera.app.NoderaSafBlobs";

    /**
     * Names a different bridge class.
     *
     * <p>A property rather than a settable static, because this worker is started by a host process
     * that cannot call into it — on Android it is loaded reflectively and given its whole
     * configuration as properties, which is the same mechanism {@code NoderaWorker} already uses for
     * {@code user.home} and the P2P ports. It is also what lets the JVM gate exercise the reflective
     * path with a fake tree, so the signatures this class looks up are checked by a test rather
     * than only by a device.
     */
    static final String BRIDGE_CLASS_PROPERTY = "nodera.storage.blob.bridge";

    /**
     * What the app side must provide. Every method is total: it reports failure in its return
     * value and leaves the reason in {@link #lastError()}, because an exception thrown across a
     * reflective boundary arrives wrapped in {@link InvocationTargetException} and loses the words
     * the platform used.
     */
    public interface Bridge {

        /** @return true if a blob is stored under {@code name} in {@code treeUri}. */
        boolean exists(String treeUri, String name);

        /** @return the bytes, or null when absent or unreadable. */
        byte[] read(String treeUri, String name);

        /** @return true when the bytes are durably stored under {@code name}. */
        boolean write(String treeUri, String name, byte[] bytes);

        /** @return the bytes freed, or -1 when nothing was stored under {@code name}. */
        long delete(String treeUri, String name);

        /**
         * @return one line per blob, {@code name \t sizeBytes \t lastModifiedMillis}, or null when
         *         the tree cannot be listed. A newline-separated string rather than a list because
         *         only {@code String}, {@code byte[]} and primitives may cross this boundary.
         */
        String list(String treeUri);

        /** Record an access. Best effort; failures are the bridge's to swallow. */
        void touch(String treeUri, String name, long epochMillis);

        /** @return the platform's own words for the most recent failure, or "" if there was none. */
        String lastError();
    }

    private final String treeUri;
    private final Bridge bridge;

    /**
     * @param treeUri the persisted {@code content://} tree the user picked.
     * @param bridge  the app-side implementation.
     */
    public SafBlobDirectory(String treeUri, Bridge bridge) {
        if (treeUri == null || treeUri.isBlank()) {
            throw new IllegalArgumentException("treeUri must not be blank");
        }
        if (bridge == null) {
            throw new IllegalArgumentException("bridge must not be null");
        }
        this.treeUri = treeUri;
        this.bridge = bridge;
    }

    @Override
    public boolean exists(String name) {
        return bridge.exists(treeUri, name);
    }

    @Override
    public byte[] read(String name) throws IOException {
        return bridge.read(treeUri, name);
    }

    @Override
    public void write(String name, byte[] bytes) throws IOException {
        if (!bridge.write(treeUri, name, bytes)) {
            // The platform's own sentence, not a generic failure: "Android does not allow…" is
            // something the person holding the phone can act on, and it is the message the app
            // shows beside the folder they chose.
            throw new IOException(reason("could not write " + name + " to " + treeUri));
        }
    }

    @Override
    public long delete(String name) throws IOException {
        return bridge.delete(treeUri, name);
    }

    @Override
    public List<Entry> list() throws IOException {
        String raw = bridge.list(treeUri);
        if (raw == null) {
            throw new IOException(reason("could not list " + treeUri));
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\t");
            // A folder the user picked holds whatever the user put there. Anything that is not one
            // of our blobs — a photo, a half-written .tmp, a subdirectory — is skipped rather than
            // counted against the byte budget or offered to the swarm.
            if (parts.length != 3 || !BlobDirectory.isBlobName(parts[0])) {
                continue;
            }
            try {
                entries.add(new Entry(parts[0], Long.parseLong(parts[1]),
                        Long.parseLong(parts[2])));
            } catch (NumberFormatException notOurs) {
                // Same reasoning: an unparseable line is not a blob of ours.
            }
        }
        return entries;
    }

    @Override
    public void touch(String name, long epochMillis) {
        bridge.touch(treeUri, name, epochMillis);
    }

    @Override
    public String location() {
        return treeUri;
    }

    private String reason(String what) {
        String detail = bridge.lastError();
        return detail == null || detail.isBlank() ? what : what + ": " + detail;
    }

    /**
     * The bridge as it exists on a device: {@code dev.nodera.app.NoderaSafBlobs}, by name.
     *
     * <p>Resolved once at construction. {@link #isInstalled()} is the only question the rest of the
     * worker asks — on a desktop the class is simply not there, and nothing about the filesystem
     * path changes.
     */
    public static final class Reflective implements Bridge {

        private final Method exists;
        private final Method read;
        private final Method write;
        private final Method delete;
        private final Method list;
        private final Method touch;
        private final Method lastError;

        private Reflective(Class<?> type) throws NoSuchMethodException {
            this.exists = type.getMethod("exists", String.class, String.class);
            this.read = type.getMethod("read", String.class, String.class);
            this.write = type.getMethod("write", String.class, String.class, byte[].class);
            this.delete = type.getMethod("delete", String.class, String.class);
            this.list = type.getMethod("list", String.class);
            this.touch = type.getMethod("touch", String.class, String.class, long.class);
            this.lastError = type.getMethod("lastError");
        }

        /**
         * @return the bridge the app installed, or empty when this process is not running inside
         *         the Android app — which is every desktop worker.
         */
        public static java.util.Optional<Bridge> installed() {
            return installed(System.getProperty(BRIDGE_CLASS_PROPERTY, BRIDGE_CLASS));
        }

        static java.util.Optional<Bridge> installed(String className) {
            try {
                return java.util.Optional.of(new Reflective(Class.forName(className)));
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError absent) {
                // Not an error anywhere but Android, and on Android it is a build fault the APK
                // script's keep-rule assertion exists to prevent.
                return java.util.Optional.empty();
            }
        }

        @Override
        public boolean exists(String treeUri, String name) {
            return (boolean) call(exists, false, treeUri, name);
        }

        @Override
        public byte[] read(String treeUri, String name) {
            return (byte[]) call(read, null, treeUri, name);
        }

        @Override
        public boolean write(String treeUri, String name, byte[] bytes) {
            return (boolean) call(write, false, treeUri, name, bytes);
        }

        @Override
        public long delete(String treeUri, String name) {
            return (long) call(delete, -1L, treeUri, name);
        }

        @Override
        public String list(String treeUri) {
            return (String) call(list, null, treeUri);
        }

        @Override
        public void touch(String treeUri, String name, long epochMillis) {
            call(touch, null, treeUri, name, epochMillis);
        }

        @Override
        public String lastError() {
            Object detail = call(lastError, "");
            return detail == null ? "" : detail.toString();
        }

        private Object call(Method method, Object onFailure, Object... arguments) {
            try {
                Object result = method.invoke(null, arguments);
                return result == null ? onFailure : result;
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException failure) {
                return onFailure;
            }
        }
    }
}
