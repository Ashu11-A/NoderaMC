package dev.nodera.peer.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The commons namespace, pinned.
 *
 * <p>These bytes are a contract with a different language. `nodera_app::peer::tracker::COMMONS_WORLD`
 * declares the same 32 bytes in Rust, and `nodera-query --commons` a third copy. If any of them
 * drifts, every implementation keeps working perfectly and announces into a namespace the others do
 * not read — two swarms, both healthy, mutually invisible, with nothing failing anywhere.
 *
 * <p>That is precisely the failure this class was written to end, so the constant gets a test rather
 * than a comment.
 */
class CommonsPresenceTest {

    /** The Rust constant, transcribed: `*b"nodera:mobile-commons:v1\0\0\0\0\0\0\0\0"`. */
    private static byte[] rustCommonsWorld() {
        byte[] expected = new byte[32];
        byte[] label = "nodera:mobile-commons:v1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(label, 0, expected, 0, label.length);
        return expected;
    }

    @Test
    @DisplayName("the world id is byte-identical to the Rust companion's")
    void matchesTheRustConstant() {
        assertArrayEquals(rustCommonsWorld(), CommonsPresence.worldIdBytes());
    }

    @Test
    @DisplayName("it is exactly 32 bytes, zero-padded after the label")
    void isThirtyTwoBytes() {
        byte[] id = CommonsPresence.worldIdBytes();

        assertEquals(32, id.length);
        assertEquals(24, "nodera:mobile-commons:v1".length());
        for (int i = 24; i < 32; i++) {
            assertEquals(0, id[i], "byte " + i + " must be padding");
        }
    }

    @Test
    @DisplayName("the label is versioned, so a future format can coexist rather than collide")
    void theLabelCarriesItsVersion() {
        String label = new String(CommonsPresence.worldIdBytes(), 0, 24, StandardCharsets.US_ASCII);

        assertEquals("nodera:mobile-commons:v1", label);
    }
}
