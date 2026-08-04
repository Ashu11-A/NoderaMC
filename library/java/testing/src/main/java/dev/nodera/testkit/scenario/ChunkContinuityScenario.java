package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.harness.Topology;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two Minecraft launchers, a phone, and the two numbers that say whether any of this worked.
 *
 * <p>Three defects were fixed together because they share one cause — the smallest unit this system
 * could talk about was a whole world — and this scenario is where the fixes are asked to hold up in
 * front of real clients rather than in front of a unit test:
 *
 * <pre>
 *   C0  three nodes: two desktop workers and a phone, all reachable off this machine
 *   C1  the phone joins the mesh and takes this run's tracker
 *   C2  player A opens a world from a real client; player B joins it
 *   C3  ADMINISTRATION: player A is an operator of their own world and stays one
 *   C4  TRAFFIC: nobody touches anything for three minutes, and the peers stay quiet
 *   C5  DELTA: a small edit costs a small transfer
 *   C6  SEAMLESS: player A's game is killed and player B does not leave the world
 *   C7  the phone is a peer of that world, not a spectator of it
 *   C8  the evidence bundle
 * </pre>
 *
 * <h2>What C4 is really testing</h2>
 *
 * <p>An idle world used to cost about 3 MB/s per peer, forever: the host repacked the entire save
 * every two minutes and re-seeded it, every piece hash moved because the container's byte offsets
 * moved, and every replicating peer fetched the world again. The ceiling here is deliberately loose
 * — several megabytes over three minutes — because the failure it exists to catch is three orders
 * of magnitude away from it. A tight ceiling would flake on ordinary discovery chatter and teach
 * everyone to ignore this stage.
 *
 * <h2>Why C6 is a kill and not a shutdown</h2>
 *
 * <p>A graceful stop lets the host say goodbye, and a goodbye is the easy case. The bar is that
 * player B keeps playing when player A's process simply ceases — which is what happens when a laptop
 * lid closes. The pid is resolved from {@code /proc/<pid>/cmdline} rather than
 * {@code ProcessHandle.info().commandLine()}, which truncates at 4096 bytes and hid every game JVM
 * from an earlier version of this harness; a run then "killed" a host that went on serving.
 *
 * <p>Requires: a device on wireless debugging ({@code ANDROID_SERIAL}), a GUI session, and a stack
 * bound to the LAN — {@code NODERA_SERVICE_BIND_ADDR=0.0.0.0} plus
 * {@code NODERA_SERVICE_ADVERTISE_ADDR=<this machine>} and the matching {@code NODERA_P2P_*} pair,
 * exported before the run starts.
 *
 * @Thread-context run on the runner's thread; stateless between runs.
 */
public final class ChunkContinuityScenario implements Scenario {

    /** ModDevGradle run tokens — the only reliable way to address one client's game JVM. */
    private static final String HOST_RUN = "clientHostRunProgramArgs";
    private static final String JOINER_RUN = "clientJoinRunProgramArgs";

    /** How long C4 watches an untouched world. */
    private static final Duration IDLE_WINDOW = Duration.ofMinutes(3);

    /** How often the traffic counters are sampled inside that window. */
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(10);

    /**
     * The most a peer may receive across {@link #IDLE_WINDOW} while nothing is happening.
     *
     * <p>Sixteen megabytes over three minutes is about 90 KB/s, which is generous for a world
     * nobody is editing. The behaviour this catches ran at roughly 3 MB/s — five hundred megabytes
     * across this window — so the gap between "quiet" and "broken" is enormous and the ceiling sits
     * comfortably in it rather than on the edge of ordinary discovery traffic.
     */
    private static final long IDLE_CEILING_BYTES = 16L * 1024 * 1024;

    /** How long player B must keep playing after player A's game is killed. */
    private static final Duration SURVIVAL_WINDOW = Duration.ofSeconds(90);

    private AndroidDevice phone = new AndroidDevice();

