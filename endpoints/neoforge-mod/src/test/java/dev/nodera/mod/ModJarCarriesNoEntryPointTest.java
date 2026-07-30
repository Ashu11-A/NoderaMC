package dev.nodera.mod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mod jar ships every player the peer's always-on services, and no way to launch one.
 *
 * <p>Both halves matter, and they used to be enforced by a module boundary. {@code dev.nodera.headless}
 * lived in {@code :worker}, which nothing bundled — so the entry point could not reach a player's
 * {@code mods/} folder, and the services could not either. On 2026-07-30 the package moved back into
 * {@code :peer} so that a peer cannot be constructed without the services that make it serve; the
 * mod bundles {@code :peer}, so the services now ship, which is the point.
 *
 * <p>What must NOT ship is a launchable {@code main}. {@code :peer} keeps it in a separate
 * {@code src/headless} source set that {@code tasks.jar} does not carry, and this test is what says
 * so about the artefact rather than about the build script. It reads the jar Gradle actually
 * produced: a bundle list edited without noticing, a source set folded back into main, or an
 * {@code application} plugin default quietly re-adding a {@code Main-Class} all fail here.
 *
 * <p>The jar path arrives as a system property from the build, and the test asserts the property is
 * set before using it — a guard that silently skips when it cannot find its subject is the failure
 * mode this whole area already had once.
 */
final class ModJarCarriesNoEntryPointTest {

    private static Path modJar() {
        String configured = System.getProperty("nodera.modJar", "");
        assertThat(configured)
                .as("`:neoforge-mod:test` must pass -Dnodera.modJar (see build.gradle.kts) — without "
                        + "it this test has no artefact to inspect and would pass vacuously")
                .isNotBlank();
        Path jar = Path.of(configured);
        assertThat(jar).as("the mod jar the build just produced").isRegularFile();
        return jar;
    }

    @Test
    @DisplayName("the mod jar carries the peer's always-on services")
    void theServicesShip() throws IOException {
        assertThat(entries("dev/nodera/headless/"))
                .as("a peer IS a worker: the hosting, archive, registry, ownership and replication "
                        + "services belong on every player's classpath")
                .hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("the mod jar carries no launchable entry point")
    void noEntryPointShips() throws IOException {
        assertThat(entries("dev/nodera/headless/HeadlessPeerMain"))
                .as("the always-on node's `main` must stay out of every player's mods/ folder; it "
                        + "compiles in :peer's `src/headless` source set, which `tasks.jar` does "
                        + "not carry")
                .isEmpty();

        try (JarFile jar = new JarFile(modJar().toFile())) {
            Manifest manifest = jar.getManifest();
            String mainClass = manifest == null
                    ? null
                    : manifest.getMainAttributes().getValue("Main-Class");
            assertThat(mainClass)
                    .as("a mod jar is loaded by FML, never launched; a Main-Class here means the "
                            + "`application` plugin reached a module it should not have")
                    .isNull();
        }
    }

    @Test
    @DisplayName("the mod jar carries the endpoint library")
    void theEndpointLibraryShips() throws IOException {
        // The single highest-risk edit in the reorganisation. `:endpoint` has to be in BOTH
        // hand-written lists in build.gradle.kts — `noderaModProjects` (so FML's module classloader
        // can see it in a dev run) and `noderaBundled` (so the shipped jar contains it). Miss either
        // and the failure is a NoClassDefFoundError at runtime, in front of a player, which no
        // compile and no unit test would have caught.
        assertThat(entries("dev/nodera/endpoint/"))
                .as("the Minecraft-free endpoint logic must be inside the jar, not merely on the "
                        + "build's compile path")
                .hasSizeGreaterThanOrEqualTo(25);
        assertThat(entries("dev/nodera/endpoint/control/CompanionClient"))
                .as("the companion wire client specifically — it is what the mod refuses to start "
                        + "without")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the mod jar carries no SLF4J binding")
    void noLoggingBindingShips() throws IOException {
        // NeoForge/log4j owns the binding inside a Minecraft runtime. Two bindings on one classpath
        // is a startup warning and a coin flip over which one wins.
        assertThat(entries("org/slf4j/simple/"))
                .as("slf4j-simple is scoped to :peer's headless source set precisely so it cannot "
                        + "arrive here and compete with NeoForge's binding")
                .isEmpty();
    }

    private static List<String> entries(String prefix) throws IOException {
        Path jar = modJar();
        List<String> found = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            var names = file.entries();
            while (names.hasMoreElements()) {
                ZipEntry entry = names.nextElement();
                if (entry.getName().startsWith(prefix)) {
                    found.add(entry.getName());
                }
            }
        }
        assertThat(Files.size(jar)).as("the jar is not empty").isGreaterThan(0);
        return found;
    }
}
