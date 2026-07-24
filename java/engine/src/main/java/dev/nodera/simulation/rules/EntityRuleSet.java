package dev.nodera.simulation.rules;

import dev.nodera.core.action.ActionEnvelope;
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

import java.util.Optional;

/** Composite block + deterministic ITEM rule set (Task 12a). */
public final class EntityRuleSet implements RuleSet {

    private final FlatWorldRules blocks = new FlatWorldRules();

    @Override
    public Optional<ActionRejection> validate(RegionWorldView view, ActionEnvelope env) {
        return switch (env.action()) {
            case PlaceBlockAction p -> blocks.validate(view, env);
            case BreakBlockAction b -> blocks.validate(view, env);
            case dev.nodera.core.action.InteractBlockAction i -> blocks.validate(view, env);
            case DropItemAction drop -> validateDrop(view, env, drop);
            case PickupItemAction pickup -> validatePickup(view, env, pickup);
        };
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

    @Override
    public void apply(MutableRegionState state, ActionEnvelope env, DeterministicRandom rng) {
        switch (env.action()) {
            case PlaceBlockAction p -> blocks.apply(state, env, rng);
            case BreakBlockAction b -> blocks.apply(state, env, rng);
            case dev.nodera.core.action.InteractBlockAction i -> blocks.apply(state, env, rng);
            case DropItemAction drop -> applyDrop(state, env, drop);
            case PickupItemAction pickup -> applyPickup(state, env, pickup);
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
        state.creditInventory(new InventoryCredit(
                env.actor(), entity.id(), stack.itemStackId(), stack.count()));
    }

    @Override
    public void tick(MutableRegionState state, long tick, DeterministicRandom rng) {
        ItemEntityRules.tick(state);
        RedstoneRules.tick(state, tick, rng);
        RandomTickRules.tick(state, tick, rng);
    }
}
