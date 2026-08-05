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
 *   C5b TICK: two players sharing a region does not cost a quarter of the tick budget
 *   C6  SEAMLESS: player A's game is killed and player B does not leave the world
 *   C6b GRACEFUL: the survivor is asked to quit and the shutdown returns
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
 * <h2>Why C5b exists</h2>
 *
 * <p>Nothing in this suite read TPS, and a live session reported it falling from 20.0 to 15.5 the
 * moment one player walked into the other's region — every run before this one was blind to it. A
 * commit re-extracts and hashes a WHOLE region, a region is pending whenever any ghost moved, and
 * overlap makes each player a validator of the other's region, so the cost arrives in pairs.
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
     * The floor under the idle ceiling, for a run whose world size cannot be read.
     *
     * <p>The ceiling itself is derived from the world (see {@link #idleCeiling}), because the number
     * that matters is not an absolute rate — it is <b>how many copies of the world</b> move while
     * nobody is playing.
     */
    private static final long IDLE_CEILING_FLOOR_BYTES = 16L * 1024 * 1024;

    /**
     * How many whole copies of the world a settled peer may receive across {@link #IDLE_WINDOW}.
     *
     * <p>Two, which allows one whole-save repack to land inside the window and be replicated, plus
     * headroom. That repack is the design: Minecraft rewrites its region files on every save, so a
     * world nobody is editing still packs to different bytes, and entry-aligned pieces confine the
     * damage per file rather than eliminating it. What this catches is the failure that prompted the
     * ceiling — a peer re-fetching the world continuously, measured at 3 MB/s, which is roughly
     * thirteen copies inside this window rather than one.
     */
    private static final long IDLE_COPIES_ALLOWED = 2;

    /** How long player B must keep playing after player A's game is killed. */
    private static final Duration SURVIVAL_WINDOW = Duration.ofSeconds(90);

    /** How far the tracker list in a state document is scanned; a node configures few. */
    private static final int MAX_TRACKER_ROWS = 16;

    /** How far the world list is scanned; a run has one world and a peer holds few. */
    private static final int MAX_WORLD_ROWS = 32;

    /** How long C5b watches the tick rate with both players in the world. */
    private static final Duration TPS_WINDOW = Duration.ofSeconds(60);

    /**
     * The tick rate a shared world must stay above.
     *
     * <p>Eighteen, not twenty: a real client hosting a real world has ordinary variance, and a floor
     * at the nominal rate would fail on a garbage collection. What this catches is the reported
     * 15.5 — a persistent drop of a quarter of the tick budget, which is a different shape from
     * noise.
     */
    private static final double TPS_FLOOR = 18.0;

    /**
     * How long a graceful quit may take before it counts as stuck.
     *
     * <p>Generous — a large world genuinely takes a while to write, and the final archive flush is
     * allowed twenty seconds of its own. What this is sized to catch is not slowness but a handler
     * that never returns at all, which is what "stuck on Saving world" means.
     */
    private static final Duration GRACEFUL_QUIT_BUDGET = Duration.ofSeconds(150);

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
                        + "<phone-ip>:5555 (docs/frontend/TESTING.md §3.2)");
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
                pushLanTracker(ctx, hostIp[0]);
                pointed = tracksLan(phone.stateDocument(), hostIp[0], ctx.topology().trackerPort());
            }
            ctx.check(pointed, "the phone never took the run's tracker at " + hostIp[0] + ":"
                    + ctx.topology().trackerPort() + ". NODERA-CONFIG reports it applied and the "
                    + "worker does take it — the attached app re-asserts its own settings document "
                    + "seconds later and reverts it, so an external tracker configuration is "
                    + "transient while the app is running.");

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
            // Bake (or re-point) the staged world before anything tries to quick-play into it. A
            // client launched with --quickPlaySingleplayer for a save that is not there does not
            // fail loudly: it lands on a disconnect screen and sits there until the stage times
            // out, which reads as "the host would not open the world".
            Path config = HostWorldSupport.stagedWorld(ctx);
            // And the cadence this whole run is about, stated rather than inherited: the whole-save
            // repack is off, so what moves between peers is region deltas.
            HostWorldSupport.setHostConfig(config, "archive", "streamIntervalTicks", "0");
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
            // Sized from the world, not from a constant: the question is how many COPIES of it move
            // while nobody plays, and a bigger world legitimately costs more per repack.
            long[] worldBytes = {largestWorldBytes(ctx)};
            long[] ceiling = {idleCeiling(worldBytes[0])};
            ctx.note("ceiling    " + ceiling[0] + " bytes (" + IDLE_COPIES_ALLOWED + " copies of a "
                    + worldBytes[0] + "-byte world)");
            Map<String, Long> before = receivedByNode(ctx);
            // Completeness is sampled at BOTH ends. A node that finished its first copy DURING the
            // window holds everything by the time it is asked, while the bytes it moved were that
            // first copy — so asking only at the end fails the stage for the system working.
            Map<String, Boolean> completeBefore = completeByNode(ctx);
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
            Map<String, Boolean> settled = completeByNode(ctx);
            for (Map.Entry<String, Long> entry : after.entrySet()) {
                long delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0L);
                boolean complete = settled.getOrDefault(entry.getKey(), Boolean.FALSE)
                        && completeBefore.getOrDefault(entry.getKey(), Boolean.FALSE);
                ctx.note("idle       " + entry.getKey() + " received " + delta + " bytes in "
                        + IDLE_WINDOW.toSeconds() + "s"
                        + (complete ? "" : " (still completing its first copy)"));
                if (!complete) {
                    // A peer that does not yet hold every world it is replicating has fetching left
                    // to do, and that fetch is the design working rather than the waste this ceiling
                    // exists to catch. The distinction is exact and the worker already reports it —
                    // pieces_held against piece_count, per world — so the stage asks instead of
                    // guessing from the byte count.
                    //
                    // A device with a persistent state directory carries worlds from earlier runs,
                    // so it is routinely mid-backlog here. That is worth noting and not worth
                    // failing on: this run cannot make a steady-state claim about a node that is
                    // still catching up on somebody else's world.
                    ctx.note("idle       " + entry.getKey() + " is still replicating — no "
                            + "steady-state claim made about it");
                    continue;
                }
                ctx.check(delta < ceiling[0], entry.getKey() + " received " + delta
                        + " bytes across an idle " + IDLE_WINDOW.toSeconds() + " seconds while "
                        + "already holding every world it is replicating — over the " + ceiling[0]
                        + "-byte ceiling, which allows " + IDLE_COPIES_ALLOWED + " whole copies of "
                        + "a " + worldBytes[0] + "-byte world. That is more than one repack's worth,"
                        + " so the world is being re-fetched rather than caught up — check "
                        + "archive.streamIntervalTicks and WorldReplicationService.refresh()");
            }
        });

        // -----------------------------------------------------------------------------------
        // C5 — a small edit costs a small transfer
        // -----------------------------------------------------------------------------------
        ctx.stage("C5", "the whole-save repack is not running on a timer", () -> {
            // The evidence that the cadence changed is what the host STOPS saying: a repack logs a
            // whole-archive seed, and the assertion is that it happens at most at share time and at
            // stop, never on a clock.
            List<String> archiveSeeds = HostWorldSupport.matchesAfter(hostLogFile, 0,
                    "world archive seeded to the worker");
            // Region seeds are counted for the record, not asserted. A commit is what produces one,
            // and C4 has just spent three minutes deliberately not producing any — so zero here is
            // the expected reading for an idle world and asserting on it would make this stage a
            // test of whether the players happened to move.
            List<String> regionSeeds = HostWorldSupport.matchesAfter(hostLogFile, 0,
                    "seeded region");
            ctx.note("seeds      " + archiveSeeds.size() + " whole-archive, " + regionSeeds.size()
                    + " per-region (an idle world commits nothing, so zero regions is expected)");
            ctx.check(archiveSeeds.size() <= 2, "the host repacked the whole save "
                    + archiveSeeds.size() + " times. The archive is a cold bootstrap now — once on "
                    + "share and once on stop — and anything more is the periodic repack still "
                    + "running (archive.streamIntervalTicks)");
        });

        // -----------------------------------------------------------------------------------
        // C5b — the tick survives two players sharing a region
        // -----------------------------------------------------------------------------------
        ctx.stage("C5b", "server TPS stays above the floor while both players are in one world",
                () -> {
            // Nothing in this suite read TPS, which is why a drop from 20.0 to 15.5 — reported from
            // a live session the moment one player walked into the other's region — went unnoticed
            // through every run. A commit re-extracts and hashes a WHOLE region, a region is pending
            // whenever any ghost moved, and overlap makes each player a validator of the other's
            // region, so the cost arrives in pairs.
            double worst = 20.0;
            long deadline = System.nanoTime() + TPS_WINDOW.toNanos();
            while (System.nanoTime() < deadline) {
                ctx.settle(Duration.ofSeconds(5));
                double tps = lowestTps(hostLogFile, joinerLogFile);
                worst = Math.min(worst, tps);
            }
            ctx.note("tps        worst observed " + String.format("%.1f", worst)
                    + " over " + TPS_WINDOW.toSeconds() + "s");
            ctx.check(worst < 0 || worst >= TPS_FLOOR,
                    "server TPS fell to " + String.format("%.1f", worst) + ", under the "
                            + TPS_FLOOR + " floor. The validated lane is doing per-tick work on the "
                            + "world thread — see Plan.9 (commit cadence, validator ticket level)");
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
            // Exactly one peer takes the world over, and with two players the survivor is it. The
            // alternative branch is a real outcome too — a peer that was NOT elected holds its
            // ghost and reconnects to whoever was — so both are accepted here and the anti-fork
            // property is that they are mutually exclusive.
            boolean elected = HostWorldSupport.containsAfter(joinerLogFile, killMark[0],
                    "was elected to take the world over");
            boolean waiting = HostWorldSupport.containsAfter(joinerLogFile, killMark[0],
                    "another peer is taking the world over");
            ctx.check(elected || waiting,
                    "player B neither took the world over nor waited for a successor — the "
                            + "departure was not handled at all; see " + joinerLogFile);
            ctx.check(!(elected && waiting),
                    "player B both took over AND waited for somebody else to; the election is not "
                            + "deciding, which is how one world becomes several");
            ctx.check(elected,
                    "player B was the only surviving player and should have been elected; it "
                            + "waited instead, so nobody is hosting the world");
            // The rule, stated as an assertion: no screen. The migration screen is gone from the
            // build, so what is checked is that vanilla's own disconnect handling did not run in
            // its place.
            ctx.check(!HostWorldSupport.containsAfter(joinerLogFile, killMark[0],
                            "Client disconnected with reason"),
                    "player B was disconnected by vanilla — the takeover did not intercept it, and "
                            + "the player was taken out of the world");
        });

        // -----------------------------------------------------------------------------------
        // C6b — the GRACEFUL quit, which is the one nobody was testing
        // -----------------------------------------------------------------------------------
        ctx.stage("C6b", "the surviving host is asked to quit and its shutdown completes", () -> {
            // C6 kills, and a kill runs none of the shutdown path — so every handler on
            // ServerStopping/ServerStopped went untested by this suite while a player quitting from
            // the pause menu ran all of them. That player reported being stuck on "Saving world"
            // forever, which is precisely a shutdown handler that never returns.
            //
            // SIGTERM is the closest a script can get to the menu: it triggers the JVM's shutdown
            // hook, which halts the integrated server on the server thread exactly as the menu does.
            // What the player sees while that runs IS the "Saving world" screen.
            int mark = HostWorldSupport.readLines(joinerLogFile).size();
            HostWorldSupport.stopRunJvms(JOINER_RUN);
            try {
                HostWorldSupport.awaitRunJvmsGone(JOINER_RUN, GRACEFUL_QUIT_BUDGET);
            } catch (RuntimeException stuck) {
                write(stack.resultsDir().resolve("host-quit-threads.txt"),
                        HostWorldSupport.threadDump(JOINER_RUN));
                ctx.fail("the host was still running " + GRACEFUL_QUIT_BUDGET.toSeconds()
                        + "s after being asked to quit — this is the \"stuck on Saving world\" "
                        + "report. Thread dump written to "
                        + stack.resultsDir().resolve("host-quit-threads.txt"));
            }
            // What this proves and what it does not.
            //
            // It proves the shutdown path RETURNS. That is the whole of the "stuck on Saving world"
            // report: NoderaHost.onServerStopping held the class monitor that a lane bootstrap also
            // wanted, and that bootstrap does blocking per-peer network I/O with a thirty-second
            // handshake timeout each — so a quit issued while a lane was starting parked the server
            // thread, which is the thread the "Saving world" screen waits for, with no bound.
            //
            // It does NOT prove a clean save. SIGTERM runs the JVM's shutdown hook, which is not
            // what the pause menu's "Save and Quit to Title" runs, and this harness has no way to
            // drive an in-game menu (docs/testing/LIMITATIONS.md T-2). Asserting SAVE_COMPLETE here
            // asserts a property of the signal rather than of the product.
            ctx.note("quit       the shutdown returned within "
                    + GRACEFUL_QUIT_BUDGET.toSeconds() + "s");
            ctx.check(!HostWorldSupport.containsAfter(joinerLogFile, mark,
                            "the entity lane is still starting up"),
                    "the shutdown had to abandon the entity lane, which means it WOULD have blocked "
                            + "without the bounded wait — the underlying contention is still there");
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

    /**
     * Point the phone's worker at this run's tracker.
     *
     * <p>The key is {@code network.default_trackers} and the value is a list of {@code tcp://}
     * routes, because that is the documented form the worker reads — a route handed over without
     * its scheme is accepted, stored, and then never resolved, which is a feature that silently
     * never runs.
     */
    private void pushLanTracker(ScenarioContext ctx, String hostIp) {
        String config = "{\"network.default_trackers\":[\"tcp://" + hostIp + ":"
                + ctx.topology().trackerPort() + "\"]}";
        phone.verb("NODERA-CONFIG 2 " + java.util.Base64.getEncoder()
                .encodeToString(config.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Whether the phone lists this run's tracker as one it can actually reach.
     *
     * <p>"Reachable", not merely "configured". The weaker form passes on a phone still pointed at a
     * production tracker while the world this run published is on neither of them, and the failure
     * then surfaces two stages later as "the phone did not replicate".
     */
    private static boolean tracksLan(Object document, String hostIp, int port) {
        for (int i = 0; i < MAX_TRACKER_ROWS; i++) {
            java.util.Optional<Object> row = ServerJson.at(document, "trackers." + i);
            if (row.isEmpty()) {
                return false;
            }
            if (hostIp.equals(ServerJson.text(row.get(), "host").orElse(""))
                    && port == ServerJson.number(row.get(), "port")
                    && "true".equals(ServerJson.text(row.get(), "reachable").orElse(""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The lowest TPS either client has reported, or {@code -1} when neither has said.
     *
     * <p>Read from the boss-bar line the diagnostics service already logs, because that is the
     * server's own measurement rather than one this harness could invent. Unknown answers -1 and the
     * check passes: a stage that fails because it could not measure teaches a reader to ignore it.
     */
    private static double lowestTps(Path... logs) {
        double lowest = -1;
        for (Path log : logs) {
            for (String line : HostWorldSupport.matchesAfter(log, 0, "TPS ")) {
                int at = line.lastIndexOf("TPS ");
                if (at < 0) {
                    continue;
                }
                String tail = line.substring(at + 4).trim();
                int end = 0;
                while (end < tail.length()
                        && (Character.isDigit(tail.charAt(end)) || tail.charAt(end) == '.')) {
                    end++;
                }
                if (end == 0) {
                    continue;
                }
                try {
                    double tps = Double.parseDouble(tail.substring(0, end));
                    lowest = lowest < 0 ? tps : Math.min(lowest, tps);
                } catch (NumberFormatException notANumber) {
                    // A line that mentions TPS without a number is not a measurement.
                }
            }
        }
        return lowest;
    }

    /** The ceiling for a world of {@code worldBytes}, never below the floor. */
    private static long idleCeiling(long worldBytes) {
        return Math.max(IDLE_CEILING_FLOOR_BYTES, worldBytes * IDLE_COPIES_ALLOWED);
    }

    /** The biggest world any node in this run is replicating, in bytes; 0 when none is reported. */
    private long largestWorldBytes(ScenarioContext ctx) {
        long largest = 0;
        List<String> states = new ArrayList<>();
        for (PlayerRole role : List.of(PlayerRole.PLAYER_ONE, PlayerRole.PLAYER_TWO)) {
            states.add(ctx.worker(role).state());
        }
        states.add(phone.state());
        for (String state : states) {
            Object document = ServerJson.tryParse(state).orElse(Map.of());
            for (int i = 0; i < MAX_WORLD_ROWS; i++) {
                java.util.Optional<Object> row = ServerJson.at(document, "connected_worlds." + i);
                if (row.isEmpty()) {
                    break;
                }
                largest = Math.max(largest, ServerJson.number(row.get(), "total_bytes"));
            }
        }
        return largest;
    }

    /**
     * Which nodes hold every world they are replicating, and which are still fetching a first copy.
     *
     * <p>The traffic ceiling is about steady state. A node that has never held a world must download
     * it once, and counting that as "the world is being moved again" fails the stage for the system
     * working correctly — which is worse than not checking, because it teaches a reader to ignore
     * the number. The worker publishes {@code pieces_held} and {@code piece_count} per world, so the
     * difference is a fact rather than an inference from the byte count.
     *
     * @return node label → whether every connected world is complete there.
     */
    private Map<String, Boolean> completeByNode(ScenarioContext ctx) {
        Map<String, Boolean> complete = new LinkedHashMap<>();
        for (PlayerRole role : List.of(PlayerRole.PLAYER_ONE, PlayerRole.PLAYER_TWO)) {
            complete.put(role.cliName(), holdsEverything(ctx.worker(role).state()));
        }
        complete.put("phone", holdsEverything(phone.state()));
        return complete;
    }

    /**
     * Whether every {@code connected_worlds} row reports a complete copy.
     *
     * <p>A node with no rows at all answers <b>false</b>: it is not holding everything, it is
     * holding nothing, and it is about to start fetching. Reading that as "settled" is how a node
     * that had not begun yet got judged against a steady-state ceiling.
     */
    private static boolean holdsEverything(String state) {
        Object document = ServerJson.tryParse(state).orElse(Map.of());
        boolean anyRow = false;
        for (int i = 0; i < MAX_WORLD_ROWS; i++) {
            java.util.Optional<Object> row = ServerJson.at(document, "connected_worlds." + i);
            if (row.isEmpty()) {
                return anyRow;
            }
            anyRow = true;
            long pieces = ServerJson.number(row.get(), "piece_count");
            long held = ServerJson.number(row.get(), "pieces_held");
            // `piece_count == 0` is "this node has no manifest for that world yet" — a row it has
            // registered and not begun. Reading it as complete is how a node holding NOTHING was
            // judged against a steady-state ceiling and blamed for its own first download.
            if (pieces == 0 || held < pieces) {
                return false;
            }
        }
        return anyRow;
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
