package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.InteractBlockAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionWorldView;

import java.util.Objects;
import java.util.Optional;

/**
 * The rule set that lets third-party {@link RulePack}s actually run (Task 16 / L-21) — the seam that
 * turns the SDK from "a pack can declare ids and an identity" into "a pack's blocks behave".
 *
 * <p>It wraps the base {@link RuleSet} and routes each action by <b>palette ownership</b>: an action
 * whose target block id was declared by a pack goes to that pack's {@link PackRules}; everything
 * else goes to the base rules untouched. Per-tick, the base tick runs first and then every pack's
 * tick in canonical namespace order, so replicas execute the same packs in the same sequence.
 *
 * <h2>Why this is safe by construction</h2>
 * <ul>
 *   <li><b>A pack cannot reach a vanilla block.</b> The base palette is frozen below
 *       {@link RulePackRegistry#PACK_ID_FLOOR}; {@link RulePackRegistry#ownerOf} returns empty for
 *       every id under it, so base behaviour is unreachable from pack code.</li>
 *   <li><b>Two packs cannot fight over an id.</b> Registration already refuses a duplicate palette
 *       id across packs, so ownership is a function, not a race.</li>
 *   <li><b>A pack cannot diverge quietly.</b> Every pack's identity is folded into
 *       {@link RulePackRegistry#combinedFingerprint}, which is the number each committee member
 *       pins — a member missing a pack, or carrying a different build of one, is refused by the
 *       engine's existing gate rather than silently computing a different root.</li>
 *   <li><b>Order is canonical, never installation order.</b> The registry keeps packs sorted by
 *       namespace, so the tick sequence is identical on every replica.</li>
 * </ul>
 *
 * <p>What this deliberately does NOT do is sandbox a pack's determinism: a pack that reads a clock
 * computes a different root and its votes stop matching the committee's. That is a refusal, not a
 * corruption — but it is the pack author's burden, and {@link PackRules} says so.
 *
 * @Thread-context thread-confined per call; holds no mutable state.
 */
public final class PackDelegatingRuleSet implements RuleSet {

    private final RuleSet base;
    private final RulePackRegistry registry;

    /**
     * @param base     the built-in rule set (handles everything below {@code PACK_ID_FLOOR}).
     * @param registry the frozen pack registry.
     */
    public PackDelegatingRuleSet(RuleSet base, RulePackRegistry registry) {
        this.base = Objects.requireNonNull(base, "base");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Optional<ActionRejection> validate(RegionWorldView view, ActionEnvelope env) {
        return packFor(view, env)
                .map(rules -> rules.validate(view, env))
                .orElseGet(() -> base.validate(view, env));
    }

    @Override
    public void apply(MutableRegionState state, ActionEnvelope env, DeterministicRandom rng) {
        Optional<PackRules> rules = packForTargetId(targetIdForApply(state, env));
        if (rules.isPresent()) {
            rules.get().apply(state, env, rng);
        } else {
            base.apply(state, env, rng);
        }
    }

    @Override
    public void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        base.tick(state, tick, rng);
        // Canonical (namespace) order — the registry keeps packs sorted, so this sequence is the
        // same on every replica regardless of the order the mods happened to load in.
        for (RulePack pack : registry.packs()) {
            pack.rules().ifPresent(rules -> rules.tick(state, tick, rng));
        }
    }

    /** The pack owning the block this action targets, for the read-only validate path. */
    private Optional<PackRules> packFor(RegionWorldView view, ActionEnvelope env) {
        Integer id = switch (env.action()) {
            // A placement is owned by the id being PLACED — that is the block whose rules decide
            // whether it may exist there at all.
            case PlaceBlockAction p -> p.blockStateId();
            // A break or an interact is owned by the id ALREADY THERE.
            case BreakBlockAction b -> blockAt(view, b.pos());
            case InteractBlockAction i -> blockAt(view, i.pos());
            default -> null;
        };
        return packForTargetId(id);
    }

    /**
     * The same question on the apply path, where the world is a {@link MutableRegionState}. A break
     * or interact is resolved against the pre-apply state, which is exactly what {@code validate}
     * saw a moment earlier, so ownership cannot change between the two calls.
     */
    private Integer targetIdForApply(MutableRegionState state, ActionEnvelope env) {
        return switch (env.action()) {
            case PlaceBlockAction p -> p.blockStateId();
            case BreakBlockAction b -> state.getBlock(b.pos());
            case InteractBlockAction i -> state.getBlock(i.pos());
            default -> null;
        };
    }

    private Optional<PackRules> packForTargetId(Integer id) {
        if (id == null) {
            return Optional.empty();
        }
        return registry.ownerOf(id).flatMap(RulePack::rules);
    }

    private static Integer blockAt(RegionWorldView view, NBlockPos pos) {
        if (!view.inOwnedRegion(pos)) {
            // Out of region is the base rules' rejection to make, with its own reason code.
            return null;
        }
        return view.getBlock(pos);
    }
}
