package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.GameAction;
import dev.nodera.core.action.AttackEntityAction;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.DropItemAction;
import dev.nodera.core.action.PickupItemAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.DeterministicRandom;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionWorldView;
import dev.nodera.simulation.entity.ItemEntityRules;
import dev.nodera.simulation.entity.MobCombatRules;

import java.util.Optional;

/** Composite block + deterministic ITEM rule set (Task 12a). */
public final class EntityRuleSet implements RuleSet {

    private final FlatWorldRules blocks = new FlatWorldRules();

    @Override
    public Optional<ActionRejection> validate(RegionWorldView view, ActionEnvelope env) {
        // An `instanceof` chain, not a type-pattern switch: those compile to an invokedynamic on
        // java.lang.runtime.SwitchBootstraps, which ART does not implement and D8 can neither run
        // nor desugar — so on Android the first execution of such a switch throws. See
        // docs/frontend/LIMITATIONS.fixed.md M-8.
        GameAction action = env.action();
        if (action instanceof PlaceBlockAction p) return blocks.validate(view, env);
        if (action instanceof BreakBlockAction b) return blocks.validate(view, env);
        if (action instanceof dev.nodera.core.action.InteractBlockAction i) {
            return blocks.validate(view, env);
        }
        if (action instanceof DropItemAction drop) return validateDrop(view, env, drop);
        if (action instanceof PickupItemAction pickup) return validatePickup(view, env, pickup);
        if (action instanceof AttackEntityAction attack) return validateAttack(view, env, attack);
        if (action instanceof dev.nodera.core.action.ContainerAction container) {
            return dev.nodera.simulation.entity.ContainerRules.validate(view, env, container);
        }
        if (action instanceof dev.nodera.core.action.MovePlayerAction move) {
            return dev.nodera.simulation.entity.MovementRules.validate(view, env, move);
        }
        if (action instanceof dev.nodera.core.action.CommandAction command) {
            return blocks.validate(view, env);
        }
        throw new IllegalStateException(
                "unhandled GameAction subtype: " + action.getClass());
    }

