package dev.nodera.peer.sync;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.event.BlockChangedEvent;
import dev.nodera.core.event.CommittedEventEnvelope;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.storage.GenesisManifest;
import dev.nodera.storage.event.EventSourcedWorldStore;
import dev.nodera.storage.event.PeerSyncFlow;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-30's remaining exit clause, discharged: Task 9's forward event-sync runs OVER the
 * {@code PeerTransport} — a fresh peer pulls another peer's certified region events (with their
 * certificates) across the wire, appends them to its own store, replays the certified chain, and
 * converges on the identical certified final root. An uncertified tail never syncs.
 */
final class EventSyncOverTransportIT {

    private final HashService hashes = new HashService();
    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    private StateRoot root(String tag) {
        return StateRoot.of(hashes.sha256(tag.getBytes()));
    }

    private EventSourcedWorldStore store() {
        return new EventSourcedWorldStore(
                new GenesisManifest(0x4E4F4445_5241L, 1, 99L, root("genesis")), hashes);
    }

    private CommittedEventEnvelope event(RegionId region, long eventId,
                                         StateRoot prevRoot, StateRoot resultingRoot,
                                         Bytes certHash) {
        return new CommittedEventEnvelope(region, RegionEpoch.INITIAL,
                new SnapshotVersion(eventId + 1), eventId * 10L, eventId,
                new BlockChangedEvent(new NBlockPos(5, 70, 5), 0, 1),
                prevRoot, resultingRoot, certHash);
    }

    private void appendCertified(EventSourcedWorldStore store, RegionId region, long eventId,
                                 StateRoot prevRoot, StateRoot resultingRoot) {
        QuorumCertificate cert = new QuorumCertificate(
                region, RegionEpoch.INITIAL, new SnapshotVersion(eventId + 1),
                StateRoot.zero(), resultingRoot,
                java.util.List.of(
                        new SignedVote(new NodeId(new java.util.UUID(0, 1)), resultingRoot,
                                VoteDecision.ACCEPT, Bytes.empty()),
                        new SignedVote(new NodeId(new java.util.UUID(0, 2)), resultingRoot,
                                VoteDecision.ACCEPT, Bytes.empty())));
        store.certificateStore().put(cert);
        Bytes certHash = store.certificateStore().contentId(cert).hash();
        store.events().append(event(region, eventId, prevRoot, resultingRoot, certHash));
    }

    @Test
    void freshPeerSyncsCertifiedEventsOverTheTransportAndConvergesOnTheRoot() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity serverId = NodeIdentity.generate();
        NodeIdentity clientId = NodeIdentity.generate();
        LoopbackTransport serverTx = net.register(serverId.nodeId());
        LoopbackTransport clientTx = net.register(clientId.nodeId());
        serverTx.start();
        clientTx.start();
        try {
            RegionId region = REGION;
            EventSourcedWorldStore serverStore = store();
            StateRoot g = serverStore.genesis().genesisRoot();
            StateRoot r1 = root("1");
            StateRoot r2 = root("2");
            appendCertified(serverStore, region, 0, g, r1);
            appendCertified(serverStore, region, 1, r1, r2);
            // An UNCERTIFIED local tail on the server must never reach the syncing peer's
            // certified state (Invariant 8).
            serverStore.events().append(event(region, 2, r2, root("3"), Bytes.empty()));

            EventSourcedWorldStore clientStore = store();
            EventSyncService serverSync = new EventSyncService(serverStore, serverTx);
            EventSyncService clientSync = new EventSyncService(clientStore, clientTx);

            AtomicReference<PeerSyncFlow.Outcome> outcome = new AtomicReference<>();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            serverTx.setHandler(new dev.nodera.transport.MessageHandler() {
                @Override public void onMessage(PeerAddress from, byte[] frame) {
                    serverSync.onMessage(from, MessageCodec.decode(frame));
                }
                @Override public void onPeerDown(PeerAddress peer) { }
            });
            clientTx.setHandler(new dev.nodera.transport.MessageHandler() {
                @Override public void onMessage(PeerAddress from, byte[] frame) {
                    var message = MessageCodec.decode(frame);
                    if (message instanceof dev.nodera.protocol.simulationmsg.EventSyncAnswer a) {
                        outcome.set(clientSync.ingest(a));
                        done.countDown();
                    }
                }
                @Override public void onPeerDown(PeerAddress peer) { }
            });

            clientSync.requestFrom(
                    PeerAddress.of(serverId.nodeId(), "loopback"), region, -1L);
            assertThat(done.await(5, TimeUnit.SECONDS))
                    .as("the sync answer arrives over the transport").isTrue();

            PeerSyncFlow.Outcome o = outcome.get();
            assertThat(o.isAccepted()).isTrue();
            assertThat(o.accepted().finalRoot())
                    .as("the fresh peer converges on the server's certified root")
                    .isEqualTo(r2);
            assertThat(o.accepted().lastCertifiedEventId())
                    .as("the uncertified tail did not sync as certified state")
                    .isEqualTo(1);
            assertThat(clientStore.events().lastEventId(region))
                    .as("certified events landed in the local log")
                    .isGreaterThanOrEqualTo(1);
        } finally {
            serverTx.stop();
            clientTx.stop();
        }
    }
}
