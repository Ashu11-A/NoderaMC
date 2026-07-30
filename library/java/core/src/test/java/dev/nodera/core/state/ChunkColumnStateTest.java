package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChunkColumnState} defensive-copy and round-trip checks (Task 2). The {@code int[]} is
 * fully encapsulated: mutating the source array after construction (or the returned array) must not
 * affect the instance.
 */
final class ChunkColumnStateTest {

    @Test
    void sourceArrayMutationDoesNotAffectInstance() {
        int[] src = {1, 2, 3};
        ChunkColumnState c = new ChunkColumnState(0, 0, src, -64, 3);
        src[0] = 999;
        assertThat(c.paletteStateIdsPerSection()).containsExactly(1, 2, 3);
    }

    @Test
    void returnedArrayIsDefensiveCopy() {
        ChunkColumnState c = new ChunkColumnState(0, 0, new int[]{1, 2, 3}, -64, 3);
        int[] got = c.paletteStateIdsPerSection();
        got[0] = 999;
        assertThat(c.paletteStateIdsPerSection()).containsExactly(1, 2, 3);
    }

    @Test
    void encodeDecodeRoundTrip() {
        int[] palette = {5, 7, 9, 0};
        ChunkColumnState c = new ChunkColumnState(10, -20, palette, -64, 4);
        CanonicalWriter w = new CanonicalWriter();
        c.encode(w);
        ChunkColumnState decoded = ChunkColumnState.decode(new CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(c);
        assertThat(decoded.paletteStateIdsPerSection()).containsExactly(5, 7, 9, 0);
    }

    @Test
    void isUnsupportedPlaceholderReturnsFalse() {
        ChunkColumnState c = new ChunkColumnState(0, 0, new int[]{1}, 0, 1);
        assertThat(c.isUnsupported()).isFalse();
    }
}
