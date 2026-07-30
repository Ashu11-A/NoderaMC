package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bootstrap dial has a route and no node id, and that is not an error.
 *
 * <p>A peer joining a world dials an address it was given — {@code host:port} — before it can know
 * who is listening there; learning that is what the {@code PeerJoin} it is about to send is for.
 * {@code dispatch} opened with {@code Objects.requireNonNull(to.nodeId())}, three lines above the
 * code that already knew how to honour a caller-supplied route.
 *
 * <p>The NPE that produced is not a {@link TransportException}, so {@code PeerRuntime.sendTo} — whose
 * entire contract is that a failed send is not the caller's problem — did not catch it. It unwound
 * {@code onHeartbeatTick} at its first statement, so the {@code SessionKeepAlive} broadcast at the
 * end of that method never ran on any tick. The node went silent, every peer dropped it as
 * "heartbeat-timeout", and the player watched a download whose seeders had just been pruned.
 */
final class BootstrapAddressHasNoNodeIdTest {

    private static final UUID NETWORK = new UUID(1, 1);
    private static final Bytes GENESIS = Bytes.unsafeWrap(new byte[32]);

    private static RendezvousPeerTransport overDirect(PeerTransport direct) {
        return new RendezvousPeerTransport(
                NodeIdentity.generate(),
                List.of(new RendezvousEndpoint("127.0.0.1", 1)),
                NETWORK, GENESIS, NodeCapabilities.initial(), direct);
    }

    private static final class RecordingDirect implements PeerTransport {
        private final List<PeerAddress> sent = new ArrayList<>();

        @Override public void start() { }

        @Override public void stop() { }

        @Override public void send(PeerAddress peer, byte[] frame) {
            sent.add(peer);
        }

        @Override public void sendStream(PeerAddress to, long streamId, byte[] payload) {
            send(to, payload);
        }

        @Override public void setHandler(MessageHandler handler) { }

        @Override public String listenRoute() {
            return "127.0.0.1:0";
        }
    }

    @Test
    @DisplayName("a bootstrap dial with no node id is sent directly, not refused")
    void anUnnamedRouteIsDialedDirectly() {
        RecordingDirect direct = new RecordingDirect();
        RendezvousPeerTransport transport = overDirect(direct);

        transport.send(PeerAddress.of(null, "10.0.0.101:25566"), new byte[]{1, 2, 3});

        assertThat(direct.sent)
                .extracting(PeerAddress::route)
                .containsExactly("10.0.0.101:25566");
    }

    @Test
    @DisplayName("an unnamed address with nothing to dial fails as a transport error, never an NPE")
    void anUnnamedAddressWithNoRouteIsATransportFailure() {
        RecordingDirect direct = new RecordingDirect();
        RendezvousPeerTransport transport = overDirect(direct);

        // The type is the point. Callers are written to tolerate TransportException; an unchecked
        // throw from here escapes them and takes down whatever loop was driving the send.
        assertThatThrownBy(() -> transport.send(PeerAddress.of(null, ""), new byte[]{1}))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("no route to dial");
        assertThat(direct.sent).isEmpty();
    }

    @Test
    @DisplayName("bulk sends to an unnamed route take the same path")
    void unnamedStreamSendsAreDirectToo() {
        RecordingDirect direct = new RecordingDirect();
        RendezvousPeerTransport transport = overDirect(direct);

        transport.sendStream(PeerAddress.of(null, "10.0.0.101:25566"), 7L, new byte[]{9});

        assertThat(direct.sent)
                .extracting(PeerAddress::route)
                .containsExactly("10.0.0.101:25566");
    }
}
