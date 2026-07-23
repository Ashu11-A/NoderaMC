package dev.nodera.peer.sync;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.simulationmsg.EventSyncAnswer;
import dev.nodera.protocol.simulationmsg.EventSyncQuery;
import dev.nodera.storage.WorldStore;
import dev.nodera.storage.event.PeerSyncFlow;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;

import java.util.ArrayList;
import java.util.List;

/**
 * The transport half of Task 9's forward event-sync (L-30's remaining exit clause): certified
 * region events flow peer-to-peer over the same {@link PeerTransport} the committee lane rides.
 *
 * <p><b>Serve side.</b> {@link #onMessage} answers an {@link EventSyncQuery} with every certified
 * event of the region whose id is strictly greater than {@code sinceEventId}, canonically encoded
 * ({@link EventSyncAnswer}).
 *
 * <p><b>Ingest side.</b> {@link #ingest} decodes an answer, appends the events to the local
 * store's log (append-only; the store's chain validation rejects gaps/forks), and replays through
 * {@link PeerSyncFlow#syncForward} — the certified chain, not the sender, is the authority: a
 * tampered or uncertified suffix is discarded by the replayer exactly as in the local case.
 *
 * <p>Thread-context: confined to the transport's delivery thread per instance.
 */
public final class EventSyncService {

    private final WorldStore store;
    private final PeerTransport transport;

    public EventSyncService(WorldStore store, PeerTransport transport) {
        if (store == null || transport == null) {
            throw new IllegalArgumentException("store and transport must not be null");
        }
        this.store = store;
        this.transport = transport;
    }

    /** Ask {@code peer} for {@code region}'s certified events after {@code sinceEventId}. */
    public void requestFrom(PeerAddress peer, RegionId region, long sinceEventId) {
        send(peer, new EventSyncQuery(region, sinceEventId));
    }

    /**
     * Handle one inbound sync message. Wire into the runtime's application-message dispatch;
     * returns true when the message was a sync message this service consumed.
     */
    public boolean onMessage(PeerAddress from, NoderaMessage message) {
        switch (message) {
            case EventSyncQuery q -> {
                List<Bytes> events = new ArrayList<>();
                List<Bytes> certificates = new ArrayList<>();
                java.util.Set<Bytes> certHashes = new java.util.LinkedHashSet<>();
                for (CommittedEventEnvelope event
                        : store.events().readFrom(q.region(), q.sinceEventId() + 1)) {
                    CanonicalWriter w = new CanonicalWriter();
                    event.encode(w);
                    events.add(w.toBytes());
                    if (!event.certificateRef().isEmpty()) {
                        certHashes.add(event.certificateRef());
                    }
                }
                for (Bytes hash : certHashes) {
                    store.certificates().getByHash(hash).ifPresent(cert -> {
                        CanonicalWriter w = new CanonicalWriter();
                        cert.encode(w);
                        certificates.add(w.toBytes());
                    });
                }
                send(from, new EventSyncAnswer(q.region(), events, certificates));
                return true;
            }
            case EventSyncAnswer a -> {
                ingest(a);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Append the answer's events to the local log and replay the certified chain forward.
     *
     * @return the sync outcome after ingest (reject reasons surface unchanged).
     */
    public PeerSyncFlow.Outcome ingest(EventSyncAnswer answer) {
        // Certificates first (content-addressed, idempotent): the replayer treats an event whose
        // certificate is absent as uncertified and discards it — the chain, not the sender, is
        // the authority.
        for (Bytes encoded : answer.encodedCertificates()) {
            CanonicalReader r = new CanonicalReader(encoded.toArray());
            store.certificates().put(
                    dev.nodera.core.consensuscert.QuorumCertificate.decode(r));
        }
        long localLatest = store.events().lastEventId(answer.region());
        for (Bytes encoded : answer.encodedEvents()) {
            CanonicalReader r = new CanonicalReader(encoded.toArray());
            CommittedEventEnvelope event = CommittedEventEnvelope.decode(r);
            if (r.available() != 0) {
                throw new IllegalArgumentException("trailing bytes in synced event envelope");
            }
            if (event.eventId() <= localLatest) {
                continue; // duplicate/overlap: the log is append-only, skip what we hold
            }
            store.events().append(event);
            localLatest = event.eventId();
        }
        return PeerSyncFlow.syncForward(store, answer.region(),
                store.events().lastEventId(answer.region()));
    }

    private void send(PeerAddress to, NoderaMessage message) {
        try {
            transport.send(to, MessageCodec.encode(message));
        } catch (TransportException unreachable) {
            // Best-effort: sync re-requests on the next cycle; an unreachable peer is not an error.
        }
    }
}
