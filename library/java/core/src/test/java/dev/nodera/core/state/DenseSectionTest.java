package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalEncoder;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 13 densification (L-26): per-block sections with canonical-form guarantees — equal
 * content is ALWAYS equal bytes, and pre-densification columns keep their exact v1 encoding.
 */
final class DenseSectionTest {

    private static ChunkColumnState uniform(int id) {
        int[] palette = new int[4];
        java.util.Arrays.fill(palette, id);
        return new ChunkColumnState(0, 0, palette, -64, 4);
    }

    @Test
    void uniformColumnsKeepTheExactV1Bytes() {
        ChunkColumnState column = uniform(3);
        CanonicalWriter w = new CanonicalWriter();
        column.encode(w);
        // Version byte pair after the tag must read 1 — the pre-densification wire form.
        byte[] bytes = w.toByteArray();
        assertThat((bytes[2] << 8) | bytes[3]).isEqualTo(1);
        assertThat(ChunkColumnState.decode(new CanonicalReader(bytes))).isEqualTo(column);
    }

    @Test
    void withBlockDensifiesAndBlockAtReadsPerBlock() {
        ChunkColumnState dense = uniform(0).withBlock(2, 5, 6, 7, 9);
        assertThat(dense.blockAt(2, 5, 6, 7)).isEqualTo(9);
        assertThat(dense.blockAt(2, 5, 6, 8)).isZero();
        assertThat(dense.blockAt(1, 5, 6, 7)).isZero();
        assertThat(dense.denseSections()).hasSize(1);
        // Canonical: the dense section's palette slot is pinned to 0.
        assertThat(dense.paletteStateIdsPerSection()[2]).isZero();
    }

    @Test
    void mutationHistoryNeverLeaksIntoTheBytes() {
        // Same content via different histories → identical canonical bytes.
        ChunkColumnState direct = uniform(0).withBlock(2, 5, 6, 7, 9);
        ChunkColumnState detoured = uniform(0)
                .withBlock(2, 1, 1, 1, 4)
                .withBlock(2, 5, 6, 7, 9)
                .withBlock(2, 1, 1, 1, 0);
        assertThat(CanonicalEncoder.encode(detoured))
                .isEqualTo(CanonicalEncoder.encode(direct));
    }

    @Test
    void denseSectionThatBecomesUniformResparsifiesToV1Bytes() {
        ChunkColumnState roundTripped = uniform(0)
                .withBlock(2, 5, 6, 7, 9)
                .withBlock(2, 5, 6, 7, 0);
        assertThat(roundTripped.denseSections()).isEmpty();
        assertThat(CanonicalEncoder.encode(roundTripped))
                .isEqualTo(CanonicalEncoder.encode(uniform(0)));
    }

    @Test
    void denseColumnRoundTripsThroughV2Wire() {
        ChunkColumnState dense = uniform(1).withBlock(0, 0, 0, 0, 5).withBlock(3, 15, 15, 15, 7);
        CanonicalWriter w = new CanonicalWriter();
        dense.encode(w);
        byte[] bytes = w.toByteArray();
        assertThat((bytes[2] << 8) | bytes[3]).isEqualTo(2);
        assertThat(ChunkColumnState.decode(new CanonicalReader(bytes))).isEqualTo(dense);
    }

    @Test
    void malformedDenseSectionsAreRejected() {
        assertThatThrownBy(() -> new ChunkColumnState(0, 0, new int[4], -64, 4,
                List.of(new ChunkColumnState.DenseSection(9, new int[4096]))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> new ChunkColumnState.DenseSection(0, new int[7]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
