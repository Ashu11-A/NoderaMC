package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.ControlClient;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.harness.Topology;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A PHONE and two desktop players, in one world, across every way a player can leave it.
 *
 * <p>{@link AndroidMeshScenario} proves the phone is on the mesh. This proves the phone is a
 * <b>peer</b>: that it takes a share of a real world's content, that it keeps taking it while
 * players come and go, and that it is still there when the player who opened the world closes the
 * game. Everything the phone reports is read from its own worker over adb, never from the app's UI.
 *
 * <pre>
 *   M0  three nodes, three routes, two attached launchers
 *   M1  the phone and both desktop workers see each other and move bytes
 *   M2  player A opens the world, player B joins it through discovery
 *   M3  the phone replicates the world's archive without being asked to host anything
 *   M4  the players cross region boundaries; every node's region evidence is compared
 *   M5  player B leaves and rejoins five times, in the same world
 *   M6  player A's GAME exits, its worker stays — what happens to player B is MEASURED here
 *   M7  the AUTHOR leaves and re-opens its own world five times
 *   M8  the verdict on M6, taken last so the host churn above still runs
 *   M8b the same departure without a shutdown (NODERA_MOBILE_HARD_KILL=1)
 *   M9  the evidence bundle
 * </pre>
 *
 * <h2>The topology has no spare peer, on purpose</h2>
 *
 * <p>The standard topology's third peer exists to hold the swarm at the quorum floor
 * ({@link Topology}). Here the PHONE is that third member — which is both what the run is about and
 * what makes it fit on a workstation that is already running two Minecraft clients.
 *
 * <h2>What M6 measures, what M8 asserts, and why they are separate stages</h2>
 *
 * <p>The pass bar this scenario was written to is <b>zero interruption</b>: when player A's game
 * exits, player B must not lose its connection. Player B's connection terminates at player A's
 * integrated server, and when that process ends there is nothing on the other end of the socket —
 * {@code NoderaContinuity} then logs {@code the host's connection ended}, fetches the archive and re-hosts.
 * So the bar is expected to fail on today's build, and M6 measures the outage rather than only
 * naming it: how long B was out, whether it recovered, and whether the rest of the network noticed.
 *
 * <p>The verdict is taken in M8, not in M6, so that M7's host churn — the half nobody had ever
 * measured — still runs. A stage that failed the whole run on a known gap would stop the scenario
 * before the questions it exists to answer.
 *
 * <p><b>Two things this stage got wrong before, both worth stating.</b> It read player B's ORIGINAL
 * log file after five churn cycles had moved B to new ones, so "was player B disconnected" was
 * asked of a file nothing was writing and answered "no" every time. And the harness's stop returned
 * as soon as the departure was requested, so the window was measured while player A was still
 * serving. Between them the stage reported a confident PASS for a property it had not observed. The
 * lesson is in {@code HostWorldSupport.awaitRunJvmsGone}, which now fails rather than shrugging.
 *
 * <p>Requires: a device on wireless debugging ({@code ANDROID_SERIAL}), a GUI session, and a stack
 * bound to the LAN — {@code NODERA_SERVICE_BIND_ADDR=0.0.0.0} plus
 * {@code NODERA_SERVICE_ADVERTISE_ADDR=<this machine>} and the matching {@code NODERA_P2P_*} pair,
 * exported before the run starts.
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs.
 */
public final class MobileContinuityScenario implements Scenario {

    /** ModDevGradle run tokens — the only reliable way to address one client's game JVM. */
    private static final String HOST_RUN = "clientHostRunProgramArgs";
    private static final String JOINER_RUN = "clientJoinRunProgramArgs";

    /** How many join/leave cycles M5 drives for the JOINER; matches {@code ChurnScenario}. */
    private static final int CYCLES = 5;

    /**
     * How many times the HOST leaves and returns in M7.
     *
     * <p>The joiner's churn is the cheap direction. This one hands the world to another player and
     * takes it back, five times over, which is the case no existing scenario drives more than once.
     */
    private static final int HOST_CYCLES = 5;

    /** How long player B must keep playing after player A's game exits, per the test plan. */
    private static final Duration SURVIVAL_WINDOW = Duration.ofSeconds(90);

    /** The most worlds a state document is scanned for; a run has one and a peer holds few. */
    private static final int MAX_WORLD_ROWS = 32;

    private AndroidDevice phone = new AndroidDevice();

    @Override
    public String id() {
        return "mobile-continuity";
    }

    @Override
    public String title() {
        return "a phone peer holds a live world's content while two desktop players come and go";
    }

    @Override
    public Set<String> tags() {
        return Set.of("hardware", "android", "live", "continuity");
    }

    @Override
    public Requirements requirements() {
        // Two full Minecraft clients and two companion apps on one machine; the phone costs this
        // machine nothing but is the reason the run needs a device at all.
        return Requirements.liveDevice(6);
    }

    @Override
    public Topology topology() {
        // No spare peer: the phone is the third session member. See the class note.
        return Topology.standard().withSparePeers(0);
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        LiveStack stack = ctx.stack();
        // The runner may execute this instance more than once in a queue, so nothing survives on
        // the scenario itself; one-element holders carry state between stages.
        phone = new AndroidDevice();
        String[] hostIp = new String[1];
        String[] phoneNode = new String[1];
        String[] worldId = new String[1];
        long[] phoneBaselineReceived = new long[1];
        long[] phoneBaselineMaintained = new long[1];
        HostWorldSupport.HostedPair[] players = new HostWorldSupport.HostedPair[1];
        // Whichever of player B's clients is in the world right now: the original one until the
        // first churn cycle replaces it, then each cycle's own.
        ManagedProcess[] joiner = new ManagedProcess[1];
        // HostDev's returning client, per M7 cycle.
        ManagedProcess[] hostClient = new ManagedProcess[1];
        // What M6 observed, judged in M8 so the host churn between them still runs.
        boolean[] hostDeparture = new boolean[1];
        long[] hostSeeding = new long[1];
        List<ManagedProcess> launchers = new ArrayList<>();

        LogWatcher hostLog = ctx.log("client-host.log");
        Path hostLogFile = stack.logDir().resolve("client-host.log");

        // Player B's log MOVES. Every churn cycle starts a new client writing to its own file, so a
        // watcher bound once to "client-join.log" goes silent after the first cycle and every later
        // read of it returns whatever the original client had written and nothing more.
        //
        // That is not a small bug. M6 asked that stale file whether player B had been disconnected;
        // a file nothing writes can never gain the line, so the answer was always "no" and the
        // stage passed without measuring anything. M7 then waited on the same file for player B to
        // announce it was hosting, and burned its full timeout every run. Both read as product
        // behaviour and neither was.
        //
        // `joinerLog` is therefore a holder, re-pointed by `useJoinerLog` every time the joiner is
        // replaced, and nothing below may capture it into a local.
        LogWatcher[] joinerLog = {ctx.log("client-join.log")};
        Path[] joinerLogFile = {stack.logDir().resolve("client-join.log")};

        // ---------------------------------------------------------------------------
        // M0 — three nodes, three routes, two launchers
        // ---------------------------------------------------------------------------
        ctx.stage("M0", "two desktop workers, a phone worker and two attached launchers are up",
                () -> {
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

            // The stack must be reachable from off this box before anything else is attempted: on
            // loopback defaults every assertion below fails in a way that looks like a phone fault.
            if (!AndroidDevice.isReachable(hostIp[0], ctx.topology().trackerPort())) {
                ctx.skip("the tracker on " + hostIp[0] + ":" + ctx.topology().trackerPort()
                        + " cannot be reached from off this machine. Export "
                        + "NODERA_SERVICE_BIND_ADDR=0.0.0.0, NODERA_SERVICE_ADVERTISE_ADDR="
                        + hostIp[0] + ", NODERA_P2P_BIND_ADDR=0.0.0.0 and "
                        + "NODERA_P2P_ADVERTISE_ADDR=" + hostIp[0] + " before starting the run.");
            }

            // Ask whether the DEVICE is answering before concluding anything about the app. A
            // wireless adb link drops when the handset sleeps, and every query then returns blank —
            // so "the app is not installed" was reported about a phone that had it installed and
            // was merely unreachable, sending the reader to rebuild an APK that was already there.
            String model = phone.adb(List.of("shell", "getprop", "ro.product.model")).trim();
            if (model.isBlank()) {
                ctx.skip("the device at " + serial + " is not answering — wireless debugging drops "
                        + "when the handset sleeps. Reconnect it (adb connect " + serial + ") and "
                        + "keep the screen awake for the run.");
            }
            String installed = phone.versionOnDevice();
            ctx.check(!installed.isBlank(), AndroidDevice.PACKAGE + " is not installed on "
                    + serial + " (the device answers: " + model + ")"
                    + " — build and install it (scripts/android-apk.sh)");
            ctx.note("phone      " + serial + " running app version " + installed);
            ctx.note("host       " + hostIp[0]);

            ctx.check(phone.restartApp() == 0, "the app would not start");
            // The worker stages an 11 MB dex on first run and then boots a JVM-worth of services.
            // Twenty attempts rather than ninety because each one now costs a bounded control read
            // with real margin (AndroidDevice.verb), not a one-second poll — this is minutes of
            // patience either way, spent waiting rather than spent retrying.
            // Ready means BOTH an identity and a route. The worker answers its control socket, and
            // therefore names its node id, before its P2P transport has finished binding — so a
            // loop that waits only for the id hands the next line a node with an empty route and
            // fails it for "advertising no route", which reads as a phone with no network rather
            // than as a phone that was asked half a second early.
            String phoneRoute = "";
            for (int waited = 0; waited < 20 && phoneRoute.isEmpty(); waited++) {
                if (phone.verb("NODERA-PROBE 2").startsWith("NODERA-OK")) {
                    Object snapshot = phone.stateDocument();
                    String id = ServerJson.text(snapshot, "node_id").orElse("");
                    String route = ServerJson.text(snapshot, "self_route").orElse("");
                    if (!id.isBlank() && !route.isBlank()) {
                        phoneNode[0] = id;
                        phoneRoute = route;
                    }
                }
            }
            if (phoneNode[0] == null || phoneRoute.isEmpty()) {
                write(stack.logDir().resolve("android-logcat.log"), phone.logcat());
                ctx.fail("the phone's worker never came up with both an identity and a route — see "
                        + stack.logDir().resolve("android-logcat.log"));
            }

            // Three nodes, three distinct identities, three routes nobody else can be reached at.
            Map<String, String> routes = new LinkedHashMap<>();
            routes.put(phoneNode[0], phoneRoute);
            for (PlayerRole role : List.of(PlayerRole.PLAYER_ONE, PlayerRole.PLAYER_TWO)) {
                String state = ctx.worker(role).state();
                routes.put(field(state, "node_id"), field(state, "self_route"));
            }
            ctx.check(routes.size() == 3, "expected three distinct node ids, got " + routes.keySet());
            routes.forEach((node, route) -> {
                ctx.check(!route.isBlank(), node + " advertises no route at all");
                ctx.check(!route.startsWith("127.0.0.1:"), node + " advertises " + route
                        + " — loopback, so no other machine can dial it");
                ctx.note("node       " + node + " at " + route);
            });

            // The launchers. Each attaches to its own worker; neither spawns one.
            for (int index = 0; index < ctx.topology().workers(); index++) {
                Optional<ManagedProcess> app = stack.startCompanionApp(index);
                app.ifPresent(launchers::add);
            }
            if (launchers.size() < ctx.topology().workers()) {
                ctx.note("the companion app is not built at " + stack.paths().companionApp()
                        + " — the run continues without launchers attached");
            } else {
                // Evidence, not liveness. The launcher is a GTK application that hands off to
                // another process during start-up, so the handle this harness spawned is legitimately
                // gone seconds later while the window is open and attached — "exited immediately" was
                // this scenario mis-reading a healthy launcher. What it actually has to prove is that
                // each one attached to the running worker rather than supervising one of its own,
                // and the app says so itself on its first line.
                ctx.settle(Duration.ofSeconds(8));
                for (ManagedProcess app : launchers) {
                    LogWatcher appLog = ctx.log(app.name() + ".log");
                    ctx.check(appLog.contains("attach mode"), app.name() + " never reported "
                            + "attach mode — it may be supervising a worker of its own, which "
                            + "would put a second peer on this run's control port (see "
                            + appLog.file() + ")");
                }
                ctx.note("launchers  " + launchers.size() + " attached in attach mode");
            }
        });

        // ---------------------------------------------------------------------------
        // M1 — the phone and the desktops see each other, and bytes move
        // ---------------------------------------------------------------------------
        ctx.stage("M1", "the phone and both desktop workers list each other and exchange bytes",
                () -> {
            // One read, several fields. Two reads a dozen seconds apart are two different moments,
            // and a baseline taken across two of them is a baseline nothing can be compared to.
            String baseline = phone.state();
            phoneBaselineReceived[0] = ServerJson.number(parse(baseline), "total_received_bytes");
            phoneBaselineMaintained[0] = ServerJson.number(parse(baseline), "maintained_bytes");
            write(stack.resultsDir().resolve("android-state-baseline.json"), baseline);
            ctx.note("baseline   phone has received " + phoneBaselineReceived[0] + " bytes");

            // Point the phone at THIS run's tracker rather than trusting a compiled-in default.
            //
            // The assertion is that THIS tracker is in the phone's list and answering — not that
            // "some tracker is reachable", which is what it used to check. That weaker form passes
            // on a phone still pointed at the production tracker and a dead 10.0.0.101 default: one
            // of them answers, the check goes green, and the world this run just published is on
            // neither of them. The stage then proves nothing and the failure surfaces two stages
            // later as "the phone did not replicate", which is the wrong place to look.
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

            // The phone dials OUT — the direction that works without forwarding a port to a handset.
            String route = hostIp[0] + ":" + ctx.topology().workerP2pPort(0);
            String reply = phone.verb("NODERA-MESH 2 " + route);
            ctx.check(reply.startsWith("NODERA-OK"),
                    "the phone refused to join the mesh at " + route + ": "
                            + (reply.isBlank() ? "no reply" : reply));

            long received = 0;
            int peers = 0;
            for (int waited = 0; waited < 12; waited++) {
                Object snapshot = phone.stateDocument();
                received = ServerJson.number(snapshot, "total_received_bytes");
                peers = peerCount(snapshot);
                if (received > phoneBaselineReceived[0] && peers > 0) {
                    break;
                }
            }
            ctx.check(peers > 0, "the phone joined the mesh but sees no peers");
            ctx.check(received > phoneBaselineReceived[0], "the phone's received-byte counter did "
                    + "not move (" + phoneBaselineReceived[0] + " -> " + received + ")");
            ctx.note("the phone RECEIVED " + (received - phoneBaselineReceived[0])
                    + " bytes from the desktop peers, and sees " + peers + " peer(s)");

            // And the other direction, per worker. A NAT can make traffic flow one way only, and a
            // single "connected" would hide it — so this is one assertion per desktop node.
            for (PlayerRole role : List.of(PlayerRole.PLAYER_ONE, PlayerRole.PLAYER_TWO)) {
                boolean sees = false;
                for (int waited = 0; waited < 30 && !sees; waited++) {
                    sees = ctx.worker(role).state().contains(phoneNode[0]);
                    if (!sees) {
                        ctx.settle(Duration.ofSeconds(2));
                    }
                }
                ctx.check(sees, "worker " + role.cliName() + " never listed the phone "
                        + phoneNode[0] + " in its own peer view");
            }

            // The half nothing on the phone can fabricate: a query issued by this machine.
            queryTracker(ctx, hostIp[0], "--services").ifPresent(output ->
                    HostWorldSupport.transcript(ctx, "nodera-query.txt",
                            "== services (M1) ==\n" + output));
        });

        // ---------------------------------------------------------------------------
        // M2 — the world
        // ---------------------------------------------------------------------------
        ctx.stage("M2", "player A opens the shared world and player B joins it", () -> {
            Path config = HostWorldSupport.stagedWorld(ctx);
            // The drive is what makes M4 possible: it walks both players across region boundaries
            // instead of leaving them standing in spawn.
            HostWorldSupport.setHostConfig(config, "debug", "regionDrive", "true");
            HostWorldSupport.setHostConfig(config, "entity", "mobCaptureDimensions",
                    "[\"minecraft:overworld\"]");
            // Evidence must be produced by THIS run; a previous run's line would satisfy the wait.
            Files.deleteIfExists(stack.paths().gameLog("run-host"));

            players[0] = HostWorldSupport.hostedTwoPlayers(ctx);
            joiner[0] = players[0].joiner();
            hostLog.await("Nodera: sharing world", Duration.ofSeconds(600));
            hostLog.await("world archive seeded to the worker", Duration.ofSeconds(180));
            ctx.check(hostLog.contains("JoinerDev joined the game"),
                    "the host never saw player B join");

            String hostState = ctx.worker(PlayerRole.PLAYER_ONE).state();
            ctx.checkAbsent(hostState, "\"connected_worlds\":[]",
                    "the host worker is not listing the world it just shared");
            ctx.checkAbsent(hostState, "\"maintained_pieces\":0,",
                    "the host worker reports zero maintained pieces");
            worldId[0] = ServerJson.tryParse(hostState)
                    .flatMap(document -> ServerJson.text(document, "connected_worlds.0.world_id"))
                    .orElse("");
            ctx.check(!worldId[0].isBlank(), "the host worker named no world id");
            ctx.note("world      " + worldId[0]);

            // The joiner's own worker must converge on the same world, by id, with content in it.
            ControlClient joinerWorker = ctx.worker(PlayerRole.PLAYER_TWO);
            long held = 0;
            for (int waited = 0; waited < 60 && held == 0; waited++) {
                held = worldNumber(joinerWorker.state(), worldId[0], "pieces_held");
                if (held == 0) {
                    ctx.settle(Duration.ofSeconds(5));
                }
            }
            ctx.check(held > 0, "player B's worker never fetched a piece of " + worldId[0]);
            ctx.note("joiner worker holds " + held + " piece(s) of the world");

            queryTracker(ctx, hostIp[0], worldId[0]).ifPresent(output -> {
                HostWorldSupport.transcript(ctx, "nodera-query.txt",
                        "== world " + worldId[0] + " (M2) ==\n" + output);
                ctx.check(!output.isBlank(), "the tracker returned nothing for " + worldId[0]);
            });
            record(ctx, "M2", phoneNode[0], worldId[0]);
        });

        // ---------------------------------------------------------------------------
        // M3 — the phone takes a share of the world
        // ---------------------------------------------------------------------------
        ctx.stage("M3", "the phone replicates the world's archive without hosting anything", () -> {
            // WorldReplicationService sweeps at 15 s after start and every 300 s thereafter, and
            // adopts at most two worlds per sweep — so this waits across two sweeps, not one.
            long held = 0;
            long pieces = 0;
            String row = "";
            for (int waited = 0; waited < 40 && held == 0; waited++) {
                // Re-assert the run's tracker on every pass. The app reverts it within seconds
                // (M1's failure text), and re-configuring the lane restarts its sweeper — so each
                // push buys one sweep that can actually see this run's world directory. Without
                // this the stage is a race the phone wins or loses at random: the same build
                // replicated 119 of 119 pieces on one run and reported "2 tracker(s) list no
                // worlds" on the next.
                pushLanTracker(ctx, hostIp[0]);
                String state = phone.state();
                row = worldRowText(state, worldId[0]);
                held = worldNumber(state, worldId[0], "pieces_held");
                pieces = worldNumber(state, worldId[0], "piece_count");
                if (held == 0) {
                    ctx.settle(Duration.ofSeconds(10));
                }
            }
            ctx.check(pieces > 0, "the phone's row for " + worldId[0]
                    + " reports no pieces at all: " + row);
            ctx.check(held > 0, "the phone never fetched a piece of " + worldId[0]
                    + " within two replication sweeps: " + row);
            ctx.note("the phone holds " + held + " of " + pieces + " piece(s)");

            // What the phone must NOT claim. A replicating peer is not an owner and not a player,
            // and a row that says otherwise is what made the dashboard show a phone "in" a world it
            // could not possibly be playing.
            ctx.checkContains(row, "\"seeding\":true", "the phone holds pieces but is not seeding");
            ctx.checkContains(row, "\"owned\":false", "the phone claims to own " + worldId[0]);
            ctx.checkContains(row, "\"connected\":false",
                    "the phone claims a live game connection to " + worldId[0]);

            long maintained = phoneNumber("maintained_bytes");
            ctx.check(maintained > phoneBaselineMaintained[0], "the phone's maintained_bytes did "
                    + "not grow (" + phoneBaselineMaintained[0] + " -> " + maintained + ")");
            record(ctx, "M3", phoneNode[0], worldId[0]);
        });

        // ---------------------------------------------------------------------------
        // M4 — regions
        // ---------------------------------------------------------------------------
        ctx.stage("M4", "the players cross region boundaries and every node's region evidence is "
                + "compared", () -> {
            // A build with the validation lane compiled off has no committees, no assignments and
            // no commits — on ANY node. Saying so is the honest outcome; asserting zeroes against
            // zeroes and passing is not.
            if (hostLog.contains("runs WITHOUT deterministic region validation")) {
                ctx.skip("this build has ValidationLane.DETERMINISTIC_VALIDATION = false, so no "
                        + "node — phone or desktop — can be assigned a region. Flip that constant "
                        + "and rebuild the worker, the mod AND the APK to run this stage.");
            }
            // Re-join the mesh now that there IS a world session to join. M1's join happened
            // before player A opened anything, so it attached the phone to a peer, not to a
            // world's membership — and resident seats are handed out from the membership the host
            // sees when it bootstraps the lane. Without this the phone is a peer that exchanges
            // bytes and is never considered for a seat, which reads exactly like "the phone was
            // passed over".
            String reply = phone.verb("NODERA-MESH 2 " + hostIp[0] + ":"
                    + ctx.topology().workerP2pPort(0));
            ctx.check(reply.startsWith("NODERA-OK"),
                    "the phone refused to re-join the mesh for the hosted world: " + reply);

            HostWorldSupport.awaitMemberNodes(ctx, hostLog, "M4");
            joinerLog[0].await("client validation lane active", Duration.ofSeconds(180));
            hostLog.await("DRIVE step 1", Duration.ofSeconds(300));
            hostLog.await("DRIVE step 2", Duration.ofSeconds(300));
            hostLog.await("REGION: ", Duration.ofSeconds(120));
            HostWorldSupport.readLines(hostLogFile).stream()
                    .filter(line -> line.contains("REGION: ") || line.contains("DRIVE "))
                    .forEach(line -> HostWorldSupport.transcript(ctx, "region-evidence.log", line));

            long hostRegions = regionCount(ctx.worker(PlayerRole.PLAYER_ONE).state());
            long joinRegions = regionCount(ctx.worker(PlayerRole.PLAYER_TWO).state());
            // One read of the phone, used for both the count and its roots below.
            Object phoneState = phone.stateDocument();
            long phoneRegions = ServerJson.number(phoneState, "validation.active_regions");
            ctx.note("active regions — host worker " + hostRegions + ", joiner worker "
                    + joinRegions + ", phone " + phoneRegions);

            // Every resident must be seated — the phone on the same terms as the two desktop
            // workers, which is the question this scenario exists to answer.
            ctx.check(hostRegions > 0, "player A's worker was never assigned a region");
            ctx.check(phoneRegions > 0, "the phone was never assigned a region, while the desktop "
                    + "workers hold " + hostRegions + " and " + joinRegions
                    + " — it is a resident of the same session and was passed over");

            // Player B's worker is RECORDED, not asserted, and the distinction is deliberate.
            // Observed here across runs: 42 seats on one, 0 on the next, with the host counting
            // three residents both times — and its client logging that its worker "joined the
            // world session via 10.200.1.1:25566", this machine's VPN interface rather than the
            // LAN every other node was pinned to. Seats addressed to an interface the swarm cannot
            // reach are seats that never arrive. That is a real defect in how the mod's peer
            // service chooses an address, it is not a property of the phone, and failing this
            // scenario on it would stop the run before the questions it exists to answer — whether
            // players can leave and rejoin, and what happens when the host departs.
            if (joinRegions == 0) {
                ctx.note("FINDING: player B's worker holds NO regions while the host counts it "
                        + "among the residents — check which interface its session bound to");
                HostWorldSupport.transcript(ctx, "findings.md", "- player B's worker was seated by "
                        + "the host's plan but reports 0 active regions; its client bound the world "
                        + "session to a non-LAN interface");
            }

            // What is deliberately NOT asserted: that the three counts match. Leftover validator
            // seats go to residents in NODE ID ORDER, capped per region at maxCommitteeSize - 1
            // (ViewOwnershipPlanner), so a resident earlier in that order is seated in more regions
            // — by design, and the order is not a property of the device. The workers mint a fresh
            // identity every run while the phone's persists, so the phone's share moves run to run
            // for reasons that say nothing about phones. Observed here: 38 / 33 / 25.
            ctx.note("seat distribution is NodeId-ordered, so these counts are expected to differ");

            // The roots are what make the count mean something: a node reporting active regions it
            // has no root for is reporting an intention, not a replica.
            long phoneRoots = ServerJson.at(phoneState, "validation.region_roots")
                    .map(roots -> ServerJson.keys(roots).size()).orElse(0);
            ctx.check(phoneRoots == phoneRegions, "the phone reports " + phoneRegions
                    + " active region(s) but " + phoneRoots + " region root(s) — it is seated on "
                    + "regions it holds no state for");
            record(ctx, "M4", phoneNode[0], worldId[0]);
        });

        // ---------------------------------------------------------------------------
        // M5 — player B churns, in the same world
        // ---------------------------------------------------------------------------
        for (int cycle = 1; cycle <= CYCLES; cycle++) {
            int index = cycle;
            ctx.stage("M5." + cycle, "cycle " + cycle + "/" + CYCLES + ": player B leaves the "
                    + "world and comes back to it", () -> {
                int mark = hostLog.lineCount();
                long phoneBefore = phoneNumber("total_received_bytes");

                // The wrapper of whichever client is currently in the world: player B's original
                // one on the first cycle, the one the previous cycle started after that. Both
                // halves matter — the game JVM is a child of the GRADLE DAEMON, so the run token is
                // what actually kills it, and stopping the wrapper is what stops the Gradle process
                // this stack started from outliving the run.
                HostWorldSupport.stopClient(joiner[0], JOINER_RUN);
                hostLog.awaitAfter("JoinerDev left the game", Duration.ofSeconds(120), mark);

                // Player A must be unaffected by player B's departure, every single time — and the
                // evidence for "unaffected" is player A's world still TICKING, not the presence of
                // a process. A game that is alive and wedged is not a player still playing, and a
                // process check cannot tell the two apart.
                //
                // Both are recorded because they answered differently the first time this ran: the
                // host's log stopped one second after the joiner was stopped, which is either the
                // host genuinely dying with its joiner — a real defect no scenario would have
                // caught, since ChurnScenario drives the same departure and asserts nothing about
                // the host — or this harness taking both clients down together. The note says which
                // of the two a reader is looking at.
                ctx.check(HostWorldSupport.runJvmAlive(HOST_RUN),
                        "player A's game died when player B left, cycle " + index);

                // The real proof that player A is still PLAYING, not merely still running, is the
                // line below: player B's rejoin has to appear in player A's OWN log, which only a
                // ticking server that is accepting connections can write.
                //
                // What this deliberately does not do is watch for the host log to gain lines in a
                // fixed window. An idle Minecraft server writes nothing for tens of seconds at a
                // time, so silence there means "nothing happened", not "the world stopped" — and it
                // failed cycle 3 of an otherwise healthy run for exactly that reason.
                int rejoinMark = hostLog.lineCount();
                String cycleLog = "client-joiner-" + index + ".log";
                joiner[0] = stack.startClient("runClientJoin", cycleLog);
                // Follow player B into its new log, or every later stage reads a dead file.
                joinerLog[0] = ctx.log(cycleLog);
                joinerLogFile[0] = stack.logDir().resolve(cycleLog);
                hostLog.awaitAfter("JoinerDev joined the game", Duration.ofSeconds(600),
                        rejoinMark);
                ctx.note("cycle " + index + ": player A accepted player B back into the world");
                ctx.settle(Duration.ofSeconds(10));

                // Nothing the churn touches may reset: the workers never restarted, so their
                // counters are monotonic, and a drop means a worker died and came back unnoticed.
                HostWorldSupport.probeWorkers(ctx);
                requirePhoneAlive(ctx, "cycle " + index);
                Object snapshot = phone.stateDocument();
                long phoneAfter = ServerJson.number(snapshot, "total_received_bytes");
                ctx.check(phoneAfter >= phoneBefore, "the phone's received-byte counter went "
                        + "BACKWARDS across cycle " + index + " (" + phoneBefore + " -> "
                        + phoneAfter + ") — its worker restarted");
                ctx.check(peerCount(snapshot) > 0,
                        "the phone lost every peer during cycle " + index);
                record(ctx, "M5." + index, phoneNode[0], worldId[0]);
            });
        }

        ctx.stage("M5c", "no errors accumulated across the churn cycles", () -> {
            List<String> offenders = new ArrayList<>();
            List<Path> audited = new ArrayList<>(List.of(hostLogFile));
            for (int i = 0; i < ctx.topology().workers(); i++) {
                audited.add(stack.logDir().resolve(
                        "worker-" + ctx.topology().workerRole(i).cliName() + ".log"));
            }
            for (Path file : audited) {
                HostWorldSupport.auditErrors(file, 0)
                        .forEach(line -> offenders.add(file.getFileName() + ": " + line));
            }
            ctx.check(offenders.isEmpty(), "error lines found across " + CYCLES + " churn cycles: "
                    + String.join(" | ", offenders));
        });

        // ---------------------------------------------------------------------------
        // M6 — player A's game exits. THE assertion.
        // ---------------------------------------------------------------------------
        ctx.stage("M6", "player A's game exits and player B keeps playing without interruption",
                () -> {
            int mark = joinerLog[0].lineCount();
            long phoneBefore = phoneNumber("total_received_bytes");
            Instant departed = Instant.now();

            // Only the GAME goes. Player A's worker and its launcher deliberately stay up — the
            // world's data has to outlive the process that published it.
            HostWorldSupport.stopClient(players[0].host(), HOST_RUN);
            ctx.note("player A's game exited at " + departed);

            // Everything that is NOT the pass bar, measured first, so a failure below still leaves
            // a report that says what the network did.
            ctx.settle(SURVIVAL_WINDOW);
            ControlClient hostWorker = ctx.worker(PlayerRole.PLAYER_ONE);
            ctx.check(hostWorker.isUp(), "player A's WORKER died with its game — the world's "
                    + "content went with the process that published it");
            long maintained = ServerJson.number(parse(hostWorker.state()), "maintained_pieces");
            ctx.check(maintained > 0, "player A's worker survived but is maintaining no pieces");
            ctx.check(phoneNumber("total_received_bytes") >= phoneBefore,
                    "the phone's counter reset while the host was away");
            queryTracker(ctx, hostIp[0], worldId[0]).ifPresent(output ->
                    HostWorldSupport.transcript(ctx, "nodera-query.txt",
                            "== world " + worldId[0] + " after A's game exited (M6) ==\n" + output));
            record(ctx, "M6", phoneNode[0], worldId[0]);

            // The recovery path, recorded whether or not it is wanted: how long B was out, and
            // whether it came back at all. This is the size of the gap the assertion below reports.
            boolean lost = HostWorldSupport.containsAfter(joinerLogFile[0], mark,
                    "Nodera: the host's connection ended");
            if (lost) {
                boolean restored = joinerLog[0].pollFor("restored to saves/",
                        Duration.ofSeconds(300), mark);
                boolean rehosted = joinerLog[0].pollFor("Nodera: sharing world",
                        Duration.ofSeconds(300), mark);
                Duration outage = Duration.between(departed, Instant.now());
                HostWorldSupport.transcript(ctx, "timeline.md", "| M6 | player B lost the host "
                        + "connection | recovered=" + (restored && rehosted) + " | outage≈"
                        + outage.toSeconds() + "s |");
                ctx.note("player B lost the connection; restored=" + restored + ", re-hosted="
                        + rehosted + ", out for about " + outage.toSeconds() + "s");
            }

            // The verdict on that is deliberately NOT taken here — see M8. Reporting it as a
            // failure at this point would stop the run before the host churn below, which is the
            // half nobody has ever measured.
            hostDeparture[0] = lost;
            hostSeeding[0] = maintained;
        });

        // ---------------------------------------------------------------------------
        // M7 — the HOST leaves and comes back, over and over
        //
        // M5 churns the joiner, which is the easy direction: the world stays where it is and a
        // guest reconnects to it. This is the other one. Player A OWNS this world — it authored it,
        // it certified its genesis, its key signs it — and when it goes, the world has to be
        // carried by somebody else and then handed back. Every existing scenario departs the host
        // exactly once and stops, so "can the author leave and return, repeatedly, to the same
        // world" has never been asked.
        //
        // The cycle drives the author RE-OPENING ITS OWN WORLD, not returning as a guest to
        // somebody else's. An earlier version waited for player B to take the world over first and
        // then had HostDev rejoin it — but B does not take it over: it is disconnected by the
        // departure and stops there, so that wait spent its whole timeout every run and the cycle
        // never executed once. Re-opening the save is also the truer subject: the question is
        // whether the node that authored a world can leave it and come back to THE SAME WORLD,
        // repeatedly, and that is answered without depending on what any other player does.
        // ---------------------------------------------------------------------------
        for (int cycle = 1; cycle <= HOST_CYCLES; cycle++) {
            int index = cycle;
            ctx.stage("M7." + cycle, "cycle " + cycle + "/" + HOST_CYCLES
                    + ": HostDev re-opens the world it authored, then leaves again", () -> {
                // Each re-opened client writes its own log; binding one watcher across cycles is
                // the same mistake that made M6 read a file nothing was writing.
                String reopenLog = "client-host-reopen-" + index + ".log";
                LogWatcher reopened = ctx.log(reopenLog);
                hostClient[0] = stack.startClient("runClientHost", reopenLog);
                reopened.awaitJoin(
                        "game server open for joiners on port " + ctx.topology().gamePort(),
                        ctx.topology().joinTimeout(), stack.logDir().resolve(reopenLog),
                        "cycle " + index + ": HostDev never re-opened the world it authored");
                reopened.await("Nodera: sharing world", Duration.ofSeconds(420));
                ctx.note("cycle " + index + ": HostDev re-opened and re-shared the world");

                // It must be THE SAME world. An author who re-opens and gets a world re-minted
                // under a new id has not come back to their world — they have been handed a copy,
                // and every grant their key ever signed applies to something else.
                String hostState = ctx.worker(PlayerRole.PLAYER_ONE).state();
                ctx.checkContains(hostState, worldId[0], "cycle " + index + ": after re-opening, "
                        + "player A's worker no longer carries " + worldId[0]
                        + " — the world changed identity across the re-open");

                ctx.settle(Duration.ofSeconds(20));

                // Out again, the way a player closes the game — and this time the harness waits for
                // the world to finish saving instead of assuming the request was the departure.
                HostWorldSupport.stopClient(hostClient[0], HOST_RUN);
                boolean saved = HostWorldSupport.awaitCleanShutdown(reopened,
                        Duration.ofSeconds(180));
                ctx.note("cycle " + index + ": the world was written out on exit = " + saved);

                HostWorldSupport.probeWorkers(ctx);
                requirePhoneAlive(ctx, "cycle " + index);
                ctx.check(ctx.worker(PlayerRole.PLAYER_ONE).isUp(), "cycle " + index
                        + ": player A's WORKER died with its game — the world's content went with "
                        + "the process that published it");
                Object snapshot = phone.stateDocument();
                ctx.check(peerCount(snapshot) > 0,
                        "cycle " + index + ": the phone lost every peer over the host's churn");
                record(ctx, "M7." + index, phoneNode[0], worldId[0]);
            });
        }

        // ---------------------------------------------------------------------------
        // M8 — the verdict on the host's departure, taken last on purpose
        // ---------------------------------------------------------------------------
        ctx.stage("M8", "player B was never interrupted by player A's departure", () ->
                // The pass bar as specified: no interruption at all.
                ctx.check(!hostDeparture[0], "player B lost its connection when player A's game "
                        + "exited. Player B's session terminates at player A's INTEGRATED SERVER, "
                        + "so nothing serves the Minecraft protocol once that process is gone: the "
                        + "peer session survives (GatewayElection) and the world's content survives "
                        + "(player A's worker kept seeding " + hostSeeding[0] + " pieces), but the "
                        + "player is disconnected and recovers by re-hosting. Zero-interruption "
                        + "host departure is not implemented."));

        // ---------------------------------------------------------------------------
        // M8b — the same departure without a shutdown
        //
        // Opt-in: it ends with a SIGKILLed world, so it runs last and only when asked.
        // ---------------------------------------------------------------------------
        if (AndroidDevice.flag("NODERA_MOBILE_HARD_KILL")) {
            ctx.stage("M8b", "the host's game is SIGKILLed and its worker keeps the world alive",
                    () -> {
                String hardLog = "client-host-hardkill.log";
                LogWatcher reopened = ctx.log(hardLog);
                stack.startClient("runClientHost", hardLog);
                reopened.awaitJoin(
                        "game server open for joiners on port " + ctx.topology().gamePort(),
                        ctx.topology().joinTimeout(), stack.logDir().resolve(hardLog),
                        "the host never re-opened the world for the hard-kill variant");

                // A real crash: no disconnect packet, no shutdown hook, nothing flushed. The WORKER
                // is deliberately left alive — WorldContinuityIT covers killing that too, headlessly.
                //
                // This is also the first run in which the kill actually kills: while the run token
                // could not be matched, `killRunJvms` was a no-op and this stage exercised a
                // graceful stop, which is the one path it exists NOT to test.
                HostWorldSupport.killRunJvms(HOST_RUN);
                HostWorldSupport.awaitRunJvmsGone(HOST_RUN, Duration.ofSeconds(60));
                ctx.settle(SURVIVAL_WINDOW);

                ctx.check(ctx.worker(PlayerRole.PLAYER_ONE).isUp(),
                        "player A's worker died with the SIGKILLed game");
                long maintained = ServerJson.number(
                        parse(ctx.worker(PlayerRole.PLAYER_ONE).state()), "maintained_pieces");
                ctx.check(maintained > 0, "player A's worker survived the kill but is maintaining "
                        + "no pieces — the world's content did not outlive the crash");
                HostWorldSupport.transcript(ctx, "timeline.md", "| M8b | host game SIGKILLed | "
                        + "worker alive, maintaining " + maintained + " piece(s) |");
            });
        }

        // ---------------------------------------------------------------------------
        // M9 — evidence
        // ---------------------------------------------------------------------------
        ctx.stage("M9", "the evidence bundle is written", () -> {
            write(stack.resultsDir().resolve("android-state-final.json"), phone.state());
            write(stack.logDir().resolve("android-logcat.log"), phone.logcat());
            queryTracker(ctx, hostIp[0], "--services").ifPresent(output ->
                    HostWorldSupport.transcript(ctx, "nodera-query.txt",
                            "== services (M9) ==\n" + output));
            launchers.forEach(app -> app.stop(Duration.ofSeconds(10)));
            stack.collectArtefacts();
            ctx.note("evidence   " + stack.resultsDir());
        });
    }

    // ---------------------------------------------------------------------------------------
    // Reading the three nodes
    // ---------------------------------------------------------------------------------------

    /**
     * Fail with the cause rather than the symptom when the phone has gone quiet.
     *
     * <p>A run reported "the phone lost every peer" and was telling the truth about a phone whose
     * app Android had killed 25 minutes earlier — the worker lives inside it, so the peers went
     * with it. Those are completely different findings: one is a mesh fault, the other is that this
     * device cannot stay on the network for the length of a session (M-2). Asking which costs one
     * adb call, and asking it first is what stops the next reader debugging the wrong layer.
     *
     * @param stage what to name in the failure.
     */
    private void requirePhoneAlive(ScenarioContext ctx, String stage) {
        if (!phone.appIsRunning()) {
            ctx.fail(stage + ": Android killed " + AndroidDevice.PACKAGE + " — the worker runs "
                    + "inside the app, so everything it was holding went with it. That is M-2, not "
                    + "a mesh fault: check the foreground service is declared and running.");
        }
    }

    /** One top-level number out of the phone's state. */
    private long phoneNumber(String field) {
        return phone.stateField(field).map(AndroidDevice::asLong).orElse(0L);
    }

    /**
     * How many peers a node reports.
     *
     * <p>Counted from the {@code peers} array, because the state document has no {@code peer_count}
     * scalar — and {@link ServerJson#number} answers 0 for a field that does not exist, so a
     * mistyped path here would read as "this node is alone" for every node, forever.
     */
    private static int peerCount(Object document) {
        return ServerJson.at(document, "peers")
                .map(value -> value instanceof List<?> list ? list.size() : 0)
                .orElse(0);
    }

    /**
     * Tell the phone's worker which tracker this run is on.
     *
     * <p>Called repeatedly rather than once: the worker applies it immediately and correctly, and
     * the app attached to it re-asserts its own settings document seconds later, which drops any
     * endpoint the app does not itself carry. Until the app treats an external configuration as
     * something other than drift, a caller that pushes once is configuring the phone for about
     * three seconds.
     */
    private void pushLanTracker(ScenarioContext ctx, String hostIp) {
        String config = "{\"network.default_trackers\":[\"tcp://" + hostIp + ":"
                + ctx.topology().trackerPort() + "\"]}";
        phone.verb("NODERA-CONFIG 2 " + Base64.getEncoder()
                .encodeToString(config.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Whether a node's state lists this run's tracker as one it can actually reach. */
    private static boolean tracksLan(Object document, String hostIp, int port) {
        for (int i = 0; i < MAX_WORLD_ROWS; i++) {
            Optional<Object> row = ServerJson.at(document, "trackers." + i);
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

    /** A top-level string field of a worker's state document. */
    private static String field(String state, String path) {
        return ServerJson.tryParse(state)
                .flatMap(document -> ServerJson.text(document, path))
                .orElse("");
    }

    /**
     * A number out of the {@code connected_worlds} row for one world.
     *
     * <p>Scanned by id rather than taken from index 0: a peer that replicates several worlds
     * publishes them in whatever order it holds them, and reading the wrong row is how a run
     * reports another world's piece count as this one's.
     */
    private static long worldNumber(String state, String worldId, String field) {
        return worldRow(state, worldId).map(row -> ServerJson.number(row, field)).orElse(0L);
    }

    /** The rendered {@code connected_worlds} row for one world, for a failure message. */
    private static String worldRowText(String state, String worldId) {
        return worldRow(state, worldId).map(ServerJson::render).orElse("<no row for " + worldId + ">");
    }

    private static Optional<Object> worldRow(String state, String worldId) {
        Optional<Object> document = ServerJson.tryParse(state);
        if (document.isEmpty() || worldId == null || worldId.isBlank()) {
            return Optional.empty();
        }
        for (int i = 0; i < MAX_WORLD_ROWS; i++) {
            Optional<Object> row = ServerJson.at(document.get(), "connected_worlds." + i);
            if (row.isEmpty()) {
                return Optional.empty();
            }
            if (worldId.equals(ServerJson.text(row.get(), "world_id").orElse(""))) {
                return row;
            }
        }
        return Optional.empty();
    }

    /**
     * How many regions a node is actually validating.
     *
     * <p>{@code validation.active_regions} rather than {@code regions_held}: the first is the
     * committee replica this node is running, the second is region CONTENT in its archive, and only
     * a node hosting exactly one world can ever have the second (see {@code CommittedRegionSeeder}).
     * A phone hosts none, so comparing it to the host worker on {@code regions_held} would compare
     * two different questions.
     */
    private static long regionCount(String state) {
        return ServerJson.number(parse(state), "validation.active_regions");
    }

    private static Object parse(String state) {
        return ServerJson.tryParse(state).orElse(Map.of());
    }

    /** One timeline row per node, per stage — the table a reader re-interprets months later. */
    private void record(ScenarioContext ctx, String stage, String phoneNode, String worldId) {
        StringBuilder row = new StringBuilder("| ").append(stage).append(" | ")
                .append(Instant.now()).append(" |");
        for (PlayerRole role : List.of(PlayerRole.PLAYER_ONE, PlayerRole.PLAYER_TWO)) {
            row.append(' ').append(describe(role.cliName(),
                    ctx.worker(role).state(), worldId)).append(" |");
        }
        row.append(' ').append(describe("phone/" + phoneNode, phone.state(), worldId)).append(" |");
        HostWorldSupport.transcript(ctx, "timeline.md", row.toString());
    }

    private static String describe(String who, String state, String worldId) {
        Object document = parse(state);
        return who
                + " peers=" + peerCount(document)
                + " sent=" + ServerJson.number(document, "total_sent_bytes")
                + " recv=" + ServerJson.number(document, "total_received_bytes")
                + " maintained=" + ServerJson.number(document, "maintained_pieces")
                + "/" + ServerJson.number(document, "maintained_bytes") + "B"
                + " held=" + worldNumber(state, worldId, "pieces_held")
                + "/" + worldNumber(state, worldId, "piece_count")
                + " regions=" + worldNumber(state, worldId, "regions_held")
                + " active=" + ServerJson.number(document, "validation.active_regions")
                + " commits=" + ServerJson.number(document, "validation.committee_commits");
    }

    /**
     * A tracker query issued by THIS machine — the one piece of evidence no node under test can
     * fabricate. Empty when the binary has not been built, which is a gap in the evidence and never
     * a failed assertion.
     */
    private static Optional<String> queryTracker(ScenarioContext ctx, String hostIp, String argument) {
        Path binary = ctx.stack().paths().rustRelease().resolve("nodera-query");
        if (!Files.isExecutable(binary)) {
            ctx.note("nodera-query is not built, so the independent tracker check is skipped "
                    + "(cargo build --release -p nodera-tracker --bin nodera-query)");
            return Optional.empty();
        }
        return Optional.of(AndroidDevice.capture(List.of(binary.toString(),
                hostIp + ":" + ctx.topology().trackerPort(), argument)));
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