    @Override
    public String id() {
        return "chunk-continuity";
    }

    @Override
    public String title() {
        return "two launchers and a phone: the creator keeps /op, an idle world is quiet, and a "
                + "killed host does not take anybody out of the world";
    }

    @Override
    public Set<String> tags() {
        return Set.of("hardware", "android", "live", "continuity");
    }

    @Override
    public Requirements requirements() {
        return Requirements.liveDevice(6);
    }

    @Override
    public Topology topology() {
        // No spare peer: the phone is the third session member, and two Minecraft clients plus
        // three desktop workers does not fit on the machine this is written for.
        return Topology.standard().withSparePeers(0);
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        LiveStack stack = ctx.stack();
        phone = new AndroidDevice();
        String[] hostIp = new String[1];
        String[] phoneNode = new String[1];
        long[] phoneBaseline = new long[1];
        HostWorldSupport.HostedPair[] players = new HostWorldSupport.HostedPair[1];
        List<String> bandwidth = new ArrayList<>();

        // Registered so both logs land in the evidence bundle. Every assertion below reads the
        // FILE rather than a watcher, because a watcher bound once goes silent if the client it
        // watches is ever replaced — which is how an earlier scenario asked a file nothing was
        // writing whether a player had been disconnected, and was told "no" every time.
        ctx.log("client-host.log");
        ctx.log("client-join.log");
        Path hostLogFile = stack.logDir().resolve("client-host.log");
        Path joinerLogFile = stack.logDir().resolve("client-join.log");
        int[] killMark = new int[1];

        // -----------------------------------------------------------------------------------
        // C0 — three nodes, reachable from off this machine
        // -----------------------------------------------------------------------------------
        ctx.stage("C0", "two desktop workers and a phone worker are up and dialable", () -> {
            HostWorldSupport.probeWorkers(ctx);

            if (!AndroidDevice.hasCommand("adb")) {
                ctx.skip("adb is not on PATH (scripts/android-toolchain.sh)");
            }
            String serial = phone.resolveSerial();
            if (serial.isBlank()) {
                ctx.skip("no wireless device. Connect one first: adb tcpip 5555 && adb connect "
                        + "<phone-ip>:5555 (docs/mobile/TESTING.md §3.2)");
            }
            ctx.check(phone.isWireless(), "the device must be reachable by IP (got '" + serial
                    + "'); a USB-only device cannot be dialled by the peers");
            hostIp[0] = AndroidDevice.hostLanAddress(phone.ip());
            ctx.check(!hostIp[0].isBlank(), "could not determine this machine's LAN address");

            if (!AndroidDevice.isReachable(hostIp[0], ctx.topology().trackerPort())) {
                ctx.skip("the tracker on " + hostIp[0] + ":" + ctx.topology().trackerPort()
                        + " cannot be reached from off this machine. Export "
                        + "NODERA_SERVICE_BIND_ADDR=0.0.0.0, NODERA_SERVICE_ADVERTISE_ADDR="
                        + hostIp[0] + ", NODERA_P2P_BIND_ADDR=0.0.0.0 and "
                        + "NODERA_P2P_ADVERTISE_ADDR=" + hostIp[0] + " before starting the run.");
            }

            String model = phone.adb(List.of("shell", "getprop", "ro.product.model")).trim();
            if (model.isBlank()) {
                ctx.skip("the device at " + serial + " is not answering — wireless debugging drops "
                        + "when the handset sleeps. Reconnect it and keep the screen awake.");
            }
            ctx.check(!phone.versionOnDevice().isBlank(), AndroidDevice.PACKAGE
                    + " is not installed on " + serial + " — scripts/android-apk.sh --install");
            ctx.check(phone.restartApp() == 0, "the app would not start");

            String route = "";
            for (int waited = 0; waited < 20 && route.isEmpty(); waited++) {
                if (phone.verb("NODERA-PROBE 2").startsWith("NODERA-OK")) {
                    Object snapshot = phone.stateDocument();
                    String id = ServerJson.text(snapshot, "node_id").orElse("");
                    String self = ServerJson.text(snapshot, "self_route").orElse("");
                    if (!id.isBlank() && !self.isBlank()) {
                        phoneNode[0] = id;
                        route = self;
                    }
                }
            }
            if (phoneNode[0] == null || route.isEmpty()) {
                write(stack.logDir().resolve("android-logcat.log"), phone.logcat());
                ctx.fail("the phone's worker never came up with both an identity and a route — see "
                        + stack.logDir().resolve("android-logcat.log"));
            }
            ctx.note("phone      " + serial + " at " + route);
            ctx.note("host       " + hostIp[0]);

            // Three distinct nodes with three routes nobody else answers at. A run where two of
            // them share a route is one node wearing two hats, and every ownership answer after
            // that point is plausible and wrong.
            Map<String, String> routes = new LinkedHashMap<>();
            routes.put(phoneNode[0], route);
            for (PlayerRole role : List.of(PlayerRole.PLAYER_ONE, PlayerRole.PLAYER_TWO)) {
                String state = ctx.worker(role).state();
                routes.put(field(state, "node_id"), field(state, "self_route"));
            }
            ctx.check(routes.size() == 3, "expected three distinct node ids, got " + routes.keySet());
            routes.forEach((node, self) -> ctx.check(!self.startsWith("127.0.0.1:"),
                    node + " advertises " + self + " — loopback, so nobody else can dial it"));
        });

        // -----------------------------------------------------------------------------------
        // C1 — the phone joins this run's mesh
        // -----------------------------------------------------------------------------------
        ctx.stage("C1", "the phone takes this run's tracker and joins the mesh", () -> {
            phoneBaseline[0] = ServerJson.number(phone.stateDocument(), "total_received_bytes");

            boolean pointed = false;
            for (int waited = 0; waited < 20 && !pointed; waited++) {
                phone.verb("NODERA-CONFIG 2 tracker.endpoints "
                        + hostIp[0] + ":" + ctx.topology().trackerPort());
                pointed = phone.state().contains(hostIp[0] + ":" + ctx.topology().trackerPort());
            }
            ctx.check(pointed, "the phone never took the run's tracker at " + hostIp[0] + ":"
                    + ctx.topology().trackerPort() + " — an attached companion app re-asserts its "
                    + "own settings document seconds later and reverts it, so close the app on the "
                    + "phone for this run");

            // The phone dials OUT: the direction that works without forwarding a port to a handset.
            String route = hostIp[0] + ":" + ctx.topology().workerP2pPort(0);
            String reply = phone.verb("NODERA-MESH 2 " + route);
            ctx.check(reply.startsWith("NODERA-OK"), "the phone refused to join the mesh at "
                    + route + ": " + (reply.isBlank() ? "no reply" : reply));
        });

        // -----------------------------------------------------------------------------------
        // C2 — two real clients in one world
        // -----------------------------------------------------------------------------------
        ctx.stage("C2", "player A opens a world from a real launcher and player B joins it", () -> {
            players[0] = HostWorldSupport.hostedTwoPlayers(ctx);
            ctx.check(HostWorldSupport.runJvmAlive(HOST_RUN), "player A's game JVM is not running");
            ctx.check(HostWorldSupport.runJvmAlive(JOINER_RUN), "player B's game JVM is not running");
        });

        // -----------------------------------------------------------------------------------
        // C3 — administration
        // -----------------------------------------------------------------------------------
        ctx.stage("C3", "the world's creator is an operator of it, and nothing takes that away",
                () -> {
            // The failure this replaces: the creator announced a keypair generated moments earlier,
            // the permission set had never heard of it, and the bridge de-opped them from their own
            // world. Two lines say whether the fix is live, and their ABSENCE is the assertion —
            // there is no positive log line for "nothing went wrong", and inventing one would make
            // this stage pass on a build where the bridge never ran at all.
            ctx.settle(Duration.ofSeconds(15));
            ctx.check(!HostWorldSupport.containsAfter(hostLogFile, 0, "de-opped"),
                    "somebody was de-opped in the creator's own world — see " + hostLogFile);
            ctx.check(!HostWorldSupport.containsAfter(joinerLogFile, 0,
                            "did not vouch for the session key"),
                    "player B's worker did not sign a session delegation, so any role that player "
                            + "holds is invisible to the world — see " + joinerLogFile);
            // And the positive half, from the side that can have one: a joiner announcing a
            // delegation that does not apply says so, and that line must not appear either.
            ctx.check(!HostWorldSupport.containsAfter(hostLogFile, 0,
                            "does not apply here"),
                    "a session delegation was rejected by the host — the joiner is being evaluated "
                            + "as a stranger, which is the bug this run exists to catch");
        });

        // -----------------------------------------------------------------------------------
        // C4 — an idle world is a quiet world
        // -----------------------------------------------------------------------------------
        ctx.stage("C4", "nobody edits anything for " + IDLE_WINDOW.toMinutes()
                + " minutes and the peers stay under the traffic ceiling", () -> {
            Map<String, Long> before = receivedByNode(ctx);
            long deadline = System.nanoTime() + IDLE_WINDOW.toNanos();
            bandwidth.add("| sample | " + String.join(" | ", before.keySet()) + " |");
            bandwidth.add("|---" + "|---".repeat(before.size()) + "|");
            int sample = 0;
            while (System.nanoTime() < deadline) {
                ctx.settle(SAMPLE_INTERVAL);
                Map<String, Long> now = receivedByNode(ctx);
                StringBuilder row = new StringBuilder("| ").append(++sample).append(" |");
                for (Map.Entry<String, Long> entry : now.entrySet()) {
                    row.append(' ')
                            .append(entry.getValue() - before.getOrDefault(entry.getKey(), 0L))
                            .append(" |");
                }
                bandwidth.add(row.toString());
            }
            Map<String, Long> after = receivedByNode(ctx);
            for (Map.Entry<String, Long> entry : after.entrySet()) {
                long delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0L);
                ctx.note("idle       " + entry.getKey() + " received " + delta + " bytes in "
                        + IDLE_WINDOW.toSeconds() + "s");
                ctx.check(delta < IDLE_CEILING_BYTES, entry.getKey() + " received " + delta
                        + " bytes across an idle " + IDLE_WINDOW.toSeconds() + " seconds, over the "
                        + IDLE_CEILING_BYTES + "-byte ceiling. A world nobody is editing is being "
                        + "moved across the network again — check archive.streamIntervalTicks and "
                        + "WorldReplicationService.refresh()");
            }
        });

