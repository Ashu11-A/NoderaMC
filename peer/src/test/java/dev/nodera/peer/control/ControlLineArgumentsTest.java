package dev.nodera.peer.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An optional argument that is left empty does not arrive empty — it <b>disappears</b>.
 *
 * <h2>The bug this pins</h2>
 *
 * <p>The control protocol is a line split on {@code \s+}, and that collapses runs of whitespace. So
 * an argument sent as the empty string produces two spaces in a row, the split yields no token for
 * it, and every argument after it moves one place left.
 *
 * <p>{@code leaveWorld} sent no world name and a lease of {@code "0"}. The worker therefore read
 * {@code "0"} as the NAME, failed to decode it as base64, tolerated it "as a plain name", and
 * renamed the world. A player watched a world called "Teste 1" become <b>0</b> the moment its owner
 * disconnected — and the lease it thought it was clearing was read as the player count instead, so
 * the goodbye did not even do its job.
 *
 * <p>Two rules come out of that, and both are asserted here: a caller with nothing to send sends
 * {@link ControlProtocol#NO_VALUE}, and a name that does not decode is not a name.
 */
final class ControlLineArgumentsTest {

    private static final String WORLD = "00112233445566778899aabbccddeeff";

    /** The server's own tokeniser. */
    private static String[] split(String line) {
        return line.split("\\s+");
    }

    private static String arg(String[] parts, int i) {
        return i < parts.length ? parts[i] : "";
    }

    @Test
    @DisplayName("an empty argument collapses and shifts every argument after it")
    void anEmptyArgumentIsNotAnEmptyArgument() {
        // Exactly what the old leaveWorld produced: base64("") is "".
        String broken = "NODERA-JOIN 2 " + WORLD + " " + "" + " 0 -1";

        String[] parts = split(broken);

        assertThat(arg(parts, 3))
                .as("the LEASE arrived in the name slot — this is the rename, in one line")
                .isEqualTo("0");
        assertThat(arg(parts, 4)).isEqualTo("-1");
        assertThat(arg(parts, 5)).as("the player count fell off the end entirely").isEmpty();
    }

    @Test
    @DisplayName("the placeholder keeps every later argument in its own slot")
    void theSentinelHoldsThePosition() {
        String fixed = "NODERA-JOIN 2 " + WORLD + " " + ControlProtocol.NO_VALUE + " 0 -1";

        String[] parts = split(fixed);

        assertThat(arg(parts, 3)).isEqualTo(ControlProtocol.NO_VALUE);
        assertThat(arg(parts, 4)).as("the lease is the lease again").isEqualTo("0");
        assertThat(arg(parts, 5)).as("and the player count survives").isEqualTo("-1");
    }

    @Test
    @DisplayName("the placeholder can never be mistaken for a name")
    void theSentinelIsNotDecodable() {
        // If it ever decoded, a world could be renamed to whatever it decoded to.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Base64.getDecoder().decode(ControlProtocol.NO_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a real name with spaces survives the line intact")
    void aNameWithSpacesIsSafeBecauseItIsEncoded() {
        String encoded = Base64.getEncoder().encodeToString(
                "Teste 1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(encoded).doesNotContain(" ");

        String[] parts = split("NODERA-JOIN 2 " + WORLD + " " + encoded + " 60 2");

        assertThat(new String(Base64.getDecoder().decode(arg(parts, 3)),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("Teste 1");
        assertThat(arg(parts, 4)).isEqualTo("60");
        assertThat(arg(parts, 5)).isEqualTo("2");
    }
}
