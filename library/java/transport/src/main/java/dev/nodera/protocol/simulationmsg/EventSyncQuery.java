package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * Forward event-sync request (Task 9 / L-30): ask a peer for the certified events of
 * {@code region} with event ids strictly greater than {@code sinceEventId} ({@code -1} = from
 * the beginning). The answer is {@link EventSyncAnswer}; the requester ingests the events into
 * its local store and replays them through {@code PeerSyncFlow.syncForward} — certified region
 * state flowing peer-to-peer.
 *
 * @param region       the region to sync; not null.
 * @param sinceEventId the requester's last locally-known certified event id, or {@code -1}.
 */
public record EventSyncQuery(RegionId region, long sinceEventId) implements NoderaMessage {

    public EventSyncQuery {
        Objects.requireNonNull(region, "region");
        if (sinceEventId < -1) {
            throw new IllegalArgumentException("sinceEventId must be >= -1: " + sinceEventId);
        }
    }
}
