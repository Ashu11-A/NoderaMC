package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.peer.archival.ArchiveObjectClass;
import dev.nodera.peer.archival.RendezvousArchivePolicy;
import dev.nodera.peer.discovery.TrackerClient;
import dev.nodera.protocol.discovery.TrackerCatalogEntry;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.membership.PeerEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Makes a shared world <b>the network's</b>, not its author's.
 *
 * <p>Announcing a world to a tracker publishes where it is; it does not put a single byte of it
 * anywhere else. Before this service existed, a world's content lived only on the machine that
 * shared it: the directory could list a world that exactly one node could actually serve, and that
 * node going away took the world with it — the precise failure the archive lane was built to
 * prevent. Discovery was network-wide; durability was not.
 *
 * <p>So on a cadence this asks each tracker for the whole world directory and, for every world,
 * runs the Task 21 placement policy over the peers the tracker reports. The policy is a pure
 * function of (manifest root, peer set), so <em>every</em> node independently computes the same
 * expected-holder list without any coordinator: if this node is on that list for a world it does
 * not yet hold, it fetches the archive — which, because the piece plane stores every verified piece
 * it downloads, immediately makes this node a seeder of it.
 *
 * <h2>Bounded on purpose</h2>
 *
 * <p>A peer must never be volunteered into filling its disk. Three bounds apply: a byte budget
 * across all replicated worlds, a cap on how many worlds are adopted per sweep, and a per-fetch
 * deadline. Hosted worlds are exempt from the budget — this node's own worlds are not optional —
 * and the policy already excludes the host from the replication factor, so a world's R replicas are
 * R replicas <em>besides</em> its host.
 *
 * <h2>Nothing is trusted</h2>
 *
 * <p>The tracker chooses which peers it reports and could try to steer placement. It cannot: every
 * fetched piece is hash-checked against a manifest whose root the peer re-derives, so a lying
 * tracker buys a wasted download, never corrupted content.
 *
 * <p>Thread-context: {@link #start}/{@link #close} from any one thread; sweeps run on a single
 * daemon scheduler and block on fetches, which is why they never run on a runtime state thread.
 */
public final class WorldReplicationService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    /** Default seconds between replication sweeps. */
    public static final int DEFAULT_SWEEP_SECONDS = 300;

    /** Default ceiling on bytes held for worlds this node does not host. */
    public static final long DEFAULT_BUDGET_BYTES = 8L * 1024 * 1024 * 1024;

    /** Worlds adopted per sweep, so a fresh node ramps up instead of stampeding the swarm. */
    private static final int MAX_ADOPTIONS_PER_SWEEP = 2;

    /** Per-world fetch deadline. */
    private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(5);

    private final NodeId self;
    private final TrackerClient tracker;
    private final WorldArchiveService archive;
    private final WorldHostingService hosting;
    private final RendezvousArchivePolicy policy;
    /**
     * Both bounds are adjustable at runtime ({@link #reconfigure}) because they are exactly the
     * two knobs the companion app's Storage settings expose. Volatile so a sweep already running
     * on the scheduler thread reads a coherent value; the restart in {@code reconfigure} is what
     * makes a changed cadence take effect rather than waiting out the old one.
     */
    private volatile long budgetBytes;
    private volatile int sweepSeconds;

    private ScheduledExecutorService scheduler;
    /**
     * Whether the owner has asked this lane to run. Distinct from {@code scheduler != null}: a
     * zero budget leaves {@link #start()} with nothing scheduled, and {@link #reconfigure} must be
     * able to tell "disabled by budget" (raise the budget → begin sweeping) from "never started"
     * (a config push must not start a lane the owner never wanted).
     */
    private boolean startRequested;

    /**
     * @param self         this node's id.
     * @param tracker      the tracker client used to read the directory and each world's peers.
     * @param archive      the archive lane that fetches and then serves content.
     * @param hosting      the hosting service, for what this node hosts and to announce seeding.
     * @param budgetBytes  ceiling on bytes held for worlds this node does not host.
     * @param sweepSeconds seconds between sweeps.
     */
    public WorldReplicationService(NodeId self, TrackerClient tracker, WorldArchiveService archive,
                                   WorldHostingService hosting, long budgetBytes, int sweepSeconds) {
        this.self = java.util.Objects.requireNonNull(self, "self");
        this.tracker = java.util.Objects.requireNonNull(tracker, "tracker");
        this.archive = java.util.Objects.requireNonNull(archive, "archive");
        this.hosting = java.util.Objects.requireNonNull(hosting, "hosting");
        this.policy = new RendezvousArchivePolicy();
        this.budgetBytes = Math.max(0, budgetBytes);
        this.sweepSeconds = Math.max(30, sweepSeconds);
    }

    /** Begin sweeping. Idempotent. The first sweep is delayed one interval so startup stays quiet. */
    public synchronized void start() {
        startRequested = true;
        if (scheduler != null || budgetBytes == 0) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nodera-world-replication");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::sweepQuietly, sweepSeconds, sweepSeconds,
                TimeUnit.SECONDS);
    }

    /**
     * Re-bound the replication lane while the node is running (the {@code NODERA-CONFIG} seam).
     *
     * <p>A changed sweep cadence only means anything if the scheduler is rebuilt — {@code
     * scheduleWithFixedDelay} captures its delay at submission — so this stops and restarts it
     * under the same monitor {@link #start()} and {@link #close()} already use. A budget of
     * {@code 0} disables the lane entirely (matching {@code start()}'s own rule), which is the
     * honest reading of an operator who only wants to host their own worlds; raising it back above
     * zero restarts sweeping without a process restart.
     *
     * <p>Note the bounds' floors are the constructor's: {@code budgetBytes} is clamped at 0 and
     * {@code sweepSeconds} at 30, so a UI cannot ask this node to hammer the swarm.
     *
     * @param newBudgetBytes  ceiling on bytes held for worlds this node does not host.
     * @param newSweepSeconds seconds between sweeps (clamped to at least 30).
     * @Thread-context any thread; serialised against {@link #start()}/{@link #close()}.
     */
    public synchronized void reconfigure(long newBudgetBytes, int newSweepSeconds) {
        long budget = Math.max(0, newBudgetBytes);
        int sweep = Math.max(30, newSweepSeconds);
        if (budget == budgetBytes && sweep == sweepSeconds) {
            return; // nothing to do; never churn the scheduler for a no-op push
        }
        boolean owner = startRequested;
        if (scheduler != null) {
            close();
        }
        this.budgetBytes = budget;
        this.sweepSeconds = sweep;
        if (owner) {
            // A lane disabled by a zero budget begins sweeping on the first non-zero push; one
            // that was running restarts on the new cadence. A lane the owner never started stays
            // stopped — configuration must not switch a subsystem on behind its owner's back.
            start();
        }
    }

    /** @return the current ceiling on bytes held for worlds this node does not host. */
    public long budgetBytes() {
        return budgetBytes;
    }

    /** @return the current seconds between replication sweeps. */
    public int sweepSeconds() {
        return sweepSeconds;
    }

    @Override
    public synchronized void close() {
        startRequested = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void sweepQuietly() {
        try {
            sweep();
        } catch (RuntimeException e) {
            LOG.debug("replication sweep failed: {}", e.toString());
        }
    }

    /**
     * One sweep: read the directory, decide which worlds this node is placed for, and adopt the
     * ones it does not already hold, up to the bounds.
     *
     * @return the number of worlds adopted this sweep.
     */
    int sweep() {
        if (tracker.endpoints().isEmpty()) {
            return 0;
        }
        List<TrackerCatalogEntry> catalog;
        try {
            catalog = tracker.catalog(0);
        } catch (RuntimeException e) {
            LOG.debug("catalog read failed: {}", e.toString());
            return 0;
        }
        long held = replicatedBytes();
        int adopted = 0;
        for (TrackerCatalogEntry entry : catalog) {
            if (adopted >= MAX_ADOPTIONS_PER_SWEEP || held >= budgetBytes) {
                break;
            }
            String worldIdHex = entry.genesisHash().toHex();
            if (holdsCompletely(worldIdHex) || hosts(worldIdHex)) {
                continue; // already this node's problem, one way or the other
            }
            if (!placedFor(entry.genesisHash())) {
                continue; // some other node is the deterministic holder for this world
            }
            long grew = adopt(worldIdHex, entry.worldName());
            if (grew > 0) {
                held += grew;
                adopted++;
            }
        }
        return adopted;
    }

    /**
     * Would the deterministic placement policy put this node among a world's expected holders?
     *
     * <p>The manifest root would be the ideal placement key, but a node that holds nothing of a
     * world has not seen its manifest yet — the very worlds this method exists to adopt. The world
     * id is the stable stand-in: it is equally well-known to every node, so every node still
     * computes the same list, which is the property that makes the policy coordinator-free.
     */
    private boolean placedFor(Bytes worldId) {
        TrackerResponse response;
        try {
            response = tracker.query(worldId).orElse(null);
        } catch (RuntimeException e) {
            return false;
        }
        if (response == null || response.peers().isEmpty()) {
            return false;
        }
        LinkedHashSet<NodeId> eligible = new LinkedHashSet<>();
        Set<NodeId> fullArchive = new LinkedHashSet<>();
        for (PeerEntry peer : response.peers()) {
            eligible.add(peer.nodeId());
            if (TrackerClient.isWorldHost(peer.capabilities())) {
                fullArchive.add(peer.nodeId());
            }
        }
        // This node is a candidate holder even though it has not announced for this world yet —
        // that announce is the consequence of being placed, not its precondition.
        eligible.add(self);
        try {
            List<NodeId> expected = policy.expectedHolders(worldId, ArchiveObjectClass.SNAPSHOT,
                    new ArrayList<>(eligible), fullArchive);
            return expected.contains(self);
        } catch (RuntimeException e) {
            // A malformed peer set (e.g. a host the tracker did not also list as a peer) is the
            // tracker's problem; declining to adopt is the safe reading.
            LOG.debug("placement for {} failed: {}", worldId.toShortHex(6), e.getMessage());
            return false;
        }
    }

    /** Fetch a world's archive and begin advertising it. @return bytes now held, or 0 on failure. */
    private long adopt(String worldIdHex, String worldName) {
        try {
            archive.fetchArchive(worldIdHex, FETCH_TIMEOUT);
        } catch (RuntimeException e) {
            // No seeder answered, or the download did not complete inside the deadline. Both are
            // ordinary in a small swarm; the next sweep tries again.
            LOG.debug("replication of {} did not complete: {}", shortId(worldIdHex), e.getMessage());
            return 0;
        }
        WorldArchiveService.PieceReport report = archive.pieceReport(worldIdHex);
        if (report == null || report.heldCount() < report.pieceCount()) {
            return 0;
        }
        hosting.seed(worldIdHex, worldName);
        LOG.info("Replicated world '{}' ({}) — {} piece(s), {} byte(s) now served from this node",
                worldName, shortId(worldIdHex), report.pieceCount(), report.totalBytes());
        return report.totalBytes();
    }

    /** @return bytes this node holds for worlds it does not host (what the budget bounds). */
    private long replicatedBytes() {
        long bytes = 0;
        for (WorldHostingService.HostedWorld world : hosting.hostedWorlds()) {
            if (!world.seeding()) {
                continue;
            }
            WorldArchiveService.PieceReport report = archive.pieceReport(world.worldIdHex());
            if (report != null) {
                bytes += report.totalBytes();
            }
        }
        return bytes;
    }

    private boolean holdsCompletely(String worldIdHex) {
        WorldArchiveService.PieceReport report = archive.pieceReport(worldIdHex);
        return report != null && report.pieceCount() > 0
                && report.heldCount() == report.pieceCount();
    }

    private boolean hosts(String worldIdHex) {
        for (WorldHostingService.HostedWorld world : hosting.hostedWorlds()) {
            if (world.worldIdHex().equalsIgnoreCase(worldIdHex) && !world.seeding()) {
                return true;
            }
        }
        return false;
    }

    private static String shortId(String hex) {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }
}
