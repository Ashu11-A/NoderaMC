package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.wire.FrameFlags;
import dev.nodera.protocol.wire.NoderaFrame;
import dev.nodera.protocol.wire.WireRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * `ContentTransferService.onMessage` ends with "any other message type belongs to another handler;
 * content transfer ignores it" — and that was true of every kind this build knows and false of every
 * kind it does not. It decoded with `WireCodec.decode`, which throws on an unknown kind exactly as it
 * throws on corruption, so the one message the contract could not describe was the one that escaped
 * out of a transport receive callback.
 *
 * <p>Cheap to state and easy to lose: the failure needs a peer from a future build to observe, which
 * is precisely the case no local test setup produces by accident.
 */
final class UnknownKindDoesNotBreakTheContentLaneTest {

    @Test
    @DisplayName("a kind from a newer build is ignored, not thrown out of the receive callback")
    void anUnknownKindIsIgnored() {
        byte[] fromTheFuture = new NoderaFrame(
                WireRegistry.WIRE_EPOCH, WireRegistry.NEXT_KIND + 17, FrameFlags.EVENT, 0L,
                Bytes.unsafeWrap(new byte[]{9, 9, 9})).encode();

        dev.nodera.core.identity.NodeId self = DistFixtures.node(1L);
        dev.nodera.testkit.LoopbackTransport.LoopbackNetwork network =
                dev.nodera.testkit.LoopbackTransport.LoopbackNetwork.newNetwork();
        ContentTransferService service = new ContentTransferService(
                self, network.register(self), new DistFixtures.MapContentStore(),
                node -> dev.nodera.transport.PeerAddress.of(node, "loopback"),
                64, 64L * 1024 * 1024);

        assertThatCode(() -> service.onMessage(
                dev.nodera.transport.PeerAddress.of(DistFixtures.node(2L), "loopback"),
                fromTheFuture))
                .as("ignoring is what the handler's own closing comment promises")
                .doesNotThrowAnyException();
    }
}
