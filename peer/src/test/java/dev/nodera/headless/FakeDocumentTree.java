package dev.nodera.headless;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An Android document tree, on the JVM.
 *
 * <p>Stands in for {@code dev.nodera.app.NoderaSafBlobs} — same method names, same parameter types,
 * same return types, same "failure is a return value" contract — so the whole
 * {@link SafBlobDirectory} path can be driven on the gate. {@link SafBlobDirectory.Reflective}
 * finds it by name through {@code nodera.storage.blob.bridge}, which is the same lookup it makes on
 * a handset; a signature that drifted here would fail to resolve exactly as it would there.
 *
 * <p>It is not a mock. It stores bytes, refuses what a document provider refuses, and answers with
 * the platform's own sentences, because the thing under test is a worker that has to keep a
 * hosted world's archive in a folder somebody picked with their file manager.
 */
public final class FakeDocumentTree {

    private static final Map<String, byte[]> BLOBS = new LinkedHashMap<>();
    private static final Map<String, Long> MODIFIED = new LinkedHashMap<>();
    private static String refusal;
    private static String lastError = "";

    private FakeDocumentTree() {
    }

    /** Forget everything, as an uninstall would. */
    public static void reset() {
        BLOBS.clear();
        MODIFIED.clear();
        refusal = null;
        lastError = "";
    }

    /** Refuse every write from now on, in the platform's own words. */
    public static void refuseWrites(String reason) {
        refusal = reason;
    }

    /** @return how many blobs the picked folder holds — what {@code adb shell ls} would show. */
    public static int blobCount() {
        return BLOBS.size();
    }

    /** @return the bytes stored under {@code name}, for a test that wants to look inside. */
    public static byte[] stored(String name) {
        return BLOBS.get(name);
    }

    private static String key(String treeUri, String name) {
        return treeUri + "/" + name;
    }

    public static boolean exists(String treeUri, String name) {
        return BLOBS.containsKey(key(treeUri, name));
    }

    public static byte[] read(String treeUri, String name) {
        return BLOBS.get(key(treeUri, name));
    }

    public static boolean write(String treeUri, String name, byte[] bytes) {
        if (refusal != null) {
            lastError = refusal;
            return false;
        }
        lastError = "";
        BLOBS.put(key(treeUri, name), bytes.clone());
        MODIFIED.put(key(treeUri, name), (long) MODIFIED.size() + 1);
        return true;
    }

    public static long delete(String treeUri, String name) {
        byte[] gone = BLOBS.remove(key(treeUri, name));
        MODIFIED.remove(key(treeUri, name));
        return gone == null ? -1L : gone.length;
    }

    public static String list(String treeUri) {
        StringBuilder lines = new StringBuilder();
        String prefix = treeUri + "/";
        for (Map.Entry<String, byte[]> entry : BLOBS.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            String name = entry.getKey().substring(prefix.length());
            lines.append(name).append('\t').append(entry.getValue().length).append('\t')
                    .append(MODIFIED.getOrDefault(entry.getKey(), 0L)).append('\n');
        }
        return lines.toString();
    }

    public static void touch(String treeUri, String name, long epochMillis) {
        if (BLOBS.containsKey(key(treeUri, name))) {
            MODIFIED.put(key(treeUri, name), epochMillis);
        }
    }

    public static String lastError() {
        return lastError;
    }
}
