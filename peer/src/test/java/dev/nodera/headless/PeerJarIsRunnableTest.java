package dev.nodera.headless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code nodera-peer.jar} — the release deliverable — starts.
 *
 * <p>The other shape of the headless node, {@code installDist}, is exercised by every live suite and
 * by the companion app. The fat jar is not: it is assembled by a task nothing else runs, from a
 * merge of ~30 dependency jars, and every way it can be broken is invisible until somebody types
 * {@code java -jar}. Three of them have a history:
 *
 * <ul>
 *   <li>a missing {@code Main-Class} — {@code java -jar} refuses to start;
 *   <li>BouncyCastle's signature files surviving the merge — the JVM throws
 *       {@code SecurityException: Invalid signature file digest} at the first class load, long after
 *       a successful build;
 *   <li>a {@code META-INF/services} entry lost to {@code DuplicatesStrategy.EXCLUDE} — the node runs
 *       with no SLF4J binding and logs nothing, which looks like a hang.
 * </ul>
 *
 * <p>So this test executes the artefact rather than reading the build script. {@code --help} is the
 * cheapest run that proves the whole chain: the launcher resolves {@code Main-Class}, the JVM loads
 * {@link HeadlessPeerMain}, that class loads {@link TestMode} out of a different source set that was
 * merged into the same jar, and the process exits 64 without starting a node or binding a port.
 */
final class PeerJarIsRunnableTest {

    /** How long the {@code --help} run may take before it is called a hang. */
    private static final int RUN_TIMEOUT_SECONDS = 60;

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

    @Test
    @DisplayName("the peer jar declares the headless entry point")
    void mainClassIsDeclared() throws IOException {
        try (JarFile jar = new JarFile(peerJar().toFile())) {
            assertThat(jar.getManifest()).as("a fat jar with no manifest cannot be run").isNotNull();
            assertThat(jar.getManifest().getMainAttributes().getValue("Main-Class"))
                    .as("`java -jar nodera-peer.jar` resolves the entry point through this attribute")
                    .isEqualTo("dev.nodera.headless.HeadlessPeerMain");
        }
    }

    @Test
    @DisplayName("the peer jar carries no inherited signature files")
    void signatureFilesWereStripped() throws IOException {
        List<String> signatures = entries().stream()
                .filter(name -> name.startsWith("META-INF/"))
                .filter(name -> name.endsWith(".SF") || name.endsWith(".DSA")
                        || name.endsWith(".RSA") || name.endsWith(".EC"))
                .toList();
        assertThat(signatures)
                .as("BouncyCastle ships signed; its digests do not describe the jar it was merged "
                        + "into, so a surviving signature file is a SecurityException on first use")
                .isEmpty();
    }

    @Test
    @DisplayName("the peer jar keeps the SLF4J provider registration")
    void serviceRegistrationsSurvivedTheMerge() throws IOException {
        assertThat(entries())
                .as("the headless source set contributes slf4j-simple; a ServiceLoader file dropped "
                        + "by the merge is a node that runs and logs nothing")
                .contains("META-INF/services/org.slf4j.spi.SLF4JServiceProvider");
    }

    @Test
    @DisplayName("the peer jar carries both source sets: the services and the entry point")
    void bothSourceSetsShip() throws IOException {
        List<String> classes = entries();
        assertThat(classes)
                .as("the entry point compiles in src/headless and must be IN this jar — unlike the "
                        + "mod jar, where ModJarCarriesNoEntryPointTest asserts the opposite")
                .contains("dev/nodera/headless/HeadlessPeerMain.class");
        assertThat(classes.stream().filter(n -> n.startsWith("dev/nodera/peer/")).toList())
                .as("the peer runtime itself")
                .isNotEmpty();
    }

    @Test
    @DisplayName("`java -jar nodera-peer.jar --help` runs and exits without starting a node")
    void theJarActuallyRuns() throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path output = Files.createTempFile("nodera-peer-help", ".txt");
        try {
            Process process = new ProcessBuilder(
                    java.toString(), "-jar", peerJar().toString(), "--help")
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            boolean exited = process.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            assertThat(exited)
                    .as("--help must not start a node; it printed:%n%s", text)
                    .isTrue();
            // 64 is EX_USAGE, which is what HeadlessPeerMain exits with for --help and for a bad
            // argument. Asserting the code AND the text keeps a jar that dies in the launcher (which
            // also fails, with a different code and a JVM message) from reading as a pass.
            assertThat(process.exitValue())
                    .as("the run printed:%n%s", text)
                    .isEqualTo(64);
            assertThat(text)
                    .as("the usage text proves TestMode loaded out of the merged source set")
                    .contains("nodera-headless");
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static List<String> entries() throws IOException {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(peerJar().toFile())) {
            for (ZipEntry entry : jar.stream().toList()) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
