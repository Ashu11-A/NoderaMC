package dev.nodera.bench;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.peer.GatewayElection;
import dev.nodera.peer.discovery.CachedPeerStore;
import dev.nodera.peer.discovery.PeerDirectory;
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

import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li>{@link PeerDirectory} ingest + liveness filtering — every gossiped membership update runs
 *       this over the whole directory, under a lock every announce also wants.</li>
 *   <li>{@link CachedPeerStore} encode/decode — the warm-start path: how fast a restarted worker
 *       can reconstruct who to dial before any tracker replies.</li>
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

    /** Directory sizes: a small world, a busy world, and the per-world cap the directory enforces. */
    @Param({"16", "256", "1024"})
    public int peers;

    private List<PeerEntry> entries;
    private Bytes genesis;
    private PeerDirectory populated;
    private List<String> routes;
    private CachedPeerStore warmStore;
    private Path storeFile;
    private Path saveFile;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        genesis = BenchFixtures.digest(7);
        entries = BenchFixtures.peers(peers);

        routes = new ArrayList<>(entries.size());
        for (PeerEntry entry : entries) {
            routes.add(entry.route());
        }

        // A directory that is already at size, for the "one more update arrives" measurements.
        populated = new PeerDirectory();
        for (PeerEntry entry : entries) {
            populated.seen(genesis, entry, 1_000L);
        }

        warmStore = new CachedPeerStore(Math.max(peers, 64));
        for (PeerEntry entry : entries) {
            warmStore.remember(new CachedPeerStore.CachedPeer(
                    genesis, entry.nodeId(), entry.route(), 1_000L));
        }
        // One file, created once. Creating a temp file per invocation would measure the filesystem's
        // create/unlink path — real work, but not ours, and it swamped the encode/decode cost this
        // lane exists to watch.
        storeFile = Files.createTempFile("nodera-bench-peers", ".bin");
        warmStore.save(storeFile);
        saveFile = Files.createTempFile("nodera-bench-save", ".bin");
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

    /** Cold ingest: build a directory from a full tracker answer. */
    @Benchmark
    public PeerDirectory directoryIngest() {
        PeerDirectory directory = new PeerDirectory();
        for (PeerEntry entry : entries) {
            directory.seen(genesis, entry, 1_000L);
        }
        return directory;
    }

    /**
     * The steady-state read: who is online right now. Runs on every gossip round and on every
     * {@code NODERA-STATE} the companion app asks for, so it is on both the network and the UI
     * path.
     */
    @Benchmark
    public List<PeerDirectory.Known> directoryOnline() {
        return populated.online(genesis, 30_000L);
    }

    /** Warm start, half 1: serialise the cache a restarting worker will read back. */
    @Benchmark
    public void cacheSave(Blackhole hole) throws Exception {
        warmStore.save(saveFile);
        hole.consume(Files.size(saveFile));
    }

    /**
     * Warm start, half 2: decode it. This is the whole of discovery for the first seconds after a
     * restart — before any tracker has answered, these are the only peers the node can dial.
     */
    @Benchmark
    public CachedPeerStore cacheLoad() {
        return CachedPeerStore.load(storeFile);
    }

    /** Gateway election over the whole membership — recomputed by every peer on every change. */
    @Benchmark
    public NodeId electGateway() {
        return GatewayElection.elect(entries, 42L);
    }
}
