package dev.nodera.protocol.wire;

/**
 * Who is allowed to send a kind, declared once instead of re-decided per handler
 * (Task 14 phase 5, {@code Plan.7} §4.4).
 *
 * <p>The old dispatch had no answer to "may this peer send me this?" — it had as many answers as
 * there were handlers, and several of them were "yes". {@code MembershipUpdate}, {@code GatewayClaim},
 * {@code ContentAvailability.holder} and {@code RegionRefusal} were all accepted from any connected
 * socket, which meant any peer could rewrite the mesh's view of who the gateway was, or claim to
 * hold content it did not have, simply by saying so.
 *
 * <p>Each of those was a separate finding with a separate fix. A declared policy turns them into one
 * table that the router enforces <b>before</b> a handler runs, so a new kind cannot arrive without
 * someone stating who may send it — the field is not optional.
 *
 * <p>Thread-context: immutable enum; any thread.
 */
public enum AuthPolicy {

    /**
     * Anyone may send it. Correct for discovery and tracker traffic, where the whole point is that
     * a peer you have never met can ask a question.
     */
    PUBLIC,

    /**
     * The message names a node, and that node must be the peer the transport authenticated.
     *
     * <p>The policy behind "a peer may not speak for another peer": a holder claiming content, a
     * joiner naming itself, a gateway claiming the seat. The router checks the field named by
     * {@link MessageType#senderField()}.
     */
    TRANSPORT_SENDER_EQUALS,

    /**
     * Only a peer holding the relevant role on this session — a committee seat, the gateway.
     *
     * <p>Distinct from {@link #TRANSPORT_SENDER_EQUALS}: proving <em>who</em> you are is not proving
     * you are entitled to say this.
     */
    ROLE_AUTHORIZED,

    /**
     * The payload carries its own proof, so the carrier does not matter.
     *
     * <p>A signed record, a tombstone, a grant. These are deliberately relayable: the whole design
     * of world deletion is that a third peer can forward the owner's proof and it still verifies.
     * The router lets them through and the handler checks the signature.
     */
    SELF_AUTHENTICATED_COURIER,

    /**
     * Advisory: acted on only after the receiver re-checks the claim against its own state.
     *
     * <p>{@code RegionRefusal} is the example — it says "I observed a condition", and the recipient
     * re-evaluates that condition itself before doing anything. A lying peer costs the receiver one
     * re-examination and nothing else.
     */
    ADVISORY_RECHECK
}
