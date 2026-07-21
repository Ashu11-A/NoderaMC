package dev.nodera.transport.rendezvous;

import dev.nodera.transport.Frames;
import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.rendezvous.RelayIncoming;

import java.io.IOException;
import java.net.Socket;
import java.util.Objects;

/**
 * Establishes {@link RelayCircuit}s over the relay: the dialing side wraps a fresh
 * {@code CONNECT} socket, the reserving side waits for a {@code RelayIncoming} on its reservation
 * control socket and accepts it (Task 29; rendezvous.md §4.5).
 *
 * <p>Both sides run the {@link EndToEndCipher} handshake before any application byte, and each binds
 * the far end to the exact identity key from the discovered record — the relay only forwards; it
 * never authenticates a peer on another's behalf (§4.4/§8.1).
 *
 * <p>Thread-context: the {@link #accept} blocks on the control socket; use from the reserver's
 * accept thread. {@link #dial} blocks on the handshake; use from the dialer's connect thread.
 */
public final class RelayCircuitClient {

    private RelayCircuitClient() {}

    /**
     * Dial a reserved target: run the initiator handshake over an open {@code CONNECT} socket.
     *
     * @param connectSocket        the socket returned by {@link RendezvousClient#openConnect}.
     * @param self                 this peer's identity.
     * @param expectedTargetKey    the target's X.509 identity key from its discovered record.
     * @return the established, end-to-end-encrypted circuit.
     * @throws IOException       on transport failure.
     * @throws SecurityException if the far end is not the expected identity.
     * @Thread-context the dialer's connect thread.
     */
    public static RelayCircuit dial(
            Socket connectSocket, NodeIdentity self, Bytes expectedTargetKey) throws IOException {
        Objects.requireNonNull(connectSocket, "connectSocket");
        EndToEndCipher.Session session = EndToEndCipher.handshake(
                connectSocket.getInputStream(), connectSocket.getOutputStream(),
                self, expectedTargetKey, true);
        return new RelayCircuit(connectSocket, session);
    }

    /**
     * Read the next {@code RelayIncoming} on a reservation's control socket and validate its echoed
     * proof against the held reservation. Split from {@link #completeAccept} so the caller can look
     * up the connecting peer's identity key by the source id the incoming carries.
     *
     * @param reserved the reservation and its control socket.
     * @return the validated incoming circuit announcement.
     * @throws IOException on transport failure, EOF, or a mismatched proof.
     * @Thread-context the reserver's accept thread.
     */
    public static RelayIncoming readIncoming(RendezvousClient.Reserved reserved) throws IOException {
        Objects.requireNonNull(reserved, "reserved");
        byte[] frame = Frames.read(reserved.socket().getInputStream())
                .orElseThrow(() -> new IOException("control socket closed before a circuit arrived"));
        NoderaMessage message = MessageCodec.decode(frame);
        if (!(message instanceof RelayIncoming incoming)) {
            throw new IOException("expected RelayIncoming, got " + message.getClass().getSimpleName());
        }
        if (!incoming.proof().equals(reserved.reservation().proof())) {
            // The relay echoed a proof that is not the one we hold: refuse rather than bridge a
            // circuit the relay may have fabricated against a different reservation.
            throw new IOException("RelayIncoming proof does not match the held reservation");
        }
        return incoming;
    }

    /**
     * Complete accepting an inbound circuit: run the responder handshake, binding the far end to the
     * expected identity key.
     *
     * @param reserved          the reservation and its control socket.
     * @param self              this peer's identity.
     * @param expectedSourceKey the connecting peer's X.509 identity key from its discovered record.
     * @return the established, end-to-end-encrypted circuit.
     * @throws IOException       on transport failure.
     * @throws SecurityException if the far end is not the expected identity.
     * @Thread-context the reserver's accept thread.
     */
    public static RelayCircuit completeAccept(
            RendezvousClient.Reserved reserved, NodeIdentity self, Bytes expectedSourceKey)
            throws IOException {
        Socket socket = reserved.socket();
        EndToEndCipher.Session session = EndToEndCipher.handshake(
                socket.getInputStream(), socket.getOutputStream(), self, expectedSourceKey, false);
        return new RelayCircuit(socket, session);
    }
}
