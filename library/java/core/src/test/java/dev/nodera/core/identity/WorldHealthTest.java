package dev.nodera.core.identity;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WorldHealth} is a frozen-ordinal wire enum shared by {@code protocol} and the
 * diagnostics view model, so its encode/decode and ordinals are pinned here.
 *
 * <p>Thread-context: single test thread.
 */
final class WorldHealthTest {

    @Test
    void ordinalsAreFrozen() {
        // GREEN/RED/GRAY mapping in the UI depends on these exact ordinals; never reorder.
        assertThat(WorldHealth.HEALTHY.ordinal()).isZero();
        assertThat(WorldHealth.DEGRADED.ordinal()).isEqualTo(1);
        assertThat(WorldHealth.DEAD.ordinal()).isEqualTo(2);
    }

    @Test
    void roundTripsThroughCanonicalEncoding() {
        for (WorldHealth h : WorldHealth.values()) {
            CanonicalWriter w = new CanonicalWriter();
            h.encode(w);
            byte[] frame = w.toByteArray();

            // The frame is self-describing: tag + version + ordinal.
            CanonicalReader peek = new CanonicalReader(frame);
            assertThat(peek.readU16()).isEqualTo(TypeTags.WORLD_HEALTH);
            assertThat(peek.readU16()).isEqualTo(dev.nodera.core.crypto.Encodable.ENCODING_VERSION);

            assertThat(WorldHealth.decode(new CanonicalReader(frame))).isEqualTo(h);
        }
    }

    @Test
    void rejectsAnUnknownOrdinalOnDecode() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(TypeTags.WORLD_HEALTH).writeU16(dev.nodera.core.crypto.Encodable.ENCODING_VERSION);
        w.writeU8(99);
        assertThatThrownBy(() -> WorldHealth.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid WorldHealth ordinal");
    }

    @Test
    void rejectsAWrongTag() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(TypeTags.NODE_ID).writeU16(dev.nodera.core.crypto.Encodable.ENCODING_VERSION);
        assertThatThrownBy(() -> WorldHealth.decode(new CanonicalReader(w.toByteArray())))
                .isInstanceOf(IllegalStateException.class);
    }
}
