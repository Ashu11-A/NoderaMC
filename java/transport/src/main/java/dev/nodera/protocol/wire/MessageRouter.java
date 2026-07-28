package dev.nodera.protocol.wire;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.session.Nack;
import dev.nodera.protocol.session.RejectCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Decodes a frame, decides whether to act on it, and delivers it — in that order
 * (Task 14 phase 5, {@code Plan.7} §4.4).
 *
 * <p>What this replaces was not really a router. {@code PeerRuntime} handled five message types and
 * dropped everything else through one last-wins fall-through, which a headless worker then fanned
 * out to six services by hand. Three things follow from that shape, and all three were real:
 *
 * <ul>
 *   <li>a service registered second silently replaced the first, with nothing to notice it;</li>
 *   <li>a kind two services both cared about could only reach one of them;</li>
 *   <li>authorisation was whatever each handler remembered to check, which for several kinds was
 *       nothing at all.</li>
 * </ul>
 *
 * <p>Registration answers the first two — {@link MessageType.HandlerMode#EXCLUSIVE} refuses a second
 * handler instead of overwriting the first, and {@link MessageType.HandlerMode#BROADCAST} delivers
 * to all. The declared {@link AuthPolicy} answers the third, once, before any handler runs.
 *
 * <p>Thread-context: registration happens at start-up on one thread; {@link #accept} is safe to call
 * from the receive loop.
 */
public final class MessageRouter {

    /** What the router did with a frame — enough for a caller to log or answer. */
    public sealed interface Outcome {

        /** Delivered to {@code handlers} handler(s). */
        record Delivered(int kind, int handlers) implements Outcome {}

        /**
         * The kind is not one this build knows. The connection is fine; {@link #nack} is the answer
         * to send back so the sender stops waiting.
         */
        record UnknownKind(int kind, Nack nack) implements Outcome {}

        /** The sender was not entitled to send this. */
        record Refused(int kind, RejectCode reason, Nack nack) implements Outcome {}

        /** Known and allowed, but nothing is registered for it. */
        record Unhandled(int kind) implements Outcome {}

        /** The frame did not parse. */
        record Malformed(String detail) implements Outcome {}
    }

    private final Map<Integer, List<BiConsumer<NodeId, NoderaMessage>>> handlers =
            new LinkedHashMap<>();

    /**
     * Register a handler for a kind.
     *
     * @param kind    the kind.
     * @param handler called with the authenticated sender (or {@code null}) and the message.
     * @throws IllegalStateException if the kind is {@link MessageType.HandlerMode#EXCLUSIVE} and
     *         already has one. Silently replacing it is how a service disappeared from a running
     *         node with no error anywhere.
     * @Thread-context call during start-up.
     */
    public void register(int kind, BiConsumer<NodeId, NoderaMessage> handler) {
        MessageType type = MessageTypes.of(kind);
        List<BiConsumer<NodeId, NoderaMessage>> existing =
                handlers.computeIfAbsent(kind, k -> new ArrayList<>());
        if (type.handlerMode() == MessageType.HandlerMode.EXCLUSIVE && !existing.isEmpty()) {
            throw new IllegalStateException(type.kind().name() + " is EXCLUSIVE and already has a "
                    + "handler; registering a second would silently replace the first");
        }
        existing.add(handler);
    }

    /** How many handlers are registered for a kind. */
    public int handlerCount(int kind) {
        return handlers.getOrDefault(kind, List.of()).size();
    }

    /**
     * Decode a received frame and deliver it if it is allowed.
     *
     * @param frame         the raw frame.
     * @param authenticated the identity the carrier proved, or {@code null} when it does not
     *                      authenticate.
     * @return what happened.
     * @Thread-context safe from the receive loop.
     */
    public Outcome accept(byte[] frame, NodeId authenticated) {
        WireCodec.DecodedFrame decoded;
        try {
            decoded = WireCodec.decodeFrame(frame);
        } catch (RuntimeException malformed) {
            return new Outcome.Malformed(String.valueOf(malformed.getMessage()));
        }

        if (decoded.unknownKind()) {
            // Answered, not dropped. A silent drop is indistinguishable from a network fault, so
            // the sender's only recovery was a timeout and its only diagnosis was a guess.
            return new Outcome.UnknownKind(decoded.kind(),
                    Nack.unsupportedKind(decoded.kind(), decoded.correlationId()));
        }

        NoderaMessage msg = decoded.message().orElseThrow();
        MessageType type = MessageTypes.of(decoded.kind());
        if (!type.permits(msg, authenticated)) {
            return new Outcome.Refused(decoded.kind(), RejectCode.NOT_AUTHORISED,
                    new Nack(decoded.kind(), RejectCode.NOT_AUTHORISED, decoded.correlationId(),
                            type.kind().name() + " must come from the peer it names"));
        }

        List<BiConsumer<NodeId, NoderaMessage>> registered =
                handlers.getOrDefault(decoded.kind(), List.of());
        if (registered.isEmpty()) {
            return new Outcome.Unhandled(decoded.kind());
        }
        for (BiConsumer<NodeId, NoderaMessage> handler : registered) {
            handler.accept(authenticated, msg);
        }
        return new Outcome.Delivered(decoded.kind(), registered.size());
    }

    /**
     * The frame to send back for an outcome, when there is one.
     *
     * @param outcome what {@link #accept} returned.
     * @return the answer frame, or empty when the outcome needs no reply.
     * @Thread-context any thread.
     */
    public static Optional<byte[]> answerFor(Outcome outcome) {
        Nack nack = switch (outcome) {
            case Outcome.UnknownKind u -> u.nack();
            case Outcome.Refused r -> r.nack();
            default -> null;
        };
        if (nack == null) {
            return Optional.empty();
        }
        return Optional.of(WireCodec.encode(nack, FrameFlags.RESPONSE, nack.correlationId()));
    }
}
