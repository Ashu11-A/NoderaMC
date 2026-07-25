package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionWorldView;

import java.util.Optional;

/**
 * The executable half of a {@link RulePack} (Task 16 / L-21): a mod's own validate / apply / tick
 * code, running inside the validated lane.
 *
 * <p>Until this existed, a pack could contribute palette entries and an identity but no
 * <i>behaviour</i> — its blocks were inert ids that the base rule set did not know what to do with.
 * A pack now owns the actions that target the ids it declared, and gets a tick of its own.
 *
 * <p><b>The determinism contract is the same one the base rules live under, and it is not
 * advisory.</b> Every method must be a pure function of its arguments: no clocks, no IO, no shared
 * mutable statics, no floating-point in anything that reaches hashed state, and the only randomness
 * is the supplied {@link DeterministicRandom}. A pack that breaks it does not corrupt the world
 * silently — the members running it compute a different root and the committee refuses their votes
 * — but it does break <i>that pack's</i> players, so the burden sits with the pack author.
 *
 * <p>Dispatch is by <b>palette ownership</b>, decided by {@link RulePackRegistry#ownerOf}: a pack
 * only ever sees actions whose target block id it declared. The base palette is frozen below
 * {@link RulePackRegistry#PACK_ID_FLOOR}, so a pack can never intercept a vanilla block, and two
 * packs cannot claim the same id (registration refuses the collision).
 *
 * @Thread-context implementations must be stateless/immutable; thread-confined per call.
 */
public interface PackRules {

    /**
     * Decide whether {@code env} is legal against {@code view}. Must not mutate {@code view}.
     *
     * @param view the read-only region state.
     * @param env  the action, whose target block id this pack owns.
     * @return a rejection, or empty when the action is legal.
     */
    Optional<ActionRejection> validate(RegionWorldView view, ActionEnvelope env);

    /**
     * Apply a legal action. Called only after {@link #validate} returned empty.
     *
     * @param state the mutable working state (block changes must go through
     *              {@link MutableRegionState#setBlock} so they reach the mutation buffer).
     * @param env   the action.
     * @param rng   the per-action deterministic RNG.
     */
    void apply(MutableRegionState state, ActionEnvelope env, DeterministicRandom rng);

    /**
     * Autonomous per-tick work for this pack, run after the base rules' tick in canonical
     * namespace order so every replica executes packs in the same sequence.
     *
     * @param state the mutable working state.
     * @param tick  the committed region tick.
     * @param rng   the per-tick deterministic RNG.
     */
    default void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
    }
}
