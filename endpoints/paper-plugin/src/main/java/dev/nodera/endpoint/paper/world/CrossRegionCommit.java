package dev.nodera.endpoint.paper.world;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.coordinator.RegionPipeline;
import dev.nodera.coordinator.WorldMutationApplier;
import dev.nodera.coordinator.entity.EntityTransferCoordinator;
import dev.nodera.storage.TransferStore;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A delta that spans two Nodera regions, on a server whose regions may be on different threads
 * (server task 4 deliverable 6, L-64).
 *
 * <h2>Stage 1 — the refusal</h2>
 *
 * <p>{@link #requireJointCriticalSection} answers the question the plugin could not previously even
 * ask: are these two regions written by one thread? If they are not, and no joint-transfer path is
 * configured, it throws {@link CrossRegionRefusedException} <b>before anything is written and
 * before any transfer stage is journalled</b>. That is the behaviour L-64 documents as today's, and
 * it is the half of the row's exit clause that matters most — "a failure on one side commits
 * neither" is trivially satisfied by a commit that never starts.
 *
 * <h2>Stage 2 — the joint transfer, and why it cannot deadlock</h2>
 *
 * <p>Folia's regioniser <b>refuses recursive operation</b>: a region thread may not synchronously
 * enter another region, and an uncaught exception on a tick thread halts the scheduler and stops
 * the whole server ({@code docs/minecraft/folia/06-schedulers.md}; the harness greps for exactly
 * that string). So the naive shape — thread A takes region A, blocks until thread B hands it region
 * B — is not merely slow here, it is a server-wide stall. It is also unnecessary.
 *
 * <p><b>The commit does not join two threads. It moves both regions' authority onto a third.</b>
 *
 * <ol>
 *   <li><b>Park.</b> Each region thread is sent a fire-and-forget task that captures its own
 *       region's committed snapshot and pipeline and completes a future with them. It captures
 *       nothing about the other region and waits for nothing.</li>
 *   <li><b>Commit.</b> When both futures have completed — observed by <i>composition</i>, on this
 *       class's own single thread, which is never a region thread — the whole
 *       {@link EntityTransferCoordinator} prepare/approve/apply/commit runs there, against the
 *       canonical {@link WorldMutationApplier}. No Bukkit state is touched, so no region ownership
 *       is required, and the two-pass compare-and-set is all-or-nothing by construction.</li>
 *   <li><b>Resume.</b> Each region thread is sent a second fire-and-forget task carrying its own
 *       certified delta (or nothing, on an abort) and takes its authority back.</li>
 * </ol>
 *
 * <p><b>The deadlock-freedom argument, stated so a reviewer can check it.</b> A deadlock needs a
 * cycle in the wait-for graph. Region threads never wait: every cross-thread step here is a message
 * (`dispatcher.execute`) and never a `get`, a `join`, or a lock. Only the commit thread waits, it
 * waits only on futures completed by region threads, it holds no region and no lock while doing so,
 * and its wait is bounded by {@code parkTimeout}. A graph in which only one node ever has an
 * outgoing wait edge has no cycle. The one way to reintroduce one is to call this from a region
 * thread and block on the returned future, so the commit body asserts it is not on a region thread
 * and the returned {@link CompletableFuture} is documented as never to be joined from one.
 *
 * <p><b>Atomicity is on the canonical side, not the projection.</b> Both regions commit in one
 * two-pass CAS on one thread, journalled through {@link TransferStoreJournal} with durable stages;
 * either both advance or neither does. The Bukkit-side projection that follows on each region
 * thread is a projection of an already-certified fact — a projection that fails is repaired by the
 * custody reconciler (server task 4 deliverable 4), never by half-committing.
 *
 * @Thread-context {@link #requireJointCriticalSection} is safe from a region thread.
 *                 {@link #commit} may be CALLED from a region thread — it only submits — but the
 *                 future it returns must not be joined from one.
 */
public final class CrossRegionCommit implements AutoCloseable {

    /** How a task reaches the thread that owns a region. */
    public interface RegionDispatcher {

        /**
         * Run {@code task} on the thread that owns {@code region}, <b>without waiting</b> for it.
         *
         * <p>On Folia this is {@code Bukkit.getRegionScheduler().execute(...)}; on Paper it is the
         * main-thread scheduler; in tests it is a one-thread executor per region.
         */
        void execute(RegionId region, Runnable task);

        /** @return whether the CALLING thread is one of the platform's region tick threads. */
        boolean onRegionThread();
    }

    /** What one region thread hands over at the park point, and takes back at the resume point. */
    public record RegionPark(RegionPipeline pipeline, RegionSnapshot snapshot) {
        public RegionPark {
            if (pipeline == null || snapshot == null) {
                throw new IllegalArgumentException("a park needs a pipeline and a snapshot");
            }
        }
    }

    /** The region thread's half of the handover. Both methods run ON that region's own thread. */
    public interface RegionAuthority {

        /** Capture this region's committed snapshot and pipeline at a tick boundary. */
        RegionPark park(RegionId region);

        /**
         * Take authority back.
         *
         * @param certified the region's own certified delta when the joint commit succeeded, or
         *                  {@code null} when it did not — in which case nothing is projected and
         *                  the region resumes exactly where it parked. An implementation must put a
         *                  pipeline still {@code PAUSED_FOR_XR} back to {@code ACTIVE}
         *                  ({@link RegionPipeline#crossRegionAborted()}), because the coordinator
         *                  leaves it paused on the paths it refuses. It must also tolerate a region
         *                  that never parked: an abandoned attempt resumes both sides rather than
         *                  reasoning about which of them got that far.
         */
        void resume(RegionId region, RegionDelta certified);
    }

    private final NoderaFoliaRegionMap regions;
    private final RegionDispatcher dispatcher;
    private final RegionAuthority authority;
    private final EntityTransferCoordinator coordinator;
    private final TransferStoreJournal journal;
    private final ExecutorService commitThread;
    private final Consumer<String> log;

    private CrossRegionCommit(
            NoderaFoliaRegionMap regions,
            RegionDispatcher dispatcher,
            RegionAuthority authority,
            WorldMutationApplier applier,
            EntityTransferCoordinator.TransferApprovalProvider approvals,
            TransferStore transfers,
            Consumer<String> log) {
        if (regions == null) {
            throw new IllegalArgumentException("a region map is required");
        }
        this.regions = regions;
        this.dispatcher = dispatcher;
        this.authority = authority;
        this.log = log == null ? line -> { } : log;
        this.journal = transfers == null ? null : new TransferStoreJournal(transfers);
        if (dispatcher == null) {
            this.coordinator = null;
            this.commitThread = null;
        } else {
            this.coordinator = new EntityTransferCoordinator(applier, approvals, journal);
            // Its own thread, deliberately: it must never be a region thread, and naming it makes
            // that visible in a thread dump rather than inferable from a stack trace.
            this.commitThread = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nodera-xregion-commit");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    /**
     * Stage 1: detect a cross-thread span and refuse it. Nothing is written, ever.
     *
     * <p>This is the constructor an endpoint uses until stage 2 is proven on a real Folia.
     */
    public static CrossRegionCommit refusing(NoderaFoliaRegionMap regions) {
        return refusing(regions, null);
    }

    /**
     * Stage 1, holding the endpoint's durable transfer store.
     *
     * <p>The store is here so the refusal's central claim — "no transfer stage was journalled" —
     * is a property of an object that <b>could</b> have written one, rather than of an object that
     * had nothing to write to.
     */
    public static CrossRegionCommit refusing(
            NoderaFoliaRegionMap regions, TransferStore transfers) {
        return new CrossRegionCommit(regions, null, null, null, null, transfers, null);
    }

    /**
     * Stage 2: commit a cross-thread span atomically through the joint-transfer path.
     *
     * @param transfers the durable stage store L-64's exit clause names.
     */
    public static CrossRegionCommit joint(
            NoderaFoliaRegionMap regions,
            RegionDispatcher dispatcher,
            RegionAuthority authority,
            WorldMutationApplier applier,
            EntityTransferCoordinator.TransferApprovalProvider approvals,
            TransferStore transfers,
            Consumer<String> log) {
        if (dispatcher == null || authority == null || applier == null || approvals == null
                || transfers == null) {
            throw new IllegalArgumentException(
                    "the joint-transfer path needs a dispatcher, region authority, applier, "
                            + "approvals and a durable transfer store");
        }
        return new CrossRegionCommit(
                regions, dispatcher, authority, applier, approvals, transfers, log);
    }

    /** @return whether this instance can actually commit a cross-thread span (stage 2). */
    public boolean jointTransferAvailable() {
        return coordinator != null;
    }

    /**
     * The gate every multi-region delta passes before a single write.
     *
     * @throws CrossRegionRefusedException when the two regions are not written by one thread and no
     *                                     joint-transfer path is configured. Nothing is written and
     *                                     no stage is journalled.
     */
    public void requireJointCriticalSection(long transferId, RegionId source, RegionId target) {
        if (regions.shareExecutionThread(source, target) || jointTransferAvailable()) {
            return;
        }
        throw new CrossRegionRefusedException(transferId, source, target,
                regions.regionised()
                        ? "are written by different Folia region threads"
                        : "cannot be shown to share an execution thread");
    }

    /**
     * Commit one cross-region transfer atomically across both regions' threads.
     *
     * <p>Safe to call from a region thread; the returned future must not be joined from one.
     *
     * @param certifiedSourceDelta the source region's next certified transition, carrying exactly
     *                             one transfer intent naming {@code target}.
     * @param parkTimeout          how long a region thread has to reach its park point before the
     *                             attempt is abandoned and both regions resumed untouched.
     * @return the transfer outcome; completes exceptionally when nothing was committed.
     */
    public CompletableFuture<EntityTransferCoordinator.TransferOutcome> commit(
            long transferId,
            RegionId source,
            RegionId target,
            RegionDelta certifiedSourceDelta,
            long tick,
            Duration parkTimeout) {
        requireJointCriticalSection(transferId, source, target);
        if (!jointTransferAvailable()) {
            // Stage 1 only. The span shares a thread (or the gate above would have refused it), so
            // it belongs on that thread's own applier — deliverable 2, not this class.
            throw new IllegalStateException(
                    "no joint-transfer path is configured; a span that shares one execution thread "
                            + "commits on that thread (server task 4 deliverable 2)");
        }

        CompletableFuture<RegionPark> sourcePark = parkOn(source, parkTimeout);
        CompletableFuture<RegionPark> targetPark = parkOn(target, parkTimeout);
        CompletableFuture<EntityTransferCoordinator.TransferOutcome> done =
                new CompletableFuture<>();

        sourcePark.thenCombine(targetPark, Parked::new).whenCompleteAsync((parked, failure) -> {
            if (failure != null) {
                // Hand BOTH regions their authority back before giving up, unconditionally.
                // Resuming only the side whose future completed is not enough: when the failure is
                // the park TIMEOUT, the other region's park task may still be queued and will pause
                // that region a moment from now, with nobody left to un-pause it. A region thread
                // runs its tasks in submission order, so a resume queued here always lands after a
                // park that is still pending — which is why `resume` must tolerate a region that
                // never parked.
                resume(source);
                resume(target);
                journal.aborted(transferId, "park failed: " + rootCause(failure));
                log.accept(CrossRegionRefusedException.CODE + ": transfer " + transferId
                        + " never reached its park point (" + rootCause(failure)
                        + "); both regions resumed untouched");
                done.completeExceptionally(failure);
                return;
            }
            runCommit(transferId, source, target, certifiedSourceDelta, tick, parked, done);
        }, commitThread);
        return done;
    }

    private void runCommit(
            long transferId,
            RegionId source,
            RegionId target,
            RegionDelta certifiedSourceDelta,
            long tick,
            Parked parked,
            CompletableFuture<EntityTransferCoordinator.TransferOutcome> done) {
        if (dispatcher.onRegionThread()) {
            // Unreachable by construction (commitThread is ours), and asserted rather than assumed:
            // a joint commit that ran on a region thread would be a recursive regioniser operation,
            // which halts the tick pool and stops the server.
            resume(source);
            resume(target);
            done.completeExceptionally(new IllegalStateException(
                    CrossRegionRefusedException.CODE + ": the joint commit reached a region thread;"
                            + " Folia refuses recursive regioniser operation"));
            return;
        }
        EntityTransferCoordinator.TransferOutcome outcome;
        try {
            outcome = coordinator.transfer(
                    transferId, parked.source().pipeline(), parked.target().pipeline(),
                    parked.source().snapshot(), parked.target().snapshot(),
                    certifiedSourceDelta, tick);
        } catch (RuntimeException refused) {
            // The coordinator's own contract is that a rejected transfer leaves both pipelines and
            // the world exactly as they were, so resuming with no delta is the whole of the undo.
            resume(source);
            resume(target);
            done.completeExceptionally(refused);
            return;
        }
        if (outcome instanceof EntityTransferCoordinator.TransferResult result) {
            dispatcher.execute(source, () -> authority.resume(source, result.sourceDelta()));
            dispatcher.execute(target, () -> authority.resume(target, result.targetDelta()));
            log.accept("cross-region commit " + transferId + ": " + source + " -> " + target
                    + " committed on both threads");
        } else {
            resume(source);
            resume(target);
            log.accept(CrossRegionRefusedException.CODE + ": transfer " + transferId
                    + " failed its paired compare-and-set; NEITHER region advanced");
        }
        done.complete(outcome);
    }

    private CompletableFuture<RegionPark> parkOn(RegionId region, Duration timeout) {
        CompletableFuture<RegionPark> parked = new CompletableFuture<>();
        dispatcher.execute(region, () -> {
            try {
                parked.complete(authority.park(region));
            } catch (RuntimeException | Error unavailable) {
                parked.completeExceptionally(unavailable);
            }
        });
        return parked.orTimeout(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
    }

    /** Hand a region's authority back with nothing to project. */
    private void resume(RegionId region) {
        dispatcher.execute(region, () -> authority.resume(region, null));
    }

    private static String rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    /** Stop the commit thread. Idempotent; the region threads are the platform's, never ours. */
    @Override
    public void close() {
        if (commitThread != null) {
            commitThread.shutdownNow();
        }
    }

    private record Parked(RegionPark source, RegionPark target) {
    }
}
