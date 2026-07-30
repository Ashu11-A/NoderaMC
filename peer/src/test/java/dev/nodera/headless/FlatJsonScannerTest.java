package dev.nodera.headless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The config payload arrives from the control endpoint, and it used to be parsed with the natural
 * JSON-string regex {@code "((?:[^"\\]|\\.)*)"}. Both alternatives match an ordinary character, so
 * an unterminated run costs O(n²) to fail — a denial of service reachable by anything that can open
 * a socket to the loopback port.
 *
 * <p>These assert the replacement scanner parses the same things and cannot be made to hang.
 */
final class FlatJsonScannerTest {

    @Test
    @DisplayName("the shapes the config payload is defined to carry still parse")
    void theDefinedShapesParse() {
        Map<String, String> parsed = WorkerControlHandler.parseFlatJson(
                "{\"a.string\": \"hello\", \"a.number\": 42, \"a.bool\": true,"
                        + " \"a.list\": [\"x\", \"y\"], \"a.escaped\": \"say \\\"hi\\\"\"}");

        assertThat(parsed).containsEntry("a.string", "\"hello\"")
                .containsEntry("a.number", "42")
                .containsEntry("a.bool", "true")
                .containsEntry("a.list", "[\"x\", \"y\"]");
        assertThat(parsed.get("a.escaped")).as("an escaped quote does not end the value")
                .isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(parsed).as("the escaped quote in a KEY is resolved")
                .containsKey("a.escaped");
    }

    @Test
    @DisplayName("a key whose name contains an escaped quote survives the round trip")
    void escapedKeysAreUnescaped() {
        assertThat(WorkerControlHandler.parseFlatJson("{\"od\\\"d\": 1}"))
                .containsEntry("od\"d", "1");
    }

    @Test
    @DisplayName("a malformed payload terminates instead of degrading")
    void malformedInputTerminates() {
        // The old regex needed quadratic time to FAIL on this. Twenty thousand characters is far
        // below any real payload and was already seconds; the scanner is linear, so the bound here
        // is generous and still cannot be met by a backtracking matcher.
        String hostile = "{\"" + "a".repeat(20_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThat(WorkerControlHandler.parseFlatJson(hostile)).isEmpty());

        String unterminatedValue = "{\"k\": \"" + "b".repeat(20_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> WorkerControlHandler.parseFlatJson(unterminatedValue));
    }

    @Test
    @DisplayName("null and empty are answered, not thrown at")
    void nullAndEmptyAreSafe() {
        assertThat(WorkerControlHandler.parseFlatJson(null)).isEmpty();
        assertThat(WorkerControlHandler.parseFlatJson("")).isEmpty();
        assertThat(WorkerControlHandler.parseFlatJson("{}")).isEmpty();
    }
}
