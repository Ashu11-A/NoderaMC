package dev.nodera.mod.server.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.mod.common.entity.NetworkEntityIdAttachment;
import dev.nodera.simulation.entity.ItemEntityRules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;

/** Stateless Minecraft-to-canonical adapters used by the live entity bridge. */
public final class MinecraftEntityAdapters {

    private static final int GHOST_PAYLOAD_VERSION = 1;

    private MinecraftEntityAdapters() {
    }

    public static RegionId region(Entity entity) {
        return RegionId.fromChunk(
                dimension((ServerLevel) entity.level()),
                entity.chunkPosition().x, entity.chunkPosition().z);
    }

    public static RegionId region(ServerLevel level, double x, double z) {
        return RegionId.fromChunk(
                dimension(level), Math.floorDiv((int) Math.floor(x), 16),
                Math.floorDiv((int) Math.floor(z), 16));
    }

    public static DimensionKey dimension(ServerLevel level) {
        var id = level.dimension().location();
        return DimensionKey.of(id.getNamespace(), id.getPath());
    }

    public static PersistedEntityState item(ItemEntity entity, NetworkEntityId id) {
        int itemId = BuiltInRegistries.ITEM.getId(entity.getItem().getItem());
        int count = Math.min(255, entity.getItem().getCount());
        return new PersistedEntityState(
                id, EntityKind.ITEM, itemId,
                fixedPosition(entity), fixedVelocity(entity), entity.getAge(),
                ItemEntityRules.DESPAWN_AGE_TICKS,
                ItemEntityRules.payload(itemId, count));
    }

    public static PersistedEntityState ghost(Entity entity) {
        NetworkEntityId id = NetworkEntityIdAttachment.getOrCreate(entity);
        return new PersistedEntityState(
                id, EntityKind.GHOST, BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()),
                fixedPosition(entity), fixedVelocity(entity), entity.tickCount,
                PersistedEntityState.NEVER_DESPAWN, ghostPayload(entity));
    }

    public static FixedVec3 fixedPosition(Entity entity) {
        return FixedVec3.fromExternal(entity.getX(), entity.getY(), entity.getZ());
    }

    public static FixedVec3 fixedVelocity(Entity entity) {
        var velocity = entity.getDeltaMovement();
        return FixedVec3.fromExternal(velocity.x, velocity.y, velocity.z);
    }

    private static Bytes ghostPayload(Entity entity) {
        CanonicalWriter writer = new CanonicalWriter();
        writer.writeU16(GHOST_PAYLOAD_VERSION);
        writer.writeU32(Integer.toUnsignedLong(entity.getPose().ordinal()));
        int healthMillis = entity instanceof LivingEntity living
                ? Math.max(0, Math.round(living.getHealth() * 1_000.0f)) : 0;
        writer.writeU32(Integer.toUnsignedLong(healthMillis));
        writer.writeBoolean(entity.onGround());
        return writer.toBytes();
    }
}
