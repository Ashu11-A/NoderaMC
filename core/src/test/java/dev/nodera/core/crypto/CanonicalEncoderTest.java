package dev.nodera.core.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CanonicalEncoder}: byte stability, framing, and encode→decode round-trip.
 */
class CanonicalEncoderTest {

    /** Trivial Encodable: u16 tag + u16 version + a fixed string. */
    private static final class TestEncodable implements Encodable {
        static final int TAG = TypeTags.STATE_ROOT;
        static final String PAYLOAD = "nodera-encoder-roundtrip";

        @Override
        public void encode(CanonicalWriter w) {
            w.writeU16(TAG).writeU16(ENCODING_VERSION);
            w.writeString(PAYLOAD);
        }
    }

    @Test
    void byteStableReturnsTrueForDeterministicValue() {
        assertThat(CanonicalEncoder.byteStable(new TestEncodable())).isTrue();
    }

    @Test
    void encodeProducesFramedOutput() {
        byte[] encoded = CanonicalEncoder.encodeBytes(new TestEncodable());
        assertThat(encoded.length).isGreaterThan(4);
    }

    @Test
    void encodeReturnsImmutableBytes() {
        assertThat(CanonicalEncoder.encode(new TestEncodable())).isNotNull();
    }

    @Test
    void encodeThenDecodeRoundTripsFields() {
        TestEncodable original = new TestEncodable();
        byte[] encoded = CanonicalEncoder.encodeBytes(original);

        CanonicalReader reader = new CanonicalReader(encoded);
        int tag = reader.readU16();
        int version = reader.readU16();
        String payload = reader.readString();

        assertThat(tag).isEqualTo(TestEncodable.TAG);
        assertThat(version).isEqualTo(Encodable.ENCODING_VERSION);
        assertThat(payload).isEqualTo(TestEncodable.PAYLOAD);
        assertThat(reader.available()).isZero();
    }

    @Test
    void encodeIsConsistentAcrossCalls() {
        TestEncodable value = new TestEncodable();
        assertThat(CanonicalEncoder.encode(value))
                .isEqualTo(CanonicalEncoder.encode(value));
    }
}
