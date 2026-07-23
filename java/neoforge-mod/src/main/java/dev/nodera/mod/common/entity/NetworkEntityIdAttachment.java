package dev.nodera.mod.common.entity;

import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.state.InventoryCredit;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.mod.common.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Optional;

/** Persistent NeoForge attachment bridge for tracked entity ids and inventory replay keys. */
public final class NetworkEntityIdAttachment {

    private NetworkEntityIdAttachment() {
    }

    /** Return an existing id without creating attachment data. */
    public static Optional<NetworkEntityId> existing(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return entity.getExistingData(ModAttachments.NETWORK_ENTITY_ID.get())
                .map(NetworkEntityId::new);
    }

    /** Assign an engine-created id to its vanilla projection. */
    public static void assign(Entity entity, NetworkEntityId id) {
        if (entity == null || id == null) {
            throw new IllegalArgumentException("entity and id must not be null");
        }
        Optional<NetworkEntityId> existing = existing(entity);
        if (existing.isPresent() && !existing.get().equals(id)) {
            throw new IllegalStateException("vanilla entity already carries a different network id");
        }
        entity.setData(ModAttachments.NETWORK_ENTITY_ID.get(), id.value());
    }

    /** Allocate a stable id from vanilla's persisted UUID for a server-authoritative ghost. */
    public static NetworkEntityId getOrCreate(Entity entity) {
        Optional<NetworkEntityId> existing = existing(entity);
        if (existing.isPresent()) {
            return existing.get();
        }
        NetworkEntityId allocated = new NetworkEntityId(StableHash.of(entity.getUUID()));
        assign(entity, allocated);
        return allocated;
    }

    /** True when this player's persistent attachment already contains the credit replay key. */
    public static boolean hasApplied(ServerPlayer player, InventoryCredit credit) {
        return player.getExistingData(ModAttachments.APPLIED_INVENTORY_CREDITS.get())
                .orElseGet(java.util.List::of)
                .contains(creditKey(credit));
    }

    /** Persist a credit replay key before mutating inventory; false means replay/no-op. */
    public static boolean markApplied(ServerPlayer player, InventoryCredit credit) {
        if (hasApplied(player, credit)) {
            return false;
        }
        ArrayList<String> keys = new ArrayList<>(player.getExistingData(
                ModAttachments.APPLIED_INVENTORY_CREDITS.get()).orElseGet(java.util.List::of));
        keys.add(creditKey(credit));
        keys.sort(String::compareTo);
        player.setData(ModAttachments.APPLIED_INVENTORY_CREDITS.get(), java.util.List.copyOf(keys));
        return true;
    }

    private static String creditKey(InventoryCredit credit) {
        return credit.actor().value() + ":" + Long.toUnsignedString(credit.entityId().value());
    }
}
