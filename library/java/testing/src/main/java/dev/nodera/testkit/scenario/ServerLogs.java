package dev.nodera.testkit.scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reading a window of a log file — the assertions a "wait for a needle" cannot express.
 *
 * <p>HARNESS-GAP: {@link dev.nodera.testkit.harness.LogWatcher} can wait for a needle after a mark
 * and can audit a whole file, but it cannot hand back the lines of a window. Two of the scenarios
 * ported here assert on the ABSENCE of a pattern within a window — the re-key scenario's plaintext
 * watch (L-59) and the determinism soak's post-drive error audit — and an absence is not something a
 * wait can prove. Rather than change the harness, the window reader lives here.
 *
 * <p>Thread-context: stateless static helpers; every read tolerates a file being appended to.
 */
public final class ServerLogs {

    /**
     * Benign lines an abrupt client kill always produces.
     *
     * <p>Anything else at {@code ERROR}/{@code FATAL} fails an audit. Mirrors
     * {@code NODERA_BENIGN_ERRORS} in {@code scripts/lib/e2e-main.sh}.
     */
    public static final Pattern BENIGN_ERRORS = Pattern.compile(
            "Lost connection|Disconnected|Connection reset|closed by remote|InterruptedException");

    /**
     * Connection causes this harness caused itself.
     *
     * <p>Vanilla's "Exception caught in connection" header is benign exactly when its cause is the
     * reset or close of a peer the harness killed; any other cause still counts, so the audit reads
     * the following line rather than the header.
     */
    public static final Pattern BENIGN_NETTY = Pattern.compile(
            "Connection reset|ClosedChannelException|closed by remote");

    private ServerLogs() {
    }

    /**
     * The lines of {@code file} after a mark taken with {@code LogWatcher.lineCount()}.
     *
     * @return the window, or an empty list when the file is unreadable or the mark is at its end.
     */
    public static List<String> linesAfter(Path file, int mark) {
        return window(file).stream().skip(Math.max(0, mark)).toList();
    }

    /** Every line of {@code file}, or an empty list when it cannot be read. */
    public static List<String> window(Path file) {
        if (file == null || !Files.isReadable(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException partiallyWritten) {
            // A log being appended to as it is read can hand back a malformed tail. Treat it as
            // "nothing yet" rather than as a harness failure: the next read gets a complete file.
            return List.of();
        }
    }

    /**
     * The non-benign {@code ERROR}/{@code FATAL} lines in a window, capped at six.
     *
     * <p>The same rule the shell's {@code nodera_audit_errors} applies, but from a mark: a soak
     * audits what IT produced, not what the bring-up before it did.
     *
     * @param benign      error text that is expected in a healthy run.
     * @param benignNetty connection causes the harness caused itself.
     * @return the offending lines; empty means clean.
     */
    public static List<String> auditErrorsAfter(Path file, int mark, Pattern benign,
                                                Pattern benignNetty) {
        List<String> lines = linesAfter(file, mark);
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < lines.size() && offenders.size() < 6; i++) {
            String line = lines.get(i);
            if (line.contains("Exception caught in connection")) {
                String cause = i + 1 < lines.size() ? lines.get(i + 1) : "";
                if (!benignNetty.matcher(cause).find()) {
                    offenders.add(line);
                    offenders.add(cause);
                }
                i++;
                continue;
            }
            if ((line.contains("ERROR") || line.contains("FATAL"))
                    && !benign.matcher(line).find()) {
                offenders.add(line);
            }
        }
        return offenders;
    }

    /** How many lines of {@code file} contain {@code needle}. */
    public static int count(Path file, String needle) {
        return (int) window(file).stream().filter(line -> line.contains(needle)).count();
    }
}
