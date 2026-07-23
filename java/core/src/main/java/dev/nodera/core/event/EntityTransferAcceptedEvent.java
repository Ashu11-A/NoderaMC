package dev.nodera.core.event;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NetworkEntityId;

/** Region-log marker that both committees accepted one transfer descriptor. */
public record EntityTransferAcceptedEvent(
        long transferId, RegionId counterpart, NetworkEntityId entityId) implements RegionEvent {

    public EntityTransferAcceptedEvent {
        if (counterpart == null || entityId == null) {
            throw new IllegalArgumentException("counterpart and entityId must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ENTITY_TRANSFER_ACCEPTED_EVENT).writeU16(ENCODING_VERSION);
        w.writeU64(transferId);
        counterpart.encode(w);
        entityId.encode(w);
    }

    static EntityTransferAcceptedEvent decodeBody(CanonicalReader r) {
        return new EntityTransferAcceptedEvent(
                r.readU64(), RegionId.decode(r), NetworkEntityId.decode(r));
    }
}
