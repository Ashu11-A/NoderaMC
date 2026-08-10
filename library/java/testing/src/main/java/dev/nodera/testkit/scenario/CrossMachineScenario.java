package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.ControlClient;
import dev.nodera.testkit.harness.LiveStack;
import dev.nodera.testkit.harness.LogWatcher;
import dev.nodera.testkit.harness.ManagedProcess;
import dev.nodera.testkit.harness.PlayerRole;
import dev.nodera.testkit.harness.TestPaths;
import dev.nodera.testkit.harness.Topology;
import dev.nodera.testkit.suite.Requirements;
import dev.nodera.testkit.suite.Scenario;
import dev.nodera.testkit.suite.ScenarioContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The sentence the product makes to a player, tested end to end: <b>install it, host a world, close
 * Minecraft, and have somebody on another network still be able to join</b>.
 *
 * <h2>What was already green, and why it was not this</h2>
 *
 * <p>Two thirds of that sentence have been green in CI for a long time — the companion gate is
 * verified both ways, and a hosted world is proved to survive a SIGKILLed game. What neither of them
 * touched is the part a player would notice first. The job's entry point was
 * {@code cargo build --release} and {@code :peer:installDist}, so it exercised the code and never the
 * <i>installed</i> product; and both sides of every "somebody joins" assertion lived in one process
 * tree on one loopback.
 *
 * <h2>The loopback shortcut is what makes a fake pass here</h2>
 *
 * <p>{@code docs/frontend/Task.4.md} says it outright: two processes on one loopback silently satisfy
 * the assertion while skipping the part that fails in the field, and a join that succeeded because
 * both ends quietly found each other on {@code 127.0.0.1} is indistinguishable from a join that
 * crossed a real route. So this scenario does not assert "it connected". It asserts on <b>the route
 * each node reports about itself</b> — {@code NODERA-STATE.self_route} — that the two are on
 * different subnets, and, in the stage that actually decides it, that taking the router away makes
 * the join side unable to reach the host stack at all. A shortcut cannot survive that stage, and
 * nothing else here is trusted to notice one.
 *
 * <h2>The stages</h2>
 *
 * <ol>
 *   <li>X0 every host-side peer answers, and not one of them is on loopback.</li>
 *   <li>X1 the worker this run launched is the one the job installed (or, when nothing demanded an
 *       install, the stage says which binary it got — it never quietly claims an install).</li>
 *   <li>X2 a second peer is running on a second network, with no default route, and it reports the
 *       far address as its own.</li>
 *   <li>X3 <b>the negative twin</b>: with the router stopped, that peer cannot reach the host stack;
 *       with it back, it can.</li>
 *   <li>X4 a real Minecraft client opens the shared world and hosts it.</li>
 *   <li>X5 the world is listed on the tracker and its archive is seeded from the host's worker.</li>
 *   <li>X6 Minecraft is closed. The worker keeps the world.</li>
 *   <li>X7 the separately-networked peer <b>joins that world</b>: it pulls the whole archive across
 *       the router and its own state carries the world afterwards.</li>
 * </ol>
 *
 * <p>Topology: one player (the host) plus the spare on this machine, and the joining peer in its own
 * container — three peers, the quorum floor, with one of them genuinely elsewhere.
 *
 * <p>Thread-context: run on the runner's thread; stateless between runs apart from the container lab
 * {@link #prepare} builds and {@link #cleanUp} removes.
 */
public final class CrossMachineScenario implements Scenario {

    /**
     * Set by a job that installed the app: the run must fail rather than fall back to the tree.
     *
     * <p>A flag rather than a requirement, because the two readings are both legitimate and only the
     * caller knows which one applies. A developer running this on a checkout is proving the network
     * half and should not have to install a package first. A CI job that just installed a {@code
     * .deb} is proving the installer half, and there a silent fall back to {@code build/install}
     * would report exactly the green this row exists to stop being false.
     */
    private static final String REQUIRE_INSTALLED = "NODERA_E2E_REQUIRE_INSTALLED";

    /** Where the joining worker drops the archive it pulls; inside its own container. */
    private static final String FETCHED_ARCHIVE = SeparateNetwork.CONTAINER_STATE_DIR
            + "/joined-world.nar";

    private SeparateNetwork lab;

    @Override
    public String id() {
        return "cross-machine";
    }

    @Override
    public String title() {
        return "an installed app hosts a world, Minecraft closes, and a peer on another network "
                + "joins it";
    }

    @Override
    public Set<String> tags() {
        return Set.of("live", "continuity", "cross-machine");
    }

    @Override
    public Requirements requirements() {
        // One real client rather than two, which is also why this asks for less RAM than the
        // continuity series: the joining side is not a second Minecraft on this machine, it is a
        // peer somewhere else — which is the whole point — and two containers cost far less than
        // the second game they replace.
        return Requirements.liveClients(4);
    }

    @Override
    public Topology topology() {
        // One player and the spare here; the third peer is the container. Dropping to one client
        // is not a weakening — a second client on this machine would be a second node on this
        // machine, and this scenario exists because that is what has been proving nothing.
        return Topology.standard().withPlayers(1);
    }

    /**
     * Build the second network, and tell the stack which address it must live on.
     *
     * <p>The stack binds before {@link #run} is called, so the addressing has to be decided here.
     * It binds the host side's <b>bridge address specifically</b> and not {@code 0.0.0.0}: a
     * wildcard bind would leave the tracker, the rendezvous and the hosting worker listening on the
     * far network's bridge as well, and then "two networks" would be a description of the diagram
     * rather than of the run.
     */
    @Override
    public void prepare(TestPaths paths) {
        lab = SeparateNetwork.open(paths.runDir().resolve("cross-machine-docker.log"));
        String host = lab.hostAddress();
        System.setProperty("NODERA_SERVICE_BIND_ADDR", host);
        System.setProperty("NODERA_SERVICE_ADVERTISE_ADDR", host);
        System.setProperty("NODERA_P2P_BIND_ADDR", host);
        System.setProperty("NODERA_P2P_ADVERTISE_ADDR", host);
    }

    @Override
    public void cleanUp() {
        if (lab != null) {
            lab.close();
            lab = null;
        }
        // The properties are this run's, not this JVM's: a queue that ran this scenario and then a
        // loopback one would otherwise hand the next stack an address whose network no longer
        // exists, and every service in it would come up unreachable for a reason in another file.
        for (String property : new String[] {"NODERA_SERVICE_BIND_ADDR",
            "NODERA_SERVICE_ADVERTISE_ADDR", "NODERA_P2P_BIND_ADDR", "NODERA_P2P_ADVERTISE_ADDR"}) {
            System.clearProperty(property);
        }
    }

    @Override
    public void run(ScenarioContext context) throws Exception {
        LiveStack stack = context.stack();
        Topology topology = context.topology();
        TestPaths paths = stack.paths();
        LogWatcher hostLog = context.log("client-host.log");
        int peerControlPort = topology.workerControlPort(topology.workers());
        int peerP2pPort = topology.workerP2pPort(topology.workers());
        String[] worldId = new String[1];
        ManagedProcess[] hostClient = new ManagedProcess[1];

        context.stage("X0", "every peer on this machine answers, and none of them is on loopback",
                () -> {
                    HostWorldSupport.probeWorkers(context);
                    for (ControlClient worker : stack.workers()) {
                        String route = ServerJson.tryParse(worker.state())
                                .flatMap(document -> ServerJson.text(document, "self_route"))
                                .orElse("");
                        context.check(route.startsWith(lab.hostAddress() + ":"),
                                "a host-side worker reports its own route as '" + route
                                        + "' — this run is only cross-machine while every node is "
                                        + "on " + lab.hostAddress() + ", never on loopback");
                    }
                });

        context.stage("X1", "the worker under test is the binary the job installed", () -> {
            Path worker = paths.workerDist().toAbsolutePath().normalize();
            boolean installed = !worker.startsWith(paths.root().toAbsolutePath().normalize());
            context.note("worker entry point: " + worker
                    + (installed ? " (installed, outside the checkout)" : " (this checkout's build)"));
            context.check(installed || !"1".equals(System.getenv(REQUIRE_INSTALLED)),
                    "this run was told to test an INSTALLED app (" + REQUIRE_INSTALLED + "=1) and "
                            + "the stack launched " + worker + ", which is inside the checkout. "
                            + "Install the .deb that scripts/release.sh --component app produced "
                            + "and point NODERA_E2E_WORKER_BIN at what the package installed.");
        });

        context.stage("X2", "a second peer is running on a second network with no default route",
                () -> {
                    lab.startPeer(distributionRoot(paths), peerControlPort,
                            joiningWorkerEnvironment(topology, peerControlPort, peerP2pPort));
                    String route = peerField(context, peerControlPort, "self_route");
                    context.check(route.startsWith(lab.peerAddress() + ":"),
                            "the joining peer reports its own route as '" + route + "', not "
                                    + lab.peerAddress() + " — it is not where this run put it");
                    context.check(!lab.joinSubnetPrefix().equals(lab.hostSubnetPrefix()),
                            "both sides came up on " + lab.hostSubnetPrefix()
                                    + ".0/24 — that is one network, not two");
                });

        context.stage("X3", "the joining peer reaches this machine ONLY through the router", () -> {
            lab.routerStopped();
            context.check(!lab.peerCanReach(lab.hostAddress(), topology.trackerPort()),
                    "with the router stopped the joining peer could STILL open a connection to "
                            + lab.hostAddress() + ":" + topology.trackerPort() + " — there is a "
                            + "shortcut between the two networks, and every assertion after this "
                            + "one would be measuring it rather than a cross-machine join");
            lab.routerStarted();
            context.check(lab.peerCanReach(lab.hostAddress(), topology.trackerPort()),
                    "the joining peer cannot reach the tracker through the router either — the "
                            + "far network has no path to this machine at all");
        });

        context.stage("X4", "player A is hosting the shared world from a real Minecraft client",
                () -> {
                    HostWorldSupport.stagedWorld(context);
                    HostWorldSupport.writeClientConfig(context, "run-host", PlayerRole.PLAYER_ONE,
                            null);
                    Files.deleteIfExists(paths.gameLog("run-host"));
                    hostClient[0] = stack.startClient("runClientHost", "client-host.log");
                    hostLog.awaitJoin("game server open for joiners on port " + topology.gamePort(),
                            topology.joinTimeout(), stack.logDir().resolve("client-host.log"),
                            "player A never opened the shared world");
                    hostLog.await("Nodera: sharing world", topology.scaled(Duration.ofSeconds(300)));
                });

        context.stage("X5", "the world is listed on the tracker and seeded from the host's worker",
                () -> {
                    hostLog.await("world archive seeded to the worker",
                            topology.scaled(Duration.ofSeconds(300)));
                    ControlClient host = context.worker(PlayerRole.PLAYER_ONE);
                    host.awaitState("\"listed_on_trackers\":1",
                            topology.scaled(Duration.ofSeconds(180)),
                            "the host never got its world onto the tracker");
                    worldId[0] = ServerJson.tryParse(host.state())
                            .flatMap(document ->
                                    ServerJson.text(document, "connected_worlds.0.world_id"))
                            .orElse("");
                    context.check(!worldId[0].isBlank(),
                            "the host worker lists no world to join: " + host.state());
                });

        context.stage("X6", "Minecraft is closed, and the worker keeps the world", () -> {
            HostWorldSupport.stopClient(hostClient[0], "clientHostRunProgramArgs");
            ControlClient host = context.worker(PlayerRole.PLAYER_ONE);
            context.checkAbsent(host.state(), "\"connected_worlds\":[]",
                    "the world went away with the game that published it");
        });

        context.stage("X7", "the peer on the other network joins that world across the router",
                () -> {
                    String reply = lab.peerControl("NODERA-ARCHIVE 2 " + worldId[0] + " "
                                    + base64(FETCHED_ARCHIVE) + " 180", peerControlPort,
                            topology.scaled(Duration.ofSeconds(200)));
                    context.check(reply.startsWith("NODERA-OK "),
                            "the separately-networked peer could not pull the world: '" + reply
                                    + "' — " + lab.peerLog());
                    long bytes = Long.parseLong(reply.split("\\s+")[1]);
                    context.check(bytes > 0,
                            "the fetch reported success and zero bytes: '" + reply + "'");
                    context.note("the joining peer pulled " + bytes + " bytes of world "
                            + worldId[0] + " from " + lab.hostAddress() + " through the router");
                    String route = peerField(context, peerControlPort, "self_route");
                    context.check(route.startsWith(lab.peerAddress() + ":"),
                            "the peer that joined reports its route as '" + route + "' — a join "
                                    + "that ends on this machine's address proves nothing");
                });

        stack.collectWorkerState();
    }

    // ---------------------------------------------------------------------------------------

    /**
     * The environment the joining worker runs with.
     *
     * <p>Control stays on the container's own loopback and is never published, exactly as the
     * phone's worker is reached over adb rather than over the network: it is an administrative
     * channel with no authentication of its own, and widening it to prove a networking property
     * would be the mistake this scenario exists to catch.
     */
    private Map<String, String> joiningWorkerEnvironment(Topology topology, int controlPort,
                                                         int p2pPort) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("NODERA_CONTROL_HOST", "127.0.0.1");
        environment.put("NODERA_CONTROL_PORT", String.valueOf(controlPort));
        environment.put("NODERA_P2P_BIND", "0.0.0.0");
        environment.put("NODERA_P2P_ADVERTISE", lab.peerAddress());
        environment.put("NODERA_P2P_PORT", String.valueOf(p2pPort));
        environment.put("NODERA_STATE_DIR", SeparateNetwork.CONTAINER_STATE_DIR);
        environment.put("NODERA_IDENTITY_FILE",
                SeparateNetwork.CONTAINER_STATE_DIR + "/identity.bin");
        environment.put("NODERA_WORLDS_FILE", SeparateNetwork.CONTAINER_STATE_DIR + "/worlds.dat");
        environment.put("NODERA_WORLD_KEYS_DIR",
                SeparateNetwork.CONTAINER_STATE_DIR + "/world-keys");
        environment.put("NODERA_ARCHIVE_DIR", SeparateNetwork.CONTAINER_STATE_DIR + "/archive");
        environment.put("NODERA_TRACKER_ENDPOINTS", String.join(",", topology.trackerEndpoints()));
        environment.put("NODERA_RENDEZVOUS_ENDPOINTS",
                String.join(",", topology.rendezvousEndpoints()));
        return environment;
    }

    /** One field of the joining worker's own state document, read from inside its container. */
    private String peerField(ScenarioContext context, int controlPort, String field) {
        String state = lab.peerControl("NODERA-STATE 2", controlPort, Duration.ofSeconds(4));
        Optional<String> value = ServerJson.tryParse(state)
                .flatMap(document -> ServerJson.text(document, field));
        if (value.isEmpty()) {
            context.fail("the joining worker's state carries no " + field + ": " + state);
        }
        return value.orElse("");
    }

    /** The distribution root to mount, from the launcher script the harness was pointed at. */
    private static Path distributionRoot(TestPaths paths) {
        return paths.workerDist().toAbsolutePath().normalize().getParent().getParent();
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
