package dev.nodera.core.region;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RegionBounds} corner behaviour for region {@code (0,0)} (positive) and {@code (-1,-1)}
 * (negative), including the exactly-1-chunk halo ring and owned/halo exclusion.
 *
 * <p>Thread-context: single test thread.
 */
final class RegionBoundsTest {

    @Test
    void region00OwnsItsSquareAndHaloRingIsExactlyOneChunk() {
        RegionBounds b = RegionBounds.of(new RegionId(DimensionKey.overworld(), 0, 0));

        assertThat(b.ownedChunkCount()).isEqualTo(64);

        assertThat(b.ownsBlock(0, 0)).isTrue();
        assertThat(b.ownsBlock(127, 127)).isTrue();
        assertThat(b.ownsBlock(-1, 0)).isFalse();
        assertThat(b.ownsBlock(0, -1)).isFalse();
        assertThat(b.ownsBlock(128, 0)).isFalse();

        assertThat(b.isHaloBlock(0, 0)).isFalse();
        assertThat(b.isHaloBlock(-1, 0)).isTrue();
        assertThat(b.isHaloBlock(0, -1)).isTrue();
        assertThat(b.isHaloBlock(-16, -16)).isTrue();
        assertThat(b.isHaloBlock(143, 143)).isTrue();
        assertThat(b.isHaloBlock(128, 0)).isTrue();

        assertThat(b.containsBlock(-16, -16)).isTrue();
        assertThat(b.containsBlock(143, 143)).isTrue();
        assertThat(b.containsBlock(-17, 0)).isFalse();
        assertThat(b.containsBlock(144, 0)).isFalse();

        assertThat(b.containsChunk(0, 0)).isTrue();
        assertThat(b.containsChunk(7, 7)).isTrue();
        assertThat(b.containsChunk(-1, 0)).isTrue();
        assertThat(b.containsChunk(8, 0)).isTrue();
        assertThat(b.containsChunk(-1, -1)).isTrue();
        assertThat(b.containsChunk(8, 8)).isTrue();
        assertThat(b.containsChunk(9, 0)).isFalse();
    }

    @Test
    void negativeRegionOwnsItsSquareAndHaloRing() {
        RegionBounds b = RegionBounds.of(new RegionId(DimensionKey.overworld(), -1, -1));

        assertThat(b.ownsBlock(-128, -128)).isTrue();
        assertThat(b.ownsBlock(-1, -1)).isTrue();
        assertThat(b.ownsBlock(-129, -1)).isFalse();
        assertThat(b.ownsBlock(0, 0)).isFalse();

        assertThat(b.isHaloBlock(-129, -1)).isTrue();
        assertThat(b.isHaloBlock(-144, -144)).isTrue();
        assertThat(b.isHaloBlock(0, 0)).isTrue();
        assertThat(b.isHaloBlock(15, 15)).isTrue();
        assertThat(b.isHaloBlock(-1, -1)).isFalse();

        assertThat(b.containsChunk(-8, -8)).isTrue();
        assertThat(b.containsChunk(-1, -1)).isTrue();
        assertThat(b.containsChunk(-9, -9)).isTrue();
        assertThat(b.containsChunk(0, 0)).isTrue();
        assertThat(b.containsChunk(1, 0)).isFalse();
        assertThat(b.containsChunk(-10, 0)).isFalse();
    }

    @Test
    void encodeDecodeRoundTrip() {
        RegionBounds b = RegionBounds.of(new RegionId(DimensionKey.overworld(), -1, 2));
        CanonicalWriter w = new CanonicalWriter();
        b.encode(w);
        RegionBounds decoded = RegionBounds.decode(new CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(b);
    }
}
