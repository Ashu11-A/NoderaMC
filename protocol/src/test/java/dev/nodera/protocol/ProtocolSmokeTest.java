package dev.nodera.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class ProtocolSmokeTest {
    @Test
    void wiring() {
        assertThat("nodera").isNotBlank();
    }
}
