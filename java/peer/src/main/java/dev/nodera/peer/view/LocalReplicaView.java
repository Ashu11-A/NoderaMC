package dev.nodera.peer.view;

import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Task-16a local-replica view core (issue #35): the Minecraft-free state machine behind the
 * seamless-handover renderer. Each client already re-executes and votes for its own FOV regions
 * ({@code ClientValidationLane}); this view turns that committed stream into something a renderer
 * can read continuously — committed state plus a locally-predicted overlay — so a departed host
 * changes nothing on screen (the committed base simply stops advancing until the committee
 * re-forms; the view keeps rendering).
 *
 * <p><b>Prediction.</b> {@link #predict(ActionEnvelope)} appends a locally-captured action to the
 * region's overlay and re-executes it on top of the committed base through THE deterministic
 * engine — the render is always a real engine product, never a hand-mutated snapshot.
 *
 * <p><b>Reconciliation.</b> {@link #committed(RegionSnapshot, StateRoot)} adopts a new committed
 * base (fed from the validation lane's applied {@code CommitAnnounce}s). Predictions the commit
 * confirmed (match ⇒ drop) fall out naturally: the overlay is re-executed on the new base and any
 * prediction the engine now rejects — or that produced state the commit already contains — is
 * discarded (mismatch ⇒ visual rollback to certified truth). The committed base is authoritative;
 * the overlay is only ever cosmetic latency-hiding.
 *
 * <p>Thread-context: all public methods {@code synchronized}; safe from any thread. Rendering
 * reads are cheap (cached; recomputed only when base or overlay change).
 */
public final class LocalReplicaView {

    private final RegionEngine engine;
    private final HashService hashes;
    private final long worldSeed;
    private final int rulesVersion;
    private final long registryFingerprint;
    private final Map<RegionId, RegionState> regions = new LinkedHashMap<>();

    public LocalReplicaView(
            RegionEngine engine, HashService hashes,
            long worldSeed, int rulesVersion, long registryFingerprint) {
        if (engine == null || hashes == null) {
            throw new IllegalArgumentException("engine and hashes must not be null");
        }
        this.engine = engine;
        this.hashes = hashes;
        this.worldSeed = worldSeed;
        this.rulesVersion = rulesVersion;
        this.registryFingerprint = registryFingerprint;
    }

    /** Activate a region with its committed base snapshot (FOV plan activation). */
    public synchronized void activate(RegionSnapshot base, RegionEpoch epoch) {
        if (base == null || epoch == null) {
            throw new IllegalArgumentException("base and epoch must not be null");
        }
        regions.put(base.region(), new RegionState(base, epoch));
    }

    /** Stop tracking a region (FOV plan deactivation). */
    public synchronized void deactivate(RegionId region) {
        regions.remove(region);
    }

    /**
     * Append a locally-captured action to the region's prediction overlay. Ignored (returns
     * false) when the region is not tracked or the engine rejects the overlay re-execution —
     * a prediction that cannot execute must never corrupt the render.
     */
    public synchronized boolean predict(ActionEnvelope action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        RegionState state = regions.get(action.region());
        if (state == null) {
            return false;
        }
        List<ActionEnvelope> candidate = new ArrayList<>(state.predictions);
        candidate.add(action);
        Optional<RegionSnapshot> rendered = execute(state, candidate);
        if (rendered.isEmpty()) {
            return false;
        }
        state.predictions = candidate;
        state.rendered = rendered.get();
        return true;
    }

    /**
     * Adopt a newly-committed base for the region (from the validation lane's applied commit)
     * and reconcile the overlay: predictions the commit absorbed or invalidated are dropped,
     * survivors are re-executed on the new base.
     *
     * @param snapshot the committed snapshot.
     * @param root     the certified resulting root; must hash-match {@code snapshot}.
     */
    public synchronized void committed(RegionSnapshot snapshot, StateRoot root) {
        if (snapshot == null || root == null) {
            throw new IllegalArgumentException("snapshot and root must not be null");
        }
        if (!StateRoot.of(hashes.hash(snapshot)).equals(root)) {
            throw new IllegalArgumentException("committed snapshot does not hash to certified root");
        }
        RegionState state = regions.get(snapshot.region());
        if (state == null) {
            return;
        }
        if (snapshot.version().value() <= state.committed.version().value()) {
            return; // stale/duplicate announce — committed state only advances
        }
        state.committed = snapshot;
        // Reconcile: re-execute the overlay on the certified base. A prediction is dropped when
        // it no longer executes (its target was consumed by the commit, or it conflicts) OR when
        // it changes nothing on top of the base (the commit absorbed it — e.g. an idempotent
        // re-place). Both cases ARE the visual rollback/hand-off to certified truth.
        List<ActionEnvelope> survivors = new ArrayList<>();
        for (ActionEnvelope prediction : state.predictions) {
            List<ActionEnvelope> candidate = new ArrayList<>(survivors);
            candidate.add(prediction);
            Optional<RegionSnapshot> with = execute(state, candidate);
            if (with.isEmpty()) {
                continue;
            }
            RegionSnapshot without = execute(state, survivors).orElse(state.committed);
            if (sameContent(with.get(), without)) {
                continue;
            }
            survivors.add(prediction);
        }
        state.predictions = survivors;
        state.rendered = survivors.isEmpty()
                ? state.committed : execute(state, survivors).orElse(state.committed);
    }

    /** Content equality (chunks + entities), ignoring the version/tick bump an execution adds. */
    private static boolean sameContent(RegionSnapshot a, RegionSnapshot b) {
        return a.chunks().equals(b.chunks()) && a.entities().equals(b.entities());
    }

    /**
     * @return what the renderer should draw for {@code region}: committed state with the
     *         prediction overlay applied — or empty when the region is not tracked. Never blocks
     *         on the network; a departed host simply stops advancing the committed base while
     *         this render stays live (the issue-#35 no-seam invariant).
     */
    public synchronized Optional<RegionSnapshot> render(RegionId region) {
        RegionState state = regions.get(region);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(state.rendered != null ? state.rendered : state.committed);
    }

    /** @return the certified base the overlay currently sits on, for diagnostics/tests. */
    public synchronized Optional<RegionSnapshot> committedBase(RegionId region) {
        return Optional.ofNullable(regions.get(region)).map(state -> state.committed);
    }

    /** @return how many predictions are currently overlaid on {@code region}. */
    public synchronized int pendingPredictions(RegionId region) {
        RegionState state = regions.get(region);
        return state == null ? 0 : state.predictions.size();
    }

    private Optional<RegionSnapshot> execute(RegionState state, List<ActionEnvelope> overlay) {
        if (overlay.isEmpty()) {
            return Optional.of(state.committed);
        }
        long tickFrom = overlay.stream().mapToLong(ActionEnvelope::targetTick).min().orElseThrow();
        long tickTo = overlay.stream().mapToLong(ActionEnvelope::targetTick).max().orElseThrow();
        long next = state.committed.tick() + 1;
        ActionBatch batch = new ActionBatch(
                state.committed.region(), state.epoch, state.committed.version(),
                Math.min(tickFrom, next), Math.max(tickTo, next), overlay);
        RegionExecutionContext ctx = new RegionExecutionContext(
                state.committed.region(), state.epoch, state.committed.version(),
                batch.tickFrom(), batch.tickTo(), worldSeed, rulesVersion, registryFingerprint);
        try {
            RegionExecutionResult result = engine.execute(
                    new RegionExecutionRequest(ctx, state.committed, batch));
            if (result.stats().actionsRejected() > 0) {
                return Optional.empty();
            }
            return Optional.of(SnapshotDeltaApplier.apply(
                    state.committed, result.delta(), batch.tickTo()));
        } catch (RuntimeException rejected) {
            return Optional.empty();
        }
    }

    private static final class RegionState {
        RegionSnapshot committed;
        RegionSnapshot rendered;
        final RegionEpoch epoch;
        List<ActionEnvelope> predictions = new ArrayList<>();

        RegionState(RegionSnapshot committed, RegionEpoch epoch) {
            this.committed = committed;
            this.rendered = committed;
            this.epoch = epoch;
        }
    }
}
