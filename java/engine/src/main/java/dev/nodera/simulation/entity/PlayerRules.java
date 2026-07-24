package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.ContainerEntry.ItemSlot;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.simulation.MutableRegionState;
import dev.nodera.simulation.RegionWorldView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Task 16 player-root lane (L-11): a player's inventory is VALIDATED ROOT STATE. The
 * {@link EntityKind#PLAYER} entity's payload carries the owning {@link NodeId} plus the ordered
 * inventory slot table (vanilla 36), so item pickups and container withdrawals mutate committed,
 * replica-identical state — the one-way {@code InventoryCredit} stopgap applies ONLY while no
 * player entity is registered in the region (the live lane's migration path). A region hand-off
 * moves the whole inventory through the same dupe-proof joint-certificate transfer pipeline as
 * any entity: removed at the source, materialised exactly once at the target.
 *
 * <p>Payload wire form: {@code [NodeId owner][list (u32 itemStackId, u8 count)]} — the
 * {@link ItemSlot} encoding shared with containers.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class PlayerRules {

    /** Vanilla player inventory size (27 main + 9 hotbar). */
    public static final int PLAYER_SLOTS = 36;
    /** The PLAYER entity type id (opaque; the mod maps it when mirroring). */
    public static final int PLAYER_TYPE_ID = 500;
    /** Vanilla player health in halves of hearts. */
    public static final int PLAYER_MAX_HEALTH = 20;

    private PlayerRules() {
    }

    /** Decoded PLAYER payload: owner + committed health + the inventory table (L-11/L-13). */
    public record PlayerState(NodeId owner, int health, int maxHealth, List<ItemSlot> inventory) {
        public PlayerState {
            if (owner == null || inventory == null || inventory.size() != PLAYER_SLOTS) {
                throw new IllegalArgumentException(
                        "a player payload needs an owner and exactly " + PLAYER_SLOTS + " slots");
            }
            if (maxHealth <= 0 || maxHealth > 0xFFFF || health <= 0 || health > maxHealth) {
                throw new IllegalArgumentException(
                        "health must be in [1, maxHealth]: " + health + "/" + maxHealth);
            }
            inventory = List.copyOf(inventory);
        }

        /** Same owner/inventory with {@code newHealth}. */
        public PlayerState withHealth(int newHealth) {
            return new PlayerState(owner, newHealth, maxHealth, inventory);
        }
    }

    /** Canonical PLAYER payload for {@code owner} with an empty inventory. */
    public static Bytes emptyInventoryPayload(NodeId owner) {
        List<ItemSlot> slots = new ArrayList<>(PLAYER_SLOTS);
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            slots.add(ItemSlot.EMPTY);
        }
        return payload(new PlayerState(owner, PLAYER_MAX_HEALTH, PLAYER_MAX_HEALTH, slots));
    }

    /** Canonical PLAYER payload bytes: {@code [NodeId][u16 health][u16 max][list slots]}. */
    public static Bytes payload(PlayerState state) {
        CanonicalWriter w = new CanonicalWriter();
        state.owner().encode(w);
        w.writeU16(state.health());
        w.writeU16(state.maxHealth());
        w.writeList(state.inventory(), (ww, s) -> {
            ww.writeU32(Integer.toUnsignedLong(s.itemStackId()));
            ww.writeU8(s.count());
        });
        return w.toBytes();
    }

    /** Decode and validate a PLAYER payload. */
    public static PlayerState decode(Bytes payload) {
        CanonicalReader r = new CanonicalReader(payload);
        NodeId owner = NodeId.decode(r);
        int health = r.readU16();
        int maxHealth = r.readU16();
        List<ItemSlot> slots = r.readList(rr -> new ItemSlot((int) rr.readU32(), rr.readU8()));
        if (r.available() != 0) {
            throw new IllegalStateException("malformed player payload");
        }
        return new PlayerState(owner, health, maxHealth, slots);
    }

    /** @return {@code actor}'s PLAYER entity in this view, or {@code null} when not registered. */
    public static PersistedEntityState playerOf(RegionWorldView view, NodeId actor) {
        if (view instanceof MutableRegionState state) {
            return findPlayer(state.entities(), actor);
        }
        return null;
    }

    /** @return {@code actor}'s PLAYER entity among {@code entities}, or {@code null}. */
    public static PersistedEntityState findPlayer(
            List<PersistedEntityState> entities, NodeId actor) {
        for (PersistedEntityState entity : entities) {
            if (entity.kind() == EntityKind.PLAYER && decode(entity.payload()).owner().equals(actor)) {
                return entity;
            }
        }
        return null;
    }

    /** @return whether {@code count} of {@code itemStackId} fits into the player's inventory. */
    public static boolean canAccept(PersistedEntityState player, int itemStackId, int count) {
        return slotFor(decode(player.payload()).inventory(), itemStackId, count) >= 0;
    }

    /**
     * Add {@code count} of {@code itemStackId} to the player's ROOT inventory (first same-item
     * slot with room, else first empty slot) and commit the mutated entity.
     *
     * @return {@code true} when the stack fit; {@code false} leaves state untouched.
     */
    public static boolean addToInventory(
            MutableRegionState state, PersistedEntityState player, int itemStackId, int count) {
        PlayerState decoded = decode(player.payload());
        int slot = slotFor(decoded.inventory(), itemStackId, count);
        if (slot < 0) {
            return false;
        }
        List<ItemSlot> next = new ArrayList<>(decoded.inventory());
        ItemSlot current = next.get(slot);
        next.set(slot, new ItemSlot(itemStackId, current.count() + count));
        state.updateEntity(new PersistedEntityState(
                player.id(), player.kind(), player.typeId(), player.pos(), player.vel(),
                player.ageTicks(), player.despawnTick(),
                payload(new PlayerState(decoded.owner(), decoded.health(), decoded.maxHealth(),
                        next))));
        return true;
    }

    private static int slotFor(List<ItemSlot> inventory, int itemStackId, int count) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemSlot s = inventory.get(i);
            if (!s.isEmpty() && s.itemStackId() == itemStackId && s.count() + count <= 255) {
                return i;
            }
        }
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }
}
