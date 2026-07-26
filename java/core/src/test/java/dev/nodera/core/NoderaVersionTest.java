package dev.nodera.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java half of the single-source versioning rule: what a running peer publishes on the wire is
 * the number in the repository's root {@code VERSION} file, not a constant somebody remembered to
 * edit.
 *
 * <p>This is the test that fails when a release bumps {@code VERSION} and forgets the build wiring,
 * or when someone re-inlines a literal into {@link NoderaConstants}. The complementary check across
 * the other toolchains (Cargo, Tauri, the UI package) is {@code scripts/version.sh --check}.
 */
final class NoderaVersionTest {

    @Test
    void theProductVersionComesFromTheBuildRatherThanTheSourceTree() {
        assertThat(NoderaConstants.PRODUCT_VERSION)
                .as("a jar built by Gradle always carries the expanded resource; the fallback means "
                        + "processResources did not run")
                .isNotEqualTo(NoderaConstants.UNBUILT_VERSION)
                .matches("\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.\\-]+)?");
    }

    @Test
    void theProductVersionEqualsTheRootVersionFile() throws IOException {
        Optional<Path> versionFile = findVersionFile();
        assertThat(versionFile)
                .as("the repository root must carry a VERSION file")
                .isPresent();
        assertThat(NoderaConstants.PRODUCT_VERSION).isEqualTo(readVersion(versionFile.get()));
    }

    @Test
    void theClientAgentIsTheProductNameAndVersion() {
        assertThat(NoderaConstants.CLIENT_AGENT)
                .isEqualTo(NoderaConstants.PRODUCT_NAME + " " + NoderaConstants.PRODUCT_VERSION);
    }

    /**
     * Walk up from the test's working directory (the module directory under Gradle) to the
     * repository root. Walking rather than a fixed relative path so the test survives the module
     * being moved again — the last move (`java/` monorepo layout) broke every fixed path in the
     * build.
     */
    private static Optional<Path> findVersionFile() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("VERSION");
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            directory = directory.getParent();
        }
        return Optional.empty();
    }

    /** First non-empty, non-comment line — the same rule {@code settings.gradle.kts} applies. */
    private static String readVersion(Path path) throws IOException {
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.split("#", 2)[0].trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        throw new IllegalStateException("VERSION is empty: " + path);
    }
}
