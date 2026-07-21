package dev.nodera.transport.rendezvous;

import dev.nodera.transport.Frames;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import java.util.Optional;

/**
 * An established, end-to-end-encrypted relay circuit between two peers (Task 29; rendezvous.md
 * §4.5/§8.2). Frames handed to {@link #send(byte[])} are sealed by the {@link EndToEndCipher.Session}
 * before they cross the relay, so the relay forwards opaque bytes it cannot read or alter
 * undetected.
 *
 * <p>Thread-context: {@link #send(byte[])} from one writer thread, {@link #receive()} from one
 * reader thread; the two directions are independent.
 */
public final class RelayCircuit implements Closeable {

    private final Socket socket;
    private final EndToEndCipher.Session session;

    RelayCircuit(Socket socket, EndToEndCipher.Session session) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.session = Objects.requireNonNull(session, "session");
    }

    /** @return whether this side initiated the circuit. */
    public boolean isInitiator() {
        return session.isInitiator();
    }

    /**
     * Seal and send one application frame.
     *
     * @param frame the plaintext frame (non-empty).
     * @throws IOException on transport failure.
     * @Thread-context one writer thread.
     */
    public void send(byte[] frame) throws IOException {
        Frames.write(socket.getOutputStream(), session.seal(frame));
    }

    /**
     * Receive and open the next application frame.
     *
     * @return the plaintext frame, or empty at end of stream.
     * @throws IOException       on transport failure.
     * @throws SecurityException if a received frame fails to decrypt (tamper by the relay).
     * @Thread-context one reader thread.
     */
    public Optional<byte[]> receive() throws IOException {
        Optional<byte[]> sealed = Frames.read(socket.getInputStream());
        if (sealed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(session.open(sealed.get())
                .orElseThrow(() -> new SecurityException("relayed frame failed to decrypt")));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
