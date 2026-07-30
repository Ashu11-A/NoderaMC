package dev.nodera.testkit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class TestkitSmokeTest {
    @Test
    void wiring() {
        assertThat(8 - 3).isEqualTo(5);
    }
}
