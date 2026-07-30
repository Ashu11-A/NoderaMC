package dev.nodera.simulation.entity;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.ContainerAction;
import dev.nodera.core.state.ContainerEntry;
import dev.nodera.core.state.ContainerEntry.ItemSlot;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionWorldView;
import dev.nodera.simulation.rules.ActionRejection;
import dev.nodera.simulation.rules.FlatWorldRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Task 16 container lane (L-10): validated chest deposit/withdraw + break-drop. Container
 * contents live in the hashed root ({@link ContainerEntry}); every mutation runs through the
 * committee like a block change — a modified client can neither conjure items into a chest nor
 * pull out what is not there.
 *
 * <p><b>Deposit</b> is one-way INTO the root (the vanilla inventory debit stays client-side
 * until the player lane puts inventories in the root, L-11); <b>withdraw</b> removes from the
 * root and credits the player's inventory through the same replay-safe {@link InventoryCredit}
 * lane as item pickup. Breaking a chest drops its contents as validated ITEM entities.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class ContainerRules {

    /** Slots in a single chest (vanilla 27; double chests are two adjacent singles for now). */
    public static final int CHEST_SLOTS = 27;
    /** Slots in a hopper (vanilla 5). */
    public static final int HOPPER_SLOTS = 5;
    /** Region ticks between hopper transfer cycles (vanilla cooldown). */
    public static final int HOPPER_INTERVAL_TICKS = 8;
    /** Per-axis melee-style reach bound for container interaction, Q32.32. */
    public static final long CONTAINER_REACH = 4L << 32;
    /** High tag bits for break-drop entity ids (never collides with action/spawn seqs). */
    private static final long DROP_SEQ_DOMAIN = 0x4348_5354L << 32; // "CHST"

    private ContainerRules() {
    }

    /** @return whether {@code blockId} is a container block. */
    public static boolean isContainer(int blockId) {
        return blockId == FlatWorldRules.CHEST || blockId == FlatWorldRules.HOPPER;
    }

    /** @return the slot count a container kind declares. */
    public static int slotsFor(int blockId) {
        return blockId == FlatWorldRules.HOPPER ? HOPPER_SLOTS : CHEST_SLOTS;
    }

    /**
     * The comparator fill signal of a container: 0 when empty/absent, else
     * {@code 1 + floor(14 × totalItems / (slots × 64))} capped at 15 — the vanilla shape with the
     * 64-stack denominator, pure integer math.
     */
    public static int containerSignal(ContainerEntry entry, int slots) {
        if (entry == null) {
            return 0;
        }
        long total = 0;
        for (ItemSlot s : entry.slots()) {
            total += Math.min(s.count(), 64);
        }
        if (total == 0) {
            return 0;
        }
        return (int) Math.min(15, 1 + (14 * total) / ((long) slots * 64));
    }

    /**
     * One hopper transfer cycle on the hashed queue: push the hopper's first item down into a
     * container below, pull one item from a container above, then reschedule — a placed hopper
     * is a deterministic 8-tick machine whose whole behaviour is committed state.
     */
    public static void hopperTick(MutableRegionState state, NBlockPos pos, long tick,
                                  dev.nodera.simulation.DeterministicRandom rng) {
        if (state.getBlock(pos) != FlatWorldRules.HOPPER) {
            return; // broken since scheduling — stale entry no-ops
        }
        NBlockPos below = new NBlockPos(pos.x(), pos.y() - 1, pos.z());
        NBlockPos above = new NBlockPos(pos.x(), pos.y() + 1, pos.z());
        boolean pushed = moveOne(state, pos, below);
        boolean pulled = moveOne(state, above, pos);
        if (pushed || pulled) {
            // Fill levels changed: adjacent comparators re-settle their networks now.
            dev.nodera.simulation.rules.RedstoneRules.onContainerChanged(state, pos, rng, tick);
            if (pushed) {
                dev.nodera.simulation.rules.RedstoneRules.onContainerChanged(state, below, rng, tick);
            }
            if (pulled) {
                dev.nodera.simulation.rules.RedstoneRules.onContainerChanged(state, above, rng, tick);
            }
        }
        state.scheduleTick(pos, FlatWorldRules.HOPPER, tick + HOPPER_INTERVAL_TICKS, 0);
    }

    /** Move ONE item from {@code from}'s first non-empty slot into {@code to}, if both accept. */
    private static boolean moveOne(MutableRegionState state, NBlockPos from, NBlockPos to) {
        if (!state.inOwnedRegion(from) || !state.inOwnedRegion(to)
                || !isContainer(state.getBlock(from)) || !isContainer(state.getBlock(to))) {
            return false;
        }
        ContainerEntry source = state.container(from);
        if (source == null) {
            return false;
        }
        int fromSlot = -1;
        for (int i = 0; i < source.slots().size(); i++) {
            if (!source.slots().get(i).isEmpty()) {
                fromSlot = i;
                break;
            }
        }
        if (fromSlot < 0) {
            return false;
        }
        ItemSlot moving = source.slots().get(fromSlot);
        ContainerEntry target = state.container(to);
        if (target == null) {
            List<ItemSlot> empty = new ArrayList<>(slotsFor(state.getBlock(to)));
            for (int i = 0; i < slotsFor(state.getBlock(to)); i++) {
                empty.add(ItemSlot.EMPTY);
            }
            target = new ContainerEntry(to, empty);
        }
        int toSlot = -1;
        for (int i = 0; i < target.slots().size(); i++) {
            ItemSlot s = target.slots().get(i);
            if ((s.isEmpty() || (s.itemStackId() == moving.itemStackId() && s.count() < 255))) {
                toSlot = i;
                break;
            }
        }
        if (toSlot < 0) {
            return false; // destination full — the item waits
        }
        ItemSlot dest = target.slots().get(toSlot);
        state.putContainer(target.withSlot(toSlot,
                new ItemSlot(moving.itemStackId(), dest.count() + 1)));
        ContainerEntry drained = source.withSlot(fromSlot,
                moving.count() == 1 ? ItemSlot.EMPTY
                        : new ItemSlot(moving.itemStackId(), moving.count() - 1));
        if (drained.isEmpty()) {
            state.removeContainer(from);
        } else {
            state.putContainer(drained);
        }
        return true;
    }

    /** Pre-apply validation of one {@link ContainerAction} against committed state. */
    public static Optional<ActionRejection> validate(
            RegionWorldView view, ActionEnvelope env, ContainerAction action) {
        if (!view.inOwnedRegion(action.pos())) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REGION));
        }
        if (!isContainer(view.getBlock(action.pos()))) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.ILLEGAL_BLOCK));
        }
        if (action.slot() >= slotsFor(view.getBlock(action.pos()))) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.MALFORMED));
        }
        if (outOfReach(action.origin(), action.pos())) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.OUT_OF_REACH));
        }
        ContainerEntry entry = view.container(action.pos());
        ItemSlot current = entry == null ? ItemSlot.EMPTY : entry.slots().get(action.slot());
        if (action.mode() == ContainerAction.Mode.DEPOSIT) {
            boolean sameItem = current.itemStackId() == action.itemStackId();
            if (!current.isEmpty() && !sameItem) {
                return Optional.of(new ActionRejection(env, ActionRejection.Reason.BAD_PREVIOUS_STATE));
            }
            if (current.count() + action.count() > 255) {
                return Optional.of(new ActionRejection(env, ActionRejection.Reason.MALFORMED));
            }
            return Optional.empty();
        }
        // WITHDRAW: the slot must hold at least the requested count of exactly that item.
        if (current.isEmpty() || current.itemStackId() != action.itemStackId()
                || current.count() < action.count()) {
            return Optional.of(new ActionRejection(env, ActionRejection.Reason.BAD_PREVIOUS_STATE));
        }
        return Optional.empty();
    }

    /** Apply a validated {@link ContainerAction} to working state. */
    public static void apply(MutableRegionState state, ActionEnvelope env, ContainerAction action,
                             dev.nodera.simulation.DeterministicRandom rng) {
        ContainerEntry entry = state.container(action.pos());
        if (entry == null) {
            int slots = slotsFor(state.getBlock(action.pos()));
            List<ItemSlot> empty = new ArrayList<>(slots);
            for (int i = 0; i < slots; i++) {
                empty.add(ItemSlot.EMPTY);
            }
            entry = new ContainerEntry(action.pos(), empty);
        }
        ItemSlot current = entry.slots().get(action.slot());
        if (action.mode() == ContainerAction.Mode.DEPOSIT) {
            state.putContainer(entry.withSlot(action.slot(),
                    new ItemSlot(action.itemStackId(), current.count() + action.count())));
            dev.nodera.simulation.rules.RedstoneRules.onContainerChanged(
                    state, action.pos(), rng, env.targetTick());
            return;
        }
        int remaining = current.count() - action.count();
        ContainerEntry updated = entry.withSlot(action.slot(),
                remaining == 0 ? ItemSlot.EMPTY : new ItemSlot(current.itemStackId(), remaining));
        if (updated.isEmpty()) {
            state.removeContainer(action.pos()); // the table stays sparse
        } else {
            state.putContainer(updated);
        }
        // L-11: a registered PLAYER entity receives the withdrawal in its VALIDATED root
        // inventory; otherwise the replay-safe one-way credit lane (like pickup) still applies.
        PersistedEntityState player = PlayerRules.findPlayer(state.entities(), env.actor());
        if (player != null
                && PlayerRules.addToInventory(state, player, action.itemStackId(), action.count())) {
            dev.nodera.simulation.rules.RedstoneRules.onContainerChanged(
                    state, action.pos(), rng, env.targetTick());
            return;
        }
        state.creditInventory(new InventoryCredit(
                env.actor(),
                NetworkEntityId.allocate(state.region(), state.baseVersion(),
                        DROP_SEQ_DOMAIN | env.serverSeq()),
                action.itemStackId(), action.count()));
        dev.nodera.simulation.rules.RedstoneRules.onContainerChanged(
                state, action.pos(), rng, env.targetTick());
    }

    /**
     * Break-drop: spill a broken container's contents as validated ITEM entities at the block
     * centre (deterministic ids from the action's serverSeq + slot) and drop the table entry.
     */
    public static void onBroken(MutableRegionState state, NBlockPos pos, ActionEnvelope env) {
        ContainerEntry entry = state.removeContainer(pos);
        if (entry == null) {
            return;
        }
        FixedVec3 centre = new FixedVec3(
                ((long) pos.x() << 32) + (1L << 31),
                (long) pos.y() << 32,
                ((long) pos.z() << 32) + (1L << 31));
        for (int slot = 0; slot < entry.slots().size(); slot++) {
            ItemSlot s = entry.slots().get(slot);
            if (s.isEmpty()) {
                continue;
            }
            state.createEntity(new PersistedEntityState(
                    NetworkEntityId.allocate(state.region(), state.baseVersion(),
                            DROP_SEQ_DOMAIN | (env.serverSeq() * CHEST_SLOTS + slot)),
                    EntityKind.ITEM, s.itemStackId(), centre, FixedVec3.ZERO,
                    0, ItemEntityRules.DESPAWN_AGE_TICKS,
                    ItemEntityRules.payload(s.itemStackId(), s.count())));
        }
    }

    private static boolean outOfReach(FixedVec3 origin, NBlockPos pos) {
        long cx = ((long) pos.x() << 32) + (1L << 31);
        long cy = (long) pos.y() << 32;
        long cz = ((long) pos.z() << 32) + (1L << 31);
        return Math.abs(origin.x() - cx) > CONTAINER_REACH
                || Math.abs(origin.y() - cy) > CONTAINER_REACH
                || Math.abs(origin.z() - cz) > CONTAINER_REACH;
    }
}
