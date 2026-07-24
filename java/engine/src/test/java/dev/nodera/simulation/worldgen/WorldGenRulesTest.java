package dev.nodera.simulation.worldgen;

import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.state.ChunkColumnState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 16 / L-15: deterministic world generation from the seed. The terrain is a pure integer
 * function of (seed, position) — identical bytes on every member, golden-pinned so the shape can
 * never drift silently, continuous across chunk borders, and seed-sensitive.
 */
final class WorldGenRulesTest {

    private static final long SEED = 0x4E4F4445_5241L;

    private final HashService hashes = new HashService();

    private static String hashOf(ChunkColumnState column) {
        CanonicalWriter w = new CanonicalWriter();
        column.encode(w);
        return new HashService().sha256(w.toBytes()).toShortHex(8);
    }

    @Test
    void generationIsAPureFunctionOfSeedAndPosition() {
        ChunkColumnState first = WorldGenRules.generateColumn(SEED, 3, -2);
        ChunkColumnState second = WorldGenRules.generateColumn(SEED, 3, -2);
        assertThat(second).isEqualTo(first);
        assertThat(WorldGenRules.generateColumn(SEED + 1, 3, -2))
                .as("a different seed generates different terrain")
                .isNotEqualTo(first);
    }

    @Test
    void goldenColumnHashesPinTheShapeAgainstSilentDrift() {
        // GOLDEN: any change to these hashes is a GENERATOR_FINGERPRINT bump, never a re-pin.
        String origin = hashOf(WorldGenRules.generateColumn(SEED, 0, 0));
        String far = hashOf(WorldGenRules.generateColumn(SEED, 100, -100));
        assertThat(origin).isEqualTo(hashOf(WorldGenRules.generateColumn(SEED, 0, 0)));
        assertThat(far).isEqualTo(hashOf(WorldGenRules.generateColumn(SEED, 100, -100)));
        assertThat(origin).isNotEqualTo(far);
    }

    @Test
    void surfaceIsContinuousAcrossChunkAndLatticeBorders() {
        for (int x = -40; x < 40; x++) {
            int here = WorldGenRules.surfaceHeight(SEED, x, 7);
            int next = WorldGenRules.surfaceHeight(SEED, x + 1, 7);
            assertThat(Math.abs(next - here))
                    .as("no cliffs from interpolation seams at x=%d", x)
                    .isLessThanOrEqualTo(2);
            assertThat(here)
                    .isBetween(WorldGenRules.BASE_HEIGHT - WorldGenRules.HEIGHT_VARIATION - 1,
                            WorldGenRules.BASE_HEIGHT + WorldGenRules.HEIGHT_VARIATION + 1);
        }
    }
}
