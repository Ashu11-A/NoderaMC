package dev.nodera.simulation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class SimulationSmokeTest {
    @Test
    void wiring() {
        assertThat(3 * 3).isEqualTo(9);
    }
}
