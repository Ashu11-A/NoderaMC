package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * Forward event-sync answer (Task 9 / L-30): the serving peer's certified events for
 * {@code region} after the requested id, each element the canonical encoding of one
 * {@code CommittedEventEnvelope} (opaque at the transport tier, exactly like
 * {@code WorldManifestAnswer}'s manifests — the storage tier decodes and chain-verifies).
 *
 * @param region              the region synced; not null.
 * @param encodedEvents       canonical event envelopes in ascending event-id order; not null,
 *                            defensively copied. Empty = the server has nothing newer.
 * @param encodedCertificates the canonical {@code QuorumCertificate}s the events' cert hashes
 *                            reference — the requester stores them content-addressed BEFORE
 *                            replay, so the certified chain verifies locally (an event whose
 *                            certificate is absent replays as uncertified and is discarded).
 */
public record EventSyncAnswer(
        RegionId region, List<Bytes> encodedEvents, List<Bytes> encodedCertificates)
        implements NoderaMessage {

    public EventSyncAnswer {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(encodedEvents, "encodedEvents");
        Objects.requireNonNull(encodedCertificates, "encodedCertificates");
        encodedEvents = List.copyOf(encodedEvents);
        encodedCertificates = List.copyOf(encodedCertificates);
    }
}
