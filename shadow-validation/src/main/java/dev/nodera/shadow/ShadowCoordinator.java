package dev.nodera.shadow;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionRequest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The headless shadow orchestrator (Task 5): the Minecraft-free heart of the Phase 1 pipeline. It
 * owns the server's authoritative <b>reference chain</b> (a {@link ReplicaStore}), assigns regions
 * to registered {@link ShadowWorker}s, and on each relayed {@link ActionBatch}:
 *
 * <ol>
 *   <li>recomputes the reference root+delta via {@link ServerRecompute} (with the intra-JVM
 *       self-check) and advances the reference chain;</li>
 *   <li>hands the batch to every worker assigned to the region, comparing each root against the
 *       reference via the {@link DivergenceTracker};</li>
 *   <li>re-snapshots any worker that fell out of sync (and any poisoned region).</li>
 * </ol>
 *
 * <p>This is the executable stand-in for the deliverable's manual multi-client soak: register N
 * workers, replay random place/break batches, and assert zero divergence — exactly what
 * {@code ShadowValidationIT} does over the {@link dev.nodera.simulation.engine.FlatWorldRegionEngine}.
 * Nothing here is ever committed; Phase 1 is observation only.
 *
 * @Thread-context single-thread-confined (the server tick / shadow-orchestrator thread). Workers run
 *                 their engine off-thread inside their own {@link WorkerRuntime}.
 */
public final class ShadowCoordinator {

    private final SessionParams params;
    private final ServerRecompute serverRecompute;
    private final ReplicaStore referenceChain;
    private final DivergenceTracker tracker = new DivergenceTracker();

    private final Map<NodeId, ShadowWorker> workers = new LinkedHashMap<>();
    private final Map<NodeId, Set<RegionId>> assignments = new HashMap<>();

    /**
     * @param params            the session's deterministic parameters.
     * @param serverEngine      the reference engine (its rulesVersion/fingerprint must match params).
     * @param maxReferenceRegions bound on the reference chain's replica cache.
     * @throws IllegalArgumentException if any argument is null or {@code maxReferenceRegions < 1}.
     */
    public ShadowCoordinator(SessionParams params, RegionEngine serverEngine, int maxReferenceRegions) {
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        this.params = params;
        this.serverRecompute = new ServerRecompute(serverEngine);
        this.referenceChain = new ReplicaStore(maxReferenceRegions);
    }

    /** Seed the reference chain for a region (the shadow lane starts from vanilla state). */
    public void seedRegion(RegionSnapshot snapshot) {
        referenceChain.seed(snapshot);
    }

    /** Register a worker (idempotent per node id). */
    public void registerWorker(ShadowWorker worker) {
        if (worker == null) {
            throw new IllegalArgumentException("worker must not be null");
        }
        workers.putIfAbsent(worker.nodeId(), worker);
        assignments.computeIfAbsent(worker.nodeId(), k -> new HashSet<>());
    }

    /**
     * Assign {@code region} to {@code worker}: stream it the current reference snapshot and start
     * relaying that region's batches to it.
     *
     * @throws IllegalStateException if the region has not been seeded, or the worker is unregistered.
     */
    public void assign(NodeId worker, RegionId region) {
        ShadowWorker w = workers.get(worker);
        if (w == null) {
            throw new IllegalStateException("unregistered worker " + worker);
        }
        RegionSnapshot seed = referenceChain.get(region);
        if (seed == null) {
            throw new IllegalStateException("seed region " + region + " before assigning it");
        }
        w.assign(seed);
        assignments.get(worker).add(region);
        tracker.metrics().recordSnapshotBytes(encodedSize(seed));
    }

    /**
     * Relay one captured batch: recompute the reference, advance the chain, and validate every
     * assigned worker's shadow result against it.
     *
     * @param batch the captured action batch (its {@code baseVersion} must equal the reference
     *              chain's current version for the region).
     * @throws IllegalStateException if the region was never seeded.
     */
    public void submitBatch(ActionBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        RegionId region = batch.region();
        RegionSnapshot base = referenceChain.get(region);
        if (base == null) {
            throw new IllegalStateException("region " + region + " was never seeded");
        }

        RegionExecutionRequest request = new RegionExecutionRequest(params.contextFor(batch), base, batch);
        ServerRecompute.Reference ref = serverRecompute.reference(request);
        referenceChain.advance(ref.delta(), batch.tickTo());
        tracker.metrics().recordBatch(encodedSize(batch));

        for (Map.Entry<NodeId, Set<RegionId>> e : assignments.entrySet()) {
            if (!e.getValue().contains(region)) {
                continue;
            }
            ShadowWorker worker = workers.get(e.getKey());
            ShadowOutcome outcome = worker.execute(batch);
            switch (outcome) {
                case ShadowOutcome.Computed c -> {
                    // A diverging worker is re-snapshotted so one bad batch does not cascade into a
                    // storm of dependent false mismatches; an in-sync worker is left untouched.
                    if (!tracker.compare(ref.root(), c.result())) {
                        reseed(worker, region);
                    }
                }
                case ShadowOutcome.Resync r -> {
                    tracker.metrics().recordResync();
                    reseed(worker, region);
                }
            }
        }
    }

    private void reseed(ShadowWorker worker, RegionId region) {
        RegionSnapshot latest = referenceChain.get(region);
        worker.assign(latest);
        tracker.metrics().recordSnapshotBytes(encodedSize(latest));
        tracker.clearPoison(region);
    }

    /** @return the divergence tracker (records + poison state). */
    public DivergenceTracker tracker() {
        return tracker;
    }

    /** @return the shadow counters. */
    public ShadowMetrics metrics() {
        return tracker.metrics();
    }

    /** @return the reference chain's current snapshot for {@code region}, or {@code null}. */
    public RegionSnapshot referenceSnapshot(RegionId region) {
        return referenceChain.get(region);
    }

    private static long encodedSize(Encodable e) {
        CanonicalWriter w = new CanonicalWriter();
        e.encode(w);
        return w.toBytes().length();
    }
}
