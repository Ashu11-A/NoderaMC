package dev.nodera.testkit.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
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
 *   <li><b>Blaming the lane for the machine.</b> {@link #awaitWithLagGuard} reads the host server's
 *       own {@code Can't keep up!} line, so a wait that expired because the box could not sustain
 *       the topology says so instead of naming the feature it was waiting for.</li>
 * </ol>
 *
 * <h2>Reading a window, not only waiting for a needle</h2>
 *
 * <p>Some assertions are about the ABSENCE of a pattern in a window — the re-key scenario's
 * plaintext watch and the determinism soak's post-drive error audit — and an absence is not
 * something a wait can prove. {@link #linesAfter}, {@link #lastMatchAfter}, {@link #matchesAfter}
 * and {@link #auditErrorsAfter} are that half. They used to live in two scenario support classes,
 * which is how one behaviour came to be described in three places.
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

    /**
     * Benign lines an abrupt client kill always produces.
     *
     * <p>Anything else at {@code ERROR}/{@code FATAL} fails an audit. Mirrors
     * {@code NODERA_BENIGN_ERRORS} in {@code scripts/lib/e2e-main.sh}.
     */
    public static final Pattern BENIGN_ERRORS = Pattern.compile(
            "Lost connection|Disconnected|Connection reset|closed by remote|InterruptedException");

    /**
     * Connection causes this harness caused itself, for the netty header's follow-up line.
     *
     * <p>Vanilla's "Exception caught in connection" header is benign exactly when its cause is the
     * reset or close of a peer the harness killed; any other cause still counts, so the audit reads
     * the following line rather than the header.
     */
    public static final Pattern BENIGN_NETTY = Pattern.compile(
            "Connection reset|ClosedChannelException|closed by remote");

    /**
     * Vanilla's overload line — {@code Can't keep up! Is the server overloaded? Running 53012ms or
     * 1066 ticks behind}. The capture is the tick count, which is the number worth reporting.
     */
    public static final Pattern SERVER_LAG =
            Pattern.compile("Can't keep up!.{0,200}?(\\d{1,10}) ticks behind");

    /**
     * How far behind the host may fall before a wait's expiry is blamed on the machine.
     *
     * <p>Not zero. Chunk generation and a world save both produce a short overload on any box, and a
     * guard that fired on those would convert every real lane bug into "blame the machine" — which
     * is the failure mode opposite to the one it exists to fix, and the more expensive of the two.
     */
    public static final int LAG_TICKS_THRESHOLD = 200;

    private static final Duration POLL = Duration.ofSeconds(2);

    private final Path file;
    private final Topology topology;

    public LogWatcher(Path file, Topology topology) {
        this.file = file;
        this.topology = topology;
    }

    /**
     * A watcher for READING a file the stack did not start — a game directory's {@code latest.log},
     * a staged config, a foreign server's output.
     *
     * <p>It carries the standard topology, so a wait taken on it is scaled by
     * {@code NODERA_E2E_TIMEOUT_MULT} exactly like every other wait in the run. Prefer
     * {@code stack.watch(path)} where a stack is in hand; this exists for the static helpers that
     * are handed a bare path.
     */
    public static LogWatcher reader(Path file) {
        return new LogWatcher(file, Topology.standard());
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
        awaitGuarded(needle, timeout, guard, because, null);
    }

    /**
     * {@link #awaitGuarded} whose EXPIRY is also explained.
     *
     * <p>The guard answers "this can never arrive"; {@code hostLog} answers the other question a
     * timeout raises — "was the machine the reason". Both together are what turn a wait into a
     * message a reader can act on without opening four files.
     *
     * @param hostLog the host server's own log, or {@code null} to keep the plain expiry message.
     */
    public void awaitGuarded(String needle, Duration timeout, String guard, String because,
                             Path hostLog) {
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
        throw expired(needle, timeout, hostLog, 0);
    }

    /**
     * The failure a wait produces when it runs out: the machine's fault where the host says so, and
     * the plain "it never appeared" otherwise.
     */
    private HarnessException expired(String needle, Duration timeout, Path hostLog, int mark) {
        OptionalInt behind = hostLog == null ? OptionalInt.empty() : reader(hostLog).ticksBehind();
        if (behind.isPresent() && behind.getAsInt() > LAG_TICKS_THRESHOLD) {
            return new HarnessException("the host server fell " + behind.getAsInt()
                    + " ticks behind (see " + hostLog + ") — the machine, not the lane, is why '"
                    + needle + "' never appeared in " + file + " within "
                    + topology.scaled(timeout).toSeconds() + "s");
        }
        return new HarnessException("waited " + topology.scaled(timeout).toSeconds() + "s for '"
                + needle + "' in " + file + (mark > 0 ? " (after line " + mark + ")" : "")
                + " — it never appeared");
    }

    /**
     * Wait for {@code needle} — and if the wait expires, say whether the MACHINE is why.
     *
     * <p>The failure this exists for: an integrated server hosting two real Minecraft clients, three
     * workers and two services on a box that cannot sustain that topology falls tens of seconds
     * behind, vanilla logs {@code Can't keep up! … 1066 ticks behind}, the joiner's <i>vanilla</i>
     * connection times out, and the wait for the lane's evidence then expires. The message that came
     * out named the validation lane and never the machine, so three consecutive capacity failures
     * read as one product defect. No harness code anywhere grepped for that line.
     *
     * <p>The guard is deliberately one-directional. It never turns a healthy run red, and it never
     * turns a red run green: the wait fails either way, and all that changes is which layer the
     * message sends the reader to. Below {@link #LAG_TICKS_THRESHOLD} the ordinary message stands,
     * because a brief overload during chunk generation is not an explanation for anything.
     *
     * @param needle  the evidence being waited for.
     * @param timeout the base timeout; the topology's multiplier is applied.
     * @param hostLog the host server's own log — the file that carries the overload line. May be
     *                {@code null} or absent, in which case this behaves exactly like
     *                {@link #await}.
     * @throws HarnessException naming the lag when the host was behind, and naming the needle
     *                          otherwise.
     */
    public void awaitWithLagGuard(String needle, Duration timeout, Path hostLog) {
        awaitWithLagGuard(needle, timeout, hostLog, 0);
    }

    /** {@link #awaitWithLagGuard(String, Duration, Path)} counting only lines after {@code mark}. */
    public void awaitWithLagGuard(String needle, Duration timeout, Path hostLog, int mark) {
        if (pollFor(needle, timeout, mark)) {
            return;
        }
        throw expired(needle, timeout, hostLog, mark);
    }

    /**
     * The worst {@code Can't keep up!} this file reports, in ticks.
     *
     * @return empty when the server never said it was behind.
     */
    public OptionalInt ticksBehind() {
        int worst = -1;
        for (String line : readLines()) {
            Matcher matcher = SERVER_LAG.matcher(line);
            if (matcher.find()) {
                try {
                    worst = Math.max(worst, Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException tooLarge) {
                    // A tick count that does not fit an int is still an overload; treat it as the
                    // largest one rather than dropping the only evidence there is.
                    worst = Integer.MAX_VALUE;
                }
            }
        }
        return worst < 0 ? OptionalInt.empty() : OptionalInt.of(worst);
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
        return auditErrorsAfter(benignErrors, benignNetty, 0);
    }

    /**
     * The non-benign {@code ERROR}/{@code FATAL} lines after {@code mark}, capped at six.
     *
     * <p>Every scenario that kills something audits only what happened afterwards: a soak audits
     * what IT produced, not what the bring-up before it did.
     *
     * @param benignErrors a regex of error text that is expected in a healthy run.
     * @param benignNetty  a regex of connection causes this harness caused itself.
     * @param mark         a line count taken with {@link #lineCount()}.
     * @return the offending lines; empty means clean.
     */
    public List<String> auditErrorsAfter(Pattern benignErrors, Pattern benignNetty, int mark) {
        List<String> lines = linesAfter(mark);
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

    // ---------------------------------------------------------------------------------------
    // Windows: the reads a "wait for a needle" cannot express
    // ---------------------------------------------------------------------------------------

    /** Every line of the file, or an empty list when it cannot be read. */
    public List<String> lines() {
        return readLines();
    }

    /** The lines at or after a mark taken with {@link #lineCount()}. */
    public List<String> linesAfter(int mark) {
        List<String> lines = readLines();
        return lines.subList(Math.min(Math.max(0, mark), lines.size()), lines.size());
    }

    /** @return {@code true} if {@code needle} appears at or after line {@code mark}. */
    public boolean containsAfter(String needle, int mark) {
        return linesAfter(mark).stream().anyMatch(line -> line.contains(needle));
    }

    /** {@link #containsAfter} ignoring case — the shell's {@code grep -i} assertions. */
    public boolean containsAfterIgnoringCase(String needle, int mark) {
        String lowered = needle.toLowerCase(Locale.ROOT);
        return linesAfter(mark).stream()
                .anyMatch(line -> line.toLowerCase(Locale.ROOT).contains(lowered));
    }

    /** The last line at or after {@code mark} containing {@code needle}. */
    public Optional<String> lastMatchAfter(String needle, int mark) {
        List<String> window = linesAfter(mark);
        for (int i = window.size() - 1; i >= 0; i--) {
            if (window.get(i).contains(needle)) {
                return Optional.of(window.get(i));
            }
        }
        return Optional.empty();
    }

    /** Every line at or after {@code mark} containing {@code needle}, in file order. */
    public List<String> matchesAfter(String needle, int mark) {
        return linesAfter(mark).stream().filter(line -> line.contains(needle)).toList();
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
