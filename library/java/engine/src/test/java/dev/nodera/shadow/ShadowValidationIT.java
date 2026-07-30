package dev.nodera.shadow;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.rules.FlatWorldRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headless Phase 1 shadow-validation soak (Task 5) — the executable stand-in for the
 * deliverable's manual multi-client scenario. Three worker runtimes shadow the same region while a
 * scripted random place/break stream is replayed; the run asserts <b>zero unexplained
 * divergence</b>. A second scenario proves a lying worker (corrupted root) is caught and its region
 * re-snapshotted. This is the Nodera debugger's {@code shadow-recompute} scenario, run entirely over
 * the {@link dev.nodera.simulation.engine.FlatWorldRegionEngine} with no NeoForge server.
 */
class ShadowValidationIT {

    private static final int WORKERS = 3;
    private static final int BATCHES = 250;

    private final List<WorkerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        runtimes.forEach(WorkerRuntime::close);
    }

    private ShadowWorker newWorker(long id, SessionParams params, RegionEngine engine) {
        WorkerRuntime rt = new WorkerRuntime(engine);
        rt.activate();
        runtimes.add(rt);
        return new ShadowWorker(Fixtures.node(id), rt, new ReplicaStore(16), params);
    }

    private ActionBatch randomBatch(RegionId region, SnapshotVersion base, long tick, Random rng) {
        int n = 1 + rng.nextInt(4);
        List<ActionEnvelope> acts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int x = rng.nextInt(128); // owned block coords [0, 128)
            int z = rng.nextInt(128);
            int y = 1 + rng.nextInt(200); // safely inside [-64, 319]
            long seq = tick * 16 + i;
            if (rng.nextBoolean()) {
                acts.add(Fixtures.place(region, seq, tick, x, y, z, 1 + rng.nextInt(8)));
            } else {
                acts.add(Fixtures.brk(region, seq, tick, x, y, z));
            }
        }
        return Fixtures.batch(region, base, tick, tick, acts);
    }

    @Test
    void threeWorkersShadowRandomPlayWithZeroDivergence() {
        SessionParams params = Fixtures.params();
        RegionId region = Fixtures.region(0, 0);

        ShadowCoordinator coord = new ShadowCoordinator(params, Fixtures.engine(), 16);
        coord.seedRegion(Fixtures.fullUniformSnapshot(region, FlatWorldRules.AIR));

        List<ShadowWorker> workers = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            ShadowWorker w = newWorker(100 + i, params, Fixtures.engine());
            coord.registerWorker(w);
            coord.assign(w.nodeId(), region);
            workers.add(w);
        }

        Random rng = new Random(0xC0FFEE);
        SnapshotVersion base = SnapshotVersion.INITIAL;
        for (long tick = 0; tick < BATCHES; tick++) {
            coord.submitBatch(randomBatch(region, base, tick, rng));
            base = base.next();
        }

        ShadowMetrics.Stats stats = coord.metrics().stats();
        assertThat(stats.mismatches()).isZero();
        assertThat(coord.tracker().divergences()).isEmpty();
        assertThat(stats.matches()).isEqualTo((long) BATCHES * WORKERS);
        assertThat(stats.batches()).isEqualTo(BATCHES);
        assertThat(stats.resyncs()).isZero();
        assertThat(stats.clean()).isTrue();
        assertThat(stats.totalBytesOut()).isPositive();

        // Every worker's replica advanced in lockstep with the reference chain.
        SnapshotVersion finalVersion = new SnapshotVersion(BATCHES);
        assertThat(coord.referenceSnapshot(region).version()).isEqualTo(finalVersion);
        for (ShadowWorker w : workers) {
            assertThat(w.replicas().version(region)).isEqualTo(finalVersion);
            // The replica the worker advanced independently is byte-identical to the reference chain.
            assertThat(Fixtures.rootOf(w.replicas().get(region)))
                    .isEqualTo(Fixtures.rootOf(coord.referenceSnapshot(region)));
        }
    }

    @Test
    void lyingWorkerIsCaughtAndRegionReSnapshotted() {
        SessionParams params = Fixtures.params();
        RegionId region = Fixtures.region(0, 0);

        ShadowCoordinator coord = new ShadowCoordinator(params, Fixtures.engine(), 16);
        coord.seedRegion(Fixtures.fullUniformSnapshot(region, FlatWorldRules.AIR));

        ShadowWorker honest = newWorker(200, params, Fixtures.engine());
        // A worker whose engine reports a corrupted root but a real delta — it diverges on the root.
        ShadowWorker liar = newWorker(201, params, corruptingEngine(Fixtures.engine()));
        NodeId liarId = liar.nodeId();

        coord.registerWorker(honest);
        coord.registerWorker(liar);
        coord.assign(honest.nodeId(), region);
        coord.assign(liarId, region);

        ActionBatch batch = Fixtures.batch(region, SnapshotVersion.INITIAL, 0, 0,
                List.of(Fixtures.place(region, 1, 0, 5, 70, 5, FlatWorldRules.STONE)));
        coord.submitBatch(batch);

        List<DivergenceRecord> divergences = coord.tracker().divergences();
        assertThat(divergences).hasSize(1);
        assertThat(divergences.get(0).clientNodeId()).isEqualTo(liarId);
        assertThat(coord.metrics().stats().matches()).isEqualTo(1); // the honest worker matched
        assertThat(coord.metrics().stats().mismatches()).isEqualTo(1);

        // The diverging region was re-snapshotted: poison cleared and the liar's replica realigned
        // to the reference version.
        assertThat(coord.tracker().isPoisoned(region)).isFalse();
        assertThat(liar.replicas().version(region))
                .isEqualTo(coord.referenceSnapshot(region).version());
    }

    private static RegionEngine corruptingEngine(RegionEngine real) {
        return request -> {
            RegionExecutionResult r = real.execute(request);
            byte[] bytes = r.resultingRoot().hash().toArray();
            bytes[0] ^= (byte) 0xFF; // flip the root so the report diverges; delta stays honest
            StateRoot bad = StateRoot.of(Bytes.unsafeWrap(bytes));
            return new RegionExecutionResult(r.delta(), bad, r.stats());
        };
    }
}
