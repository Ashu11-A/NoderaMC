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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class EntityTransferEventsTest {

    private static final RegionId OTHER = new RegionId(DimensionKey.overworld(), 1, 0);
    private static final PersistedEntityState ENTITY = new PersistedEntityState(
            new NetworkEntityId(9), EntityKind.ITEM, 4,
            FixedVec3.ofBlock(127, 5, 1), FixedVec3.ZERO,
            2, 6_000, Bytes.unsafeWrap(new byte[]{0, 0, 0, 4, 1}));

    @Test
    void preparedEventRoundTripsPolymorphically() {
        RegionEvent event = new EntityTransferPreparedEvent(7, OTHER, ENTITY);
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void acceptedEventRoundTripsPolymorphically() {
        RegionEvent event = new EntityTransferAcceptedEvent(7, OTHER, ENTITY.id());
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void committedEventRoundTripsPolymorphically() {
        RegionEvent event = new EntityTransferCommittedEvent(7, OTHER, ENTITY.id());
        assertThat(roundTrip(event)).isEqualTo(event);
    }

    @Test
    void preparedRejectsNullFields() {
        assertThatThrownBy(() -> new EntityTransferPreparedEvent(1, null, ENTITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityTransferPreparedEvent(1, OTHER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void committedRejectsNullFields() {
        assertThatThrownBy(() -> new EntityTransferCommittedEvent(1, null, ENTITY.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityTransferCommittedEvent(1, OTHER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptedRejectsNullFields() {
        assertThatThrownBy(() -> new EntityTransferAcceptedEvent(1, null, ENTITY.id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EntityTransferAcceptedEvent(1, OTHER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RegionEvent roundTrip(RegionEvent event) {
        CanonicalWriter w = new CanonicalWriter();
        event.encode(w);
        return RegionEvent.decodeEvent(new CanonicalReader(w.toByteArray()));
    }
}