    private static Optional<ActionRejection> validateDrop(
            RegionWorldView view, ActionEnvelope env, DropItemAction drop) {
        NBlockPos pos = new NBlockPos(
                drop.origin().blockX(), drop.origin().blockY(), drop.origin().blockZ());
        if (!view.inOwnedRegion(pos)) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REGION));
        }
        if (pos.y() < FlatWorldRules.MIN_Y || pos.y() > FlatWorldRules.MAX_Y) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REACH));
        }
        return Optional.empty();
    }

    private static Optional<ActionRejection> validatePickup(
            RegionWorldView view, ActionEnvelope env, PickupItemAction pickup) {
        PersistedEntityState entity = view.entity(pickup.entityId());
        if (entity == null) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.ENTITY_NOT_FOUND));
        }
        if (entity.kind() != EntityKind.ITEM) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.ENTITY_NOT_PICKUPABLE));
        }
        return Optional.empty();
    }

    private static Optional<ActionRejection> validateAttack(
            RegionWorldView view, ActionEnvelope env, AttackEntityAction attack) {
        PersistedEntityState entity = view.entity(attack.target());
        if (entity == null) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.ENTITY_NOT_FOUND));
        }
        if (entity.kind() != EntityKind.MOB && entity.kind() != EntityKind.PLAYER) {
            // GHOST combat stays on the vanilla lane; items/carts/TNT are not attackable.
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.ENTITY_NOT_ATTACKABLE));
        }
        if (Math.abs(attack.origin().x() - entity.pos().x()) > MobCombatRules.MELEE_REACH
                || Math.abs(attack.origin().y() - entity.pos().y()) > MobCombatRules.MELEE_REACH
                || Math.abs(attack.origin().z() - entity.pos().z()) > MobCombatRules.MELEE_REACH) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REACH));
        }
        return Optional.empty();
    }

    @Override
    public void apply(MutableRegionState state, ActionEnvelope env, DeterministicRandom rng) {
        // An `instanceof` chain, not a type-pattern switch: those compile to an
        // invokedynamic on SwitchBootstraps, which ART does not implement.
        GameAction acted = env.action();
        if (acted instanceof PlaceBlockAction p) {
            blocks.apply(state, env, rng);
        } else if (acted instanceof BreakBlockAction b) {
            blocks.apply(state, env, rng);
        } else if (acted instanceof dev.nodera.core.action.InteractBlockAction i) {
            blocks.apply(state, env, rng);
        } else if (acted instanceof DropItemAction drop) {
            applyDrop(state, env, drop);
        } else if (acted instanceof PickupItemAction pickup) {
            applyPickup(state, env, pickup);
        } else if (acted instanceof AttackEntityAction attack) {
            applyAttack(state, attack);
        } else if (acted instanceof dev.nodera.core.action.ContainerAction container) {
            dev.nodera.simulation.entity.ContainerRules.apply(state, env, container, rng);
        } else if (acted instanceof dev.nodera.core.action.MovePlayerAction move) {
            dev.nodera.simulation.entity.MovementRules.apply(state, env, move);
        } else if (acted instanceof dev.nodera.core.action.CommandAction command) {
            blocks.apply(state, env, rng);
        } else {
            throw new IllegalStateException("unhandled GameAction subtype: " + acted.getClass());
        }
    }

    private static void applyAttack(MutableRegionState state, AttackEntityAction attack) {
        PersistedEntityState target = state.entity(attack.target());
        if (target != null) {
            MobCombatRules.damage(state, target, MobCombatRules.MELEE_DAMAGE);
        }
    }

    private static void applyDrop(MutableRegionState state, ActionEnvelope env, DropItemAction drop) {
        NetworkEntityId id = NetworkEntityId.allocate(
                state.region(), state.baseVersion(), env.serverSeq());
        state.createEntity(new PersistedEntityState(
                id,
                EntityKind.ITEM,
                drop.itemStackId(),
                drop.origin(),
                FixedVec3.ZERO,
                0,
                ItemEntityRules.DESPAWN_AGE_TICKS,
                ItemEntityRules.payload(drop.itemStackId(), drop.count())));
    }

    private static void applyPickup(
            MutableRegionState state, ActionEnvelope env, PickupItemAction pickup) {
        PersistedEntityState entity = state.removeEntity(pickup.entityId());
        ItemEntityRules.ItemStack stack = ItemEntityRules.decodePayload(entity.payload());
        // L-11: when the actor's PLAYER entity is registered in this region, the pickup lands in
        // the VALIDATED root inventory; the one-way credit remains only as the migration stopgap
        // (no player entity yet, or a full inventory — never lost, never duped either way).
        PersistedEntityState player = dev.nodera.simulation.entity.PlayerRules
                .findPlayer(state.entities(), env.actor());
        if (player != null && dev.nodera.simulation.entity.PlayerRules
                .addToInventory(state, player, stack.itemStackId(), stack.count())) {
            return;
        }
        state.creditInventory(new InventoryCredit(
                env.actor(), entity.id(), stack.itemStackId(), stack.count()));
    }

    @Override
    public void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        ItemEntityRules.tick(state);
        RedstoneRules.tick(state, tick, rng);
        RandomTickRules.tick(state, tick, rng);
        dev.nodera.simulation.entity.SpawnRules.tick(state, tick, rng);
        dev.nodera.simulation.entity.MobAiRules.tick(state, tick, rng);
        dev.nodera.simulation.entity.TntRules.tick(state, tick, rng);
        dev.nodera.simulation.entity.ProjectileRules.tick(state, tick, rng);
        dev.nodera.simulation.entity.RailRules.tick(state, tick, rng);
        dev.nodera.simulation.entity.PortalRules.tick(state, tick);
        // Entity↔redstone coupling (L-26 palette v2): run AFTER the movement rules above, so a
        // plate reads where entities ENDED this tick rather than where they started.
        PressurePlateRules.tick(state, tick, rng);
    }
}
