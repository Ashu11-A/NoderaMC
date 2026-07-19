package dev.nodera.mod;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: proves the {@code neoforge-mod} test wiring is live on the JUnit platform and
 * the mod id constant is stable. No Minecraft runtime needed. (Task 1 acceptance §3.)
 */
final class NoderaModSmokeTest {

    @Test
    void modIdIsNodera() {
        assertThat(NoderaMod.MOD_ID).isEqualTo("nodera");
    }
}
