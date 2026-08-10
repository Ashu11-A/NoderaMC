package dev.nodera.testkit.harness;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The "a skip becomes a failure" mechanism, exercised.
 *
 * <p>{@link SpawnedService#binariesRequired()} is what separates <i>"this machine has no cargo
 * toolchain"</i> from <i>"the job that promised to build these did not"</i>, and twelve suites
 * decide between skipping and failing on it. It shipped with no test and with no reachable caller:
 * nothing in the tree set either source, and a launcher {@code -D} lands in the Gradle daemon
 * rather than in a forked test worker, so the documented recipe could not have worked either. This
 * class asserts the decision itself; {@code NoderaServiceBinaries.kt} is what carries the flag into
 * this JVM.
 *
 * <p>The binary name below is deliberately one that no build produces. Using a real one would make
 * the assertion depend on whether the developer running it happened to have {@code cargo build}
 * output on disk — which is the exact ambiguity this mechanism exists to remove.
 */
final class SpawnedServiceRequirementTest {

    /** A name no profile of any build ever writes, so "absent" is a property of the test. */
    private static final String NEVER_BUILT = "nodera-binary-that-does-not-exist";

    /**
     * What the property was when this class started. Saved and restored rather than cleared, because
     * CI sets it globally — `./gradlew check build -Dnodera.test.requireServiceBinaries=true` — and a
     * bare `clearProperty` in `@AfterEach` would switch the promise off for the rest of this JVM.
     * Nothing else in this fork consumes it today, so that would cost nothing today, and would
     * silently downgrade the next `SpawnedService` test ordered after this class from "fails when a
     * binary is missing" to "skips".
     */
    private String inherited;

    @BeforeEach
    void rememberFlag() {
        inherited = System.getProperty(SpawnedService.REQUIRE_PROPERTY);
    }

    @AfterEach
    void restoreFlag() {
        if (inherited == null) {
            System.clearProperty(SpawnedService.REQUIRE_PROPERTY);
        } else {
            System.setProperty(SpawnedService.REQUIRE_PROPERTY, inherited);
        }
    }

    @Test
    @DisplayName("with no promise, a missing binary is empty and the caller may skip")
    void absenceIsASkipWhenNothingPromisedTheBinary() {
        System.clearProperty(SpawnedService.REQUIRE_PROPERTY);

        assertThat(SpawnedService.binariesRequired())
                .as("a plain run promises nothing, so a Java-only checkout stays green")
                .isFalse();
        assertThat(SpawnedService.binary(NEVER_BUILT)).isEmpty();
    }

    @Test
    @DisplayName("with the promise set, a missing binary throws instead of skipping")
    void absenceIsAFailureWhenTheRunPromisedTheBinary() {
        System.setProperty(SpawnedService.REQUIRE_PROPERTY, "true");

        assertThat(SpawnedService.binariesRequired()).isTrue();
        assertThatThrownBy(() -> SpawnedService.binary(NEVER_BUILT))
                .as("the message has to name the binary, the flag and how to build it, because the "
                        + "reader is somebody looking at a red CI job for a suite that was green")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(NEVER_BUILT)
                .hasMessageContaining(SpawnedService.REQUIRE_PROPERTY)
                .hasMessageContaining("cargo build --release --bin " + NEVER_BUILT);
    }

    /**
     * The three ways a run says "no" without unsetting the variable. CI templates and shell
     * wrappers export {@code FLAG=false} or {@code FLAG=0} far more often than they unset, and an
     * "is it non-null" check would read every one of those as a promise.
     */
    @Test
    @DisplayName("false, 0 and blank are not a promise")
    void explicitlyNegativeValuesAreNotAPromise() {
        for (String negative : new String[] {"false", "FALSE", "0", "", "   "}) {
            System.setProperty(SpawnedService.REQUIRE_PROPERTY, negative);
            assertThat(SpawnedService.binariesRequired())
                    .as("value %s", negative.isBlank() ? "<blank>" : negative)
                    .isFalse();
            assertThat(SpawnedService.binary(NEVER_BUILT)).isEmpty();
        }
    }

    @Test
    @DisplayName("any other value is a promise")
    void anyOtherValueIsAPromise() {
        for (String positive : new String[] {"true", "TRUE", "1", "yes"}) {
            System.setProperty(SpawnedService.REQUIRE_PROPERTY, positive);
            assertThat(SpawnedService.binariesRequired()).as("value %s", positive).isTrue();
        }
    }

    /**
     * The failure message points at the directory that was searched, not just at the binary. The
     * whole point of the flag is that somebody reads the message and fixes the CI step, and
     * {@code dir.cargoTarget} is the thing a wrong step gets wrong.
     */
    @Test
    @DisplayName("the failure names the directory it searched")
    void theFailureNamesTheSearchedDirectory() {
        Path root = LayoutManifest.load().dir("cargoTarget");
        System.setProperty(SpawnedService.REQUIRE_PROPERTY, "true");

        assertThatThrownBy(() -> SpawnedService.binary(NEVER_BUILT))
                .hasMessageContaining(root.resolve("release").toString())
                .hasMessageContaining(root.resolve("debug").toString());
    }
}
