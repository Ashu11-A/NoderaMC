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
 *   peer 1  player 1's companion worker   control 25610 · p2p 25620   role PLAYER_ONE
 *   peer 2  player 2's companion worker   control 25611 · p2p 25621   role PLAYER_TWO
 *   peer 3  spare standalone headless     control 25612 · p2p 25622   role SPARE
 * </pre>
 *
 * <p>The spare peer has no client. It holds the swarm above the quorum floor and keeps seeding when
 * a player's worker dies with its game — which is exactly the situation the continuity and crash
 * scenarios create on purpose.
 *
 * <p>Thread-context: immutable value; {@link #awaitFreePorts} performs IO.
 */
public record Topology(
        int players,
        int sparePeers,
        int trackers,
        int rendezvous,
        int gamePort,
        int trackerPort,
        int rendezvousPort,
        int workerControlBase,
        int workerP2pBase,
        int rconPort,
        String rconPassword,
        Duration joinTimeout,
        int timeoutMultiplier) {

    /** The standard topology every scenario gets unless it asks for something else. */
    public static Topology standard() {
        return new Topology(2, 1, 1, 1, 25599, 25600, 25601, 25610, 25620, 25575, "nodera-dev",
                Duration.ofSeconds(300), multiplierFromEnvironment());
    }

    /** Same shape with a different player count (1 = single client, 3 = the disagreement floor). */
    public Topology withPlayers(int newPlayers) {
        return new Topology(newPlayers, sparePeers, trackers, rendezvous, gamePort, trackerPort,
                rendezvousPort, workerControlBase, workerP2pBase, rconPort, rconPassword,
                joinTimeout, timeoutMultiplier);
    }

    /** Same shape without the spare peer — the "can it survive below quorum" arrangement. */
    public Topology withSparePeers(int newSparePeers) {
        return new Topology(players, newSparePeers, trackers, rendezvous, gamePort, trackerPort,
                rendezvousPort, workerControlBase, workerP2pBase, rconPort, rconPassword,
                joinTimeout, timeoutMultiplier);
    }

    /** Same shape with more discovery services — the migration and failover arrangements. */
    public Topology withServices(int newTrackers, int newRendezvous) {
        return new Topology(players, sparePeers, newTrackers, newRendezvous, gamePort, trackerPort,
                rendezvousPort, workerControlBase, workerP2pBase, rconPort, rconPassword,
                joinTimeout, timeoutMultiplier);
    }

    /** Total workers: one companion per player plus the spares. */
    public int workers() {
        return players + sparePeers;
    }

    /** Control port of worker {@code index} (0-based). */
    public int workerControlPort(int index) {
        return workerControlBase + index;
    }

    /** P2P port of worker {@code index} (0-based). */
    public int workerP2pPort(int index) {
        return workerP2pBase + index;
    }

    /** The role worker {@code index} is launched with. */
    public PlayerRole workerRole(int index) {
        return index < players ? PlayerRole.forIndex(index) : PlayerRole.SPARE;
    }

    /** Tracker port {@code index} (0-based); extras live in their own block. */
    public int trackerPortAt(int index) {
        return index == 0 ? trackerPort : 25640 + index;
    }

    /** Rendezvous port {@code index} (0-based). */
    public int rendezvousPortAt(int index) {
        return index == 0 ? rendezvousPort : 25650 + index;
    }

    /** {@code host:port} for every tracker, in announce order. */
    public List<String> trackerEndpoints() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < trackers; i++) {
            out.add("127.0.0.1:" + trackerPortAt(i));
        }
        return out;
    }

    /** {@code host:port} for every rendezvous, in registration order. */
    public List<String> rendezvousEndpoints() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rendezvous; i++) {
            out.add("127.0.0.1:" + rendezvousPortAt(i));
        }
        return out;
    }

    /** Every port this topology is about to bind. */
    public List<Integer> allPorts() {
        List<Integer> ports = new ArrayList<>();
        ports.add(gamePort);
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
     * frees up and still fails, with the port named.
     *
     * @param timeout how long a single port may stay busy.
     * @throws HarnessException naming the port that stayed busy.
     */
    public void awaitFreePorts(Duration timeout) {
        for (int port : allPorts()) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (isBound(port)) {
                if (System.nanoTime() > deadline) {
                    throw new HarnessException("port " + port + " is still held after "
                            + timeout.toSeconds() + "s — stop the other stack first "
                            + "(scripts/dev.sh? a stale client JVM?)");
                }
                sleep(Duration.ofSeconds(2));
            }
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
