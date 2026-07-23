package dev.nodera.storage.event;

import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.event.EntityTransferAcceptedEvent;
import dev.nodera.core.event.EntityTransferCommittedEvent;
import dev.nodera.core.event.EntityTransferPreparedEvent;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.CertificateStore;
import dev.nodera.storage.RegionEventStore;

import java.util.Optional;

/**
 * Replays a region's certified event log and verifies it (Task 9, Phase 5). Walking from a start
 * root (genesis or a checkpoint), each event must:
 * <ul>
 *   <li>chain — its {@code prevRoot} equals the running root;</li>
 *   <li>be certified — a {@link QuorumCertificate} exists for its {@code certificateRef} whose
 *       {@code region}/{@code version}/{@code resultingRoot} match the event (Invariant 3).</li>
 * </ul>
 *
 * <p>An event with <b>no matching certificate</b> is treated as a locally-newer-but-uncertified
 * suffix and replay stops there — the forward-sync rule (Invariant 8): a returning peer never lets an
 * uncommitted local tail override the certified network state. A CHAIN break or a certificate that
 * exists but contradicts the event is a hard error (tampering).
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class EventReplayer {

    private EventReplayer() {
    }

    /**
     * Replay {@code region}'s log from {@code fromEventId}.
     *
     * @param events      the event store.
     * @param certs       the certificate store.
     * @param region      the region.
     * @param startRoot   the root to start from (genesis or checkpoint root).
     * @param fromEventId the first event id to replay (inclusive).
     * @return the verified final root and replay stats.
     */
    public static ReplayResult replay(RegionEventStore events, CertificateStore certs, RegionId region,
                                      StateRoot startRoot, long fromEventId) {
        StateRoot running = startRoot;
        long applied = 0;
        long lastCertifiedEventId = fromEventId - 1;
        boolean stoppedAtUncertified = false;

        for (CommittedEventEnvelope e : events.readFrom(region, fromEventId)) {
            if (!e.prevRoot().equals(running)) {
                throw new IllegalStateException("event chain break for " + region
                        + " at event " + e.eventId() + ": prevRoot does not match running root");
            }
            Optional<QuorumCertificate> cert = certs.getByHash(e.certificateRef());
            Optional<EntityTransferCertificate> transferCert =
                    cert.isEmpty() ? certs.getTransferByHash(e.certificateRef()) : Optional.empty();
            if (cert.isEmpty() && transferCert.isEmpty()) {
                // uncertified suffix → uncommitted; stop replaying forward.
                stoppedAtUncertified = true;
                break;
            }
            boolean backed = cert.map(c -> c.region().equals(region)
                    && c.version().equals(e.version())
                    && c.resultingRoot().equals(e.resultingRoot()))
                    .orElseGet(() -> transferBacks(transferCert.orElseThrow(), e));
            if (!backed) {
                throw new IllegalStateException("certificate does not back event " + e.eventId()
                        + " for " + region + " (tampered log)");
            }
            running = e.resultingRoot();
            applied++;
            lastCertifiedEventId = e.eventId();
        }
        return new ReplayResult(running, applied, lastCertifiedEventId, stoppedAtUncertified);
    }

    private static boolean transferBacks(
            EntityTransferCertificate certificate, CommittedEventEnvelope event) {
        var descriptor = certificate.descriptor();
        boolean source = event.region().equals(descriptor.sourceRegion());
        if (!source && !event.region().equals(descriptor.targetRegion())) {
            return false;
        }
        var other = source ? descriptor.targetRegion() : descriptor.sourceRegion();
        var base = source ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion();
        var result = source
                ? descriptor.sourceResultingVersion() : descriptor.targetResultingVersion();
        StateRoot previous = source ? descriptor.sourcePrevRoot() : descriptor.targetPrevRoot();
        StateRoot resulting = source
                ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot();
        if (event.event() instanceof EntityTransferPreparedEvent prepared) {
            return prepared.transferId() == descriptor.transferId()
                    && prepared.counterpart().equals(other)
                    && prepared.entity().id().equals(descriptor.entityId())
                    && event.version().equals(base)
                    && event.prevRoot().equals(previous)
                    && event.resultingRoot().equals(previous);
        }
        if (event.event() instanceof EntityTransferAcceptedEvent accepted) {
            return accepted.transferId() == descriptor.transferId()
                    && accepted.counterpart().equals(other)
                    && accepted.entityId().equals(descriptor.entityId())
                    && event.version().equals(base)
                    && event.prevRoot().equals(previous)
                    && event.resultingRoot().equals(previous);
        }
        if (event.event() instanceof EntityTransferCommittedEvent committed) {
            return committed.transferId() == descriptor.transferId()
                    && committed.counterpart().equals(other)
                    && committed.entityId().equals(descriptor.entityId())
                    && event.version().equals(result)
                    && event.prevRoot().equals(previous)
                    && event.resultingRoot().equals(resulting);
        }
        return false;
    }

    /**
     * @param finalRoot            the verified head root after replay.
     * @param eventsApplied        number of certified events folded in.
     * @param lastCertifiedEventId id of the last certified event (or {@code fromEventId - 1} if none).
     * @param stoppedAtUncertified {@code true} if replay stopped at an uncertified suffix.
     */
    public record ReplayResult(StateRoot finalRoot, long eventsApplied, long lastCertifiedEventId,
                               boolean stoppedAtUncertified) {
    }
}
