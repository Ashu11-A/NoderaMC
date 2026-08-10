package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.ControlClient;
import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * THE CONSENT LANE, END TO END, HEADLESS.
 *
 * <pre>
 *   T0  start the collector
 *   T1  a fresh worker collects NOTHING — nobody has answered the question
 *   T2  the registry the peer can emit is a subset of what the collector accepts (the
 *       cross-language mirror, run against the real binaries)
 *   T3  consent is granted over the control socket; a batch arrives; the row on disk carries a
 *       country and a rotating subject, and carries NEITHER the source address NOR the install id
 *   T4  an undeclared event is refused by the collector, with a reason
 *   T5  consent is revoked: the queue is cleared and the install id forgotten
 *   T6  THE OUTAGE LANE — with the collector killed, the worker keeps serving every other control
 *       verb with byte-identical answers (Plan.6 D10)
 * </pre>
 *
 * <p>No GUI, no Minecraft: this scenario is about the measurement plane, and it runs in CI
 * unchanged. The live gameplay half rides the other scenarios.
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class TelemetryScenario implements Scenario {

    private static final int INGEST_PORT = 25630;
    private static final int CONTROL_PORT = 25640;
    private static final int P2P_PORT = 25641;

    /** A 64-bit rotating subject, as it appears on disk. */
    private static final Pattern SUBJECT = Pattern.compile("\"subject\":\"[0-9a-f]{16}\"");

    /** Any address or identity field. Finding one means the privacy contract is broken. */
    private static final Pattern ADDRESS_FIELD = Pattern.compile("\"(ip|address|route|node_id)\"");

    /** An undeclared event: the collector must refuse it and say why. */
    private static final String UNDECLARED = "{\"v\":1,\"src\":\"peer\",\"consent\":\"granted\","
            + "\"install\":\"0123456789abcdef0123456789abcdef\",\"agent\":\"e2e\","
            + "\"events\":[{\"name\":\"world.name\",\"t\":0,"
            + "\"attrs\":{\"value\":\"My Secret Base\"}}]}";

    /** A batch with no consent: it must write nothing and say so. */
    private static final String NO_CONSENT = "{\"v\":1,\"src\":\"peer\","
            + "\"install\":\"0123456789abcdef0123456789abcdef\","
            + "\"events\":[{\"name\":\"session.end\",\"t\":0,\"attrs\":{}}]}";

    @Override
    public String id() {
        return "telemetry";
    }

    @Override
    public String title() {
        return "nothing is collected until consent is granted, and nothing identifying survives it";
    }

    @Override
    public Set<String> tags() {
        return Set.of("headless", "telemetry");
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        LiveStack stack = ctx.stack();
        Path ingestBinary = stack.paths().rustRelease().resolve("nodera-telemetry");
        Path workerBinary = stack.paths().workerDist();
        Path spool = stack.logDir().resolve("spool");
        Path workerState = stack.logDir().resolve("worker-state");
        Path results = stack.resultsDir();
        ControlClient worker = new ControlClient(CONTROL_PORT);

        List<ManagedProcess> mine = new ArrayList<>();
        ManagedProcess[] collector = new ManagedProcess[1];
        try {
            // --- T0: start the collector --------------------------------------------------------
            ctx.stage("T0", "the collector is up on 127.0.0.1:" + INGEST_PORT, () -> {
                ctx.check(Files.isExecutable(ingestBinary), "T0: no collector binary at "
                        + ingestBinary + " (cargo build --release -p nodera-telemetry)");
                ctx.check(Files.isExecutable(workerBinary), "T0: no worker launcher at "
                        + workerBinary + " (./gradlew :worker:installDist)");

                // The spool and the worker's state are wiped on every run, and that is load-bearing
                // rather than tidiness: T1 asserts that a worker nobody asked writes NOTHING, and a
                // row left behind by the previous run would fail it for the wrong reason. (It did,
                // the first time this suite was re-run.)
                ServerEndpointSupport.deleteRecursively(spool);
                ServerEndpointSupport.deleteRecursively(workerState);
                Files.createDirectories(spool);
                Files.createDirectories(workerState);

                Path config = stack.logDir().resolve("nodera-telemetry.toml");
                Files.writeString(config, """
                        bind_addr = "127.0.0.1:%d"
                        spool_dir = "%s"
                        spool_max_seconds = 5
                        subject_rotation_days = 1
                        max_event_age_seconds = 604800
                        report_interval_seconds = 5
                        """.formatted(INGEST_PORT, spool));

                // The ingest mints its pseudonymisation key in memory
                // (docs/telemetry/LIMITATIONS.fixed.md L-72); there is no secret to pass it. It
                // boots straight from the config file above.
                collector[0] = ManagedProcess.start("telemetry-ingest", stack.paths().root(),
                        stack.logDir().resolve("ingest.log"), ManagedProcess.env(),
                        List.of(ingestBinary.toString(), "--config", config.toString()));
                mine.add(collector[0]);

                boolean healthy = false;
                for (int waited = 0; waited < 30 && !healthy; waited++) {
                    healthy = ServerDedicatedDrive.run(List.of(ingestBinary.toString(),
                            "--healthcheck", "127.0.0.1:" + INGEST_PORT)) == 0;
                    if (!healthy) {
                        ServerDedicatedDrive.sleep(Duration.ofSeconds(1));
                    }
                }
                ctx.check(healthy, "T0: the collector never became healthy (see "
                        + stack.logDir().resolve("ingest.log") + ")");
            });

            // --- T1: a fresh worker collects nothing --------------------------------------------
            ctx.stage("T1", "a worker nobody has asked reports 'unanswered' and writes no rows",
                    () -> {
                Map<String, String> env = ManagedProcess.env(
                        "NODERA_CONTROL_PORT", String.valueOf(CONTROL_PORT),
                        "NODERA_P2P_PORT", String.valueOf(P2P_PORT),
                        "NODERA_IDENTITY_FILE", workerState.resolve("worker-identity.bin").toString(),
                        "NODERA_ARCHIVE_DIR", workerState.resolve("archive").toString(),
                        "NODERA_TELEMETRY_DIR", workerState.toString(),
                        "NODERA_TELEMETRY_ENDPOINT", "tcp://127.0.0.1:" + INGEST_PORT,
                        "NODERA_TELEMETRY_INTERVAL_SECONDS", "10",
                        // Deliberately unreachable: this scenario is about the measurement plane,
                        // and a worker that found a real tracker would be doing other work too.
                        "NODERA_TRACKER_ENDPOINTS", "127.0.0.1:1",
                        "NODERA_RENDEZVOUS_ENDPOINTS", "127.0.0.1:1");
                mine.add(ManagedProcess.start("telemetry-worker", stack.paths().root(),
                        stack.logDir().resolve("worker.log"), env,
                        List.of(workerBinary.toString())));

                ctx.check(awaitPort(CONTROL_PORT, Duration.ofSeconds(90)),
                        "T1: the worker never opened its control endpoint (see "
                                + stack.logDir().resolve("worker.log") + ")");

                String status = worker.require("NODERA-TELEMETRY 2 GET");
                write(results.resolve("t1-status.json"), status);
                ctx.checkContains(status, "\"consent\":\"unanswered\"",
                        "T1: a fresh worker should report unanswered");

                // Two collection windows must produce nothing at all.
                ctx.settle(Duration.ofSeconds(12));
                ctx.check(listFiles(spool).isEmpty(),
                        "T1: a worker nobody asked wrote rows: " + listFiles(spool));
            });

            // --- T2: the cross-language registry mirror ------------------------------------------
            ctx.stage("T2", "the peer's registry is a subset of what the collector accepts", () -> {
                String schema = capture(List.of(ingestBinary.toString(), "--print-schema"),
                        stack.paths().root());
                write(results.resolve("schema.json"), schema);
                ctx.check(!schema.isBlank(), "T2: --print-schema failed");
                int exit = ServerDedicatedDrive.run(List.of(
                        stack.paths().root().resolve("gradlew").toString(), ":peer:test",
                        "--tests", "*TelemetryRegistryMirrorTest*", "-q"));
                ctx.check(exit == 0, "T2: the Java registry and "
                        + "rust/nodera-telemetry/src/schema.rs disagree");
            });

            // --- T3: consent, a batch, and what a stored row contains ----------------------------
            ctx.stage("T3", "a granted worker's rows carry a rotating subject and a country, and "
                    + "nothing identifying", () -> {
                String reply = worker.require("NODERA-TELEMETRY 2 SET granted");
                ctx.check("NODERA-OK".equals(reply.trim()),
                        "T3: the worker refused the consent decision: " + reply);

                boolean spooled = false;
                for (int waited = 0; waited < 60 && !spooled; waited++) {
                    spooled = listFiles(spool).stream().anyMatch(name -> name.endsWith(".ndjson"));
                    if (!spooled) {
                        ServerDedicatedDrive.sleep(Duration.ofSeconds(1));
                    }
                }
                ctx.check(spooled, "T3: no row ever reached the collector (see "
                        + stack.logDir().resolve("ingest.log") + ")");

                String rows = concatenateNdjson(spool);
                write(results.resolve("rows.ndjson"), rows);
                ctx.check(!rows.isBlank(), "T3: the spool file exists but is empty");
                ctx.note("T3: " + rows.lines().count() + " row(s) stored");

                // The privacy assertions, made against the bytes on disk rather than against any
                // in-memory model.
                ctx.check(SUBJECT.matcher(rows).find(),
                        "T3: no rotating subject in the stored rows");
                ctx.checkContains(rows, "\"country\":\"", "T3: rows carry no country field");
                ctx.check(!ADDRESS_FIELD.matcher(rows).find(),
                        "T3: a stored row contains an address/identity field — the privacy "
                                + "contract is broken");
                String installId = readIfPresent(workerState.resolve("telemetry-install-id"));
                ctx.check(installId.isBlank() || !rows.contains(installId.trim()),
                        "T3: the install id reached the warehouse — pseudonymisation did not "
                                + "happen");
            });

            // --- T4: an undeclared event is refused ------------------------------------------------
            ctx.stage("T4", "the collector refuses what the registry does not declare", () -> {
                String reply = ingestRequest(UNDECLARED);
                write(results.resolve("t4-reply.json"), reply);
                ctx.checkContains(reply, "\"unknown_event\"",
                        "T4: expected an unknown_event refusal");
                ctx.checkContains(ingestRequest(NO_CONSENT), "\"no_consent\"",
                        "T4: expected a no_consent refusal");
            });

            // --- T5: revocation --------------------------------------------------------------------
            ctx.stage("T5", "revoking clears the queue and forgets the installation identifier",
                    () -> {
                String reply = worker.require("NODERA-TELEMETRY 2 SET denied");
                ctx.check("NODERA-OK".equals(reply.trim()),
                        "T5: the worker refused the revocation: " + reply);

                String status = worker.require("NODERA-TELEMETRY 2 GET");
                write(results.resolve("t5-status.json"), status);
                ctx.checkContains(status, "\"consent\":\"denied\"",
                        "T5: consent did not become denied");
                ctx.checkContains(status, "\"queued\":0", "T5: the queue was not cleared");
                ctx.check(!Files.exists(workerState.resolve("telemetry-install-id")),
                        "T5: the install id survived revocation");
            });

            // --- T6: the outage lane ---------------------------------------------------------------
            ctx.stage("T6", "with the collector gone the node answers unchanged and reports the "
                    + "failure", () -> {
                String before = worker.require("NODERA-STATE 2");
                worker.ask("NODERA-TELEMETRY 2 SET granted");
                collector[0].stop(Duration.ofSeconds(10));
                ctx.settle(Duration.ofSeconds(12));   // at least one window with nowhere to send

                Optional<String> probe = worker.ask("NODERA-PROBE 2");
                String after = worker.require("NODERA-STATE 2");
                Optional<String> identity = worker.ask("NODERA-IDENTITY 2");
                write(results.resolve("t6-state.json"), after);

                ctx.check(probe.filter(line -> line.startsWith("NODERA-OK")).isPresent()
                                && identity.filter(line -> !line.isBlank()).isPresent(),
                        "T6: the worker stopped answering control verbs after the collector died");

                // The comparison that matters: every field of the state answer except the telemetry
                // block and the clocks is unchanged. Compared field-wise rather than as whole lines,
                // because uptime moves.
                Set<String> volatileKeys = Set.of("uptime_seconds", "telemetry");
                Object beforeDocument = ServerJson.parse(before);
                Object afterDocument = ServerJson.parse(after);
                Set<String> allKeys = new TreeSet<>(ServerJson.keys(beforeDocument));
                allKeys.addAll(ServerJson.keys(afterDocument));
                List<String> changed = allKeys.stream()
                        .filter(key -> !volatileKeys.contains(key))
                        .filter(key -> !ServerJson.text(beforeDocument, key)
                                .equals(ServerJson.text(afterDocument, key)))
                        .toList();
                ctx.check(changed.isEmpty(), "T6: the node's reported state changed while "
                        + "telemetry was failing: " + changed);

                ctx.checkContains(after, "\"last_error\"",
                        "T6: the worker did not surface the send failure at all");
            });

            stack.collectArtefacts();
        } finally {
            // HARNESS-GAP: the collector and this scenario's own worker are started here rather
            // than by LiveStack, so their teardown is this scenario's responsibility.
            for (int i = mine.size() - 1; i >= 0; i--) {
                mine.get(i).stop(Duration.ofSeconds(10));
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    /**
     * One framed request against the ingest service: u32 big-endian length + body, the framing every
     * Nodera TCP leg speaks.
     */
    private static String ingestRequest(String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", INGEST_PORT), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(ByteBuffer.allocate(4).putInt(payload.length).array());
            out.write(payload);
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] header = in.readNBytes(4);
            if (header.length < 4) {
                return "";
            }
            int length = ByteBuffer.wrap(header).getInt();
            return new String(in.readNBytes(length), StandardCharsets.UTF_8);
        } catch (IOException notAnswering) {
            return "";
        }
    }

    /** Wait until something accepts a connection on {@code port}. */
    private static boolean awaitPort(int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException notYet) {
                ServerDedicatedDrive.sleep(Duration.ofSeconds(1));
            }
        }
        return false;
    }

    private static List<String> listFiles(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        } catch (IOException none) {
            return List.of();
        }
    }

    private static String concatenateNdjson(Path spool) {
        StringBuilder out = new StringBuilder();
        try (Stream<Path> files = Files.list(spool)) {
            files.filter(file -> file.getFileName().toString().endsWith(".ndjson")).sorted()
                    .forEach(file -> LogWatcher.reader(file).lines()
                            .forEach(line -> out.append(line).append('\n')));
        } catch (IOException none) {
            return "";
        }
        return out.toString();
    }

    private static String capture(List<String> command, Path workingDirectory) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            process.waitFor();
            return output;
        } catch (IOException cannotRun) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while running "
                    + String.join(" ", command), e);
        }
    }

    private static String readIfPresent(Path file) {
        return Files.isReadable(file) ? String.join("\n", LogWatcher.reader(file).lines()) : "";
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new HarnessException("cannot write " + file, e);
        }
    }
}
