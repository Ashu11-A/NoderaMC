package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.wire.FrameFlags;
import dev.nodera.protocol.wire.NoderaFrame;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.wire.WireRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

        dev.nodera.transport.PeerAddress from =
                dev.nodera.transport.PeerAddress.of(DistFixtures.node(2L), "loopback");

        // First: prove this entry point is live at all. `doesNotThrowAnyException` on its own is
        // satisfied by a handler that was never reached — a renamed method, a changed signature, a
        // guard that returns before the decoder — and the whole claim here is about what the
        // decoder does with a kind it has no row for. A KNOWN kind that leaves a mark establishes
        // that the frame gets as far as the dispatch.
        byte[] known = WireCodec.encode(
                new ContentRequest(Bytes.unsafeWrap(new byte[32]), List.of(0)));
        service.onMessage(from, known);
        assertThat(service.requestsForUnknownContent())
                .as("a well-formed request reached the dispatch through this same method")
                .isEqualTo(1L);

        // Then the actual subject: the kind from a future build takes the same path and is dropped
        // there rather than thrown out of the receive callback.
        assertThatCode(() -> service.onMessage(from, fromTheFuture))
                .as("ignoring is what the handler's own closing comment promises")
                .doesNotThrowAnyException();
        assertThat(service.requestsForUnknownContent())
                .as("and it was dropped at the unknown-kind branch, not before the decoder")
                .isEqualTo(1L);
    }
}
