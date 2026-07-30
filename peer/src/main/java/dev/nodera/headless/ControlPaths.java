package dev.nodera.headless;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the control endpoint is willing to read and write on its client's say-so.
 *
 * <h2>The problem</h2>
 *
 * Several control verbs take a filesystem path from the client: {@code NODERA-SEED} hands over a
 * freshly packed world archive, {@code NODERA-SEED-REGION} a region snapshot, {@code NODERA-FETCH}
 * names where to write what it fetched, {@code NODERA-REKEY} names the blob to re-encrypt. Each of
 * those went straight to {@code Files.readAllBytes} / {@code Files.write}.
 *
 * <p>That made the loopback endpoint an <b>arbitrary file read and write primitive</b> for anything
 * that can open a socket to {@code 127.0.0.1:25610}. "Loopback only" bounds it to this machine; it
 * does not bound it to this application. Any local process running as the same user could have made
 * the worker read its SSH key into a world archive it then seeds to the network, or overwrite the
 * user's own files with fetched bytes.
 *
 * <h2>The fix</h2>
 *
 * A path from a client must resolve, after normalisation, inside one of a small set of roots the
 * worker has a reason to touch:
 *
 * <ul>
 *   <li>the worker's own state directory ({@code NODERA_STATE_DIR}) and archive directory — where
 *       it keeps identity, the world registry and content;</li>
 *   <li>the system temporary directory — where a packer may spool;</li>
 *   <li>the user's home directory — which is where Minecraft saves live, and the mod writes every
 *       archive it hands over into {@code <saveRoot>/nodera/spool}.</li>
 * </ul>
 *
 * That covers every path the mod actually sends while removing the primitive. It is deliberately
 * not tighter: the worker cannot know a save root before the mod names one, and a rule that broke
 * hosting would be removed by the first person it inconvenienced, which is worse than this.
 *
 * <p>Normalisation happens before the containment test, so {@code ../../etc/passwd} is resolved and
 * then rejected rather than compared as text.
 *
 * <p>Thread-context: immutable after construction; the roots are read once at startup.
 */
public final class ControlPaths {

    private final List<Path> roots;

    /**
     * @param stateDir   the worker's state directory.
     * @param archiveDir the worker's content/archive directory.
     */
    public ControlPaths(Path stateDir, Path archiveDir) {
        List<Path> permitted = new ArrayList<>();
        add(permitted, stateDir);
        add(permitted, archiveDir);
        add(permitted, systemProperty("java.io.tmpdir"));
        add(permitted, systemProperty("user.home"));
        this.roots = List.copyOf(permitted);
    }

    /**
     * The roots this node may touch, from the same environment {@link PeerNode} composes itself
     * from.
     *
     * <p>Read here rather than passed down a constructor because the guard belongs at the sink and
     * every sink is in one class; threading a parameter through four overloaded constructors would
     * have put the decision further from the code it protects, which is how a guard gets forgotten
     * on the fifth verb.
     *
     * @return the permitted roots.
     */
    public static ControlPaths fromEnvironment() {
        String home = System.getProperty("user.home", "");
        String state = env("NODERA_STATE_DIR", home + "/.nodera");
        String archive = env("NODERA_ARCHIVE_DIR", home + "/.nodera/archive");
        return new ControlPaths(Path.of(state), Path.of(archive));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Path systemProperty(String key) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static void add(List<Path> into, Path candidate) {
        if (candidate == null) {
            return;
        }
        Path normalised = candidate.toAbsolutePath().normalize();
        if (!into.contains(normalised)) {
            into.add(normalised);
        }
    }

    /**
     * Resolve a client-supplied path, or refuse it.
     *
     * @param raw the path as the client sent it.
     * @param what what the path is for, named in the refusal so the caller can act on it.
     * @return the normalised absolute path, guaranteed to sit under a permitted root.
     * @throws IllegalArgumentException if it is blank, malformed, or outside every permitted root.
     */
    public Path resolve(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("missing " + what);
        }
        if (raw.indexOf('\0') >= 0) {
            // A NUL truncates the name for some native calls and not for the checks above them.
            throw new IllegalArgumentException(what + " contains a NUL byte");
        }
        Path candidate;
        try {
            candidate = Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException(what + " is not a valid path", malformed);
        }
        for (Path root : roots) {
            if (candidate.startsWith(root)) {
                return candidate;
            }
        }
        // The refusal names the path but not the permitted roots: the caller is a local program
        // that already knows where it put the file, and an attacker probing the endpoint learns
        // nothing about this machine's layout from the answer.
        throw new IllegalArgumentException(
                what + " is outside every directory this node may touch: " + candidate);
    }

    /** The permitted roots, for diagnostics and tests. */
    public List<Path> roots() {
        return roots;
    }
}
