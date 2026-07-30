package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.ContainerEntry.ItemSlot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The judgement half of the player-root lane (L-11): what a {@code PLAYER} root entity is seeded
 * with, and what a committed change to one owes the real vanilla inventory.
 *
 * <p>{@link PlayerRules} has carried a validated 36-slot inventory in the {@code PLAYER} payload
 * since Task 16, and nothing in production ever registered such an entity — so
 * {@link dev.nodera.simulation.rules.EntityRuleSet} always took the {@code InventoryCredit}
 * branch and {@link MovementRules} always rejected
 * {@code ENTITY_NOT_FOUND}. Registering one naively is not free, though: the moment a player
 * entity exists the engine routes pickups into the validated root inventory instead of the credit
 * the mod mirrors back into vanilla, so an empty seeded registration would make picked-up items
 * disappear out of real inventories.
 *
 * <p><b>The contract this class encodes, in both directions.</b>
 * <ul>
 *   <li><b>Registration seeds from vanilla, never from nothing.</b> {@link #seedSlots} copies the
 *       player's ACTUAL inventory into the root. Items already held survive registration because
 *       they are what the root is made of.</li>
 *   <li><b>Every committed gain is projected back.</b> {@link #gains} is the multiset a committed
 *       payload holds beyond the previous one, and the live world view adds exactly that to the
 *       vanilla inventory. A pickup that lands in the validated root therefore still lands in the
 *       player's hands.</li>
 *   <li><b>A seed is not a gain.</b> {@code gains(null, seeded)} is empty. Seeding copies items
 *       vanilla already holds; projecting them would duplicate the whole inventory on every
 *       login.</li>
 *   <li><b>Losses are not projected.</b> The root shrinking (a drop, a cross-region hand-off) is
 *       vanilla's own event, already applied there; taking items out again would delete them.</li>
 * </ul>
 *
 * <p>Vanilla stays the durable store — Minecraft persists it across logout — and the root is the
 * validated mirror, re-seeded from vanilla at every registration. That is why a logout/login
 * round-trip loses nothing: what the engine committed was projected into vanilla when it
 * committed, and the next login rebuilds the root out of vanilla again.
 *
 * <p>It lives in the engine beside {@link PlayerRules} rather than in the mod: it is a rule about
 * validated root state, and a rule only observable in a running game is a rule nobody can
 * regression-test.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class PlayerRootRegistration {

    private PlayerRootRegistration() {
    }

    /** One item stack the validated root gained and the vanilla inventory is owed. */
    public record Gain(int itemStackId, int count) {
        public Gain {
            if (itemStackId == 0 || count <= 0) {
                throw new IllegalArgumentException("a gain needs a real item and a positive count");
            }
        }
    }

    /**
     * The exact {@link PlayerRules#PLAYER_SLOTS} slot table a registration seeds the root with.
     * Shorter vanilla inventories are padded with empties; a longer one (a modded inventory) is
     * truncated rather than refused, because the validated lane's slot count is structural.
     */
    public static List<ItemSlot> seedSlots(List<ItemSlot> vanilla) {
        List<ItemSlot> slots = new ArrayList<>(PlayerRules.PLAYER_SLOTS);
        for (int i = 0; i < PlayerRules.PLAYER_SLOTS; i++) {
            ItemSlot slot = vanilla != null && i < vanilla.size() ? vanilla.get(i) : ItemSlot.EMPTY;
            slots.add(slot == null ? ItemSlot.EMPTY : slot);
        }
        return List.copyOf(slots);
    }

    /** The canonical PLAYER payload for {@code owner} seeded from a real vanilla inventory. */
    public static Bytes seedPayload(NodeId owner, int health, int maxHealth, List<ItemSlot> vanilla) {
        return PlayerRules.payload(new PlayerRules.PlayerState(
                owner, health, maxHealth, seedSlots(vanilla)));
    }

    /**
     * What {@code next} holds beyond {@code previous}, per item id.
     *
     * @param previous the previously committed payload, or {@code null} for a registration seed.
     * @param next     the newly committed payload.
     * @return the stacks the vanilla inventory is owed, in item-id order; never {@code null}.
     */
    public static List<Gain> gains(Bytes previous, Bytes next) {
        if (next == null || previous == null) {
            // A seed (or a removal) is not a gain: those items are already in vanilla's hands.
            return List.of();
        }
        Map<Integer, Integer> before = totals(PlayerRules.decode(previous).inventory());
        Map<Integer, Integer> after = totals(PlayerRules.decode(next).inventory());
        List<Gain> gained = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : after.entrySet()) {
            int delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
            if (delta > 0) {
                gained.add(new Gain(entry.getKey(), delta));
            }
        }
        gained.sort(java.util.Comparator.comparingInt(Gain::itemStackId));
        return List.copyOf(gained);
    }

    private static Map<Integer, Integer> totals(List<ItemSlot> slots) {
        Map<Integer, Integer> totals = new LinkedHashMap<>();
        for (ItemSlot slot : slots) {
            if (!slot.isEmpty()) {
                totals.merge(slot.itemStackId(), slot.count(), Integer::sum);
            }
        }
        return totals;
    }
}
