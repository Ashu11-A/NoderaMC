package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionId;

/** Consensus-bound request to insert an entity into another region after border crossing. */
public record EntityTransferIntent(
        RegionId targetRegion,
        PersistedEntityState targetState
) implements Encodable {

    public EntityTransferIntent {
        if (targetRegion == null || targetState == null) {
            throw new IllegalArgumentException("targetRegion and targetState must not be null");
        }
    }

    public NetworkEntityId entityId() {
        return targetState.id();
    }

    @Override
    public void encode(CanonicalWriter writer) {
        writer.writeU16(TypeTags.ENTITY_TRANSFER_INTENT).writeU16(ENCODING_VERSION);
        targetRegion.encode(writer);
        targetState.encode(writer);
    }

    public static EntityTransferIntent decode(CanonicalReader reader) {
        int tag = reader.readU16();
        if (tag != TypeTags.ENTITY_TRANSFER_INTENT) {
            throw new IllegalStateException("expected ENTITY_TRANSFER_INTENT tag, got " + tag);
        }
        reader.readVersion(ENCODING_VERSION);
        return new EntityTransferIntent(
                RegionId.decode(reader), PersistedEntityState.decode(reader));
    }
}
