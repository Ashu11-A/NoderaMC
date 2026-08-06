package dev.nodera.distribution;

import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The entry names in a world archive are chosen by whoever built the archive, and a joiner fetches
 * archives from the swarm. This is the suite that says what those names may not be.
 *
 * <p>It drives the guard both directly and through {@link WorldArchive#unpackInto}, because the
 * interesting failure is not "the function rejects a bad string" — it is "nothing was written
 * outside the destination", and only the second one is a fact about the disk. Every refusal case
 * therefore also asserts the outside directory stayed empty.
 */
final class ContainedPathTest {

    /**
     * A hand-built archive blob, bypassing {@link WorldArchive#pack}'s own name check.
     *
     * <p>{@code pack} refuses these names, which is the point — but it means a malicious archive
     * cannot be produced by this repository's own packer, and the guard that matters is the one on
     * the <b>unpack</b> side, facing bytes somebody else wrote.
     */
    private static byte[] blobWithEntry(String entryName, String contents) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(entryName, contents.getBytes(StandardCharsets.UTF_8));
        CanonicalWriter w = new CanonicalWriter();
        w.writeU64(WorldArchive.MAGIC);
        w.writeList(files.entrySet(), (ww, e) -> {
            ww.writeString(e.getKey());
            ww.writeBytes(e.getValue());
        });
        return w.toByteArray();
    }

    @ParameterizedTest(name = "an entry named \"{0}\" is refused")
    @ValueSource(strings = {
            "/etc/passwd",                 // absolute, POSIX
            "/../../etc/passwd",           // absolute and traversing
            "\\windows\\system32\\x.dll",  // absolute, Windows
            "C:/windows/x.dll",            // absolute, Windows drive — relative on Linux
            "C:x.dll",                     // drive-relative, still not ours to interpret
            "../evil.dat",                 // the plain traversal
            "region/../../evil.dat",       // traversal from a legitimate-looking prefix
            "..",                          // the segment on its own
            "a/../../b",                   // cancels past the root
            "region\\..\\..\\evil.dat",    // separator the `/` split would never see
            "",                            // no name at all
            "   ",                         // nor this one
    })
    void anUnsafeEntryNameIsRefusedAndWritesNothing(String entryName, @TempDir Path tmp)
            throws Exception {
        Path destination = tmp.resolve("world");
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);

        assertThatThrownBy(() -> WorldArchive.unpackInto(blobWithEntry(entryName, "pwned"),
                destination))
                .as("a downloaded archive must not name a file outside the destination")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(regularFilesUnder(outside))
                .as("and nothing may reach the disk outside the destination")
                .isEmpty();
        assertThat(regularFilesUnder(tmp))
                .as("nor anywhere else in the tree")
                .isEmpty();
    }

    @Test
    void aSymlinkedDirectoryIsNotAWayOut(@TempDir Path tmp) throws Exception {
        Path destination = tmp.resolve("world");
        Path outside = tmp.resolve("outside");
        Files.createDirectories(destination);
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(destination.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException noSymlinks) {
            // Windows without developer mode; the lexical half is covered by the cases above.
            return;
        }

        // Every string check in the tree passes this name: it is relative, has no `..` and no
        // backslash, and `destination/link/evil.dat` lexically starts with `destination`.
        assertThatThrownBy(() ->
                WorldArchive.unpackInto(blobWithEntry("link/evil.dat", "pwned"), destination))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link");

        assertThat(regularFilesUnder(outside))
                .as("following the link would have written outside the destination")
                .isEmpty();
    }

    @Test
    void anOrdinarySaveStillUnpacks(@TempDir Path tmp) throws Exception {
        Path destination = tmp.resolve("world");
        byte[] blob = WorldArchive.pack(Map.of(
                "level.dat", "root".getBytes(StandardCharsets.UTF_8),
                "region/r.0.0.mca", "chunks".getBytes(StandardCharsets.UTF_8),
                "DIM-1/region/r.-1.0.mca", "nether".getBytes(StandardCharsets.UTF_8)));

        WorldArchive.unpackInto(blob, destination);

        assertThat(Files.readString(destination.resolve("level.dat"))).isEqualTo("root");
        assertThat(Files.readString(destination.resolve("region/r.0.0.mca"))).isEqualTo("chunks");
        assertThat(Files.readString(destination.resolve("DIM-1/region/r.-1.0.mca")))
                .isEqualTo("nether");
    }

    /**
     * The reason this class exists: one guard, so the three call sites cannot disagree again.
     *
     * <p>A relative root is the case that used to separate them — {@code normalize()} is lexical
     * and cannot cancel a leading {@code ..} against a directory whose name it does not know, so
     * only the copy that made the root absolute first actually refused it.
     */
    @Test
    void theGuardIsTheSameWhicheverRootItIsGiven(@TempDir Path tmp) throws Exception {
        Path relative = Path.of("build").resolve("contained-path-probe");
        assertThatThrownBy(() -> ContainedPath.inside(relative, "../escape"))
                .as("a relative root must refuse a traversal exactly like an absolute one does")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContainedPath.inside(tmp, "../escape"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ContainedPath.inside(tmp, "a/b.dat"))
                .as("the result is absolute and normalised, whatever the root was")
                .isAbsolute()
                .isEqualTo(tmp.toAbsolutePath().normalize().resolve("a/b.dat"));
        assertThatCode(() -> ContainedPath.checkedName("region/r.0.0.mca"))
                .doesNotThrowAnyException();
    }

    @Test
    void theDestinationRootItselfIsNotAnEntry(@TempDir Path tmp) {
        assertThatThrownBy(() -> ContainedPath.inside(tmp, "."))
                .as("an entry that resolves to the root is not a file the archive can carry")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static java.util.List<Path> regularFilesUnder(Path root) throws java.io.IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }
}
