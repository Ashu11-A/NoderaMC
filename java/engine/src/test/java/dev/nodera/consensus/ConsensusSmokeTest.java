package dev.nodera.consensus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class ConsensusSmokeTest {
    @Test
    void wiring() {
        assertThat(5 - 1).isEqualTo(4);
    }
}
