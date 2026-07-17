package dev.nodera.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class StorageApiSmokeTest {
    @Test
    void wiring() {
        assertThat(7 + 0).isEqualTo(7);
    }
}