        // -----------------------------------------------------------------------------------
        // C5 — a small edit costs a small transfer
        // -----------------------------------------------------------------------------------
        ctx.stage("C5", "the world keeps being seeded region by region rather than whole", () -> {
            // The evidence that the cadence changed is what the host STOPS saying. A repack logs
            // its whole-archive seed; a region delta logs a region. Both lines exist, and which one
            // repeats is the entire behavioural difference.
            List<String> archiveSeeds = HostWorldSupport.matchesAfter(hostLogFile, 0,
                    "Seeding world archive");
            List<String> regionSeeds = HostWorldSupport.matchesAfter(hostLogFile, 0,
                    "Seeding region");
            ctx.note("seeds      " + archiveSeeds.size() + " whole-archive, " + regionSeeds.size()
                    + " per-region");
            ctx.check(archiveSeeds.size() <= 2, "the host repacked the whole save "
                    + archiveSeeds.size() + " times. The archive is a cold bootstrap now — once on "
                    + "share and once on stop — and anything more is the periodic repack still "
                    + "running (archive.streamIntervalTicks)");
        });

        // -----------------------------------------------------------------------------------
        // C6 — the seamless kill
        // -----------------------------------------------------------------------------------
        ctx.stage("C6", "player A's game is killed and player B stays in the world", () -> {
            killMark[0] = HostWorldSupport.readLines(joinerLogFile).size();
            HostWorldSupport.killRunJvms(HOST_RUN);
            // A kill that killed nothing is the failure mode this harness has had before: the run
            // token used to be read from a truncated command line, so the search matched nothing,
            // the kill was a no-op, and a host-departure stage passed with the host still running.
            HostWorldSupport.awaitRunJvmsGone(HOST_RUN, Duration.ofSeconds(30));
            players[0].host().stop(Duration.ofSeconds(20));

            ctx.settle(SURVIVAL_WINDOW);

            ctx.check(HostWorldSupport.runJvmAlive(JOINER_RUN),
                    "player B's game exited when the host died — the whole point is that it does not");
            ctx.check(HostWorldSupport.containsAfter(joinerLogFile, killMark[0],
                            "keeping this player in the world"),
                    "player B never took over locally; see " + joinerLogFile);
            // The rule, stated as an assertion: no screen. The migration screen is gone from the
            // build, so what is checked is that vanilla's own disconnect handling did not run in
            // its place.
            ctx.check(!HostWorldSupport.containsAfter(joinerLogFile, killMark[0],
                            "Client disconnected with reason"),
                    "player B was disconnected by vanilla — the takeover did not intercept it, and "
                            + "the player was taken out of the world");
        });

