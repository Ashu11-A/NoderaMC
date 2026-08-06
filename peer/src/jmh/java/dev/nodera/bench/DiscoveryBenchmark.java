package dev.nodera.bench;

import dev.nodera.core.identity.NodeId;
import dev.nodera.peer.GatewayElection;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.protocol.membership.PeerEntry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lane 1 — <b>peer discovery</b>: everything between "this node knows nothing" and "this node has a
 * ranked list of peers to dial".
 *
 * <h2>What this measures, and what it deliberately does not</h2>
 *
 * <p>Discovery latency in the field is dominated by the network, and a benchmark that opened
 * sockets would measure the loopback stack rather than our code. What it CAN measure — and what
 * has repeatedly been the difference between "found peers in 200 ms" and "found peers in eight
 * seconds" — is the per-peer CPU work the node does with each answer once it arrives:
 *
 * <ul>
 *   <li>{@link TrackerClient.Endpoint#parse} — run once per configured endpoint on every announce,
 *       and the site of a real outage (a {@code tcp://} scheme handed to a resolver).</li>
 *   <li>{@link GatewayElection#elect} — recomputed by every member on every membership change.</li>
 * </ul>
 *
 * <p>Each of those is O(peers) or worse, and the peer count is the variable that grows with the
 * network, which is why {@code peers} is a {@link Param}: a regression that only appears at 1024
 * peers is invisible at 8.
 *
 * <p>Thread-context: JMH state per benchmark; no shared mutable state between iterations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@State(Scope.Benchmark)
public class DiscoveryBenchmark {

    /** Membership sizes: a small world, a busy world, and one an order of magnitude larger. */
    @Param({"16", "256", "1024"})
    public int peers;

    private List<PeerEntry> entries;
    private List<String> routes;

    @Setup(Level.Trial)
    public void setUp() {
        entries = BenchFixtures.peers(peers);

        routes = new ArrayList<>(entries.size());
        for (PeerEntry entry : entries) {
            routes.add(entry.route());
        }
    }

    /**
     * Route parsing, once per endpoint. Cheap per call and run constantly — the point of measuring
     * it is that it sits in front of every other discovery step, so a regression here delays
     * everything downstream.
     */
    @Benchmark
    public void parseRoutes(Blackhole hole) {
        for (String route : routes) {
            hole.consume(TrackerClient.Endpoint.parse(route));
        }
    }

    /** Gateway election over the whole membership — recomputed by every peer on every change. */
    @Benchmark
    public NodeId electGateway() {
        return GatewayElection.elect(entries, 42L);
    }
}
