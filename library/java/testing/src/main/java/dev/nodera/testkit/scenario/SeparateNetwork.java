package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;
import dev.nodera.testkit.suite.SkipSignal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A second network, with a router between it and this machine — the thing a loopback run cannot be.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code docs/frontend/Task.4.md} states the failure mode this class is built to prevent, in its
 * own words: "Running both sides in one process, or even in two processes on one loopback, silently
 * satisfies the assertion while skipping the part that actually fails in the field." A join that
 * succeeded because both ends quietly found each other on {@code 127.0.0.1} looks *identical* to a
 * join that crossed a real route. So the shortcut is not merely avoided here, it is made
 * structurally unavailable and then proved unavailable:
 *
 * <ul>
 *   <li>Two Docker bridge networks are created. The host stack (tracker, rendezvous, the hosting
 *       player's worker) is told to bind the <b>host side</b>'s gateway address and nothing else —
 *       not {@code 0.0.0.0}, which would leave it listening on the join side's bridge too and make
 *       "two networks" a claim rather than a fact, and certainly not loopback.</li>
 *   <li>The joining peer runs in a container on the <b>join side</b>, whose default route is
 *       <b>deleted</b>. Its only path off its own subnet is an explicit route through a router
 *       container attached to both networks, forwarding between them.</li>
 *   <li>{@link #routerStopped()} takes that router away and {@link #peerCanReach} then reports
 *       that the joining peer can no longer reach the host stack at all — the negative twin that
 *       distinguishes "it crossed the router" from "it found a shortcut".</li>
 * </ul>
 *
 * <h2>Why the far side is a container and this side is not</h2>
 *
 * <p>The host stack has to stay on the host: it is a tracker, a rendezvous and a worker with a real
 * Minecraft client attached to it. That asymmetry is also what keeps the return path honest —
 * replies from a service running ON the host are locally generated, so they leave through the far
 * bridge directly and never touch the {@code FORWARD} chain. Docker's own bridge-to-bridge
 * isolation rules live in {@code FORWARD} and would drop them, which is exactly what happens if both
 * ends are containers: measured on this design, a container-to-container version of the same
 * arrangement needs either a return route or NAT before a single packet completes, and the
 * host-to-container version needs neither.
 *
 * <h2>The control wire comes out through {@code docker exec}</h2>
 *
 * <p>The joining worker's control socket stays on its own loopback and is never published. It is an
 * administrative channel with no authentication of its own, and widening it to prove a networking
 * property would be the same mistake this class exists to prevent. Control lines are carried in the
 * way {@code AndroidDevice} already carries them to a phone over adb — a pipe into {@code nc} on the
 * far side — so the assertion still reads the product's own {@code NODERA-STATE} and nothing else.
 *
 * <p>Thread-context: one instance per scenario, used from the scenario's thread; {@link #close()} is
 * idempotent.
 */
public final class SeparateNetwork implements AutoCloseable {

    /** Everything this class creates carries the prefix, so a crashed run is cleanable by name. */
    private static final String PREFIX = "nodera-e2e";

    /**
     * The image both containers run.
     *
     * <p>A stock JRE image, not one this repository builds. The first shape of this class built a
     * small Dockerfile that {@code apt-get}ed {@code iproute2} and a netcat — which made every run
     * depend on a package index being reachable from inside a build container, and that is a
     * dependency a test should not have (it failed outright on the machine this was written on,
     * where {@code docker run} had DNS and {@code docker build} did not). The Alpine variant already
     * carries everything needed: busybox is the {@code ip} that deletes the default route and the
     * {@code nc} that carries the control wire, and the JRE runs the worker.
     *
     * <p>The worker itself is the ordinary glibc distribution, bind-mounted in. It runs here
     * unmodified — verified by booting the installed {@code nodera-headless} in this image and
     * watching it announce, discover seeders and replicate a world archive — because every native
     * library it loads ships a musl build alongside the glibc one.
     */
    private static final String IMAGE = "eclipse-temurin:21-jre-alpine";
    private static final String HOST_NET = PREFIX + "-host-net";
    private static final String JOIN_NET = PREFIX + "-join-net";
    private static final String ROUTER = PREFIX + "-router";
    private static final String PEER = PREFIX + "-peer";

    /** Where the joining worker's state lives inside its container; {@code /opt/nodera} is read-only. */
    static final String CONTAINER_STATE_DIR = "/var/nodera";

    /** The router's address on each network — static, so it survives the negative twin's restart. */
    private static final int ROUTER_HOST_BYTE = 250;

    /** The joining peer's address on the far network; it must announce what it holds. */
    private static final int PEER_HOST_BYTE = 100;

    private static final Pattern CIDR = Pattern.compile("(\\d+\\.\\d+\\.\\d+)\\.\\d+/(\\d+)");

    private final Path logFile;
    private final List<String> created = new ArrayList<>();

    private String hostSubnet = "";
    private String hostGateway = "";
    private String joinSubnet = "";
    private String routerJoinAddress = "";

    private SeparateNetwork(Path logFile) {
        this.logFile = logFile;
    }

    /**
     * Build the two networks and the router between them.
     *
     * <p>The subnets are whatever Docker allocates and are then <b>measured</b> rather than assumed:
     * a hard-coded subnet collides with whatever else this machine runs, and an address the harness
     * asserted instead of reading is an address the run can be wrong about.
     *
     * @param logFile where every docker command and its output is recorded.
     * @return the open lab.
     * @throws SkipSignal when this machine has no usable Docker — a circumstance, not a verdict.
     */
    public static SeparateNetwork open(Path logFile) {
        SeparateNetwork lab = new SeparateNetwork(logFile);
        if (lab.docker(List.of("version", "--format", "{{.Server.Version}}")).exit != 0) {
            throw new SkipSignal("no usable Docker daemon — the cross-machine scenario builds its "
                    + "second network out of two Docker bridges and a router container");
        }
        lab.refuseRootless();
        try {
            lab.pullImage();
            lab.hostGateway = lab.network(HOST_NET, true);
            lab.network(JOIN_NET, false);
            lab.startRouter();
            return lab;
        } catch (RuntimeException failure) {
            lab.close();
            throw failure;
        }
    }

    // ---------------------------------------------------------------------------------------
    // What the host stack is told about itself
    // ---------------------------------------------------------------------------------------

    /**
     * The address the host-side stack must bind and advertise.
     *
     * <p>This is the host machine's own address on the host-side bridge — a real interface, routable
     * from the other network through the router, and reachable from nowhere else. Handing it to the
     * stack is what takes loopback off the table.
     */
    public String hostAddress() {
        return hostGateway;
    }

    /**
     * The address the joining peer will hold on the far network.
     *
     * <p>Fixed rather than discovered, because the worker has to be told what to advertise in the
     * same command that starts it: a peer that announces an address it does not hold is a peer
     * nobody can dial back, and finding out afterwards would be one restart too late.
     */
    public String peerAddress() {
        return prefixOf(joinSubnet) + "." + PEER_HOST_BYTE;
    }

    /** The {@code a.b.c} prefix of the host side's subnet. */
    public String hostSubnetPrefix() {
        return prefixOf(hostSubnet);
    }

    /** The {@code a.b.c} prefix of the join side's subnet. */
    public String joinSubnetPrefix() {
        return prefixOf(joinSubnet);
    }

    // ---------------------------------------------------------------------------------------
    // The joining peer
    // ---------------------------------------------------------------------------------------

    /**
     * Start the joining worker in a container on the far network.
     *
     * <p>The distribution is bind-mounted read-only. It is deliberately the caller's path rather
     * than something baked into the image: when the run was told to use an installed app's worker,
     * the container runs <b>that</b> executable, so the installer half and the cross-network half
     * are the same claim rather than two.
     *
     * @param workerDist  the {@code nodera-headless} distribution root on this machine.
     * @param controlPort the control port the worker listens on inside its container.
     * @param environment the worker's environment, less the addressing this class owns.
     */
    public void startPeer(Path workerDist, int controlPort, Map<String, String> environment) {
        List<String> command = new ArrayList<>(List.of(
                "run", "--detach", "--name", PEER,
                "--network", JOIN_NET, "--ip", peerAddress(),
                "--cap-add", "NET_ADMIN",
                "--volume", workerDist.toAbsolutePath() + ":/opt/nodera:ro"));
        environment.forEach((key, value) -> command.addAll(List.of("--env", key + "=" + value)));
        command.addAll(List.of(IMAGE, "sh", "-c",
                // The default route goes first. Everything after it is reachable only through the
                // router, which is the whole property under test — and deleting it before the
                // worker starts means there is never a window in which the worker announced over a
                // path the assertion does not describe.
                "set -e; ip route del default; "
                        + "ip route add " + hostSubnet + " via " + routerJoinAddress + "; "
                        + "exec /opt/nodera/bin/nodera-headless --test-mode --role player-two"));
        require(command, "start the joining peer container");
        created.add(PEER);
        awaitPeerControl(controlPort, Duration.ofSeconds(120));
    }

    /**
     * One control line to the joining worker, answered from inside its container.
     *
     * <p>{@code nc} cannot know when a reply is finished, so the pipe is held open and then closed —
     * the same shape, and the same reason, as {@code AndroidDevice.verb}.
     *
     * <p>The <b>terminal</b> line is what comes back, not the first one. {@code NODERA-ARCHIVE}
     * writes interim {@code NODERA-PROGRESS} lines on the same connection while it works, so a
     * reader that took the first line would report a fetch in progress as the fetch's verdict —
     * forever, and as a pass-shaped one.
     *
     * @param hold how long to keep the pipe open; a fetch needs longer than a state read.
     */
    public String peerControl(String verb, int controlPort, Duration hold) {
        long seconds = Math.max(1, hold.toSeconds());
        String piped = "(printf '%s\\n' '" + verb + "'; sleep " + seconds + ") | timeout "
                + (seconds + 20) + " nc 127.0.0.1 " + controlPort;
        String[] lines = exec(PEER, piped).replace("\r", "").split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("NODERA-") && !line.startsWith("NODERA-PROGRESS")) {
                return line;
            }
        }
        return "";
    }

    /** The joining worker's own log, for a failure message that names a cause. */
    public String peerLog() {
        return docker(List.of("logs", "--tail", "40", PEER)).output;
    }

    /**
     * Whether the joining peer can open a TCP connection to {@code host:port} <b>from where it is</b>.
     *
     * <p>Asked from inside the container on purpose. The same address probed from this machine
     * answers "yes" whatever the routing does, which is precisely the false green this scenario
     * exists to rule out.
     */
    public boolean peerCanReach(String host, int port) {
        // busybox nc, connected and immediately given EOF: exit 0 when the connection was made and
        // non-zero when it was refused or the address had no route. `-z` is not in every busybox.
        return dockerExitOf(List.of("exec", PEER, "sh", "-c",
                "echo | timeout 5 nc " + host + " " + port)) == 0;
    }

    /** Take the router away, so the far network has no path to this machine at all. */
    public void routerStopped() {
        require(List.of("stop", "--timeout", "5", ROUTER), "stop the router");
    }

    /** Put the router back, with the addresses it had — they are static for exactly this reason. */
    public void routerStarted() {
        require(List.of("start", ROUTER), "restart the router");
        awaitRouterForwarding();
    }

    // ---------------------------------------------------------------------------------------
    // Construction and teardown
    // ---------------------------------------------------------------------------------------

    /**
     * Refuse a rootless daemon, and say why rather than failing four stages later.
     *
     * <p>Rootless Docker puts every bridge inside a user namespace of its own, so the addresses
     * {@code docker network inspect} reports <b>do not exist on this machine</b> — {@code ip addr}
     * has no interface carrying the gateway, and a container therefore has no way to reach a service
     * the host is running. This whole scenario is built on the opposite: the tracker, the rendezvous
     * and the hosting player's worker run on the host, and the far peer dials them.
     *
     * <p>Left undetected this is not an error message, it is a timeout in the middle of X3 with an
     * assertion about routing on it — which reads as a product defect and is a property of somebody's
     * Docker installation. Named here, it is a skip with a fix in it.
     */
    private void refuseRootless() {
        String security = docker(List.of("info", "--format", "{{json .SecurityOptions}}")).output;
        if (security.contains("name=rootless")) {
            throw new SkipSignal("this machine's Docker is ROOTLESS, and its bridge gateways are "
                    + "addresses inside a user namespace rather than interfaces on this host — a "
                    + "container here cannot reach a service the host is running, which is the one "
                    + "thing this scenario needs. Point DOCKER_HOST at a rootful daemon (CI's "
                    + "ubuntu-latest is one) and re-run.");
        }
    }

    private void pullImage() {
        if (docker(List.of("image", "inspect", "--format", "{{.Id}}", IMAGE)).exit == 0) {
            return;
        }
        Result pulled = docker(List.of("pull", "--quiet", IMAGE));
        if (pulled.exit != 0) {
            throw new SkipSignal("this machine has neither " + IMAGE + " nor a way to fetch it: "
                    + tail(pulled.output));
        }
    }

    /**
     * Create one network and read back the subnet Docker chose for it.
     *
     * @param gatewayWanted whether the caller needs this network's gateway address returned.
     * @return the gateway address when asked for, otherwise blank.
     */
    private String network(String name, boolean gatewayWanted) {
        require(List.of("network", "create", "--driver", "bridge", name), "create " + name);
        created.add(name);
        String subnet = inspect("network", name, "{{(index .IPAM.Config 0).Subnet}}");
        String gateway = inspect("network", name, "{{(index .IPAM.Config 0).Gateway}}");
        if (!CIDR.matcher(subnet).matches()) {
            throw new HarnessException(name + " reported an unusable subnet '" + subnet + "'");
        }
        if (name.equals(HOST_NET)) {
            hostSubnet = subnet;
        } else {
            joinSubnet = subnet;
        }
        return gatewayWanted ? gateway : "";
    }

    /**
     * The router: one container on both networks, forwarding between them.
     *
     * <p>It carries a fixed address on each network. That is not tidiness — the negative twin stops
     * and restarts this container, and the joining peer's one route out names the address it had.
     */
    private void startRouter() {
        String hostIp = prefixOf(hostSubnet) + "." + ROUTER_HOST_BYTE;
        routerJoinAddress = prefixOf(joinSubnet) + "." + ROUTER_HOST_BYTE;
        require(List.of("run", "--detach", "--name", ROUTER,
                "--network", HOST_NET, "--ip", hostIp,
                // Forwarding is a property of this container's own network namespace, so Docker's
                // own --sysctl sets it and nothing inside the container needs a capability or a
                // tool. The first shape of this used iptables to masquerade; it does not need to —
                // both bridges are on this machine, so the reply path back to the far subnet is
                // already on-link here, and NAT would only have hidden which node sent what.
                "--sysctl", "net.ipv4.ip_forward=1",
                IMAGE, "sh", "-c", routerScript()), "start the router");
        created.add(ROUTER);
        require(List.of("network", "connect", "--ip", routerJoinAddress, JOIN_NET, ROUTER),
                "attach the router to the far network");
        awaitRouterForwarding();
    }

    /**
     * What the router runs: a marker and a wait. The forwarding is the kernel's.
     *
     * <p>The marker exists so that "the router is back" is observed rather than assumed. The
     * negative twin stops and restarts this container, and a run that carried on before the
     * namespace was up again would report a routing failure that was the harness's own.
     */
    private static String routerScript() {
        return "echo router-ready; sleep infinity";
    }

    private void awaitRouterForwarding() {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (docker(List.of("logs", ROUTER)).output.contains("router-ready")) {
                return;
            }
            sleep(Duration.ofMillis(500));
        }
        throw new HarnessException("the router container never reported ready: "
                + tail(docker(List.of("logs", ROUTER)).output));
    }

    private void awaitPeerControl(int controlPort, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (peerControl("NODERA-PROBE 2", controlPort, Duration.ofSeconds(2))
                    .startsWith("NODERA-OK")) {
                return;
            }
            sleep(Duration.ofSeconds(2));
        }
        throw new HarnessException("the joining worker never answered NODERA-PROBE inside its "
                + "container within " + timeout.toSeconds() + "s — " + tail(peerLog()));
    }

    /** Remove everything this instance created, newest first. Safe to call more than once. */
    @Override
    public void close() {
        for (int i = created.size() - 1; i >= 0; i--) {
            String name = created.get(i);
            if (name.endsWith("-net")) {
                docker(List.of("network", "rm", name));
            } else {
                docker(List.of("rm", "--force", "--volumes", name));
            }
        }
        created.clear();
    }

    // ---------------------------------------------------------------------------------------

    private String inspect(String kind, String name, String format) {
        return docker(List.of(kind, "inspect", "--format", format, name)).output.trim();
    }

    private String exec(String container, String script) {
        return docker(List.of("exec", container, "sh", "-c", script)).output;
    }

    private void require(List<String> arguments, String what) {
        Result result = docker(arguments);
        if (result.exit != 0) {
            throw new HarnessException("could not " + what + " (docker exit " + result.exit + "): "
                    + tail(result.output));
        }
    }

    private int dockerExitOf(List<String> arguments) {
        return docker(arguments).exit;
    }

    private Result docker(List<String> arguments) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(arguments);
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            int exit = process.waitFor();
            record(command, exit, output);
            return new Result(exit, output);
        } catch (IOException noDocker) {
            record(command, 127, String.valueOf(noDocker.getMessage()));
            return new Result(127, String.valueOf(noDocker.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while running "
                    + String.join(" ", command), e);
        }
    }

    /**
     * Every docker command and everything it said, appended to one file.
     *
     * <p>A failure in this class is a failure in somebody's Docker installation nine times in ten,
     * and the difference between the tenth and the nine is entirely in what the command printed.
     */
    private void record(List<String> command, int exit, String output) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, "$ " + String.join(" ", command) + "\n"
                            + output + "[exit " + exit + "]\n\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            throw new UncheckedIOException("cannot record docker output to " + logFile, unwritable);
        }
    }

    private static String prefixOf(String cidr) {
        Matcher matcher = CIDR.matcher(cidr);
        if (!matcher.matches()) {
            throw new HarnessException("not a subnet this class can address within: " + cidr);
        }
        return matcher.group(1);
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= 600 ? trimmed : "…" + trimmed.substring(trimmed.length() - 600);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while waiting for the container network", e);
        }
    }

    private record Result(int exit, String output) {
    }
}