        // -----------------------------------------------------------------------------------
        // C7 — the phone is a peer of this world
        // -----------------------------------------------------------------------------------
        ctx.stage("C7", "the phone received this world's content", () -> {
            long received = 0;
            for (int waited = 0; waited < 30 && received <= phoneBaseline[0]; waited++) {
                ctx.settle(Duration.ofSeconds(2));
                received = ServerJson.number(phone.stateDocument(), "total_received_bytes");
            }
            ctx.check(received > phoneBaseline[0], "the phone received nothing across the whole "
                    + "run (still " + received + " bytes) — it is on the mesh but is not a peer of "
                    + "this world");
            ctx.note("phone      received " + (received - phoneBaseline[0]) + " bytes this run");
        });

        // -----------------------------------------------------------------------------------
        // C8 — the evidence
        // -----------------------------------------------------------------------------------
        ctx.stage("C8", "the bandwidth table and the state of every node are written out", () -> {
            for (String row : bandwidth) {
                HostWorldSupport.transcript(ctx, "bandwidth.md", row);
            }
            for (PlayerRole role : List.of(PlayerRole.PLAYER_ONE, PlayerRole.PLAYER_TWO)) {
                write(stack.resultsDir().resolve("state-" + role.cliName() + ".json"),
                        ctx.worker(role).state());
            }
            write(stack.resultsDir().resolve("state-phone.json"), phone.state());
        });
    }

    /**
     * Bytes received per node, right now — the one measurement this whole scenario turns on.
     *
     * <p>Read from each worker's own {@code NODERA-STATE}, which is a product surface: no counter
     * the harness maintains itself could be evidence about the product.
     */
    private Map<String, Long> receivedByNode(ScenarioContext ctx) {
        Map<String, Long> received = new LinkedHashMap<>();
        for (PlayerRole role : List.of(PlayerRole.PLAYER_ONE, PlayerRole.PLAYER_TWO)) {
            String state = ctx.worker(role).state();
            received.put(role.cliName(),
                    ServerJson.number(ServerJson.tryParse(state).orElse(Map.of()),
                            "total_received_bytes"));
        }
        received.put("phone", ServerJson.number(phone.stateDocument(), "total_received_bytes"));
        return received;
    }

    /** A top-level string field of a worker's state document. */
    private static String field(String state, String path) {
        return ServerJson.tryParse(state)
                .flatMap(document -> ServerJson.text(document, path))
                .orElse("");
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (java.io.IOException e) {
            throw new dev.nodera.testkit.harness.HarnessException("cannot write " + file, e);
        }
    }
}
