package dev.nodera.peer;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.session.Nack;
import dev.nodera.protocol.session.RejectCode;
import dev.nodera.protocol.wire.FrameFlags;
import dev.nodera.protocol.wire.NoderaFrame;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.wire.WireRegistry;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NDR2's cross-version premise is that a peer newer than this one is allowed to exist, and
 * {@code WireCodec.decodeFrame} already reports an unknown kind as a fact rather than a failure.
 * Nothing on a production receive path called it: {@code PeerRuntime} used {@code decode}, which
 * throws on an unknown kind exactly as it throws on corruption, so an older peer answered a newer
 * one's message with silence.
 *
 * <p>Silence is the one answer a sender cannot act on. It is indistinguishable from a lost packet, a
 * dead process, or a network fault, so the only recovery was a timeout and the only diagnosis was a
 * guess — the failure {@code MessageRouter}'s own comment describes, in the runtime that has no
 * router. {@code ForwardCompatibilityTest} proves the mechanism; these prove it runs.
 */
final class UnsupportedKindIsAnsweredTest {

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

    /** A transport with no runtime behind it, recording whatever the host sends back. */
    private static final class Listener implements MessageHandler {
        final List<NoderaMessage> received = new CopyOnWriteArrayList<>();

        @Override
        public void onMessage(PeerAddress from, byte[] frame) {
            try {
                WireCodec.decodeFrame(frame).message().ifPresent(received::add);
            } catch (RuntimeException ignored) {
                // Not what this test is about.
            }
        }

        @Override
        public void onPeerDown(PeerAddress peer) {
        }
    }

    private static byte[] fromTheFuture(int flags, long correlationId) {
        return new NoderaFrame(WireRegistry.WIRE_EPOCH, WireRegistry.NEXT_KIND + 40, flags,
                correlationId, Bytes.unsafeWrap(new byte[]{1, 2, 3, 4})).encode();
    }

    @Test
    @DisplayName("a kind this build has no row for is answered with a Nack, not dropped")
    void anUnknownKindIsAnswered() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        PeerRuntime host = start(hostId, net, "host");
        PeerAddress hostAddress = PeerAddress.of(hostId.nodeId(), "host");

        Listener heard = new Listener();
        LoopbackTransport newer = net.register(NodeIdentity.generate().nodeId());
        newer.setHandler(heard);
        newer.start();

        newer.send(hostAddress, fromTheFuture(FrameFlags.REQUEST, 0xCAFEBABEL));

        Await.until("the host answers", 5_000, () -> !heard.received.isEmpty());
        assertThat(heard.received.get(0)).isInstanceOfSatisfying(Nack.class, nack -> {
            assertThat(nack.code()).isEqualTo(RejectCode.UNSUPPORTED_KIND);
            assertThat(nack.kind()).isEqualTo(WireRegistry.NEXT_KIND + 40);
            assertThat(nack.correlationId())
                    .as("the sender has to be able to match the answer to what it asked")
                    .isEqualTo(0xCAFEBABEL);
        });
        assertThat(host.sessionView().size())
                .as("and the frame is answered rather than fatal — the session is untouched")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown RESPONSE is not answered, so two peers cannot Nack each other forever")
    void anUnknownResponseIsNotAnswered() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        start(hostId, net, "host");
        PeerAddress hostAddress = PeerAddress.of(hostId.nodeId(), "host");

        Listener heard = new Listener();
        LoopbackTransport newer = net.register(NodeIdentity.generate().nodeId());
        newer.setHandler(heard);
        newer.start();

        newer.send(hostAddress, fromTheFuture(FrameFlags.RESPONSE, 1L));
        // Ordered, not timed: a REQUEST sent afterwards is answered, and the runtime handles frames
        // from one peer in arrival order — so its answer arriving proves the RESPONSE was already
        // processed and deliberately left alone.
        newer.send(hostAddress, fromTheFuture(FrameFlags.REQUEST, 2L));

        Await.until("the request is answered", 5_000, () -> !heard.received.isEmpty());
        assertThat(heard.received).hasSize(1);
        assertThat(((Nack) heard.received.get(0)).correlationId())
                .as("the one answer belongs to the REQUEST, never to the RESPONSE")
                .isEqualTo(2L);
    }
}
