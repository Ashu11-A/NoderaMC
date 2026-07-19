package dev.nodera.core.event;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.SnapshotVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entity-lifecycle {@link RegionEvent}s (Task 12a): each round-trips through the polymorphic
 * {@link RegionEvent#decodeEvent} dispatch, and the sealed hierarchy stays self-describing — every
 * event tag names exactly one permit.
 */
final class EntityEventsTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    private static PersistedEntityState entity(int seq) {
        return new PersistedEntityState(
                NetworkEntityId.allocate(REGION, new SnapshotVersion(1), seq),
                EntityKind.ITEM, 7, new FixedVec3(FixedVec3.ONE, 64 * FixedVec3.ONE, 0),
                FixedVec3.ZERO, 0, 6000, Bytes.fromHex("01"));
    }

    @Test
    void createdAndUpdatedRoundTripWithFullState() {
        PersistedEntityState state = entity(1);
        EntityCreatedEvent created = new EntityCreatedEvent(state);
        EntityUpdatedEvent updated = new EntityUpdatedEvent(state.tick());
        assertThat(decode(encode(created))).isEqualTo(created);
        assertThat(decode(encode(updated))).isEqualTo(updated);
        assertThat(((EntityCreatedEvent) decode(encode(created))).entityId()).isEqualTo(state.id());
    }

    @Test
    void removedRoundTripsByIdOnly() {
        EntityRemovedEvent removed = new EntityRemovedEvent(entity(2).id());
        assertThat(decode(encode(removed))).isEqualTo(removed);
    }

    @Test
    void mixedBlockAndEntityEventsDispatchWithoutCollision() {
        RegionEvent[] events = {
                new BlockChangedEvent(new dev.nodera.core.state.NBlockPos(1, 2, 3), 0, 1),
                new EntityCreatedEvent(entity(0)),
                new EntityUpdatedEvent(entity(1).tick()),
                new EntityRemovedEvent(entity(2).id()),
        };
        for (RegionEvent event : events) {
            RegionEvent decoded = decode(encode(event));
            assertThat(decoded).isEqualTo(event);
            assertThat(decoded.getClass()).isEqualTo(event.getClass());
        }
    }

    private static byte[] encode(RegionEvent e) {
        CanonicalWriter w = new CanonicalWriter();
        e.encode(w);
        return w.toBytes().toArray();
    }

    private static RegionEvent decode(byte[] bytes) {
        return RegionEvent.decodeEvent(new CanonicalReader(bytes));
    }
}
