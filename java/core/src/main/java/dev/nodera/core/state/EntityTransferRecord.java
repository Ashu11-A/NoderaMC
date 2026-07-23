package dev.nodera.core.state;

import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/** Durable restart record for one cross-region transfer state machine. */
public record EntityTransferRecord(
        Stage stage,
        EntityTransferDescriptor descriptor,
        RegionDelta sourceDelta,
        RegionDelta targetDelta,
        EntityTransferCertificate certificate,
        String failure
) implements Encodable {

    /** Monotonic durable stages. */
    public enum Stage {
        PREPARED,
        ACCEPTED,
        APPLIED,
        COMMITTED,
        ABORTED
    }

    public EntityTransferRecord {
        if (stage == null || descriptor == null || sourceDelta == null || targetDelta == null
                || failure == null) {
            throw new IllegalArgumentException("transfer record arguments must not be null");
        }
        boolean certificateRequired = stage == Stage.ACCEPTED
                || stage == Stage.APPLIED || stage == Stage.COMMITTED;
        if (certificateRequired != (certificate != null)) {
            throw new IllegalArgumentException(
                    "certificate presence does not match transfer stage " + stage);
        }
        if ((stage == Stage.ABORTED) != !failure.isEmpty()) {
            throw new IllegalArgumentException("only an aborted transfer carries a failure");
        }
        if (!descriptor.sourceRegion().equals(sourceDelta.region())
                || !descriptor.targetRegion().equals(targetDelta.region())
                || !descriptor.sourceBaseVersion().equals(sourceDelta.baseVersion())
                || !descriptor.targetBaseVersion().equals(targetDelta.baseVersion())) {
            throw new IllegalArgumentException("transfer deltas do not match descriptor");
        }
        if (certificate != null && !certificate.descriptor().equals(descriptor)) {
            throw new IllegalArgumentException("transfer certificate does not match descriptor");
        }
    }

    /** Transfer id used as durable store key. */
    public long transferId() {
        return descriptor.transferId();
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.ENTITY_TRANSFER_RECORD).writeU16(ENCODING_VERSION);
        w.writeU8(stage.ordinal());
        descriptor.encode(w);
        sourceDelta.encode(w);
        targetDelta.encode(w);
        w.writeOptional(certificate);
        if (certificate != null) {
            certificate.encode(w);
        }
        w.writeString(failure);
    }

    /** Decode one durable transfer record. */
    public static EntityTransferRecord decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.ENTITY_TRANSFER_RECORD) {
            throw new IllegalStateException("expected ENTITY_TRANSFER_RECORD tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        int ordinal = r.readU8();
        if (ordinal >= Stage.values().length) {
            throw new IllegalStateException("unknown transfer stage " + ordinal);
        }
        EntityTransferDescriptor descriptor = EntityTransferDescriptor.decode(r);
        RegionDelta sourceDelta = RegionDelta.decode(r);
        RegionDelta targetDelta = RegionDelta.decode(r);
        EntityTransferCertificate certificate = r.readOptional()
                ? EntityTransferCertificate.decode(r) : null;
        return new EntityTransferRecord(
                Stage.values()[ordinal], descriptor, sourceDelta, targetDelta,
                certificate, r.readString());
    }
}
