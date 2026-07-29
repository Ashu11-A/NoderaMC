package dev.nodera.structure;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodEntryRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The headless worker, run under a debugger, so the report can say what the code <em>does</em>
 * rather than only what it looks like.
 *
 * <h2>Why a debugger and not a profiler or a coverage agent</h2>
 *
 * <p>Static analysis can prove that nothing names a method. It cannot prove the opposite — that a
 * method with a hundred call sites is entered once a week, or that a whole service is constructed,
 * registered, and then never invoked because the one production caller sits behind a config flag
 * nobody sets. Those are this project's actual failure mode ("built but never called"), and they
 * are only visible from the running process.
 *
 * <p>JDI is the right instrument for that: it attaches to the REAL {@code nodera-headless} process
 * — the same binary the companion app supervises — over JDWP, and reports class-prepare and
 * method-entry events without the process being modified, recompiled, or aware it is being watched.
 * A coverage agent would need the build to instrument classes; a sampling profiler would tell us
 * where time went but not which of two hundred services were ever touched.
 *
 * <h2>What it costs and what it therefore measures</h2>
 *
 * <p>Method-entry events deoptimise the filtered classes, so this run is much slower than a normal
 * one and its <em>timings</em> mean nothing. Its <em>counts</em> are exact: entered once is entered
 * once. So the probe reports counts and coverage, never durations — the benchmark lane
 * ({@code :peer:jmh}) is where speed is measured, on an uninstrumented JVM.
 *
 * <p>Thread-context: {@link #run} owns the child process and one event-pump thread; the returned
 * profile is an immutable snapshot.
 */
final class HeadlessDebugProbe {

    /** Packages the probe instruments. Everything else is loaded but not entry-traced. */
    static final List<String> DEFAULT_TRACED_PACKAGES = List.of(
            "dev.nodera.headless.*",
            "dev.nodera.peer.*",
            "dev.nodera.distribution.*",
            "dev.nodera.diagnostics.*");

    /** Stop tracing after this many entries: the report needs coverage, not a transcript. */
    private static final long MAX_EVENTS = 400_000L;

    /** The worker's main class — the same entry point the installed launcher runs. */
    private static final String MAIN_CLASS = "dev.nodera.headless.HeadlessPeerMain";

    private HeadlessDebugProbe() {
    }

    /** What the debugger saw. */
    record RuntimeProfile(Map<String, Long> methodEntries, Set<String> classesLoaded,
                          List<String> scenario, long events, long durationMillis) {

        static RuntimeProfile empty() {
            return new RuntimeProfile(Map.of(), Set.of(), List.of(), 0L, 0L);
        }

        boolean isEmpty() {
            return methodEntries.isEmpty() && classesLoaded.isEmpty();
        }

        /** Entry count for a scanned method, or 0 if the debugger never saw it run. */
        long entriesOf(CodeGraph.MethodId method) {
            return methodEntries.getOrDefault(key(method), 0L);
        }

        static String key(CodeGraph.MethodId method) {
            return method.owner() + "#" + method.name() + method.desc();
        }

        /** Was this class ever loaded by the running worker? */
        boolean loaded(String internalName) {
            return classesLoaded.contains(internalName);
        }
    }

    /**
     * Launch the worker under JDWP, drive a scenario through its control port, and record what ran.
     *
     * @param workDir  a scratch directory used as the worker's whole state directory — the probe
     *                 never touches {@code ~/.nodera}, because a diagnostic that mutates the
     *                 developer's real node is not a diagnostic.
     * @param logFile  where the worker's stdout/stderr is captured (attached to the report on
     *                 failure — a probe that fails without the child's output is unfixable).
     * @param traced   class-filter globs for method-entry tracing.
     * @return the profile.
     */
    static RuntimeProfile run(Path workDir, Path logFile, List<String> traced) throws Exception {
        int debugPort = freePort();
        int controlPort = freePort();
        int p2pPort = freePort();
        long startedAt = System.currentTimeMillis();

        Process worker = launch(workDir, logFile, debugPort, controlPort, p2pPort);
        VirtualMachine vm = null;
        Map<String, LongAdder> entries = new ConcurrentHashMap<>();
        Set<String> loaded = ConcurrentHashMap.newKeySet();
        AtomicLong eventCount = new AtomicLong();
        AtomicBoolean stopped = new AtomicBoolean();
        List<String> scenario = new ArrayList<>();
        Thread pump = null;

        try {
            vm = attach(debugPort, worker);
            EventRequestManager requests = vm.eventRequestManager();

            ClassPrepareRequest prepares = requests.createClassPrepareRequest();
            prepares.addClassFilter("dev.nodera.*");
            prepares.setSuspendPolicy(EventRequest.SUSPEND_NONE);
            prepares.enable();

            List<MethodEntryRequest> entryRequests = new ArrayList<>();
            for (String glob : traced) {
                MethodEntryRequest request = requests.createMethodEntryRequest();
                request.addClassFilter(glob);
                // SUSPEND_NONE: the worker keeps running while we watch. Suspending on every method
                // entry would not "profile" the worker, it would stop it dead.
                request.setSuspendPolicy(EventRequest.SUSPEND_NONE);
                request.enable();
                entryRequests.add(request);
            }

            VirtualMachine attached = vm;
            pump = new Thread(() -> pumpEvents(attached, entries, loaded, eventCount, stopped,
                    entryRequests), "nodera-jdi-pump");
            pump.setDaemon(true);
            pump.start();

            // The VM was launched suspended so no class could load before the requests existed.
            vm.resume();

            scenario.addAll(driveScenario(controlPort, worker));
        } finally {
            stopped.set(true);
            if (pump != null) {
                pump.join(5_000);
            }
            if (vm != null) {
                try {
                    vm.dispose();
                } catch (Exception alreadyGone) {
                    // The child may have exited first; nothing left to dispose.
                }
            }
            worker.destroy();
            if (!worker.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                worker.destroyForcibly();
            }
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        entries.forEach((key, adder) -> counts.put(key, adder.sum()));
        return new RuntimeProfile(Map.copyOf(counts), Set.copyOf(loaded), List.copyOf(scenario),
                eventCount.get(), System.currentTimeMillis() - startedAt);
    }

    private static Process launch(Path workDir, Path logFile, int debugPort, int controlPort,
                                  int p2pPort) throws IOException {
        Files.createDirectories(workDir);
        Files.createDirectories(logFile.getParent());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(
                java,
                // server=y + suspend=y: nothing runs until the probe has attached and installed its
                // requests, so class loading during boot is observed rather than missed.
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + debugPort,
                "-cp", System.getProperty("java.class.path"),
                MAIN_CLASS);

        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        env.put("NODERA_CONTROL_PORT", String.valueOf(controlPort));
        env.put("NODERA_CONTROL_HOST", "127.0.0.1");
        env.put("NODERA_P2P_PORT", String.valueOf(p2pPort));
        env.put("NODERA_P2P_BIND", "127.0.0.1");
        env.put("NODERA_P2P_ADVERTISE", "127.0.0.1");
        env.put("NODERA_STATE_DIR", workDir.toString());
        env.put("NODERA_IDENTITY_FILE", workDir.resolve("identity.bin").toString());
        env.put("NODERA_WORLDS_FILE", workDir.resolve("worlds.dat").toString());
        env.put("NODERA_WORLD_KEYS_DIR", workDir.resolve("world-keys").toString());
        env.put("NODERA_ARCHIVE_DIR", workDir.resolve("archive").toString());
        // Closed ports on purpose: the probe measures OUR code paths, and a real tracker would make
        // the run depend on somebody else's uptime.
        env.put("NODERA_TRACKER_ENDPOINTS", "127.0.0.1:1");
        env.put("NODERA_RENDEZVOUS_ENDPOINTS", "127.0.0.1:1");
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        return builder.start();
    }

    private static VirtualMachine attach(int debugPort, Process worker) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors()
                .stream()
                .filter(c -> "com.sun.jdi.SocketAttach".equals(c.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no JDI SocketAttach connector on this JVM — the structural probe needs a "
                                + "JDK, not a JRE"));
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(String.valueOf(debugPort));
        arguments.get("timeout").setValue("5000");

        Exception last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            if (!worker.isAlive()) {
                throw new IllegalStateException(
                        "the worker exited before the debugger could attach (exit "
                                + worker.exitValue() + ")");
            }
            try {
                return connector.attach(arguments);
            } catch (Exception notListeningYet) {
                last = notListeningYet;
                Thread.sleep(250);
            }
        }
        throw new IllegalStateException("could not attach to the worker on port " + debugPort, last);
    }

    private static void pumpEvents(VirtualMachine vm, Map<String, LongAdder> entries,
                                   Set<String> loaded, AtomicLong eventCount, AtomicBoolean stopped,
                                   List<MethodEntryRequest> entryRequests) {
        EventQueue queue = vm.eventQueue();
        while (!stopped.get()) {
            EventSet events;
            try {
                events = queue.remove(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception disconnected) {
                return;
            }
            if (events == null) {
                continue;
            }
            for (Event event : events) {
                if (event instanceof MethodEntryEvent entry) {
                    String owner = entry.method().declaringType().name().replace('.', '/');
                    entries.computeIfAbsent(
                                    owner + "#" + entry.method().name() + entry.method().signature(),
                                    key -> new LongAdder())
                            .increment();
                    if (eventCount.incrementAndGet() > MAX_EVENTS) {
                        // Enough. Disabling the requests hands the worker its speed back and the
                        // report loses nothing: coverage is a set, not a total.
                        entryRequests.forEach(EventRequest::disable);
                    }
                } else if (event instanceof ClassPrepareEvent prepared) {
                    loaded.add(prepared.referenceType().name().replace('.', '/'));
                } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                    return;
                }
            }
            if (events.suspendPolicy() != EventRequest.SUSPEND_NONE) {
                try {
                    events.resume();
                } catch (Exception disconnected) {
                    return;
                }
            }
        }
    }

    /**
     * Drive the worker the way the companion app drives it.
     *
     * <p>Every verb here is one the Tauri dashboard or the Minecraft mod sends on a normal desktop
     * session, so what the probe records is the production path, not a synthetic one. The scenario
     * is deliberately read-only: it must be safe to run on any machine, in CI, in any order.
     */
    private static List<String> driveScenario(int controlPort, Process worker) throws Exception {
        List<String> executed = new ArrayList<>();
        awaitControlPort(controlPort, worker);

        for (String verb : List.of(
                "NODERA-PROBE 2",      // what the mod sends before it will let the game start
                "NODERA-IDENTITY 2",   // the node's own key material
                "NODERA-STATE 2",      // the dashboard's whole model
                "NODERA-WORLDS 2",     // the Worlds screen
                "NODERA-DIRECTORY 2",  // tracker/rendezvous service discovery
                "NODERA-MESH 2",       // membership as the peer sees it
                "NODERA-PIECES 2",     // the distribution plane's holdings
                "NODERA-EVENTS 2",     // the replayable event log the app reads on open
                "NODERA-CONFIG 2",     // settings round-trip
                "NODERA-STATUS 2",     // hosting status
                "NODERA-LAN 2",        // the Open-to-LAN tunnel lane
                "NODERA-TELEMETRY 2")) {
            String reply = control(controlPort, verb);
            executed.add(verb + " -> " + summarise(reply));
        }
        return executed;
    }

    private static String summarise(String reply) {
        if (reply == null) {
            return "(no reply)";
        }
        String head = reply.length() > 60 ? reply.substring(0, 60) + "..." : reply;
        return head.replace('\n', ' ');
    }

    private static void awaitControlPort(int controlPort, Process worker) throws Exception {
        // Under JDWP with method-entry tracing the worker boots slowly; the deadline is generous on
        // purpose, and it fails loudly rather than reporting an empty profile as a clean run.
        for (int attempt = 0; attempt < 240; attempt++) {
            if (!worker.isAlive()) {
                throw new IllegalStateException("the worker exited during boot (exit "
                        + worker.exitValue() + ")");
            }
            try {
                String reply = control(controlPort, "NODERA-PROBE 2");
                if (reply != null && reply.startsWith("NODERA-OK")) {
                    return;
                }
            } catch (IOException notUpYet) {
                // Expected while the control server is still binding.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("the worker never answered on control port " + controlPort);
    }

    private static String control(int port, String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write((request + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Serialise a profile so the report step (and a later run) can read it back. */
    static void write(Path file, RuntimeProfile profile) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"events\": ").append(profile.events())
                .append(",\n  \"durationMillis\": ").append(profile.durationMillis())
                .append(",\n  \"scenario\": [");
        for (int i = 0; i < profile.scenario().size(); i++) {
            json.append(i == 0 ? "\n    " : ",\n    ").append(quote(profile.scenario().get(i)));
        }
        json.append(profile.scenario().isEmpty() ? "" : "\n  ").append("],\n  \"classesLoaded\": [");
        List<String> classes = new ArrayList<>(new LinkedHashSet<>(profile.classesLoaded()));
        classes.sort(String::compareTo);
        for (int i = 0; i < classes.size(); i++) {
            json.append(i == 0 ? "\n    " : ",\n    ").append(quote(classes.get(i)));
        }
        json.append(classes.isEmpty() ? "" : "\n  ").append("],\n  \"methodEntries\": {");
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(profile.methodEntries().entrySet());
        sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        for (int i = 0; i < sorted.size(); i++) {
            json.append(i == 0 ? "\n    " : ",\n    ")
                    .append(quote(sorted.get(i).getKey())).append(": ").append(sorted.get(i).getValue());
        }
        json.append(sorted.isEmpty() ? "" : "\n  ").append("}\n}\n");
        Files.writeString(file, json.toString());
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
