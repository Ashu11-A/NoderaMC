package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.rendezvous.ObservedAddress;
import dev.nodera.protocol.rendezvous.RelayReservation;
import dev.nodera.protocol.rendezvous.RelayReserve;
import dev.nodera.protocol.rendezvous.RendezvousRegister;
import dev.nodera.protocol.service.ServiceDirectoryEntry;
import dev.nodera.protocol.service.ServiceDrainNotice;
import dev.nodera.protocol.service.ServiceKind;
import dev.nodera.protocol.service.ServiceLifecycle;
import dev.nodera.protocol.service.ServiceRecord;
import dev.nodera.protocol.service.ServiceScore;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.transport.Frames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A relay that announces it is draining loses this peer, and the replacement it names gains it
 * (issue #232).
 *
 * <p><b>Why this test exists.</b> {@code onDrainNotice} had no caller anywhere in the tree, so
 * {@code drainHandlers} was permanently empty and {@code onDrain} iterated nothing. The whole
 * service-drain lane from PR #78 — the signed notice, its verification on the reservation socket,
 * the replacement list it carries — was implemented, tested at both ends, and connected to nothing:
 * a peer stayed on a draining relay until the circuit broke under it.
 *
 * <p><b>What is asserted is the migration, not the registration.</b> Asserting that the handler list
 * is non-empty would pass against a handler that does nothing, which is the defect it is supposed to
 * catch one level up. So this drives the real path — a real signed {@link ServiceDrainNotice},
 * written as a real NDR2 frame down the real reservation control socket, verified by the real
 * {@link RelayCircuitClient#readIncoming} — and asserts that the peer <em>registers with a relay it
 * was never configured with</em>, which it can only have learned from the notice.
 */
final class RelayDrainMigrationTest {

    private static final UUID NETWORK = new UUID(7, 7);
    private static final Bytes GENESIS = Bytes.unsafeWrap(new byte[32]);
    private static final long TIMEOUT_SECONDS = 15L;

    private final List<FakeRelay> relays = new ArrayList<>();
    private RendezvousPeerTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.stop();
        }
        relays.forEach(FakeRelay::close);
    }

    @Test
    @DisplayName("a drain notice moves the peer onto the replacement it names")
    void a_drain_notice_moves_the_peer_onto_the_replacement_it_names() throws Exception {
        FakeRelay leaving = relay();
        FakeRelay replacement = relay();

        // Configured with the draining relay and nothing else. That is the case the notice's
        // replacement list exists for: without reading it, this peer has nowhere at all to go.
        transport = new RendezvousPeerTransport(NodeIdentity.generate(), List.of(leaving.endpoint()),
                NETWORK, GENESIS, NodeCapabilities.initial(), null);
        transport.start();
        assertThat(leaving.awaitRegistrations(1)).as("the peer starts on the relay it was given")
                .isTrue();
        assertThat(replacement.registered).as("and knows nothing of the replacement yet").isEmpty();

        leaving.announceDrainNaming(replacement);

        assertThat(replacement.awaitRegistrations(1))
                .as("the peer re-registered with the replacement the notice named")
                .isTrue();
        assertThat(replacement.registered).containsExactly(transport.nodeId());
    }

    @Test
    @DisplayName("a drained relay stops being registered with; the surviving one is re-registered")
    void a_drained_relay_stops_being_registered_with() throws Exception {
        FakeRelay leaving = relay();
        FakeRelay staying = relay();

        transport = new RendezvousPeerTransport(NodeIdentity.generate(),
                List.of(leaving.endpoint(), staying.endpoint()),
                NETWORK, GENESIS, NodeCapabilities.initial(), null);
        transport.start();
        assertThat(leaving.awaitRegistrations(1)).isTrue();
        assertThat(staying.awaitRegistrations(1)).isTrue();

        // No replacements named: the peer has to fall back on what it already holds. The assertion
        // is that the departing relay is dropped rather than kept alongside the survivor — the
        // re-registration that follows must reach exactly one of the two.
        leaving.announceDrain(List.of());

        assertThat(staying.awaitRegistrations(2))
                .as("the peer re-registers immediately with the relay it is staying on")
                .isTrue();
        assertThat(leaving.registered)
                .as("and never announces itself to the relay that is going away again")
                .hasSize(1);
    }

    private FakeRelay relay() {
        try {
            FakeRelay relay = new FakeRelay();
            relays.add(relay);
            return relay;
        } catch (IOException e) {
            throw new IllegalStateException("could not open a fake relay", e);
        }
    }

    /**
     * The smallest thing that behaves like {@code nodera-rendezvous} for this lane: it grants one
     * reservation, answers a registration, and can push a signed drain notice down the reservation's
     * still-open control socket. Everything on the peer's side of that socket is the production code.
     */
    private static final class FakeRelay implements AutoCloseable {
        private final NodeIdentity identity = NodeIdentity.generate();
        private final ServerSocket server;
        private final List<NodeId> registered = new CopyOnWriteArrayList<>();
        private final CountDownLatch reservation = new CountDownLatch(1);
        private volatile Socket control;
        private volatile boolean open = true;

        FakeRelay() throws IOException {
            this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptLoop, "fake-relay-" + server.getLocalPort());
            acceptor.setDaemon(true);
            acceptor.start();
        }

        RendezvousEndpoint endpoint() {
            return new RendezvousEndpoint("127.0.0.1", server.getLocalPort());
        }

        /** Wait until this relay has answered at least {@code count} registrations. */
        boolean awaitRegistrations(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (registered.size() >= count) {
                    return true;
                }
                Thread.sleep(20L);
            }
            return registered.size() >= count;
        }

        /** Announce a drain naming {@code replacement} as where to go instead. */
        void announceDrainNaming(FakeRelay replacement) throws Exception {
            announceDrain(List.of(new ServiceDirectoryEntry(replacement.serving(),
                    replacement.identity.sign(replacement.serving().signedBytes()),
                    new ServiceScore(1_000, 10, 20, 1_000, 1_000, 0, 0).withComposite())));
        }

        /** Announce a drain down the reservation's control socket, exactly as the service does. */
        void announceDrain(List<ServiceDirectoryEntry> replacements) throws Exception {
            assertThat(reservation.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the peer must hold a reservation before it can be told about a drain")
                    .isTrue();
            ServiceRecord draining = record(ServiceLifecycle.DRAINING);
            ServiceDrainNotice notice = new ServiceDrainNotice(draining,
                    identity.sign(draining.signedBytes()), replacements,
                    ServiceDrainNotice.REASON_UPDATE);
            Frames.write(control.getOutputStream(), WireCodec.encode(notice));
        }

        private ServiceRecord serving() {
            return record(ServiceLifecycle.SERVING);
        }

        private ServiceRecord record(ServiceLifecycle lifecycle) {
            long now = System.currentTimeMillis();
            return new ServiceRecord(identity.nodeId(), identity.publicKeyBytes(),
                    ServiceKind.RENDEZVOUS, lifecycle, NETWORK,
                    List.of("tcp://127.0.0.1:" + server.getLocalPort()), "0.1.0",
                    0, 0, 0, 0, 0, now, now + 300_000L,
                    lifecycle == ServiceLifecycle.DRAINING ? now + 30_000L : 0L);
        }

        private void acceptLoop() {
            while (open) {
                try {
                    Socket socket = server.accept();
                    Thread worker = new Thread(() -> serve(socket), "fake-relay-conn");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void serve(Socket socket) {
            try {
                byte[] frame = Frames.read(socket.getInputStream()).orElse(null);
                if (frame == null) {
                    socket.close();
                    return;
                }
                NoderaMessage request = WireCodec.decode(frame);
                if (request instanceof RendezvousRegister register) {
                    registered.add(register.signed().record().peer());
                    Frames.write(socket.getOutputStream(), WireCodec.encode(new ObservedAddress(
                            register.signed().record().peer(), "127.0.0.1:1")));
                    socket.close();
                } else if (request instanceof RelayReserve) {
                    Frames.write(socket.getOutputStream(), WireCodec.encode(new RelayReservation(
                            true, "127.0.0.1:" + server.getLocalPort(),
                            System.currentTimeMillis() + 300_000L, 1 << 20, 300_000L,
                            Bytes.unsafeWrap(new byte[16]), "")));
                    // Held open: this is the channel a drain notice arrives on.
                    control = socket;
                    reservation.countDown();
                } else {
                    socket.close();
                }
            } catch (IOException | RuntimeException ignored) {
                try {
                    socket.close();
                } catch (IOException closing) {
                    // nothing left to do with it
                }
            }
        }

        @Override
        public void close() {
            open = false;
            Socket held = control;
            try {
                if (held != null) {
                    held.close();
                }
                server.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
