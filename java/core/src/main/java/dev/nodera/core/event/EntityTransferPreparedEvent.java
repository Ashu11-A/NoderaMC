package dev.nodera.core.event;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.PersistedEntityState;

/** One region log's durable PREPARE marker for an entity handoff (Task 12c). */
public record EntityTransferPreparedEvent(
        long transferId,
        RegionId counterpart,
        PersistedEntityState entity
) implements RegionEvent {

    public EntityTransferPreparedEvent {
        if (counterpart == null || entity == null) {
            throw new IllegalArgumentException("counterpart and entity must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ENTITY_TRANSFER_PREPARED_EVENT).writeU16(ENCODING_VERSION);
        w.writeU64(transferId);
        counterpart.encode(w);
        entity.encode(w);
    }

    static EntityTransferPreparedEvent decodeBody(CanonicalReader r) {
        return new EntityTransferPreparedEvent(
                r.readU64(), RegionId.decode(r), PersistedEntityState.decode(r));
    }
}
