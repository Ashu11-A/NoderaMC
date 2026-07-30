package dev.nodera.testkit.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate that makes {@code layout.properties} the layout table rather than a document about it.
 *
 * <p>A single source of truth is only single if something fails when a copy reappears. The manifest
 * has one structural risk — that it and {@code settings.gradle.kts} drift apart, so Gradle builds
 * one tree while every other consumer reads another — and {@link #theManifestMatchesWhatGradleBuilt}
 * is the assertion that makes that impossible rather than unlikely. The rest of this class checks
 * the manifest describes a tree that exists.
 *
 * <p>The Gradle side of the comparison arrives as a system property, injected by
 * {@code settings.gradle.kts} from the projects it actually configured. Deliberately not from
 * {@code java/build-logic}: editing a precompiled script plugin on this toolchain re-triggers the
 * kotlin-dsl accessor breakage {@code gradle.properties} documents.
 */
final class LayoutManifestTest {

    /** Populated by `:testing`'s test task from `settings.gradle.kts`; `name=dir` pairs, `;`-joined. */
    private static final String CONFIGURED = System.getProperty("nodera.layout.modules", "");

    private static final LayoutManifest LAYOUT = LayoutManifest.load();

    @Test
    @DisplayName("every module directory exists and holds a build script")
    void everyModuleIsReal() {
        assertThat(LAYOUT.modules())
                .as("layout.properties declares at least the Minecraft-free core")
                .hasSizeGreaterThanOrEqualTo(7);

        LAYOUT.modules().forEach((name, directory) -> {
            assertThat(directory)
                    .as("module %s → %s", name, directory)
                    .isDirectory();
            assertThat(directory.resolve("build.gradle.kts"))
                    .as("module %s has a build script (if it does not, it is not a module)", name)
                    .isRegularFile();
        });
    }

    @Test
    @DisplayName("every crate directory exists and holds a Cargo manifest")
    void everyCrateIsReal() {
        assertThat(LAYOUT.crates()).hasSizeGreaterThanOrEqualTo(5);
        LAYOUT.crates().forEach((name, directory) -> {
            assertThat(directory).as("crate %s → %s", name, directory).isDirectory();
            assertThat(directory.resolve("Cargo.toml"))
                    .as("crate %s has a Cargo manifest", name)
                    .isRegularFile();
        });
    }

    @Test
    @DisplayName("a crate's manifest declares the package name the manifest keys it under")
    void everyCrateKeyIsItsPackageName() throws IOException {
        for (Map.Entry<String, Path> entry : LAYOUT.crates().entrySet()) {
            String declared = Files.readAllLines(
                            entry.getValue().resolve("Cargo.toml"), StandardCharsets.UTF_8)
                    .stream()
                    .map(String::trim)
                    .filter(line -> line.startsWith("name"))
                    .map(line -> line.substring(line.indexOf('=') + 1).trim().replace("\"", ""))
                    .findFirst()
                    .orElse("<none>");
            assertThat(declared)
                    .as("crate.%s must key the crate by its own package name", entry.getKey())
                    .isEqualTo(entry.getKey());
        }
    }

    @Test
    @DisplayName("the build-logic key names the included build")
    void buildLogicIsAnIncludedBuild() {
        Path buildLogic = LAYOUT.root().resolve(LAYOUT.value("buildLogic"));
        assertThat(buildLogic).isDirectory();
        assertThat(buildLogic.resolve("settings.gradle.kts"))
                .as("an included build has its own settings script; a module does not")
                .isRegularFile();
        assertThat(LAYOUT.modules().values())
                .as("build-logic is included, not `include`d — listing it as a module would give it "
                        + "a project path that nothing can depend on")
                .doesNotContain(buildLogic);
    }

    @Test
    @DisplayName("no manifest value escapes the repository root")
    void nothingEscapesTheRoot() {
        Map<String, Path> all = new LinkedHashMap<>(LAYOUT.modules());
        all.putAll(LAYOUT.crates());
        all.forEach((name, directory) -> assertThat(directory.normalize())
                .as("%s must resolve inside the repository", name)
                .startsWithRaw(LAYOUT.root()));
    }

    @Test
    @DisplayName("the cargo target directory sits under the cargo workspace")
    void theTargetDirectoryBelongsToTheWorkspace() {
        // Not cosmetic: `test-counts.sh` runs cargo in one and stages binaries out of the other, and
        // `nodera-app`'s tracker self-test resolves the binary by walking from its own manifest. A
        // target directory that is not the workspace's own means those three disagree.
        assertThat(LAYOUT.dir("cargoTarget"))
                .isEqualTo(LAYOUT.dir("cargoWorkspace").resolve("target").normalize());
    }

    @Test
    @DisplayName("the manifest matches the projects Gradle actually configured")
    void theManifestMatchesWhatGradleBuilt() {
        assertThat(CONFIGURED)
                .as("settings.gradle.kts must inject nodera.layout.modules into :testing's test "
                        + "task — without it this test passes vacuously and the manifest can drift")
                .isNotBlank();

        Set<String> fromGradle = Arrays.stream(CONFIGURED.split(";"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> fromManifest = LAYOUT.modules().entrySet().stream()
                .map(e -> e.getKey() + "="
                        + LAYOUT.root().relativize(e.getValue()).toString().replace('\\', '/'))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(fromManifest)
                .as("layout.properties and settings.gradle.kts describe the same tree — if they "
                        + "ever differ, Gradle builds one layout while the harness, the structural "
                        + "report and the scripts read another")
                .isEqualTo(fromGradle);
    }
}
