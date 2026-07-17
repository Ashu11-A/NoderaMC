package dev.nodera.transport.neoforge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: proves the {@code transport-neoforge} test wiring is live on the JUnit platform.
 * Placeholder until Task 4 adds the relay transport + reassembler. (Task 1 acceptance §3.)
 */
final class TransportNeoForgeSmokeTest {

    @Test
    void moduleOnClasspath() {
        assertThat(getClass().getModule().getName()).isNull(); // classpath, not named module
    }
}
