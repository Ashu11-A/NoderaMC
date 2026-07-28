package dev.nodera.protocol.wire;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;

import java.util.List;
import java.util.Optional;

/**
 * The wire's front door: a {@link NoderaMessage} in, an {@code NDR2} frame out, and back
 * (Task 14 phases 2–3).
 *
 * <p>This is where the plane split becomes bytes ({@code Plan.7} D1).
 *
 * <ul>
 *   <li><b>Infrastructure</b> messages get a canonical TLV body from {@link InfrastructureCodec}, so
 *       a field this build has never heard of is skipped rather than fatal.</li>
 *   <li><b>Consensus</b> messages cross as a single opaque {@code BYTES} field holding their
 *       original strict canonical encoding ({@code Plan.7} D5). Their bytes are their identity —
 *       hashed, signed, and compared for equality by peers that must agree on a state root — so this
 *       layer never re-spells them. It routes them, and hands them to the strict codec unchanged.
 *       A peer that cannot interpret a consensus payload is a peer that should not be voting on it,
 *       which is a membership question, not an encoding one.</li>
 * </ul>
 *
 * <p>The practical consequence is the one the programme exists for: the frame is understandable —
 * kind, flags, correlation, length — <em>without</em> understanding the body. A peer can therefore
 * route, relay, refuse, and answer for kinds it does not implement, which is what lets two builds
 * from different releases stay on the same network.
 *
 * <p>Thread-context: stateless; any thread.
 */
public final class WireCodec {

    private WireCodec() {}

    /** The TLV field id that carries a consensus payload's opaque canonical bytes. */
    public static final int CONSENSUS_PAYLOAD_FIELD = 1;

    /**
     * Encode a message as an unsolicited event.
     *
     * @param msg the message.
     * @return the complete frame.
     * @Thread-context any thread.
     */
    public static byte[] encode(NoderaMessage msg) {
        return encode(msg, FrameFlags.EVENT, 0L);
    }

    /**
     * Encode a message with explicit routing metadata.
     *
     * @param msg           the message.
     * @param flags         see {@link FrameFlags}.
     * @param correlationId 0 for events; the request's id for a response.
     * @return the complete frame.
     * @throws IllegalArgumentException if {@code msg} is null.
     * @Thread-context any thread.
     */
    public static byte[] encode(NoderaMessage msg, int flags, long correlationId) {
        return encode(msg, TlvOverlay.NONE, flags, correlationId);
    }

    /**
     * Encode a message, re-emitting fields this build did not understand when it was decoded.
     *
     * <p>Used when forwarding. A peer in the middle of a version spread that drops what it cannot
     * read is a lossy relay between two peers that understand each other perfectly well.
     *
     * @param msg           the message.
     * @param overlay       how the frame's field set differed from this build's, from
     *                      {@link #decodeFrame(byte[])}.
     * @param flags         see {@link FrameFlags}.
     * @param correlationId 0 for events; the request's id for a response.
     * @return the frame.
     * @Thread-context any thread.
     */
    public static byte[] encode(NoderaMessage msg, TlvOverlay overlay, int flags,
                                long correlationId) {
        if (msg == null) {
            throw new IllegalArgumentException("msg must not be null");
        }
        WireKind row = WireRegistry.of(msg);
        byte[] body = row.plane() == MessagePlane.CONSENSUS
                ? consensusBody(msg, overlay)
                : InfrastructureCodec.encodeBody(msg, overlay);
        return new NoderaFrame(WireRegistry.WIRE_EPOCH, row.kind(), flags, correlationId,
                Bytes.unsafeWrap(body)).encode();
    }

    /** Wrap a consensus payload's strict canonical bytes in a one-field TLV envelope. */
    private static byte[] consensusBody(NoderaMessage msg, TlvOverlay overlay) {
        return overlay.applyTo(new TlvWriter()
                .bytes(CONSENSUS_PAYLOAD_FIELD, MessageCodec.encodeBytes(msg))
                .toByteArray());
    }

    /**
     * Decode a frame, requiring the kind to be known.
     *
     * @param frame the complete frame.
     * @return the message.
     * @throws IllegalStateException if the frame is malformed or the kind is unknown. Callers on a
     *         network path should prefer {@link #decodeFrame(byte[])}, which reports an unknown kind
     *         as a fact rather than as a failure — a peer newer than this one is allowed to exist.
     * @Thread-context any thread.
     */
    public static NoderaMessage decode(byte[] frame) {
        return decodeFrame(frame).message().orElseThrow(() -> new IllegalStateException(
                "unknown message kind " + NoderaFrame.peekKind(frame)));
    }

    /**
     * Decode a frame, tolerating a kind this build does not know.
     *
     * @param frame the complete frame.
     * @return the decoded frame; {@link DecodedFrame#message()} is empty when the kind is unknown.
     * @throws IllegalStateException if the header, epoch, or body is malformed.
     * @Thread-context any thread.
     */
    public static DecodedFrame decodeFrame(byte[] frame) {
        NoderaFrame parsed = NoderaFrame.decode(frame);
        Optional<WireKind> row = WireRegistry.find(parsed.kind());
        if (row.isEmpty()) {
            // Not an error. The body's length let us find the end of it, so the connection is
            // intact and the sender can be told exactly what happened.
            return new DecodedFrame(parsed, Optional.empty(), TlvOverlay.NONE);
        }
        NoderaMessage msg;
        TlvOverlay overlay;
        if (row.get().plane() == MessagePlane.CONSENSUS) {
            TlvReader body = new TlvReader(parsed.body());
            msg = MessageCodec.decode(body.bytes(CONSENSUS_PAYLOAD_FIELD).toArray());
            overlay = body.overlay();
        } else {
            InfrastructureCodec.Decoded decoded =
                    InfrastructureCodec.decodeBody(parsed.kind(), parsed.body().toArray());
            msg = decoded.message();
            overlay = decoded.overlay();
        }
        WireKind declared = WireRegistry.of(msg);
        if (declared.kind() != parsed.kind()) {
            throw new IllegalStateException("frame claims kind " + parsed.kind() + " ("
                    + row.get().name() + ") but its payload decoded to " + declared.name());
        }
        return new DecodedFrame(parsed, Optional.of(msg), overlay);
    }

    /**
     * A decoded frame, including the ones this build cannot interpret.
     *
     * @param frame   the parsed header and raw body.
     * @param message the decoded message, or empty for an unknown kind.
     * @Thread-context immutable; any thread.
     */
    public record DecodedFrame(NoderaFrame frame, Optional<NoderaMessage> message,
                               TlvOverlay overlay) {

        /** The kind number from the header, known or not. */
        public int kind() {
            return frame.kind();
        }

        /** The correlation id, for matching a response to its request. */
        public long correlationId() {
            return frame.correlationId();
        }

        /** The frame's flags. */
        public int flags() {
            return frame.flags();
        }

        /** @return {@code true} if this build has no schema row for the kind. */
        public boolean unknownKind() {
            return message.isEmpty();
        }
    }
}
