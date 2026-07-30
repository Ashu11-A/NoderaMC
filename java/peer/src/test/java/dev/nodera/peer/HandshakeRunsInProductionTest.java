package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.protocol.service.ServiceRecord;
import dev.nodera.protocol.session.Negotiation;
import dev.nodera.protocol.session.SessionRole;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handshake, over a real runtime pair rather than a decision function (network L-87 / L-88).
 *
 * <p>{@code Negotiation} and {@code PeerSession} were complete, tested, and referenced by <b>nothing
 * that ships</b> — a structural scan found the class reachable only from its own tests. So L-87 and
 * L-88 sat at RETIRING on the strength of tests over code no peer ever executed: production
 * authenticated the carrier, sent {@code PeerJoin}, and admitted, comparing no protocol version, no
 * rules version, no registry fingerprint and no feature set.
 *
 * <p>These tests are about the wiring, which is the part that was missing. {@code NegotiationTest}
 * already proves the decision; what needed proving is that two runtimes doing the ordinary thing —
 * one dials the other and joins — actually exchange the frames, verify the identity against the
 * carrier, and end up holding the agreement.
 *
 * <p>The second property is the one that makes it safe to gate on: an <em>absent</em> answer is not a
 * refusal. A peer that never handshaked is not reported as an observer, because turning a dropped
 * frame into "this world cannot be validated" would be a worse failure than the one being fixed.
 */
final class HandshakeRunsInProductionTest {

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
                EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP, PeerRole.REGION_VALIDATOR));
    }

    private PeerRuntime start(NodeIdentity id, LoopbackNetwork net, String route, int rulesVersion) {
        LoopbackTransport tx = net.register(id.nodeId());
        PeerRuntime rt = PeerRuntime.bootstrap(id, caps(), tx, () -> route, fast,
                new RecordingListener());
        rt.setLocalProfile(Negotiation.LocalProfile.of("test", rulesVersion, 7L,
                ServiceRecord.DEFAULT_NETWORK, caps()));
        runtimes.add(rt);
        return rt;
    }

    @Test
    @DisplayName("two peers of the same build negotiate ADMITTED, both directions, on an ordinary join")
    void anOrdinaryJoinNegotiates() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity workerId = NodeIdentity.generate();

        PeerRuntime host = start(hostId, net, "host", 1);
        PeerRuntime worker = start(workerId, net, "worker", 1);

        worker.joinSession(PeerAddress.of(hostId.nodeId(), "host"));

        Await.until("both see 2 members", 5_000,
                () -> host.sessionView().size() == 2 && worker.sessionView().size() == 2);
        // The agreement is what was missing, and it has to exist on both ends: the answering peer
        // records what it answered, the asking peer records what it was told.
        Await.until("the host holds an admitted session for the worker", 5_000,
                () -> host.sessionOf(workerId.nodeId()).role() == SessionRole.ADMITTED);
        Await.until("the worker holds an admitted session for the host", 5_000,
                () -> worker.sessionOf(hostId.nodeId()).role() == SessionRole.ADMITTED);

        assertThat(host.isNegotiatedObserver(workerId.nodeId())).isFalse();
        assertThat(worker.isNegotiatedObserver(hostId.nodeId())).isFalse();
        assertThat(host.sessionOf(workerId.nodeId()).features())
                .as("the emit profile is the intersection, and two equal builds intersect to all")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a rules-version skew is answered at the handshake, not experienced in the engine")
    void aRulesSkewYieldsAnObserver() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity strangerId = NodeIdentity.generate();

        PeerRuntime host = start(hostId, net, "host", 1);
        PeerRuntime stranger = start(strangerId, net, "stranger", 2); // a different rule set

        stranger.joinSession(PeerAddress.of(hostId.nodeId(), "host"));

        Await.until("the host answers the skew with an observer role", 5_000,
                () -> host.isNegotiatedObserver(strangerId.nodeId()));
        Await.until("and the stranger is told the same thing about itself", 5_000,
                () -> stranger.sessionOf(hostId.nodeId()).role() == SessionRole.OBSERVER);

        // Skew bars co-validation, not communication: the peer still meshes, seeds and relays.
        assertThat(host.sessionView().size())
                .as("an observer is a member — refusing membership would break seeding and relay")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an absent answer is not a refusal: an unknown peer is never reported as an observer")
    void silenceIsNotRefusal() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        PeerRuntime host = start(NodeIdentity.generate(), net, "host", 1);
        NodeId neverSpoke = new NodeId(UUID.randomUUID());

        assertThat(host.isNegotiatedObserver(neverSpoke))
                .as("a dropped Hello must not be able to unvalidate a world")
                .isFalse();
        assertThat(host.sessionOf(neverSpoke).role())
                .as("while sessionOf still assumes the least about a peer it has not heard from")
                .isEqualTo(SessionRole.OBSERVER);
    }

    @Test
    @DisplayName("the agreement is dropped with the member, so it cannot accumulate")
    void theSessionIsPrunedWithTheMember() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity workerId = NodeIdentity.generate();

        PeerRuntime host = start(hostId, net, "host", 1);
        PeerRuntime worker = start(workerId, net, "worker", 1);
        worker.joinSession(PeerAddress.of(hostId.nodeId(), "host"));

        Await.until("the host holds the worker's session", 5_000,
                () -> host.sessionOf(workerId.nodeId()).role() == SessionRole.ADMITTED);

        worker.stop();

        Await.until("the departure drops the member", 8_000, () -> host.sessionView().size() == 1);
        // A map keyed by remote input that nothing prunes is the shape of cache this category treats
        // as a security bug, so the pruning is asserted rather than assumed.
        assertThat(host.sessionOf(workerId.nodeId()).features())
                .as("the conservative default assumes no features at all")
                .isEmpty();
    }
}
