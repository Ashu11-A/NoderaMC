package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionWorldView;

import java.util.Optional;

/**
 * The pluggable simulation rule set (Task 3). A {@code RuleSet} decides, for one
 * {@link ActionEnvelope}, whether the action is legal against the current {@link RegionWorldView}
 * and — if legal — how to mutate the {@link MutableRegionState}. Rule sets are versioned
 * ({@link RegionExecutionContext#rulesVersion()}) and registry-bound
 * ({@link RegionExecutionContext#registryFingerprint()}); the engine refuses to run a request whose
 * version or fingerprint it does not support, so mixed-version committees never validate each other.
 *
 * <p><b>Determinism contract.</b> Both methods must be pure functions of
 * {@code (view/env/state, env, rng)}: no clocks, no IO, no shared static state, and the only RNG
 * touched is the supplied per-action {@link DeterministicRandom}. The MVP implementation is
 * {@link FlatWorldRules}.
 *
 * @Thread-context thread-confined per call.
 */
public interface RuleSet {

    /**
     * Decide whether {@code env} is legal against {@code view} <b>before</b> mutating state. Must
     * not observably mutate {@code view}. The validator runs in the engine's main replay loop, so
     * it must be side-effect free except for any internal read-only inspection of {@code view}.
     *
     * @param view the read-only region state the action would apply to.
     * @param env  the action envelope under consideration.
     * @return an {@link ActionRejection} if the action must be dropped, or {@link Optional#empty()}
     *         if it is legal and should be applied.
     * @Thread-context thread-confined per call.
     */
    Optional<ActionRejection> validate(RegionWorldView view, ActionEnvelope env);

    /**
     * Apply {@code env} to {@code state}. Called by the engine only when {@link #validate} returned
     * empty. Implementations forward block changes through
     * {@link MutableRegionState#setBlock} so they land in the working copy AND the mutation buffer.
     * The {@code rng} is the per-action deterministic generator; MVP rules do not draw from it but
     * keep the parameter so later rule sets (growth, combat) can.
     *
     * @param state the mutable working state; never the snapshot directly.
     * @param env   the legal action to apply.
     * @param rng   the per-action deterministic RNG.
     * @Thread-context thread-confined per call.
     */
    void apply(MutableRegionState state, ActionEnvelope env, DeterministicRandom rng);

    /**
     * Advance autonomous state for one committed region tick after batch actions are applied.
     * Block-only rules have no autonomous work; entity/environment rules override this hook.
     */
    default void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
    }
}
