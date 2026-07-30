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

    /**
     * Volunteered replicas released per sweep. Deliberately smaller than the adoption limit: a
     * release destroys local content and a mistaken one costs the swarm a copy, so even a
     * systematically wrong placement answer can only drain a node one world per sweep.
     */
    private static final int MAX_RELEASES_PER_SWEEP = 1;

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
        // Sized for world saves, not for simulation state. The spec's fixed ×5 leaves a world whose
        // holders are home machines online about a third of the time unreachable roughly 12% of the
        // time; ReplicationTarget derives the count from that availability, and the policy caps it
        // at the peers that exist — so a small network puts a FULL COPY ON EVERY PEER and a large
        // one asks each peer for a shrinking share of the whole corpus.
        this.policy = new RendezvousArchivePolicy(
                dev.nodera.peer.archival.ReplicationFactors.forWorldArchives(
                        dev.nodera.peer.archival.ReplicationTarget.standard()));
        this.budgetBytes = Math.max(0, budgetBytes);
        this.sweepSeconds = Math.max(30, sweepSeconds);
    }

    /**
     * How soon after starting the first sweep runs.
     *
     * <p>Not a full sweep interval. A node that has just been told about a world — because its
     * player joined one — should start pulling it in seconds, not in the five minutes the steady
     * cadence is sized for. Long enough that a worker still binding its transport and probing its
     * trackers is not asked to download anything mid-boot.
     */
    private static final int FIRST_SWEEP_SECONDS = 15;

    /** Begin sweeping. Idempotent. */
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
        scheduler.scheduleWithFixedDelay(this::sweepQuietly,
                Math.min(FIRST_SWEEP_SECONDS, sweepSeconds), sweepSeconds, TimeUnit.SECONDS);
    }

    /**
     * Sweep now, off the caller's thread — the "something changed, do not wait out the cadence" seam.
     *
     * <p>Joining a world is the case this exists for. The steady sweep is sized for a background
     * duty and runs every few minutes; a player who has just joined a world would sit for that long
     * with an empty progress bar while the peer that could serve them did nothing, which reads as
     * broken and is indistinguishable from it.
     *
     * @Thread-context any thread; a no-op when the lane is not running.
     */
    public synchronized void sweepNow() {
        if (scheduler == null) {
            return;
        }
        scheduler.execute(this::sweepQuietly);
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
            // Waits: a sweep in flight is writing fetched pieces into the content store, and the
            // caller is entitled to delete that store the moment this returns.
            Shutdown.stopAndWait(scheduler);
            scheduler = null;
        }
    }

    /**
     * How soon a sweep that could not fetch anything tries again, instead of waiting out the cadence.
     *
     * <p>The steady cadence is sized for a background duty; the first sweep after boot is not one.
     * It fires seconds after start, which on a fresh session is <em>before</em> the host has
     * archived a single version — so the honest answer is "nobody holds it yet", and the peer then
     * sat at 0 bytes for five more minutes while the world it was there to support filled up next
     * door. Bounded to one extra attempt per steady sweep, so a world that simply cannot be fetched
     * costs one retry, not a permanent minute-by-minute loop.
     */
    private static final int RETRY_AFTER_FAILURE_SECONDS = 45;

    private void sweepQuietly() {
        sweepQuietly(true);
    }

    private void sweepQuietly(boolean mayRetry) {
        try {
            if (sweepFailedToFetch() && mayRetry) {
                ScheduledExecutorService s;
                synchronized (this) {
                    s = scheduler;
                }
                if (s != null) {
                    s.schedule(() -> sweepQuietly(false),
                            RETRY_AFTER_FAILURE_SECONDS, TimeUnit.SECONDS);
                }
            }
        } catch (RuntimeException e) {
            // WARN, not debug. A sweep that throws is the difference between "this node is helping"
            // and "this node looks like it is helping", and the whole lane runs unattended.
            LOG.warn("Replication sweep failed: {}", e.toString());
        }
    }

    /** Run one sweep. @return whether any world it decided to fetch could not be fetched. */
    private boolean sweepFailedToFetch() {
        sweep();
        return lastSweepFailures > 0;
    }

    /** Worlds the last sweep tried and failed to fetch — what {@link #sweepQuietly} retries for. */
    private volatile int lastSweepFailures;

    /**
     * One sweep: read the directory, decide which worlds this node is placed for, and adopt the
     * ones it does not already hold, up to the bounds.
     *
     * @return the number of worlds adopted this sweep.
     */
    int sweep() {
        lastSweepFailures = 0;
        if (tracker.endpoints().isEmpty()) {
            LOG.info("Replication sweep skipped — this node has no tracker to read a directory from");
            return 0;
        }
        List<TrackerCatalogEntry> catalog;
        try {
            catalog = tracker.catalog(0);
        } catch (RuntimeException e) {
            LOG.warn("Replication sweep could not read the directory from {} tracker(s): {}",
                    tracker.endpoints().size(), e.toString());
            return 0;
        }
        if (catalog.isEmpty()) {
            LOG.info("Replication sweep: {} tracker(s) list no worlds — nothing to support",
                    tracker.endpoints().size());
            return 0;
        }
        long held = replicatedBytes();
        // Before deciding what to take on, give back what this node is no longer expected to hold.
        // Ordered first deliberately: the freed bytes are spendable in this same sweep, so a node
        // whose placement moved converges in one cycle instead of one release per five minutes.
        long freed = release(held);
        if (freed > 0) {
            held = replicatedBytes();
        }
        int adopted = 0;
        // Every sweep says what it DECIDED, per world, at INFO. This lane was silent by design —
        // an empty catalog returned 0, a declined placement logged nothing, and a failure went to
        // debug — so "my peer is not receiving any chunks" produced no evidence anywhere and took
        // several rounds of live debugging to even locate. Sweeps are minutes apart; the lines are
        // cheap and they are the only account of why a node is or is not holding somebody's world.
        int skippedComplete = 0;
        int skippedUnplaced = 0;
        int skippedBounded = 0;
        int refreshed = 0;
        int failed = 0;
        for (TrackerCatalogEntry entry : catalog) {
            String worldIdHex = entry.genesisHash().toHex();
            if (holdsCompletely(worldIdHex)) {
                // Complete is a statement about ONE VERSION, and worlds do not stop changing.
                //
                // This used to `continue` here, and that single line is why a peer could hold a
                // world forever without ever holding the world: it fetched v2 while the host was
                // still building, the host played on and streamed v3…v6, and every sweep from then
                // on read "complete" off the v2 manifest and skipped. Seen side by side on two
                // machines in the same world — 7 of 7 pieces at v2 against 208 of 208 at v6, both
                // rendering "100.0%". The peer was not supporting that world in any sense a player
                // would recognise; it was seeding a museum piece.
                //
                // A world this node HOSTS is exempt: it is the authority on its own newest version,
                // so there is nothing on the network to catch up to.
                if (hosts(worldIdHex)) {
                    skippedComplete++;
                    continue;
                }
                if (refresh(worldIdHex, entry.worldName())) {
                    refreshed++;
                } else {
                    skippedComplete++;
                }
                continue;
            }
            // A world this node CLAIMS is repaired unconditionally, ahead of the bounds.
            //
            // The skip used to read `holdsCompletely(...) || hosts(...)` — "already this node's
            // problem, one way or the other". But `hosts()` asks what the registry says, not what
            // the content store has, and `restoreFromRegistry` reloads every row as hosted at boot
            // whether or not a single byte survived. So the one state that most needs repairing —
            // this node announcing a world it cannot serve — was the exact state that disqualified
            // it from being repaired. Observed live: a node showing "Yours — hosted here" and
            // "0.0% · 0 of 73 pieces", permanently, while three other peers held all 73.
            //
            // `holdsCompletely` already covers a host that really does hold its world, so this
            // branch only ever fires for a claim with nothing behind it.
            boolean ours = hosts(worldIdHex);
            // Bounds and placement govern volunteered replicas only; see the class doc — a node
            // must never be volunteered into filling its disk, but its own worlds are not optional.
            boolean bounded = withinBounds(adopted, held, budgetBytes);
            boolean placed = ours || (bounded && placedFor(entry.genesisHash()));
            if (!shouldAdopt(false, ours, placed, bounded)) {
                if (!bounded) {
                    skippedBounded++;
                } else {
                    skippedUnplaced++;
                }
                continue;
            }
            if (ours) {
                LOG.info("Repairing world '{}' ({}) — this node announces it but holds none of it",
                        entry.worldName(), shortId(worldIdHex));
            }
            LOG.info("Supporting world '{}' ({}) — fetching its archive from the network",
                    entry.worldName(), shortId(worldIdHex));
            long grew = adopt(worldIdHex, entry.worldName());
            if (grew <= 0) {
                // Counted, because a sweep that tried and failed used to report the same all-zero
                // summary as a sweep that decided there was nothing to do.
                failed++;
                continue;
            }
            if (!ours) {
                held += grew;
                adopted++;
            }
        }
        LOG.info("Replication sweep over {} world(s): {} adopted, {} brought up to date, "
                        + "{} already current here, {} not placed on this node, {} past the bounds, "
                        + "{} could not be fetched, {} byte(s) released ({} of {} byte(s) used)",
                catalog.size(), adopted, refreshed, skippedComplete, skippedUnplaced,
                skippedBounded, failed, freed, held, budgetBytes);
        lastSweepFailures = failed;
        return adopted;
    }

    /**
     * Catch a complete-but-stale copy up to the version the swarm is actually seeding.
     *
     * <p>Never fatal and never bounded: this is content this node already committed to holding, so
     * a newer version of it is not a new adoption to be rationed — it is the same obligation, kept
     * honestly. A world that cannot be refreshed stays exactly as playable as it was.
     *
     * @return whether this node moved to a newer version.
     */
    private boolean refresh(String worldIdHex, String worldName) {
        boolean caughtUp;
        try {
            caughtUp = archive.refreshArchive(worldIdHex, FETCH_TIMEOUT);
        } catch (RuntimeException e) {
            LOG.info("World '{}' ({}) has a newer version this node could not fetch ({}) — the copy "
                            + "held here stays available", worldName, shortId(worldIdHex),
                    e.getMessage());
            return false;
        }
        if (!caughtUp) {
            return false;
        }
        // Re-announce under the new root: `holdingsFor` reads the manifest table at announce time,
        // so this is what tells the swarm the newer version has another holder.
        hosting.seed(worldIdHex, worldName);
        WorldArchiveService.PieceReport report = archive.pieceReport(worldIdHex);
        LOG.info("Brought world '{}' ({}) up to date — now seeding v{}, {} piece(s)", worldName,
                shortId(worldIdHex), report == null ? 0 : report.version(),
                report == null ? 0 : report.pieceCount());
        return true;
    }

    /**
     * What the placement policy says about this node and one world.
     *
     * <p>Three values, not two, because the two ways of not being placed have opposite consequences.
     * Declining to <b>adopt</b> is safe under either — but releasing content already held is only
     * safe under {@link #NOT_PLACED}. A tracker that is down, unreachable, or momentarily returning
     * an empty peer set produces {@link #UNKNOWN}, and a node that treated that as "not placed"
     * would empty itself during an outage and then re-fetch everything when the outage ended.
     */
    enum Placement {
        /** The policy expects a replica here. */
        PLACED,
        /** The policy answered, and this node is not among the expected holders. */
        NOT_PLACED,
        /** No usable answer: no tracker response, no peers listed, or a malformed peer set. */
        UNKNOWN
    }

    /**
     * Would the deterministic placement policy put this node among a world's expected holders?
     *
     * <p>The manifest root would be the ideal placement key, but a node that holds nothing of a
     * world has not seen its manifest yet — the very worlds this method exists to adopt. The world
     * id is the stable stand-in: it is equally well-known to every node, so every node still
     * computes the same list, which is the property that makes the policy coordinator-free.
     */
    private Placement placementFor(Bytes worldId) {
        TrackerResponse response;
        try {
            response = tracker.query(worldId).orElse(null);
        } catch (RuntimeException e) {
            return Placement.UNKNOWN;
        }
        if (response == null || response.peers().isEmpty()) {
            return Placement.UNKNOWN;
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
            return expected.contains(self) ? Placement.PLACED : Placement.NOT_PLACED;
        } catch (RuntimeException e) {
            // A malformed peer set (e.g. a host the tracker did not also list as a peer) is the
            // tracker's problem; declining to adopt is the safe reading.
            LOG.debug("placement for {} failed: {}", worldId.toShortHex(6), e.getMessage());
            return Placement.UNKNOWN;
        }
    }

    /** @return whether the policy expects a replica of {@code worldId} on this node. */
    private boolean placedFor(Bytes worldId) {
        return placementFor(worldId) == Placement.PLACED;
    }

    /**
     * Give the budget back: release volunteered replicas this node is no longer placed for.
     *
     * <h2>Why this exists</h2>
     *
     * <p>The sweep only ever grew. `withinBounds` stops adoptions once {@code budgetBytes} is used,
     * and nothing ever freed a byte of it, so placement was effectively decided once — by whichever
     * worlds happened to exist when a node first filled up. Placement is <b>not</b> a one-time
     * decision: it is a deterministic function of the peer set, and the peer set changes every time
     * anyone joins or leaves. A node that filled its budget in a five-peer swarm and then watched
     * the swarm grow to fifty was, from then on, holding worlds no policy expected it to hold and
     * refusing every world the policy did expect — permanently, and silently, because a full node's
     * sweep summary reports "past the bounds" and looks like the bound working.
     *
     * <h2>Why it is safe</h2>
     *
     * <p>Four rules, each removing one way this could destroy content that is still needed:
     *
     * <ul>
     *   <li><b>Only under pressure.</b> Nothing is released while the node is inside its budget. A
     *       release is a pressure valve, never tidiness — so a node that is not full behaves exactly
     *       as it did before, and a tracker misanswer costs it nothing.</li>
     *   <li><b>Only volunteered content.</b> A world this node hosts is its own; {@code seeding()}
     *       is the whole eligible set.</li>
     *   <li><b>Only on a real answer.</b> {@link Placement#UNKNOWN} — no tracker, no peers listed,
     *       a malformed set — keeps the world. This is the rule that matters most: without it a
     *       tracker outage would empty every full node on the network at once and then have them all
     *       re-fetch when it came back.</li>
     *   <li><b>Bounded.</b> One world per sweep, so even a systematically wrong answer drains a node
     *       slowly enough to be seen in the log it writes on the way.</li>
     * </ul>
     *
     * @return bytes released this sweep.
     */
    private long release(long heldBytes) {
        boolean budgetFull = heldBytes >= budgetBytes;
        if (!budgetFull) {
            return 0;
        }
        long freed = 0;
        int released = 0;
        for (WorldHostingService.HostedWorld world : hosting.hostedWorlds()) {
            if (released >= MAX_RELEASES_PER_SWEEP) {
                break;
            }
            if (!world.seeding()) {
                continue;
            }
            Bytes worldId;
            try {
                worldId = Bytes.fromHex(world.worldIdHex());
            } catch (RuntimeException notHex) {
                continue;
            }
            // The placement query is a network round trip, so it is asked only for a world that has
            // already passed every local reason not to release it.
            if (!shouldRelease(true, placementFor(worldId), true, released)) {
                continue;
            }
            WorldArchiveService.PieceReport report = archive.pieceReport(world.worldIdHex());
            long bytes = report == null ? 0 : report.totalBytes();
            // Announce STOPPED before dropping the bytes, so no window exists in which this node is
            // still listed as a holder of content it can no longer serve — the W-DUP-1 failure, in
            // reverse.
            hosting.stop(world.worldIdHex());
            archive.forget(world.worldIdHex());
            freed += bytes;
            released++;
            LOG.info("Released world '{}' ({}) — this node is no longer among its expected holders "
                            + "and its budget is full ({} byte(s) freed)",
                    world.name(), shortId(world.worldIdHex()), bytes);
        }
        return freed;
    }

    /**
     * Should this volunteered replica be given up? The sweep's release decision, as a pure function
     * of what is known about one world, so every safety rule can be exercised without a tracker.
     *
     * @param seeding            whether this node holds the world for the network rather than
     *                           hosting it. A hosted world is never released.
     * @param placement          what the policy answered. Only {@link Placement#NOT_PLACED} — an
     *                           actual answer that excludes this node — permits a release;
     *                           {@link Placement#UNKNOWN} keeps the world, because an outage must
     *                           not read as an eviction notice.
     * @param budgetFull         whether the node is at or over its replication budget. Releasing is
     *                           a pressure valve; an unfull node keeps everything it holds.
     * @param releasedThisSweep  how many worlds this sweep has already released.
     * @return whether to release.
     */
    static boolean shouldRelease(boolean seeding, Placement placement, boolean budgetFull,
                                 int releasedThisSweep) {
        if (!seeding || !budgetFull) {
            return false;
        }
        if (releasedThisSweep >= MAX_RELEASES_PER_SWEEP) {
            return false;
        }
        return placement == Placement.NOT_PLACED;
    }

    /** Fetch a world's archive and begin advertising it. @return bytes now held, or 0 on failure. */
    private long adopt(String worldIdHex, String worldName) {
        try {
            archive.fetchArchive(worldIdHex, FETCH_TIMEOUT);
        } catch (RuntimeException e) {
            // INFO, not debug. No seeder answering is ordinary in a small swarm and the next sweep
            // tries again — but this line is the ONLY account of why a world the sweep announced it
            // was fetching never arrived, and at debug it was invisible in every log anyone reads.
            // A live session showed "Supporting world 'Hello' — fetching its archive" followed by
            // silence and a summary of all-zeroes, with nothing anywhere to say what had happened.
            LOG.info("Replication of '{}' ({}) did not complete: {}", worldName,
                    shortId(worldIdHex), e.getMessage());
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

    /** Whether another volunteered replica may be adopted this sweep. */
    static boolean withinBounds(int adoptedThisSweep, long replicatedBytes, long budgetBytes) {
        return adoptedThisSweep < MAX_ADOPTIONS_PER_SWEEP && replicatedBytes < budgetBytes;
    }

    /**
     * Should this world be fetched now? The sweep's decision, as a pure function of what is known
     * about one world, so it can be exercised without a tracker.
     *
     * @param holdsCompletely whether this node already holds every piece.
     * @param hosts           whether this node announces the world as its own.
     * @param placedFor       whether the placement policy expects a replica here.
     * @param withinBounds    whether the replica bounds still allow an adoption.
     * @return whether to fetch.
     */
    static boolean shouldAdopt(boolean holdsCompletely, boolean hosts, boolean placedFor,
                               boolean withinBounds) {
        if (holdsCompletely) {
            return false;
        }
        // A claim with no content behind it is repaired regardless of placement or bounds: this node
        // is telling the network it serves a world it cannot serve, and no other node will fix that.
        if (hosts) {
            return true;
        }
        return withinBounds && placedFor;
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
