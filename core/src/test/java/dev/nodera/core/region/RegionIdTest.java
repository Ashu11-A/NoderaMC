package dev.nodera.core.region;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Critical negative-coordinate behaviour for {@link RegionId} (Task 2 acceptance #3) plus
 * canonical round-trip and byte-determinism.
 *
 * <p>Thread-context: single test thread.
 */
final class RegionIdTest {

    private final DimensionKey overworld = DimensionKey.overworld();

    @Test
    void negativeChunkCoordinatesFloorTowardNegativeInfinity() {
        assertThat(RegionId.fromChunk(overworld, -1, 0).regionX()).isEqualTo(-1);
        assertThat(RegionId.fromChunk(overworld, -8, 0).regionX()).isEqualTo(-1);
        assertThat(RegionId.fromChunk(overworld, -9, 0).regionX()).isEqualTo(-2);
    }

    @Test
    void negativeChunkCoordinatesOnZAxis() {
        assertThat(RegionId.fromChunk(overworld, 0, -1).regionZ()).isEqualTo(-1);
        assertThat(RegionId.fromChunk(overworld, 0, -8).regionZ()).isEqualTo(-1);
        assertThat(RegionId.fromChunk(overworld, 0, -9).regionZ()).isEqualTo(-2);
    }

    @Test
    void positiveChunkCoordinates() {
        assertThat(RegionId.fromChunk(overworld, 0, 0).regionX()).isEqualTo(0);
        assertThat(RegionId.fromChunk(overworld, 7, 0).regionX()).isEqualTo(0);
        assertThat(RegionId.fromChunk(overworld, 8, 0).regionX()).isEqualTo(1);
    }

    @Test
    void encodeDecodeRoundTrip() {
        RegionId id = new RegionId(overworld, 3, -5);
        CanonicalWriter w = new CanonicalWriter();
        id.encode(w);
        RegionId decoded = RegionId.decode(new CanonicalReader(w.toByteArray()));
        assertThat(decoded).isEqualTo(id);
    }

    @Test
    void equalRegionIdsEncodeToIdenticalBytes() {
        RegionId a = new RegionId(overworld, 3, -5);
        RegionId b = new RegionId(overworld, 3, -5);
        CanonicalWriter wa = new CanonicalWriter();
        a.encode(wa);
        CanonicalWriter wb = new CanonicalWriter();
        b.encode(wb);
        assertThat(wa.toByteArray()).isEqualTo(wb.toByteArray());
    }

    @Test
    void negativeRegionEncodesAndDecodesStably() {
        RegionId id = new RegionId(overworld, -1, -2);
        CanonicalWriter w1 = new CanonicalWriter();
        id.encode(w1);
        RegionId decoded = RegionId.decode(new CanonicalReader(w1.toByteArray()));
        CanonicalWriter w2 = new CanonicalWriter();
        decoded.encode(w2);
        assertThat(decoded).isEqualTo(id);
        assertThat(w2.toByteArray()).isEqualTo(w1.toByteArray());
    }
}
