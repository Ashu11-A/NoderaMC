package dev.nodera.headless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code nodera-peer.jar} carries only what the headless node loads.
 *
 * <p>On 2026-07-31 this artefact was 107 MB, of which 1.5 MB was Nodera code. The rest was two
 * dependencies nobody had checked:
 *
 * <ul>
 *   <li><b>rocksdbjni, 67 MB.</b> {@code :storage} declared it {@code implementation}, which keeps
 *       a library off a consumer's compile classpath but still puts it on their <i>runtime</i>
 *       classpath. So every consumer of {@code :storage} inherited fourteen platform natives
 *       (linux 32/64, musl variants, aarch64, riscv64, ppc64le, s390x, macOS x86_64 + arm64,
 *       win64). The headless peer classloads none of them: its only use of the durable tier's
 *       package was {@code FsContentStore}, which is {@code java.nio.file} and now lives in
 *       {@code dev.nodera.storage.fs}.
 *   <li><b>fastutil, 19.5 MB.</b> Declared by {@code :engine} with a comment claiming it backed
 *       the hot path, and imported by exactly zero source files in the repository.
 * </ul>
 *
 * <p>Both were invisible in review because a dependency declaration is one line and its cost lands
 * in an artefact nobody weighs. The desktop installers inherited the same payload through
 * {@code installDist} — the {@code .deb} was 114 MB for a 19 MB application. So the guard is on the
 * built file, and it is about bytes rather than about declarations: a transitive dependency added
 * three modules away fails here, which is where it would otherwise have gone unnoticed.
 *
 * @see PeerJarIsRunnableTest for the complementary check that the artefact still starts
 */
final class PeerJarPayloadTest {

    /**
     * The jar's ceiling. Roughly double the ~18 MB it occupies today — loose enough that ordinary
     * growth does not touch it, tight enough that any of the four dependencies deleted here coming
     * back fails the build. Raise it deliberately, with the reason, never to make a build go green.
     */
    private static final long MAX_JAR_BYTES = 32L * 1024 * 1024;

    private static Path peerJar() {
        String configured = System.getProperty("nodera.peerJar", "");
        assertThat(configured)
                .as("`:peer:test` must pass -Dnodera.peerJar (see build.gradle.kts) — without it "
                        + "this test has no artefact to inspect and would pass vacuously")
                .isNotBlank();
        Path jar = Path.of(configured);
        assertThat(jar).as("the peer jar the build just produced").isRegularFile();
        return jar;
    }

    private static List<String> entriesMatching(String... substrings) throws IOException {
        List<String> hits = new ArrayList<>();
        try (JarFile jar = new JarFile(peerJar().toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                for (String needle : substrings) {
                    if (name.contains(needle)) {
                        hits.add(name);
                        break;
                    }
                }
            }
        }
        return hits;
    }

    @Test
    @DisplayName("the peer jar bundles no RocksDB — not the classes, not the fourteen natives")
    void carriesNoRocksDb() throws IOException {
        assertThat(entriesMatching("org/rocksdb/", "librocksdbjni"))
                .as("rocksdbjni is back on the peer's runtime classpath. The headless node never "
                        + "classloads the tier; something re-declared it (or declared it "
                        + "`implementation` rather than `compileOnly`) and put ~67 MB of foreign "
                        + "platform natives into this jar and into every desktop installer")
                .isEmpty();
    }

    @Test
    @DisplayName("the peer jar bundles no fastutil")
    void carriesNoFastutil() throws IOException {
        assertThat(entriesMatching("it/unimi/dsi/"))
                .as("fastutil is back. It was carried for years with zero call sites; if a "
                        + "primitive-collection need has genuinely arrived, the commit that adds "
                        + "the dependency must add the import too, and this test can then move to "
                        + "asserting the size rather than the absence")
                .isEmpty();
    }

    @Test
    @DisplayName("the peer jar stays under its size ceiling")
    void staysUnderTheSizeCeiling() throws IOException {
        long size = Files.size(peerJar());
        assertThat(size)
                .as("nodera-peer.jar is %d MB, over the %d MB ceiling. Somebody added a heavy "
                        + "transitive dependency; find it with `unzip -v` grouped by top-level "
                        + "directory before raising this number",
                        size / (1024 * 1024), MAX_JAR_BYTES / (1024 * 1024))
                .isLessThanOrEqualTo(MAX_JAR_BYTES);
    }
}
