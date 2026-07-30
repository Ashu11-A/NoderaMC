package dev.nodera.protocol.wire;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.function.Function;

/**
 * One kind's full descriptor: what it is, who may send it, and how it is dispatched
 * (Task 14 phase 5, {@code Plan.7} §4.4).
 *
 * <p>{@link WireKind} says what a message <em>is</em>; this says how the runtime should <em>treat</em>
 * it. Keeping them in one table is what turns three separate classes of defect into three columns:
 *
 * <ul>
 *   <li><b>Authorisation</b> ({@link #authPolicy}) — enforced by the router before any handler runs,
 *       instead of being re-decided, and sometimes forgotten, per handler.</li>
 *   <li><b>Correlation</b> ({@link #expectsResponse}) — a kind that asks a question is answered into
 *       a pending table, so a response nobody asked for is dropped before it can be merged into
 *       somebody's state.</li>
 *   <li><b>Dispatch</b> ({@link #handlerMode}) — declared, rather than emerging from the order in
 *       which handlers happened to be registered.</li>
 * </ul>
 *
 * @param kind          the schema row.
 * @param authPolicy    who may send it.
 * @param senderField   for {@link AuthPolicy#TRANSPORT_SENDER_EQUALS}, the field the router compares
 *                      against the authenticated peer; {@code null} otherwise.
 * @param expectsResponse whether a sender of this kind waits for an answer.
 * @param handlerMode   how many handlers may claim it.
 * @Thread-context immutable; any thread.
 */
public record MessageType(WireKind kind,
                          AuthPolicy authPolicy,
                          Function<NoderaMessage, NodeId> senderField,
                          boolean expectsResponse,
                          HandlerMode handlerMode) {

    /**
     * How a kind reaches its handlers.
     *
     * <p>The runtime used to handle five types itself and drop everything else through a single
     * last-wins fall-through, which a headless worker then fanned out to six services by hand. Two
     * failure modes came out of that: a service registered second silently replaced the first, and a
     * kind two services both cared about could only reach one of them.
     */
    public enum HandlerMode {
        /** Exactly one handler; registering a second is a programming error, not a replacement. */
        EXCLUSIVE,
        /** Every registered handler sees it — gossip, membership, diagnostics. */
        BROADCAST
    }

    public MessageType {
        if (kind == null || authPolicy == null || handlerMode == null) {
            throw new IllegalArgumentException("a message type needs a kind, a policy and a mode");
        }
        if (authPolicy == AuthPolicy.TRANSPORT_SENDER_EQUALS && senderField == null) {
            throw new IllegalArgumentException(kind.name() + " claims its sender must match the "
                    + "authenticated peer but names no field to compare");
        }
        if (authPolicy != AuthPolicy.TRANSPORT_SENDER_EQUALS && senderField != null) {
            throw new IllegalArgumentException(kind.name() + " names a sender field but does not "
                    + "use it; the field would look like a check without being one");
        }
    }

    /**
     * Whether {@code msg} may be acted on when it arrived from {@code authenticated}.
     *
     * @param msg           the decoded message.
     * @param authenticated the identity the carrier proved, or {@code null} on a carrier that does
     *                      not authenticate. A null defers the check to the handler, which is stated
     *                      here rather than assumed at each call site.
     * @return {@code true} if the router may dispatch it.
     * @Thread-context any thread.
     */
    public boolean permits(NoderaMessage msg, NodeId authenticated) {
        if (authPolicy != AuthPolicy.TRANSPORT_SENDER_EQUALS || authenticated == null) {
            return true;
        }
        NodeId claimed = senderField.apply(msg);
        return claimed == null || authenticated.equals(claimed);
    }
}
