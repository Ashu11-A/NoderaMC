package dev.nodera.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the test wiring compiles & runs for the {@code core} module (Task 1 acceptance #3). */
final class CoreSmokeTest {
    @Test
    void junitAndAssertjAreAvailable() {
        assertThat(2 + 2).isEqualTo(4);
    }
}
