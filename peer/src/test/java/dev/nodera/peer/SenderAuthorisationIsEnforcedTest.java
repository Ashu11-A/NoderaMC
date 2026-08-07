package dev.nodera.peer;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.PeerGoodbye;
import dev.nodera.protocol.membership.PeerJoin;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.testkit.peer.Await;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The NDR2 authorisation table, over a real runtime rather than its own unit test.
 *
 * <p>{@code MessageTypes} declares, per kind, who is allowed to send it, and six membership and
 * content kinds carry {@code TRANSPORT_SENDER_EQUALS}: a peer may only speak for <b>itself</b>. The
 * table was complete and asserted — and {@code MessageType.permits} had exactly one non-test caller
 * in the whole repository, {@code MessageRouter}, which has no production caller either. So the rule
 * held in tests and nowhere on a running node: {@code PeerRuntime.dispatch} accepted every membership
 * frame from any connected socket, which is the state the table's own comment describes as the defect
 * it was written to remove.
 *
 * <p>Both tests are ordered rather than timed. The runtime dispatches on one state thread in arrival
 * order, so a legitimate frame sent <i>after</i> the forged one, and observed to have taken effect,
 * proves the forged one was already handled — and refused. That also makes each test its own positive
 * control: a check that refused everything would fail them just as loudly as one that refused nothing.
 */
final class SenderAuthorisationIsEnforcedTest {

    private final PeerRuntimeConfig fast =
            new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500));
    private final List<PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
    }

    private static NodeCapabilities caps() {
        return NodeCapabilities.initial().withRoles(
                EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.REGION_VALIDATOR));
    }

    private PeerRuntime start(NodeIdentity id, LoopbackNetwork net, String route) {
        LoopbackTransport tx = net.register(id.nodeId());
        PeerRuntime rt = PeerRuntime.bootstrap(id, caps(), tx, () -> route, fast,
                new RecordingListener());
        runtimes.add(rt);
        return rt;
    }

    /** A transport with no runtime behind it: the shape of a peer that only wants to send frames. */
    private static LoopbackTransport rawPeer(NodeId id, LoopbackNetwork net) {
        LoopbackTransport tx = net.register(id);
        tx.setHandler(new dev.nodera.transport.MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        });
        tx.start();
        return tx;
    }

    private static boolean holds(PeerRuntime runtime, NodeId member) {
        for (PeerEntry entry : runtime.sessionView().members()) {
            if (entry.nodeId().equals(member)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a stranger's goodbye about a live peer is a report, not an eviction")
    void aForgedGoodbyeDoesNotEvictALivePeer() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity victimId = NodeIdentity.generate();
        NodeIdentity decoyId = NodeIdentity.generate();

        PeerRuntime host = start(hostId, net, "host");
        PeerRuntime victim = start(victimId, net, "victim");
        PeerRuntime decoy = start(decoyId, net, "decoy");
        PeerAddress hostAddress = PeerAddress.of(hostId.nodeId(), "host");
        victim.joinSession(hostAddress);
        decoy.joinSession(hostAddress);
        Await.until("the host sees all three", 5_000, () -> host.sessionView().size() == 3);

        LoopbackTransport attacker = rawPeer(NodeIdentity.generate().nodeId(), net);
        attacker.send(hostAddress,
                WireCodec.encode(new PeerGoodbye(victimId.nodeId(), 0L, "evicted by a stranger")));
        // The honest frame that orders the assertion: same state thread, sent second. A peer IS the
        // authority on its own exit, so this one is acted on immediately.
        net.transportOf(decoyId.nodeId()).send(hostAddress,
                WireCodec.encode(new PeerGoodbye(decoyId.nodeId(), 0L, "leaving")));

        Await.until("the decoy's own goodbye is honoured", 5_000,
                () -> !holds(host, decoyId.nodeId()));
        // The victim is alive and keeps sending keep-alives, so the report expires against its own
        // liveness rather than removing it. Blanket-refusing the frame instead would have been
        // wrong in the other direction: `handleTransportDown` and the heartbeat sweep broadcast
        // goodbyes naming third parties, and that gossip is the mesh's fast departure path.
        assertThat(holds(host, victimId.nodeId()))
                .as("a live peer is not evicted by a stranger asserting it left")
                .isTrue();
        Await.until("and it stays a member while it keeps talking", 3_000,
                () -> holds(host, victimId.nodeId()));
    }

    @Test
    @DisplayName("a peer cannot enrol a node that never joined by sending a join in its name")
    void aForgedJoinDoesNotAddItsSubject() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity attackerId = NodeIdentity.generate();
        NodeId fabricated = NodeIdentity.generate().nodeId();

        PeerRuntime host = start(hostId, net, "host");
        PeerAddress hostAddress = PeerAddress.of(hostId.nodeId(), "host");
        LoopbackTransport attacker = rawPeer(attackerId.nodeId(), net);

        attacker.send(hostAddress, WireCodec.encode(new PeerJoin(
                fabricated, "ghost", caps(), false, Bytes.empty(), "test")));
        attacker.send(hostAddress, WireCodec.encode(new PeerJoin(
                attackerId.nodeId(), "attacker", caps(), false, Bytes.empty(), "test")));

        Await.until("the attacker's own join is honoured", 5_000,
                () -> holds(host, attackerId.nodeId()));
        assertThat(holds(host, fabricated))
                .as("a membership the mesh gains from a peer speaking for a node it is not")
                .isFalse();
        assertThat(host.sessionView().size())
                .as("the host and the attacker, and nobody invented")
                .isEqualTo(2);
    }
}
