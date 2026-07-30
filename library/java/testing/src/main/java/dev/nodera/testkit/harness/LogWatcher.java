package dev.nodera.testkit.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Waiting for evidence in a log file — the single assertion primitive of every live scenario.
 *
 * <h2>Why the waits are shaped like this</h2>
 *
 * <p>Three failure modes cost this project real debugging time, and each one is a method here:
 *
 * <ol>
 *   <li><b>A needle that was already there.</b> "Wait for the host to share the world" matches the
 *       previous run's line and the stage passes instantly, proving nothing. {@link #awaitAfter}
 *       takes a mark from {@link #lineCount()} and only considers what arrives after it.</li>
 *   <li><b>Burning the whole timeout on a known-fatal state.</b> If the log already says the entity
 *       lane failed to boot, waiting five more minutes for ownership to appear is five minutes
 *       spent to produce a worse message. {@link #awaitGuarded} fails the moment the guard shows
 *       up, with the guard's own explanation.</li>
 *   <li><b>A client parked on a screen quick-play never reaches.</b> {@link #awaitJoin} watches the
 *       client's own log for the blocked screens while waiting for the join evidence, turning a
 *       thirty-minute "never joined" into a one-line cause.</li>
 * </ol>
 *
 * <p>Thread-context: one watcher per file; the polling methods block the calling thread.
 */
public final class LogWatcher {

    /**
     * Screens a quick-play client must never be sitting on.
     *
     * <p>Quick play connects with no GUI interaction at all, so any of these means it never ran and
     * the game is waiting for a human who will not arrive. Deliberately absent: the disconnected
     * and Nodera multiplayer screens — a client legitimately passes through both when the host it
     * was playing on dies, which is the whole point of the continuity and crash scenarios.
     */
    public static final Pattern BLOCKED_SCREENS =
            Pattern.compile("screen: (AccessibilityOnboardingScreen|TitleScreen|BanNotice)[A-Za-z]*");

    private static final Duration POLL = Duration.ofSeconds(2);

    private final Path file;
    private final Topology topology;

    public LogWatcher(Path file, Topology topology) {
        this.file = file;
        this.topology = topology;
    }

    /** The file being watched. */
    public Path file() {
        return file;
    }

    /** Lines currently in the file — the mark {@link #awaitAfter} counts from. */
    public int lineCount() {
        return readLines().size();
    }

    /** @return {@code true} if the needle is present anywhere in the file right now. */
    public boolean contains(String needle) {
        return readLines().stream().anyMatch(line -> line.contains(needle));
    }

    /** How many lines contain the needle. */
    public int count(String needle) {
        return (int) readLines().stream().filter(line -> line.contains(needle)).count();
    }

    /** The last line containing the needle, if any. */
    public Optional<String> lastMatch(String needle) {
        List<String> lines = readLines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains(needle)) {
                return Optional.of(lines.get(i));
            }
        }
        return Optional.empty();
    }

    /** The first capture group of the last line matching {@code pattern}, if any. */
    public Optional<String> lastCapture(Pattern pattern) {
        List<String> lines = readLines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.find()) {
                return Optional.of(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
            }
        }
        return Optional.empty();
    }

    /**
     * Wait for {@code needle} to appear anywhere in the file.
     *
     * @param needle  the literal text (never a regex — an assertion nobody can read is an assertion
     *                nobody maintains).
     * @param timeout the base timeout; the topology's multiplier is applied.
     * @throws HarnessException if it never appears, naming the file to read.
     */
    public void await(String needle, Duration timeout) {
        awaitAfter(needle, timeout, 0);
    }

    /**
     * Wait for a NEW occurrence of {@code needle}, strictly after line {@code mark}.
     *
     * @param mark a line count taken with {@link #lineCount()} before the action under test.
     */
    public void awaitAfter(String needle, Duration timeout, int mark) {
        if (!pollFor(needle, timeout, mark)) {
            throw new HarnessException("waited " + topology.scaled(timeout).toSeconds() + "s for '"
                    + needle + "' in " + file + (mark > 0 ? " (after line " + mark + ")" : "")
                    + " — it never appeared");
        }
    }

    /**
     * Wait for {@code needle}, but fail immediately if {@code guard} shows up first.
     *
     * @param guard   text whose presence means the needle can never arrive.
     * @param because what to tell the reader when the guard fires.
     */
    public void awaitGuarded(String needle, Duration timeout, String guard, String because) {
        long deadline = System.nanoTime() + topology.scaled(timeout).toNanos();
        while (System.nanoTime() < deadline) {
            List<String> lines = readLines();
            if (lines.stream().anyMatch(line -> line.contains(needle))) {
                return;
            }
            if (lines.stream().anyMatch(line -> line.contains(guard))) {
                throw new HarnessException(because + " (guard '" + guard + "' in " + file + ")");
            }
            Topology.sleep(POLL);
        }
        throw new HarnessException("waited " + topology.scaled(timeout).toSeconds() + "s for '"
                + needle + "' in " + file + " — it never appeared");
    }

    /**
     * Wait for join evidence in this file while watching a client's log for a blocked screen.
     *
     * @param needle     the join evidence (a server or host log line).
     * @param clientLog  the joining client's own log.
     * @param what       what was being attempted, for the failure message.
     */
    public void awaitJoin(String needle, Duration timeout, Path clientLog, String what) {
        long deadline = System.nanoTime() + topology.scaled(timeout).toNanos();
        LogWatcher client = new LogWatcher(clientLog, topology);
        while (System.nanoTime() < deadline) {
            if (contains(needle)) {
                return;
            }
            Optional<String> screen = client.lastCapture(BLOCKED_SCREENS);
            if (screen.isPresent()) {
                throw new HarnessException(what + " — the client is parked on a screen quick play "
                        + "never reaches (" + screen.get() + "); see " + clientLog);
            }
            Topology.sleep(POLL);
        }
        throw new HarnessException(what + " — waited "
                + topology.scaled(timeout).toSeconds() + "s for '" + needle + "' in " + file);
    }

    /** Non-throwing variant: {@code true} if the needle turned up in time. */
    public boolean pollFor(String needle, Duration timeout, int fromLine) {
        long deadline = System.nanoTime() + topology.scaled(timeout).toNanos();
        while (System.nanoTime() < deadline) {
            List<String> lines = readLines();
            for (int i = Math.max(0, fromLine); i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) {
                    return true;
                }
            }
            Topology.sleep(POLL);
        }
        return false;
    }

    /**
     * The non-benign {@code ERROR}/{@code FATAL} lines in this file, capped at six.
     *
     * <p>Netty's "Exception caught in connection" header is benign exactly when its cause is the
     * reset or close of a peer the harness itself killed — which every crash and continuity
     * scenario does deliberately. Any other cause still counts, so the audit reads the following
     * line rather than the header.
     *
     * @param benignErrors  a regex of error text that is expected in a healthy run.
     * @param benignNetty   a regex of connection causes this harness caused itself.
     * @return the offending lines; empty means clean.
     */
    public List<String> auditErrors(Pattern benignErrors, Pattern benignNetty) {
        List<String> lines = readLines();
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
                    && !benignErrors.matcher(line).find()) {
                offenders.add(line);
            }
        }
        return offenders;
    }

    private List<String> readLines() {
        if (!Files.isReadable(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException partiallyWritten) {
            // A log being appended to as we read it can hand back a malformed tail. Treat it as
            // "not yet" rather than as a harness failure: the next poll gets a complete file.
            return List.of();
        }
    }
}
