package dev.nodera.core.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the unbounded-allocation DoS bound in {@link CanonicalReader}: a
 * variable-length field's u32 length/count prefix is attacker-controlled on the wire, so the
 * reader must reject a prefix that exceeds the remaining frame bytes BEFORE allocating.
 */
final class CanonicalReaderBoundsTest {

    /** A single big-endian u32. */
    private static byte[] u32(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    @Test
    void readBytesRejectsLengthPrefixExceedingRemainingFrame() {
        // Declares ~2 GiB of payload; the frame has 0 payload bytes. Must throw, not allocate.
        CanonicalReader reader = new CanonicalReader(u32(0x7FFFFFFF));
        assertThatThrownBy(reader::readBytes)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds remaining");
    }

    @Test
    void readListRejectsCountExceedingRemainingFrame() {
        // Declares 0x7FFFFFFF list elements; no element bytes follow. Must throw, not allocate.
        CanonicalReader reader = new CanonicalReader(u32(0x7FFFFFFF));
        assertThatThrownBy(() -> reader.readList(r -> r.readU8()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds remaining");
    }

    @Test
    void readBytesAcceptsLengthExactlyEqualToRemaining() {
        // Legitimate field: length = 2 with exactly 2 payload bytes present.
        CanonicalReader reader = new CanonicalReader(new byte[] {0, 0, 0, 2, 'a', 'b'});
        assertThat(reader.readBytes()).containsExactly('a', 'b');
    }

    @Test
    void readU32AsIntThrowsOnHighBit() {
        // 0x80000001 wraps negative under a bare (int) cast; the helper must reject it instead.
        CanonicalReader reader = new CanonicalReader(u32(0x80000001));
        assertThatThrownBy(reader::readU32AsInt)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds Integer.MAX_VALUE");
    }

    @Test
    void readU32AsIntAcceptsIntegerMaxValue() {
        CanonicalReader reader = new CanonicalReader(u32(0x7FFFFFFF));
        assertThat(reader.readU32AsInt()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void readBytesRejectsLengthOneByteTooLarge() {
        // length = 3 but only 2 payload bytes follow.
        CanonicalReader reader = new CanonicalReader(new byte[] {0, 0, 0, 3, 'a', 'b'});
        assertThatThrownBy(reader::readBytes)
                .isInstanceOf(IllegalStateException.class);
    }
}
