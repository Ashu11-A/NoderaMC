package dev.nodera.distribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The one containment guard: a name that arrived from somewhere else becomes a path <b>inside</b> a
 * directory, or it becomes an exception.
 *
 * <h2>Why one</h2>
 *
 * <p>This tree wrote the same guard three times and got three different answers. {@code
 * HexKeyedStore} validated the shape of the name, resolved, normalised and re-checked containment.
 * {@code ManifestIndexStore} made the root absolute first — the step that matters when the root is
 * relative, because {@code normalize()} is purely lexical and cannot cancel a leading {@code ..}
 * against a directory it does not know the name of. {@link WorldArchive#unpackInto} did neither: it
 * compared against a root it re-normalised inside the loop and never made absolute.
 *
 * <p>The third was the weakest and the only one whose input is attacker-controlled — its names are
 * entries of a <i>downloaded</i> world archive. A guard that exists in three versions is a guard
 * that at most one of its copies is keeping up to date, so this is the one version, and it is the
 * strongest of the three plus the two checks none of them had.
 *
 * <h2>What it refuses</h2>
 *
 * <ol>
 *   <li>an empty or blank name;</li>
 *   <li>an <b>absolute</b> name, judged portably — a leading {@code /}, a leading {@code \}, or a
 *       {@code C:} drive prefix. Portably, because an archive is written on one operating system
 *       and unpacked on another: a name that is absolute on Windows must be refused on Linux too,
 *       where {@code Path.of} would quietly read it as relative;</li>
 *   <li>a backslash anywhere. Archive and store names are {@code /}-separated by contract, so a
 *       backslash is either a Windows path that has no business here or an attempt to hide a
 *       separator from a check that only knows about {@code /};</li>
 *   <li>any {@code ..} path segment;</li>
 *   <li>a name that, resolved against the <b>absolute, normalised</b> root, lexically lands outside
 *       it — the belt-and-braces answer to "did the shape checks above actually cover everything";</li>
 *   <li>a name whose nearest existing ancestor really lives outside the root. Lexical normalisation
 *       cannot see a symbolic link: {@code link/evil.dat} under a root containing {@code link ->
 *       /etc} passes every check above and writes to {@code /etc/evil.dat}. This is the one check
 *       that asks the file system rather than the string.</li>
 * </ol>
 *
 * <p>Thread-context: stateless static helpers; safe for any thread. Check 6 does blocking IO.
 */
public final class ContainedPath {

    private ContainedPath() {
    }

    /**
     * The shape half: a name that is safe to append to <i>some</i> directory.
     *
     * <p>Used where there is no root to resolve against yet — packing an archive names its entries
     * long before anybody chooses where they will be unpacked, and a name that could never be
     * unpacked safely should never be packed.
     *
     * @param name the {@code /}-separated relative name.
     * @return the same name, once it is known to be relative and traversal-free.
     * @throws IllegalArgumentException on any of refusals 1–4 above.
     * @Thread-context any thread; pure function.
     */
    public static String checkedName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        if (name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("unsafe path (backslash): " + name);
        }
        if (isAbsoluteAnywhere(name)) {
            throw new IllegalArgumentException("unsafe path (absolute): " + name);
        }
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("unsafe path (traversal): " + name);
            }
        }
        return name;
    }

    /**
     * Resolve {@code name} under {@code root} and prove the result is really inside it.
     *
     * @param root the directory the result must live in; need not exist yet.
     * @param name the {@code /}-separated relative name.
     * @return the resolved path, absolute and normalised.
     * @throws IllegalArgumentException on any of refusals 1–6 above.
     * @Thread-context any thread; blocking IO for the symlink check.
     */
    public static Path inside(Path root, String name) {
        checkedName(name);
        Path base = root.toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = base.resolve(name).normalize();
        } catch (InvalidPathException notAPath) {
            throw new IllegalArgumentException("unsafe path (not a path): " + name, notAPath);
        }
        if (!resolved.startsWith(base) || resolved.equals(base)) {
            throw new IllegalArgumentException("path escapes " + base + ": " + name);
        }
        requireRealAncestorInside(base, resolved, name);
        return resolved;
    }

    /**
     * The symlink check: walk up from the target to the first ancestor that exists, and make the
     * file system say where that ancestor really is.
     *
     * <p>Nothing above is wrong; all of it is lexical, and a lexical check cannot see that a
     * directory is a link. An archive whose entries are {@code link} (a symlink) and then
     * {@code link/evil.dat} defeats every string check ever written, because by the time the second
     * entry is resolved the escape is a fact about the disk rather than about the name.
     *
     * <p>Silent when the root itself does not exist yet: there is nothing to follow, and refusing
     * to unpack into a directory that has not been created would break the ordinary case.
     */
    private static void requireRealAncestorInside(Path base, Path resolved, String name) {
        Path existing = resolved;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !Files.exists(base)) {
            return;
        }
        try {
            // toRealPath on BOTH sides, because the root itself may sit under a link that is
            // nobody's fault (a temporary directory, a mounted home) and comparing a resolved
            // path against an unresolved one would refuse every write into it.
            Path realBase = base.toRealPath();
            Path realExisting = existing.toRealPath();
            if (!realExisting.startsWith(realBase)) {
                throw new IllegalArgumentException(
                        "path escapes " + base + " through a link: " + name);
            }
            // A link that resolves back inside is still a link, and the entry about to be written
            // is then not the file the archive said it was. Refuse rather than follow. Only the
            // components BELOW the root are inspected — the root's own ancestry is not the
            // archive's doing.
            for (Path p = existing; p != null && !p.equals(base); p = p.getParent()) {
                if (Files.isSymbolicLink(p)) {
                    throw new IllegalArgumentException(
                            "path escapes " + base + " through a link: " + name);
                }
            }
        } catch (IOException unresolvable) {
            throw new IllegalArgumentException(
                    "cannot prove " + name + " stays inside " + base, unresolvable);
        }
    }

    /**
     * Absolute on <i>any</i> platform this archive may be unpacked on.
     *
     * <p>{@link Path#isAbsolute} answers for the platform running right now, which is the wrong
     * question: the attacker chooses the archive, not the operating system that opens it.
     */
    private static boolean isAbsoluteAnywhere(String name) {
        if (name.startsWith("/") || name.startsWith("\\")) {
            return true;
        }
        // A drive-relative or drive-absolute Windows name ("C:", "C:/x", "C:x"): on Linux this is
        // an ordinary relative name with a colon in it, and no legitimate save or store file has one.
        return name.length() >= 2 && name.charAt(1) == ':'
                && Character.isLetter(name.charAt(0));
    }
}
