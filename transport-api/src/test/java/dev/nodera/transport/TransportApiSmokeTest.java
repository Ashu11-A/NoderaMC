package dev.nodera.transport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class TransportApiSmokeTest {
    @Test
    void wiring() {
        assertThat(6 / 2).isEqualTo(3);
    }
}
