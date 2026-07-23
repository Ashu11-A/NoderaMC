package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.region.RegionId;
import dev.nodera.simulation.MutableRegionState;

import java.util.List;

/** Deterministic fixed-point item physics and merge/despawn rules (Task 12a). */
public final class ItemEntityRules {

    /** Item age at which the entity is removed. */
    public static final int DESPAWN_AGE_TICKS = 6_000;
    /** Ground-rest Y on the MVP flat terrain. */
    public static final long GROUND_Y = FixedVec3.ONE;
    /** Per-tick downward acceleration, Q32.32 literal (approximately 0.04 block/tick²). */
    public static final long GRAVITY_PER_TICK = 171_798_692L;
    /** Airborne X/Z velocity retention, Q32.32 literal (0.98 per tick). */
    public static final long HORIZONTAL_FRICTION = 4_209_067_950L;
    /** Per-axis overlap radius for deterministic item merging (0.5 block). */
    public static final long MERGE_RADIUS = FixedVec3.ONE / 2;

    private ItemEntityRules() {
    }

    /** Fingerprint contribution pinning physics constants and payload shape. */
    public static long semanticFingerprint() {
        return StableHash.of(
                StableHash.of("nodera.simulation.item-rules.v1"),
                DESPAWN_AGE_TICKS,
                GROUND_Y,
                GRAVITY_PER_TICK,
                HORIZONTAL_FRICTION,
                MERGE_RADIUS);
    }

    /** Canonical opaque ITEM payload: unsigned item id + unsigned-byte count. */
    public static Bytes payload(int itemStackId, int count) {
        if (count <= 0 || count > 255) {
            throw new IllegalArgumentException("count must be in [1, 255]: " + count);
        }
        CanonicalWriter w = new CanonicalWriter(5);
        w.writeU32(Integer.toUnsignedLong(itemStackId));
        w.writeU8(count);
        return w.toBytes();
    }

    /** Decode and validate an ITEM payload. */
    public static ItemStack decodePayload(Bytes payload) {
        CanonicalReader r = new CanonicalReader(payload);
        // itemStackId is an opaque unsigned 32-bit id: high-bit values are legitimate.
        int itemStackId = (int) r.readU32();
        int count = r.readU8();
        if (count == 0 || r.available() != 0) {
            throw new IllegalStateException("malformed item payload");
        }
        return new ItemStack(itemStackId, count);
    }

    /** Advance every ITEM by one deterministic tick, then merge overlapping equal stacks. */
    public static void tick(MutableRegionState state) {
        for (PersistedEntityState entity : state.entities()) {
            if (entity.kind() != EntityKind.ITEM) {
                continue;
            }
            PersistedEntityState aged = entity.tick();
            if (aged.shouldDespawn()) {
                state.removeEntity(aged.id());
                continue;
            }
            PersistedEntityState moved = move(aged);
            if (!state.bounds().ownsBlock(moved.pos().blockX(), moved.pos().blockZ())) {
                RegionId target = RegionId.fromChunk(
                        state.region().dimension(),
                        Math.floorDiv(moved.pos().blockX(), 16),
                        Math.floorDiv(moved.pos().blockZ(), 16));
                state.transferEntity(target, moved);
                continue;
            }
            state.updateEntity(moved);
        }
        merge(state);
    }

    private static PersistedEntityState move(PersistedEntityState entity) {
        FixedVec3 pos = entity.pos();
        if (pos.y() <= GROUND_Y && entity.vel().y() <= 0) {
            return withMotion(entity, new FixedVec3(pos.x(), GROUND_Y, pos.z()), FixedVec3.ZERO);
        }
        long nextX = pos.x() + entity.vel().x();
        long nextZ = pos.z() + entity.vel().z();
        long nextVy = entity.vel().y() - GRAVITY_PER_TICK;
        long nextY = pos.y() + nextVy;
        if (nextY <= GROUND_Y) {
            return withMotion(entity, new FixedVec3(nextX, GROUND_Y, nextZ), FixedVec3.ZERO);
        }
        FixedVec3 velocity = new FixedVec3(
                multiplyFixed(entity.vel().x(), HORIZONTAL_FRICTION),
                nextVy,
                multiplyFixed(entity.vel().z(), HORIZONTAL_FRICTION));
        return withMotion(entity, new FixedVec3(nextX, nextY, nextZ), velocity);
    }

    private static PersistedEntityState withMotion(
            PersistedEntityState entity, FixedVec3 position, FixedVec3 velocity) {
        return new PersistedEntityState(
                entity.id(), entity.kind(), entity.typeId(), position, velocity,
                entity.ageTicks(), entity.despawnTick(), entity.payload());
    }

    private static void merge(MutableRegionState state) {
        List<PersistedEntityState> ordered = state.entities();
        for (int i = 0; i < ordered.size(); i++) {
            PersistedEntityState left = state.entity(ordered.get(i).id());
            if (left == null || left.kind() != EntityKind.ITEM) {
                continue;
            }
            for (int j = i + 1; j < ordered.size(); j++) {
                PersistedEntityState right = state.entity(ordered.get(j).id());
                if (right == null || right.kind() != EntityKind.ITEM || !overlaps(left, right)) {
                    continue;
                }
                ItemStack a = decodePayload(left.payload());
                ItemStack b = decodePayload(right.payload());
                int mergedCount = a.count() + b.count();
                if (a.itemStackId() != b.itemStackId() || mergedCount > 255) {
                    continue;
                }
                PersistedEntityState merged = new PersistedEntityState(
                        left.id(), EntityKind.ITEM, left.typeId(), left.pos(), left.vel(),
                        Math.max(left.ageTicks(), right.ageTicks()),
                        Math.min(left.despawnTick(), right.despawnTick()),
                        payload(a.itemStackId(), mergedCount));
                state.updateEntity(merged);
                state.removeEntity(right.id());
                left = merged;
            }
        }
    }

    private static boolean overlaps(PersistedEntityState a, PersistedEntityState b) {
        return within(a.pos().x(), b.pos().x())
                && within(a.pos().y(), b.pos().y())
                && within(a.pos().z(), b.pos().z());
    }

    private static boolean within(long a, long b) {
        return a >= b - MERGE_RADIUS && a <= b + MERGE_RADIUS;
    }

    private static long multiplyFixed(long value, long multiplier) {
        return Math.multiplyHigh(value, multiplier) << 32 | (value * multiplier) >>> 32;
    }

    /** Decoded ITEM stack payload. */
    public record ItemStack(int itemStackId, int count) {
    }
}
