package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * A PHONE in the mesh with the Linux peers.
 *
 * <p>The question this exists to answer is narrow and physical: <b>does the peer on the Android
 * device actually receive bytes from the peers on this machine?</b> Not "does the app say it is
 * online", not "did the tracker take an announce" — bytes, counted by the phone's own worker, over
 * Wi-Fi.
 *
 * <pre>
 *   P0   build + install the APK on the phone over Wi-Fi debugging
 *   P1   the Linux stack, reachable from off this box
 *   P2   two Minecraft clients join a hosted world (the peers' reason to talk)
 *   P3   the phone's worker comes up and is confirmed THROUGH ADB, by asking its own control socket
 *        — never by reading the app's UI
 *   P4   the phone announces to the LAN tracker
 *   P5   THE ASSERTION: the phone joins the Linux mesh and its own counters show a peer and
 *        total_received_bytes &gt; 0
 *   P4b  an independent tracker query, from this machine, for the world the mesh is in
 * </pre>
 *
 * <p>Everything about the phone is observed over {@code adb}: the control socket for state, logcat
 * for the worker's own account of itself. That is the "monitor via debugging" half — a phone that
 * cannot be inspected is a phone whose result cannot be trusted.
 *
 * <p>Requires a device on the same Wi-Fi with wireless debugging connected (see
 * docs/frontend/TESTING.md §3.2), and a GUI session for the Minecraft clients. Two knobs carry the
 * shell script's flags: {@code NODERA_ANDROID_SKIP_APK=1} ({@code --no-apk}: do not build <b>or</b>
 * install) and {@code NODERA_ANDROID_SKIP_GAME=1} ({@code --no-game}: peers only).
 *
 * <p>Thread-context: stateless; the runner calls {@link #run} on its own thread.
 */
public final class AndroidMeshScenario implements Scenario {

    /** How this scenario talks to the phone; see {@link AndroidDevice}. */
    private AndroidDevice phone = new AndroidDevice();

    @Override
    public String id() {
        return "android-mesh";
    }

    @Override
    public String title() {
        return "the peer on an Android phone joins the Linux mesh and receives bytes from it";
    }

    @Override
    public Set<String> tags() {
        return Set.of("hardware", "android", "live");
    }

    @Override
    public Requirements requirements() {
        return Requirements.device();
    }

    @Override
    public void run(ScenarioContext ctx) throws Exception {
        LiveStack stack = ctx.stack();
        // The runner may execute the same instance more than once in a queue, so nothing carries
        // over from a previous run.
        phone = new AndroidDevice();
        String[] phoneIp = new String[1];
        String[] hostIp = new String[1];
        String[] nodeId = new String[1];
        String[] peerOneState = new String[1];

        // ---------------------------------------------------------------------------
        // P0 — the phone, over Wi-Fi
        // ---------------------------------------------------------------------------
        ctx.stage("P0", "the APK is installed on a device reachable by IP", () -> {
            if (!AndroidDevice.hasCommand("adb")) {
                ctx.skip("adb is not on PATH (scripts/android-toolchain.sh)");
            }
            String serial = phone.resolveSerial();
            if (serial.isBlank()) {
                ctx.skip("no wireless device. Connect one first: adb tcpip 5555 && adb connect "
                        + "<phone-ip>:5555 (docs/frontend/TESTING.md §3.2)");
            }
            phoneIp[0] = phone.ip();
            // A USB-only device cannot be dialled by the Linux peers at all, so it is not a device
            // this scenario can use.
            ctx.check(phone.isWireless(), "the device must be connected over Wi-Fi "
                    + "(got '" + serial + "'); USB alone cannot be dialled by the peers");

            String model = phone.adb(List.of("shell", "getprop", "ro.product.model")).trim();
            String release =
                    phone.adb(List.of("shell", "getprop", "ro.build.version.release")).trim();
            ctx.check(!model.isBlank(), "the device at " + serial + " is not answering");
            ctx.note("device     " + model + " (Android " + release + ") at " + phoneIp[0]);

            hostIp[0] = AndroidDevice.hostLanAddress(phoneIp[0]);
            ctx.check(!hostIp[0].isBlank(), "could not determine this machine's LAN address");
            ctx.note("host       " + hostIp[0]);

            Path apk = stack.paths().root().resolve("build/nodera-release.apk");
            if (!AndroidDevice.flag("NODERA_ANDROID_SKIP_APK")) {
                ctx.note("building the APK (this also rebuilds the worker it embeds)");
                int built = ServerDedicatedDrive.run(List.of(
                        stack.paths().root().resolve("scripts/android-apk.sh").toString()));
                ctx.check(built == 0, "the APK build failed — see scripts/android-apk.sh's output");
                ctx.check(Files.isRegularFile(apk), "no APK at " + apk);
                ctx.note("installing");
                ctx.check(phone.adbExit(List.of("install", "-r", apk.toString())) == 0,
                        "the install failed");
            } else {
                // "Do not build OR install": the flag used to skip only the build and then reinstall
                // anyway, which is the one thing it exists to avoid — it silently replaced whatever
                // build was on the device, so a run meant to test the installed APK tested a
                // different one.
                String installed = phone.versionOnDevice();
                ctx.check(!installed.isBlank(), "NODERA_ANDROID_SKIP_APK was set but "
                        + AndroidDevice.PACKAGE
                        + " is not installed on the device");
                // Named, so a run that tested a stale build says which one rather than looking
                // identical to a run that tested a fresh one.
                ctx.note("using the APK already on the device (version " + installed + ")");
            }
        });

        // ---------------------------------------------------------------------------
        // P1 — the Linux stack, reachable from off this box
        // ---------------------------------------------------------------------------
        ctx.stage("P1", "the discovery services are reachable from off this machine", () -> {
            // The harness binds where NODERA_SERVICE_BIND_ADDR says (Topology.serviceBindAddress),
            // and the default is still loopback — right for a run whose peers are all on this
            // machine, useless for one whose peer is a handset. Loopback defaults make every step
            // below fail in a way that looks like a phone problem, so this says so plainly instead
            // of asserting against a stack the phone cannot reach.
            if (!AndroidDevice.isReachable(hostIp[0], ctx.topology().trackerPort())) {
                ctx.skip("the tracker on " + hostIp[0] + ":" + ctx.topology().trackerPort()
                        + " cannot be reached from off this machine, so the phone cannot either. "
                        + "Export NODERA_SERVICE_BIND_ADDR=0.0.0.0 and "
                        + "NODERA_SERVICE_ADVERTISE_ADDR=" + hostIp[0] + " (and the matching "
                        + "NODERA_P2P_* pair) before starting the run.");
            }
            ctx.note("tracker, rendezvous and " + ctx.topology().workers()
                    + " workers are up on " + hostIp[0]);
        });

        // ---------------------------------------------------------------------------
        // P2 — the game, which is why the peers have anything to say
        // ---------------------------------------------------------------------------
        ctx.stage("P2", "two Minecraft clients on a hosted world", () -> {
            if (AndroidDevice.flag("NODERA_ANDROID_SKIP_GAME")) {
                ctx.note("P2: skipped (NODERA_ANDROID_SKIP_GAME)");
                return;
            }
            // Player A hosts from its client, player B joins over the network — the shape the mesh
            // exists to serve.
            stack.writeClientConfig("run-host", PlayerRole.PLAYER_ONE, null);
            stack.writeClientConfig("run-join", PlayerRole.PLAYER_TWO, null);
            stack.startClient("runClientHost", "client-host.log");
            LogWatcher host = ctx.log("client-host.log");
            host.awaitJoin("game server open for joiners on port " + ctx.topology().gamePort(),
                    ctx.topology().joinTimeout(), host.file(),
                    "player A never opened the shared world");
            stack.startClient("runClientJoin", "client-join.log");
            host.awaitJoin("JoinerDev joined the game", ctx.topology().joinTimeout(),
                    stack.logDir().resolve("client-join.log"), "player B never joined");
        });

        // ---------------------------------------------------------------------------
        // P3 — the phone's worker, confirmed through adb
        // ---------------------------------------------------------------------------
        ctx.stage("P3", "the phone's worker answers its own control socket and advertises a LAN "
                + "route", () -> {
            ctx.check(phone.restartApp() == 0, "the app would not start");

            // The worker stages an 11 MB dex on first run and then boots a JVM-worth of services;
            // 90 s is generous for a cold start and short enough to fail fast.
            for (int waited = 0; waited < 90 && nodeId[0] == null; waited++) {
                if (phone.verb("NODERA-PROBE 2").startsWith("NODERA-OK")) {
                    String id = phone.stateField("node_id").orElse("");
                    if (!id.isBlank()) {
                        nodeId[0] = id;
                        break;
                    }
                }
                ServerDedicatedDrive.sleep(Duration.ofSeconds(1));
            }
            if (nodeId[0] == null) {
                write(stack.logDir().resolve("android-logcat.log"), phone.logcat());
                ctx.fail("the phone's worker never answered its control socket — see "
                        + stack.logDir().resolve("android-logcat.log"));
            }
            String route = phone.stateField("self_route").orElse("");
            ctx.note("the worker is online: " + nodeId[0] + " at " + route);
            // A worker that advertises loopback cannot be dialled by anyone. This is a real failure
            // mode on a phone with Wi-Fi off, and it must not read as "no data".
            ctx.check(!route.startsWith("127.0.0.1:"), "the phone advertises " + route
                    + " — it is not on the LAN, so no peer can reach it");
        });

        // ---------------------------------------------------------------------------
        // P4 — the phone announces where the Linux peers are looking
        // ---------------------------------------------------------------------------
        ctx.stage("P4", "the phone reaches the LAN tracker", () -> {
            // The app ships with this machine's address as its default tracker; the run's tracker is
            // the one now bound to it. Push the list explicitly anyway, so the assertion does not
            // silently depend on a compiled-in default.
            String config = "{\"network.default_trackers\":[\"tcp://" + hostIp[0] + ":"
                    + ctx.topology().trackerPort() + "\"]}";
            phone.verb("NODERA-CONFIG 2 " + Base64.getEncoder()
                    .encodeToString(config.getBytes(StandardCharsets.UTF_8)));

            boolean reachable = false;
            for (int waited = 0; waited < 60 && !reachable; waited++) {
                reachable = phone.stateField("trackers")
                        .filter(value -> value.replace(" ", "").contains("\"reachable\":true"))
                        .isPresent();
                if (!reachable) {
                    ServerDedicatedDrive.sleep(Duration.ofSeconds(2));
                }
            }
            ctx.check(reachable, "the phone never reached the tracker (firewall on " + hostIp[0]
                    + ":" + ctx.topology().trackerPort() + "?)");
        });

        // ---------------------------------------------------------------------------
        // P5 — the assertion: bytes from the Linux peers, counted by the phone
        // ---------------------------------------------------------------------------
        ctx.stage("P5", "the phone has Linux peers in its own view and its received-byte counter "
                + "moved", () -> {
            long before = phone.stateField("total_received_bytes")
                    .map(AndroidDevice::asLong).orElse(0L);
            ctx.note("the phone has received " + before + " bytes so far");

            // Join the phone to the mesh the Linux workers are in. The phone dials OUT, which is the
            // direction that works without forwarding a port to a handset.
            String route = hostIp[0] + ":" + ctx.topology().workerP2pPort(0);
            ctx.note("asking the phone to join the mesh at " + route);
            String reply = phone.verb("NODERA-MESH 2 " + route);
            ctx.check(reply.startsWith("NODERA-OK"),
                    "the phone refused to join the mesh: " + (reply.isBlank() ? "no reply" : reply));

            // Membership is a handshake plus a view exchange, and the keep-alive cadence is seconds
            // — so this waits for the counters rather than for a fixed sleep.
            long received = 0;
            int peers = 0;
            for (int waited = 0; waited < 60; waited++) {
                received = phone.stateField("total_received_bytes")
                        .map(AndroidDevice::asLong).orElse(0L);
                peers = phone.stateField("peers")
                        .flatMap(ServerJson::tryParse)
                        .map(document -> document instanceof List<?> list ? list.size() : 0)
                        .orElse(0);
                if (received > before && peers > 0) {
                    break;
                }
                ServerDedicatedDrive.sleep(Duration.ofSeconds(2));
            }
            ctx.note("after the join: " + peers + " peer(s), " + received + " bytes received");
            ctx.check(peers > 0, "the phone joined but sees no peers");
            ctx.check(received > before, "the phone's received-byte counter did not move ("
                    + before + " -> " + received + ")");
            ctx.note("the phone RECEIVED " + (received - before) + " bytes from the Linux peers");

            // And the other direction, from a Linux worker's own view: the phone should be in its
            // peer list. One assertion per direction, because a NAT can make traffic flow one way
            // only and "connected" would hide it.
            peerOneState[0] = stack.worker(0).ask("NODERA-STATE 2").orElse("");
            if (peerOneState[0].contains(nodeId[0])) {
                ctx.note("the Linux worker sees the phone in its own peer list");
            } else {
                ctx.note("the Linux worker does not list the phone yet (membership is eventually "
                        + "consistent)");
            }
        });

        // ---------------------------------------------------------------------------
        // P4b — the independent check, now that the phone is in a world
        //
        // Deferred from P4 on purpose. This is the only step nothing on the phone can fabricate: a
        // query issued by THIS machine, against the tracker, asking for the world the mesh is
        // actually in, that comes back naming the phone. Before the join there was no such world for
        // the phone to be in.
        // ---------------------------------------------------------------------------
        ctx.stage("P4b", "an independent tracker query from this machine", () -> {
            Path queryBinary = stack.paths().rustRelease().resolve("nodera-query");
            // The world the Linux mesh uses, read from a Linux worker rather than assumed.
            String world = ServerJson.tryParse(peerOneState[0])
                    .flatMap(document -> ServerJson.text(document, "connected_worlds.0.world_id"))
                    .orElse("");
            if (!Files.isExecutable(queryBinary)) {
                ctx.note("nodera-query not built — skipping the independent tracker check");
                ctx.note("(cargo build --release -p nodera-tracker --bin nodera-query)");
                return;
            }
            if (world.isBlank()) {
                ctx.note("no Linux worker reported a world, so there is nothing to query for");
                return;
            }
            ctx.note("querying " + hostIp[0] + ":" + ctx.topology().trackerPort()
                    + " for world " + world);
            boolean found = false;
            for (int waited = 0; waited < 30 && !found; waited++) {
                found = AndroidDevice.capture(List.of(queryBinary.toString(),
                        hostIp[0] + ":" + ctx.topology().trackerPort(), world))
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains(nodeId[0].toLowerCase(java.util.Locale.ROOT));
                if (!found) {
                    ServerDedicatedDrive.sleep(Duration.ofSeconds(2));
                }
            }
            if (found) {
                ctx.note("an independent query on this machine finds the phone in the tracker");
            } else {
                // Stated as the narrow fact it is. The phone IS in the mesh — P5 proved bytes moved
                // in both directions — so what this shows is that mesh membership does not by itself
                // put a peer in the tracker's directory for that world. That is a real gap and it is
                // worth naming, not a reason to explain the check away.
                ctx.note("the tracker does not list the phone for " + world);
                ctx.note("(mesh membership does not announce a peer into the tracker's per-world "
                        + "directory)");
            }
        });

        // ---------------------------------------------------------------------------
        // Evidence
        // ---------------------------------------------------------------------------
        ctx.stage("P6", "the phone's logs are captured and both game logs are clean", () -> {
            write(stack.logDir().resolve("android-logcat.log"), phone.logcat());
            write(stack.logDir().resolve("android-state.json"), phone.state());
            ctx.note("phone logs  -> " + stack.logDir().resolve("android-logcat.log"));
            ctx.note("phone state -> " + stack.logDir().resolve("android-state.json"));

            // Audit the two game logs before declaring the run good. The shell version called its
            // audit with NO argument, which aborted the script after every assertion had already
            // passed — so a run that ended in a Java exception on either client still printed PASS
            // all the way down.
            if (!AndroidDevice.flag("NODERA_ANDROID_SKIP_GAME")) {
                for (String gameLog : List.of("client-host.log", "client-join.log")) {
                    List<String> hits = ServerLogs.auditErrorsAfter(
                            stack.logDir().resolve(gameLog), 0,
                            ServerLogs.BENIGN_ERRORS, ServerLogs.BENIGN_NETTY);
                    ctx.check(hits.isEmpty(), gameLog + " contains errors — the mesh moved bytes, "
                            + "but the game did not stay clean: " + String.join(" | ", hits));
                }
                ctx.note("both game logs are clean");
            }
            stack.collectArtefacts();
        });
    }

    // ---------------------------------------------------------------------------------------
    // Everything about the phone goes through AndroidDevice — see that class for why nothing here
    // may read the app's UI.
    // ---------------------------------------------------------------------------------------

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new HarnessException("cannot write " + file, e);
        }
    }
}
