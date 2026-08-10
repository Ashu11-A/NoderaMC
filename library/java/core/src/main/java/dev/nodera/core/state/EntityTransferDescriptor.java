package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;

/**
 * Canonical description jointly approved by both committees for one cross-region transfer.
 * Transition roots bind every state mutation while {@code tick} binds the target catch-up barrier.
 *
 * @Thread-context immutable, any thread.
 */
public record EntityTransferDescriptor(
        long transferId,
        RegionId sourceRegion,
        RegionId targetRegion,
        RegionEpoch sourceEpoch,
        RegionEpoch targetEpoch,
        NetworkEntityId entityId,
        SnapshotVersion sourceBaseVersion,
        SnapshotVersion sourceResultingVersion,
        StateRoot sourcePrevRoot,
        StateRoot sourceResultingRoot,
        StateRoot sourceTransitionRoot,
        SnapshotVersion targetBaseVersion,
        SnapshotVersion targetResultingVersion,
        StateRoot targetPrevRoot,
        StateRoot targetResultingRoot,
        StateRoot targetTransitionRoot,
        long tick
) implements Encodable {

    public EntityTransferDescriptor {
        if (sourceRegion == null || targetRegion == null
                || sourceEpoch == null || targetEpoch == null || entityId == null
                || sourceBaseVersion == null || sourceResultingVersion == null
                || sourcePrevRoot == null || sourceResultingRoot == null
                || sourceTransitionRoot == null || targetBaseVersion == null
                || targetResultingVersion == null || targetPrevRoot == null
                || targetResultingRoot == null || targetTransitionRoot == null) {
            throw new IllegalArgumentException("transfer descriptor arguments must not be null");
        }
        if (sourceRegion.equals(targetRegion)) {
            throw new IllegalArgumentException("source and target regions must differ");
        }
        requireNext(sourceBaseVersion, sourceResultingVersion, "source");
        requireNext(targetBaseVersion, targetResultingVersion, "target");
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeFrame(TypeTags.ENTITY_TRANSFER_DESCRIPTOR, ENCODING_VERSION);
        w.writeU64(transferId);
        sourceRegion.encode(w);
        targetRegion.encode(w);
        sourceEpoch.encode(w);
        targetEpoch.encode(w);
        entityId.encode(w);
        sourceBaseVersion.encode(w);
        sourceResultingVersion.encode(w);
        sourcePrevRoot.encode(w);
        sourceResultingRoot.encode(w);
        sourceTransitionRoot.encode(w);
        targetBaseVersion.encode(w);
        targetResultingVersion.encode(w);
        targetPrevRoot.encode(w);
        targetResultingRoot.encode(w);
        targetTransitionRoot.encode(w);
        w.writeU64(tick);
    }

    /** Decode one canonical transfer descriptor. */
    public static EntityTransferDescriptor decode(CanonicalReader r) {
        r.expectFrame(TypeTags.ENTITY_TRANSFER_DESCRIPTOR, "ENTITY_TRANSFER_DESCRIPTOR", ENCODING_VERSION);
        return new EntityTransferDescriptor(
                r.readU64(), RegionId.decode(r), RegionId.decode(r),
                RegionEpoch.decode(r), RegionEpoch.decode(r), NetworkEntityId.decode(r),
                SnapshotVersion.decode(r), SnapshotVersion.decode(r), StateRoot.decode(r),
                StateRoot.decode(r), StateRoot.decode(r), SnapshotVersion.decode(r),
                SnapshotVersion.decode(r), StateRoot.decode(r), StateRoot.decode(r),
                StateRoot.decode(r), r.readU64());
    }

    private static void requireNext(SnapshotVersion base, SnapshotVersion result, String side) {
        if (base.value() == Long.MAX_VALUE || result.value() != base.value() + 1) {
            throw new IllegalArgumentException(side + " transfer version must advance exactly once");
        }
    }
}
