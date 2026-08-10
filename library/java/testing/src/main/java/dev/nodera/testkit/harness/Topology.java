package dev.nodera.testkit.harness;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The standard live topology and its port plan — the one place either is decided.
 *
 * <h2>Why three peers and two players is the floor, not a preference</h2>
 *
 * <p>{@code DiagnosticsCollector.deriveHealth} reports DEGRADED below three session members, so a
 * live run with fewer peers measures a degraded system and calls the result normal. Two players is
 * the smallest arrangement in which region ownership is actually shared: one player owns everything
 * and proves nothing about a committee.
 *
 * <pre>
 *   2 players · 1 tracker · 1 rendezvous · 3 headless peers
 *
 *   peer 1  player 1's companion worker   control 26610 · p2p 26620   role PLAYER_ONE
 *   peer 2  player 2's companion worker   control 26611 · p2p 26621   role PLAYER_TWO
 *   peer 3  spare standalone headless     control 26612 · p2p 26622   role SPARE
 * </pre>
 *
 * <p>The spare peer has no client. It holds the swarm above the quorum floor and keeps seeding when
 * a player's worker dies with its game — which is exactly the situation the continuity and crash
 * scenarios create on purpose.
 *
 * <h2>Why the harness has its own port block, 1000 above the product's</h2>
 *
 * <p>This plan used to be the <b>product's</b> plan: tracker 25600, rendezvous 25601, worker control
 * 25610+i, P2P 25620+i — the same numbers a shipped Nodera binds by default
 * ({@code DefaultServices.DEVELOPMENT_TRACKER}, {@code PeerNode.DEFAULT_CONTROL_PORT},
 * {@code PeerNode.DEFAULT_P2P_PORT}). So a developer with the companion app running could not run a
 * single live scenario: the app holds 25610, the preflight below refused every run, and the more
 * complete somebody's install the less of the suite they could execute. The live report of
 * 2026-08-07 — 0 passed, 17 failed, every one of them "port 25600 is still held after 60s" — is what
 * that looks like from the outside, and it was read at the time as a stale test stack.
 *
 * <p>So every port here is {@code portBase + offset}, and the offsets are exactly the historical
 * ones: a base of {@value #PRODUCTION_PORT_BASE} reproduces the old numbers, and the default base of
 * {@value #DEFAULT_PORT_BASE} shifts the whole block a thousand up, clear of anything the product
 * binds. {@link #chosenBase()} then probes that block at startup and steps to the next one if
 * anything at all is listening in it — a second harness, a leftover run, an unrelated service — and
 * announces the block it settled on, so a failing run can be traced to the ports it actually used.
 * {@code NODERA_E2E_PORT_BASE} pins it when a run has to be reproducible port for port.
 *
 * <p>Thread-context: immutable value; {@link #awaitFreePorts} and {@link #chosenBase} perform IO.
 */
public record Topology(
        int players,
        int sparePeers,
        int trackers,
        int rendezvous,
        int portBase,
        String rconPassword,
        Duration joinTimeout,
        int timeoutMultiplier) {

    /**
     * The base the <b>product</b>'s own defaults sit on: 25575 RCON, 25600 tracker, 25610 control.
     *
     * <p>Named rather than implied, because the one thing the harness's base must never become
     * again is this one. {@code HarnessPortPlanTest} asserts the two blocks stay disjoint.
     */
    public static final int PRODUCTION_PORT_BASE = 25500;

    /** The harness's own base — the product's block plus 1000, reserved for test stacks. */
    public static final int DEFAULT_PORT_BASE = 26500;

    /** How far {@link #chosenBase} will walk, and in what step, when the default block is busy. */
    private static final int BASE_STEP = 200;
    private static final int BASE_ATTEMPTS = 8;

    // The offsets within a block. Unchanged from the numbers this harness has always used, so that
    // PRODUCTION_PORT_BASE + offset is the historical port and no scenario's expectations moved.
    private static final int RCON_OFFSET = 75;
    private static final int GAME_OFFSET = 99;
    private static final int TRACKER_OFFSET = 100;
    private static final int RENDEZVOUS_OFFSET = 101;
    private static final int WORKER_CONTROL_OFFSET = 110;
    private static final int WORKER_P2P_OFFSET = 120;
    private static final int TRACKER_EXTRA_OFFSET = 140;
    private static final int RENDEZVOUS_EXTRA_OFFSET = 150;
    /** The last offset a block can use: extra rendezvous 150..159. */
    private static final int LAST_OFFSET = 159;

    /** The standard topology every scenario gets unless it asks for something else. */
    public static Topology standard() {
        return new Topology(2, 1, 1, 1, chosenBase(), "nodera-dev",
                Duration.ofSeconds(300), multiplierFromEnvironment());
    }

    /** Same shape with a different player count (1 = single client, 3 = the disagreement floor). */
    public Topology withPlayers(int newPlayers) {
        return new Topology(newPlayers, sparePeers, trackers, rendezvous, portBase, rconPassword,
                joinTimeout, timeoutMultiplier);
    }

    /** Same shape without the spare peer — the "can it survive below quorum" arrangement. */
    public Topology withSparePeers(int newSparePeers) {
        return new Topology(players, newSparePeers, trackers, rendezvous, portBase, rconPassword,
                joinTimeout, timeoutMultiplier);
    }

    /** Same shape with more discovery services — the migration and failover arrangements. */
    public Topology withServices(int newTrackers, int newRendezvous) {
        return new Topology(players, sparePeers, newTrackers, newRendezvous, portBase, rconPassword,
                joinTimeout, timeoutMultiplier);
    }

    /** Same shape on a different port block — the knob {@code NODERA_E2E_PORT_BASE} turns. */
    public Topology withPortBase(int newPortBase) {
        return new Topology(players, sparePeers, trackers, rendezvous, newPortBase, rconPassword,
                joinTimeout, timeoutMultiplier);
    }

    /** RCON of the dedicated server this run stages. */
    public int rconPort() {
        return portBase + RCON_OFFSET;
    }

    /** The Minecraft port every client in this run dials. */
    public int gamePort() {
        return portBase + GAME_OFFSET;
    }

    /** The first (and usually only) tracker's port. */
    public int trackerPort() {
        return portBase + TRACKER_OFFSET;
    }

    /** The first (and usually only) rendezvous's port. */
    public int rendezvousPort() {
        return portBase + RENDEZVOUS_OFFSET;
    }

    /** Worker 0's control port; worker {@code i} adds {@code i}. */
    public int workerControlBase() {
        return portBase + WORKER_CONTROL_OFFSET;
    }

    /** Worker 0's P2P port; worker {@code i} adds {@code i}. */
    public int workerP2pBase() {
        return portBase + WORKER_P2P_OFFSET;
    }

    /** Total workers: one companion per player plus the spares. */
    public int workers() {
        return players + sparePeers;
    }

    /** Control port of worker {@code index} (0-based). */
    public int workerControlPort(int index) {
        return workerControlBase() + index;
    }

    /** P2P port of worker {@code index} (0-based). */
    public int workerP2pPort(int index) {
        return workerP2pBase() + index;
    }

    /** The role worker {@code index} is launched with. */
    public PlayerRole workerRole(int index) {
        return index < players ? PlayerRole.forIndex(index) : PlayerRole.SPARE;
    }

    /** Tracker port {@code index} (0-based); extras live in their own block. */
    public int trackerPortAt(int index) {
        return index == 0 ? trackerPort() : portBase + TRACKER_EXTRA_OFFSET + index;
    }

    /** Rendezvous port {@code index} (0-based). */
    public int rendezvousPortAt(int index) {
        return index == 0 ? rendezvousPort() : portBase + RENDEZVOUS_EXTRA_OFFSET + index;
    }

    /** {@code host:port} for every tracker, in announce order. */
    public List<String> trackerEndpoints() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < trackers; i++) {
            out.add(serviceAdvertiseAddress() + ":" + trackerPortAt(i));
        }
        return out;
    }

    /** {@code host:port} for every rendezvous, in registration order. */
    public List<String> rendezvousEndpoints() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rendezvous; i++) {
            out.add(serviceAdvertiseAddress() + ":" + rendezvousPortAt(i));
        }
        return out;
    }

    /** Every port this topology is about to bind. */
    public List<Integer> allPorts() {
        List<Integer> ports = new ArrayList<>();
        ports.add(gamePort());
        for (int i = 0; i < trackers; i++) {
            ports.add(trackerPortAt(i));
        }
        for (int i = 0; i < rendezvous; i++) {
            ports.add(rendezvousPortAt(i));
        }
        for (int i = 0; i < workers(); i++) {
            ports.add(workerControlPort(i));
            ports.add(workerP2pPort(i));
        }
        return ports;
    }

    /**
     * A timeout scaled by the environment's multiplier.
     *
     * <p>CI runners are slower than workstations by a factor nobody can predict from the code, so
     * every wait in the harness passes through here and {@code NODERA_E2E_TIMEOUT_MULT} moves all
     * of them together. Scaling waits individually is how a suite ends up with one stage that still
     * times out on the slow machine.
     */
    public Duration scaled(Duration base) {
        return base.multipliedBy(Math.max(1, timeoutMultiplier));
    }

    /**
     * Wait for every port in the plan to be free.
     *
     * <p>Bounded rather than instant: suites run back to back, and a killed JVM can hold its
     * listener for a few seconds. Failing on the first probe fails a healthy stack; waiting forever
     * hides a foreign occupant. A genuinely foreign process (a dev stack, a stale client) never
     * frees up and still fails — and the failure names the process holding the port, because
     * "port 25610 is still held" on its own sent every reader hunting a leaked test stack when the
     * answer was the reader's own companion app.
     *
     * @param timeout how long a single port may stay busy.
     * @throws HarnessException naming the port that stayed busy and who is holding it.
     */
    public void awaitFreePorts(Duration timeout) {
        for (int port : allPorts()) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (isBound(port)) {
                if (System.nanoTime() > deadline) {
                    throw new HarnessException(PortHolder.describeHeldPort(port, timeout)
                            + " — free it, or move this run's whole block with "
                            + "NODERA_E2E_PORT_BASE=<base> (currently " + portBase + ")");
                }
                sleep(Duration.ofSeconds(2));
            }
        }
    }

    /**
     * The port block this JVM's scenarios run on, decided once and announced.
     *
     * <p>Computed lazily and memoised: {@code standard()} is called by every scenario and a base
     * that changed between two calls would give the runner and the stack different plans.
     */
    public static int chosenBase() {
        return ChosenBase.VALUE;
    }

    /** Every port a block can contain, whatever topology is built on it — what the probe checks. */
    static List<Integer> blockPorts(int base) {
        List<Integer> ports = new ArrayList<>();
        ports.add(base + RCON_OFFSET);
        for (int offset = GAME_OFFSET; offset <= LAST_OFFSET; offset++) {
            ports.add(base + offset);
        }
        return ports;
    }

    /** Lazy holder: the probe runs on first use and never again. */
    private static final class ChosenBase {
        static final int VALUE = choose();

        private static int choose() {
            String pinned = System.getenv("NODERA_E2E_PORT_BASE");
            if (pinned != null && !pinned.isBlank()) {
                try {
                    int base = Integer.parseInt(pinned.trim());
                    announce(base, "pinned by NODERA_E2E_PORT_BASE");
                    return base;
                } catch (NumberFormatException notANumber) {
                    throw new HarnessException("NODERA_E2E_PORT_BASE='" + pinned
                            + "' is not a port number");
                }
            }
            int firstBusyBase = -1;
            int firstBusyPort = -1;
            for (int attempt = 0; attempt < BASE_ATTEMPTS; attempt++) {
                int base = DEFAULT_PORT_BASE + attempt * BASE_STEP;
                int busy = firstBoundPort(base);
                if (busy < 0) {
                    announce(base, attempt == 0 ? "free" : "the default block was busy");
                    return base;
                }
                if (firstBusyBase < 0) {
                    firstBusyBase = base;
                    firstBusyPort = busy;
                }
            }
            // Nothing free anywhere in the walk. Fall back to the documented default rather than
            // guessing: awaitFreePorts is about to fail, and it names the holder, which is a far
            // better diagnosis than a run on a block nobody can predict.
            announce(DEFAULT_PORT_BASE, "no free block in " + BASE_ATTEMPTS + " attempts; "
                    + PortHolder.describeHeldPort(firstBusyPort, Duration.ZERO)
                    + " in block " + firstBusyBase);
            return DEFAULT_PORT_BASE;
        }

        private static int firstBoundPort(int base) {
            for (int port : blockPorts(base)) {
                if (isBound(port)) {
                    return port;
                }
            }
            return -1;
        }

        private static void announce(int base, String why) {
            System.out.println("nodera-test: port block " + base + " (" + why + ") — tracker "
                    + (base + TRACKER_OFFSET) + ", rendezvous " + (base + RENDEZVOUS_OFFSET)
                    + ", worker control " + (base + WORKER_CONTROL_OFFSET) + "+i, p2p "
                    + (base + WORKER_P2P_OFFSET) + "+i, game " + (base + GAME_OFFSET));
        }
    }

    private static boolean isBound(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException nothingListening) {
            return false;
        }
    }

    /** Sleep, translating an interrupt into a harness failure rather than swallowing it. */
    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HarnessException("interrupted while waiting", e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Where the stack listens, and what it tells everybody else
    //
    // A live run whose peers are all on this machine wants loopback, and that is the default here:
    // it needs no firewall hole, it cannot be joined by accident from the network, and every
    // scenario and CI job written before these existed behaves exactly as it did.
    //
    // A run with a node that is NOT this machine — a phone on the same Wi-Fi — needs two different
    // answers to two different questions, which is why there are four variables and not two. BIND is
    // the socket this process opens (0.0.0.0 to accept from the LAN); ADVERTISE is the address that
    // goes into the tracker list, the client TOMLs and the peer's own announce, and a peer that
    // advertises 0.0.0.0 or 127.0.0.1 is a peer nobody can dial back.
    //
    // The names are the shell launcher's names (scripts/lib/e2e-main.sh) on purpose: that launcher
    // already supported this, the Java one did not, and AndroidMeshScenario's P1 stage skipped every
    // run because of it. Two launchers with two spellings of the same knob is how the next reader
    // ends up debugging a stack that is up and unreachable.
    // ---------------------------------------------------------------------------------------

    /** The address the tracker and the rendezvous bind. {@code NODERA_SERVICE_BIND_ADDR}. */
    public static String serviceBindAddress() {
        return address("NODERA_SERVICE_BIND_ADDR");
    }

    /** The address peers and clients are told to reach the services on. */
    public static String serviceAdvertiseAddress() {
        return address("NODERA_SERVICE_ADVERTISE_ADDR");
    }

    /** The address each worker binds its P2P listener to. {@code NODERA_P2P_BIND_ADDR}. */
    public static String p2pBindAddress() {
        return address("NODERA_P2P_BIND_ADDR");
    }

    /** The address each worker announces itself at. {@code NODERA_P2P_ADVERTISE_ADDR}. */
    public static String p2pAdvertiseAddress() {
        return address("NODERA_P2P_ADVERTISE_ADDR");
    }

    /** @return whether this run is bound anywhere other than loopback. */
    public static boolean isLanBound() {
        return !"127.0.0.1".equals(serviceBindAddress())
                || !"127.0.0.1".equals(serviceAdvertiseAddress());
    }

    private static String address(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? "127.0.0.1" : value.trim();
    }

    private static int multiplierFromEnvironment() {
        String value = System.getenv("NODERA_E2E_TIMEOUT_MULT");
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }
}
