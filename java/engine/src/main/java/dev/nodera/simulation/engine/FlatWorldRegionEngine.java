package dev.nodera.simulation.engine;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.border.BorderClassifier;
import dev.nodera.simulation.rules.ActionRejection;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.simulation.rules.EntityRuleSet;
import dev.nodera.simulation.rules.RuleSet;
import dev.nodera.core.region.RegionBounds;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The deterministic flat-world {@link RegionEngine} (Task 3). Wires together the border filter,
 * per-action {@link DeterministicRandom}, {@link FlatWorldRules}, and {@link MutableRegionState},
 * and emits a {@link RegionExecutionResult} whose root is the SHA-256 of the post-state snapshot.
 *
 * <p><b>Pure function of its arguments.</b> No clocks, no IO, no static mutable state, no RNG other
 * than {@link DeterministicRandom}. The forbidden-API ArchUnit test in this module enforces the
 * clock/IO bans; the jqwik property test proves that {@code execute(request)} twice yields identical
 * {@code resultingRoot} and identical canonical delta bytes, and that two batches differing at the
 * section level yield different roots.
 *
 * <p><b>Contract of {@code execute}.</b>
 * <ol>
 *   <li>Pure function of {@code (context, snapshot, batch)}.</li>
 *   <li>Actions applied in {@link dev.nodera.core.action.ActionBatch#actions()} order; rejected
 *       actions do not mutate state and are recorded (non-hashed) in
 *       {@link RegionExecutionResult.ExecutionStats#rejections()}.</li>
 *   <li>{@code resultingRoot} = SHA-256 of the post-state {@link RegionSnapshot} at
 *       {@code context.baseVersion().next()} and tick {@code context.tickTo()}. Root is truth; the
 *       delta is transport.</li>
 *   <li>A cross-region action reaching the engine, or a halo write, throws
 *       {@link IllegalStateException} (the coordinator must filter first; the engine asserts it).</li>
 *   <li>A {@code rulesVersion} / {@code registryFingerprint} mismatch throws — mixed-version or
 *       mixed-palette committees must never validate each other.</li>
 * </ol>
 *
 * <p><b>Timing.</b> {@link RegionExecutionResult.ExecutionStats#engineNanos()} is always zero here:
 * reading a clock is forbidden inside the engine (Task 0 §6). Callers that need timing measure it
 * around {@code execute}.
 *
 * @Thread-context thread-confined per call; the engine holds no per-call mutable state across
 *                 invocations and may be used from many threads (one call each).
 */
public final class FlatWorldRegionEngine implements RegionEngine {

    private final int rulesVersion;
    private final long expectedRegistryFingerprint;
    private final HashService hashService;
    private final RuleSet ruleSet;

    /**
     * @param rulesVersion              the rules version this engine supports; refused requests
     *                                  whose context advertises a different value.
     * @param expectedRegistryFingerprint the palette fingerprint this engine supports; refused
     *                                  requests whose context advertises a different value.
     * @param hashService               the SHA-256 service used to compute the post-state root.
     * @throws IllegalArgumentException if the parameters do not match {@link FlatWorldRules}'s own
     *                                  version/fingerprint (an engine must use exactly the rule set
     *                                  it advertises).
     * @Thread-context deterministic; safe from any thread.
     */
    public FlatWorldRegionEngine(int rulesVersion, long expectedRegistryFingerprint, HashService hashService) {
        if (hashService == null) {
            throw new IllegalArgumentException("hashService must not be null");
        }
        if (rulesVersion != FlatWorldRules.RULES_VERSION) {
            throw new IllegalArgumentException(
                    "rulesVersion " + rulesVersion + " does not match FlatWorldRules.RULES_VERSION "
                            + FlatWorldRules.RULES_VERSION);
        }
        if (expectedRegistryFingerprint != FlatWorldRules.registryFingerprint()) {
            throw new IllegalArgumentException(
                    "expectedRegistryFingerprint does not match FlatWorldRules.registryFingerprint()");
        }
        this.rulesVersion = rulesVersion;
        this.expectedRegistryFingerprint = expectedRegistryFingerprint;
        this.hashService = hashService;
        this.ruleSet = new EntityRuleSet();
    }

    @Override
    public RegionExecutionResult execute(RegionExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        RegionExecutionContext ctx = request.context();
        if (ctx.rulesVersion() != this.rulesVersion) {
            throw new IllegalStateException(
                    "rulesVersion mismatch: request=" + ctx.rulesVersion()
                            + ", engine=" + this.rulesVersion);
        }
        if (ctx.registryFingerprint() != this.expectedRegistryFingerprint) {
            throw new IllegalStateException(
                    "registryFingerprint mismatch: mixed-palette committees may not validate each other");
        }
        validateAnchors(request);

        RegionBounds bounds = RegionBounds.of(ctx.region());
        MutableRegionState state = new MutableRegionState(request.snapshot(), bounds);

        List<ActionRejection> rejections = new ArrayList<>();
        int applied = 0;

        int localTickIndex = 0;
        for (long tick = ctx.tickFrom(); ; tick++) {
            for (ActionEnvelope env : request.batch().actions()) {
                if (env.targetTick() != tick) {
                    continue;
                }
                if (BorderClassifier.isCrossRegion(env)) {
                    throw new IllegalStateException(
                            "cross-region action reached the engine (coordinator must filter first): "
                                    + env.action() + " for " + env.region());
                }
                DeterministicRandom rng = new DeterministicRandom(
                        DeterministicRandom.seedFor(ctx, ctx.worldSeed(), env.serverSeq()));
                Optional<ActionRejection> rejection = ruleSet.validate(state, env);
                if (rejection.isPresent()) {
                    rejections.add(rejection.get());
                    continue;
                }
                ruleSet.apply(state, env, rng);
                applied++;
            }
            long tickSequence = (0x454E545FL << 32) | Integer.toUnsignedLong(localTickIndex++);
            DeterministicRandom rng = new DeterministicRandom(
                    DeterministicRandom.seedFor(ctx, ctx.worldSeed(), tickSequence));
            ruleSet.tick(state, tick, rng);
            if (tick == ctx.tickTo()) {
                break;
            }
        }

        SnapshotVersion resultingVersion = ctx.baseVersion().next();
        RegionSnapshot postSnapshot = state.toSnapshot(resultingVersion, ctx.tickTo());
        StateRoot resultingRoot = StateRoot.of(hashService.hash(postSnapshot));

        assert resultingRoot.equals(StateRoot.of(hashService.hash(postSnapshot)))
                : "post-state root not stable across re-hash — determinism violated";

        int rejected = rejections.size();
        return new RegionExecutionResult(
                state.toDelta(ctx.baseVersion(), resultingVersion, resultingRoot),
                resultingRoot,
                new RegionExecutionResult.ExecutionStats(applied, rejected, 0L, List.copyOf(rejections)),
                state.borderSignals());
    }

    private static void validateAnchors(RegionExecutionRequest request) {
        RegionExecutionContext context = request.context();
        RegionSnapshot snapshot = request.snapshot();
        dev.nodera.core.action.ActionBatch batch = request.batch();
        if (!context.region().equals(snapshot.region()) || !context.region().equals(batch.region())) {
            throw new IllegalStateException("context, snapshot, and batch regions must match");
        }
        if (!context.baseVersion().equals(snapshot.version())
                || !context.baseVersion().equals(batch.baseVersion())) {
            throw new IllegalStateException("context, snapshot, and batch base versions must match");
        }
        if (!context.epoch().equals(batch.epoch())) {
            throw new IllegalStateException("context and batch epochs must match");
        }
        if (context.tickFrom() != batch.tickFrom() || context.tickTo() != batch.tickTo()) {
            throw new IllegalStateException("context and batch tick ranges must match");
        }
        if (snapshot.tick() > context.tickFrom()) {
            throw new IllegalStateException(
                    "snapshot tick " + snapshot.tick() + " is after batch tick "
                            + context.tickFrom());
        }
        for (ActionEnvelope action : batch.actions()) {
            if (!action.region().equals(context.region())) {
                throw new IllegalStateException("action region does not match execution region");
            }
            if (action.targetTick() < context.tickFrom() || action.targetTick() > context.tickTo()) {
                throw new IllegalStateException(
                        "action target tick outside batch range: " + action.targetTick());
            }
        }
    }
}
