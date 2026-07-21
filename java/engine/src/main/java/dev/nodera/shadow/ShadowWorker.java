package dev.nodera.shadow;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;

/**
 * One client's shadow-lane worker (Task 5): holds the client's {@link ReplicaStore}, executes
 * relayed {@link ActionBatch}es on its {@link WorkerRuntime}, advances its local replica by the
 * resulting delta, and reports a root-only {@link ShadowResult}. If its replica is missing or at the
 * wrong version it refuses to guess and returns a {@link ShadowOutcome.Resync}.
 *
 * @Thread-context confined to the owning worker thread (the coordinator drives one worker at a
 *                 time); the underlying {@link WorkerRuntime} runs the engine off-thread.
 */
public final class ShadowWorker {

    private final NodeId nodeId;
    private final WorkerRuntime runtime;
    private final ReplicaStore replicas;
    private final SessionParams params;

    /**
     * @param nodeId   this client's node id (stamped on every {@link ShadowResult}).
     * @param runtime  the client's worker executor (must be ACTIVE before {@link #execute}).
     * @param replicas the client's bounded replica store.
     * @param params   the session's deterministic parameters.
     * @throws IllegalArgumentException if any argument is null.
     */
    public ShadowWorker(NodeId nodeId, WorkerRuntime runtime, ReplicaStore replicas, SessionParams params) {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        if (runtime == null) {
            throw new IllegalArgumentException("runtime must not be null");
        }
        if (replicas == null) {
            throw new IllegalArgumentException("replicas must not be null");
        }
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        this.nodeId = nodeId;
        this.runtime = runtime;
        this.replicas = replicas;
        this.params = params;
    }

    /** @return this worker's node id. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** @return this worker's replica store (for assertion in tests / HUD in the mod). */
    public ReplicaStore replicas() {
        return replicas;
    }

    /** Seed (or re-seed after a resync) the replica for {@code snapshot.region()}. */
    public void assign(RegionSnapshot snapshot) {
        replicas.seed(snapshot);
    }

    /**
     * Execute one relayed batch against the local replica.
     *
     * @param batch the relayed action batch.
     * @return {@link ShadowOutcome.Computed} with the root-only result, or
     *         {@link ShadowOutcome.Resync} if the replica is missing / at the wrong version.
     * @throws IllegalArgumentException if {@code batch} is null.
     */
    public ShadowOutcome execute(ActionBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        RegionId region = batch.region();
        RegionSnapshot replica = replicas.get(region);
        if (replica == null) {
            return new ShadowOutcome.Resync(region, batch.baseVersion(), ShadowOutcome.Reason.NO_REPLICA);
        }
        if (!replica.version().equals(batch.baseVersion())) {
            return new ShadowOutcome.Resync(region, batch.baseVersion(), ShadowOutcome.Reason.VERSION_MISMATCH);
        }

        RegionExecutionRequest request =
                new RegionExecutionRequest(params.contextFor(batch), replica, batch);
        long t0 = System.nanoTime();
        RegionExecutionResult result = runtime.execute(request).join();
        long workerNanos = System.nanoTime() - t0;

        // Advance the local replica so the next batch (baseVersion = this resultingVersion) lines up.
        replicas.advance(result.delta(), batch.tickTo());

        return new ShadowOutcome.Computed(new ShadowResult(
                region, batch.epoch(), batch.baseVersion(),
                result.delta().resultingVersion(), result.resultingRoot(), nodeId, workerNanos));
    }
}
