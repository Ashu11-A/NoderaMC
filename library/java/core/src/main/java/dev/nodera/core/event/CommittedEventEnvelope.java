package dev.nodera.core.event;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

/**
 * The committed-event envelope broadcast when a delta is finalised (Task 2 event/). Carries the
 * region, epoch, version, tick, a monotonic {@code eventId}, the polymorphic {@link RegionEvent},
 * the {@code prevRoot} → {@code resultingRoot} state-root transition, and an optional
 * {@code certificateRef} content-hash pointer to the {@link dev.nodera.core.consensuscert.QuorumCertificate}
 * that finalised this commit (may be {@link Bytes#empty()}).
 *
 * <p>The {@code event} is encoded polymorphically via its own {@link RegionEvent#encode} (each
 * permit owns its typeTag), and decoded by dispatching on the next tag through
 * {@link RegionEvent#decodeEvent}.
 *
 * <p>Wire form: {@code [u16 COMMITTED_EVENT_ENV][u16 ENCODING_VERSION][RegionId][RegionEpoch]
     * [SnapshotVersion][u64 tick][u64 eventId][RegionEvent][StateRoot prevRoot][StateRoot resultingRoot]
     * [bytes certificateRef]}.
 *
 * @Thread-context immutable, any thread.
 */
public record CommittedEventEnvelope(
        RegionId region,
        RegionEpoch epoch,
        SnapshotVersion version,
        long tick,
        long eventId,
        RegionEvent event,
        StateRoot prevRoot,
        StateRoot resultingRoot,
        Bytes certificateRef
) implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public CommittedEventEnvelope {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }
        if (epoch == null) {
            throw new IllegalArgumentException("epoch must not be null");
        }
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (prevRoot == null) {
            throw new IllegalArgumentException("prevRoot must not be null");
        }
        if (resultingRoot == null) {
            throw new IllegalArgumentException("resultingRoot must not be null");
        }
        if (certificateRef == null) {
            throw new IllegalArgumentException("certificateRef must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.COMMITTED_EVENT_ENV).writeU16(ENCODING_VERSION);
        region.encode(w);
        epoch.encode(w);
        version.encode(w);
        w.writeU64(tick);
        w.writeU64(eventId);
        event.encode(w);
        prevRoot.encode(w);
        resultingRoot.encode(w);
        w.writeBytes(certificateRef);
    }

    /**
     * Full-frame decode. The {@code event} is decoded polymorphically via
     * {@link RegionEvent#decodeEvent}; an unknown event tag throws.
     *
     * @throws IllegalStateException if the next tag is not {@code COMMITTED_EVENT_ENV}, or if the
     *                               embedded event tag is unknown.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static CommittedEventEnvelope decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.COMMITTED_EVENT_ENV) {
            throw new IllegalStateException("expected COMMITTED_EVENT_ENV tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        RegionId region = RegionId.decode(r);
        RegionEpoch epoch = RegionEpoch.decode(r);
        SnapshotVersion version = SnapshotVersion.decode(r);
        long tick = r.readU64();
        long eventId = r.readU64();
        RegionEvent event = RegionEvent.decodeEvent(r);
        StateRoot prevRoot = StateRoot.decode(r);
        StateRoot resultingRoot = StateRoot.decode(r);
        Bytes certificateRef = r.readBytesValue();
        return new CommittedEventEnvelope(region, epoch, version, tick, eventId, event,
                prevRoot, resultingRoot, certificateRef);
    }
}
