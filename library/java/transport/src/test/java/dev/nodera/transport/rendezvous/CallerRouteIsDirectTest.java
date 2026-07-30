package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A route the caller supplied is a direct path (network L-30).
 *
 * <p>`availablePaths` used to ask only whether the peer had advertised a candidate through
 * rendezvous. A send addressed to a perfectly dialable {@code host:port} was therefore judged
 * relay-only and went to a circuit — and when no circuit existed, it simply failed.
 *
 * <p>That is what kept the always-on peers out of every committee. `NoderaHost` seats a resident by
 * sending {@code RegionAssigned} to the route in its membership entry, and a live mesh soak
 * reported <em>"12 resident seat(s) were planned but every dispatch failed — first failure was
 * 127.0.0.1:25620 → relay send failed"</em>: loopback, same machine, unreachable because nothing
 * would dial it.
 */
final class CallerRouteIsDirectTest {

    private static final NodeId PEER = new NodeId(new UUID(0, 42));
    private static final UUID NETWORK = new UUID(1, 1);
    private static final Bytes GENESIS = Bytes.unsafeWrap(new byte[32]);

    private static RendezvousPeerTransport overDirect(PeerTransport direct) {
        // One endpoint is required by contract; nothing in these tests contacts it, because the
        // whole point is that a caller-supplied route must never reach the rendezvous path.
        return new RendezvousPeerTransport(
                NodeIdentity.generate(),
                List.of(new RendezvousEndpoint("127.0.0.1", 1)),
                NETWORK, GENESIS, NodeCapabilities.initial(), direct);
    }

    /** Records what it was asked to send, and to where. */
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
    @DisplayName("a send to a dialable route goes direct, with no rendezvous candidate at all")
    void aCallerSuppliedRouteIsDialed() {
        RecordingDirect direct = new RecordingDirect();
        RendezvousPeerTransport transport = overDirect(direct);
        {
            transport.send(PeerAddress.of(PEER, "127.0.0.1:25620"), new byte[]{1, 2, 3});

            // Before the fix this went to a relay circuit that did not exist, and threw
            // "relay send to NodeId[...] failed" — on loopback.
            assertThat(direct.sent)
                    .extracting(PeerAddress::route)
                    .containsExactly("127.0.0.1:25620");
        }
    }

    @Test
    @DisplayName("with no route and no candidate there is still nothing to dial")
    void withoutARouteDirectIsNotOffered() {
        RecordingDirect direct = new RecordingDirect();
        RendezvousPeerTransport transport = overDirect(direct);
        {
            // No route, no advertised candidate: the relay is genuinely the only path, and the
            // fix must not invent a direct one.
            try {
                transport.send(PeerAddress.of(PEER, ""), new byte[]{1});
            } catch (RuntimeException relayUnavailable) {
                // expected — there is no relay in this fixture either
            }
            assertThat(direct.sent).isEmpty();
        }
    }
}
