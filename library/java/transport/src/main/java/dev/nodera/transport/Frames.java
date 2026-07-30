package dev.nodera.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

/**
 * Length-prefixed stream framing shared by every Nodera TCP leg (rendezvous control plane, relay
 * circuits, tracker client). Promoted from the rendezvous package by the Java API unification
 * (issue #30) so the cap and the wire shape have one home.
 *
 * <p>Every frame is {@code u32 length (big-endian) + length bytes}, capped at {@link #MAX_FRAME_BYTES}
 * — byte-identical to {@code SocketPeerTransport} and the Rust {@code nodera-codec::framing}, so the
 * control plane, the relay circuit, and the direct socket all speak the same framing.
 *
 * <p>Thread-context: stateless static helpers; each call operates on a caller-owned stream.
 */
public final class Frames {

    /** Absolute cap on a single frame — the one constant SocketPeerTransport mirrors. */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private Frames() {}

    /**
     * Write one length-prefixed frame and flush it.
     *
     * @param out   the destination stream.
     * @param frame the payload (must not exceed {@link #MAX_FRAME_BYTES}).
     * @throws IOException              on write failure.
     * @throws IllegalArgumentException if the frame is too large.
     * @Thread-context any thread; one writer per stream.
     */
    public static void write(OutputStream out, byte[] frame) throws IOException {
        if (frame.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException(
                    "frame of " + frame.length + " bytes exceeds the cap " + MAX_FRAME_BYTES);
        }
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(frame.length);
        dos.write(frame);
        dos.flush();
    }

    /**
     * Read one length-prefixed frame, blocking until it is complete.
     *
     * @param in the source stream.
     * @return the frame, or empty at a clean end of stream.
     * @throws IOException              on read failure.
     * @throws IllegalStateException    if the announced length exceeds the cap (hostile input).
     * @Thread-context any thread; one reader per stream.
     */
    public static Optional<byte[]> read(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int length;
        try {
            length = dis.readInt();
        } catch (EOFException eof) {
            return Optional.empty();
        }
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IllegalStateException("frame length out of range: " + length);
        }
        byte[] frame = new byte[length];
        dis.readFully(frame);
        return Optional.of(frame);
    }
}
