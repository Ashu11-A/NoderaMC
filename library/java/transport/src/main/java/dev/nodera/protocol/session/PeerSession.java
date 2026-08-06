package dev.nodera.protocol.session;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.protocol.wire.WireCodec;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one peer agreed to receive, consulted every time something is sent to it
 * (Task 14 phase 4, retiring {@code Plan.7} R3).
 *
 * <h2>The failure this closes</h2>
 *
 * <p>Compatibility on the old wire ran in one direction only. Readers were tolerant and writers were
 * not: {@code SessionKeepAlive} accepted body versions 1 and 2 and <em>always emitted 2</em>,
 * {@code RegionProposal} always emitted 3, {@code ExternalDelta} always emitted 2. So a current peer
 * would happily accept an older peer's keep-alive — and then send one the older peer could not
 * parse. The older peer heard nothing back it could read, its liveness timer expired, and it
 * declared a perfectly healthy peer dead.
 *
 * <p>No amount of per-message care fixes that, because the writer has no idea who it is writing to.
 * This class is the missing knowledge: the handshake agreed a feature set, and
 * {@link #shapeForEmit(NoderaMessage)} demotes anything the peer did not accept <em>before</em> it
 * is encoded. A feature the other side cannot read is structurally unable to reach it.
 *
 * <p>Thread-context: immutable; safe to share across the threads that send to one peer.
 */
public final class PeerSession {

    private final NodeId peer;
    private final SessionRole role;
    private final Set<Integer> features;

    private PeerSession(NodeId peer, SessionRole role, Set<Integer> features) {
        this.peer = peer;
        this.role = role;
        this.features = Collections.unmodifiableSet(new TreeSet<>(features));
    }

    /**
     * Build a session from a completed handshake.
     *
     * @param peer the authenticated identity on the far end.
     * @param ack  the answer that closed the negotiation.
     * @return the session profile.
     * @Thread-context any thread.
     */
    public static PeerSession of(NodeId peer, HelloAck ack) {
        return new PeerSession(peer, ack.role(), ack.selectedFeatures());
    }

    /**
     * A session that assumes nothing: no optional features at all.
     *
     * <p>The right default for a peer that has not completed a handshake, because the only safe
     * assumption about an unknown peer is the smallest one.
     */
    public static PeerSession conservative(NodeId peer) {
        return new PeerSession(peer, SessionRole.OBSERVER, Set.of());
    }

    /** A session with everything this build can do — for loopback and tests. */
    public static PeerSession full(NodeId peer) {
        return new PeerSession(peer, SessionRole.ADMITTED, WireFeature.all());
    }

    /** The authenticated peer this session belongs to. */
    public NodeId peer() {
        return peer;
    }

    /** What the peer may do; see {@link SessionRole}. */
    public SessionRole role() {
        return role;
    }

    /** The negotiated feature codes — the intersection, never one side's wish list. */
    public Set<Integer> features() {
        return features;
    }

    /** @return {@code true} if the peer accepted {@code feature}. */
    public boolean supports(WireFeature feature) {
        return features.contains(feature.code());
    }

    /** @return {@code true} if this peer may hold a committee seat. */
    public boolean consensusCompatible() {
        return role == SessionRole.ADMITTED;
    }

    /**
     * Reduce a message to what this peer agreed to receive.
     *
     * <p>This is the writer half of the "tolerant reader, unconditional writer" pair. The demotion
     * is deliberate and lossy in exactly the way the peer asked for: it gets a message it can read
     * instead of one it cannot.
     *
     * <p><b>Only infrastructure messages can be demoted, and that is not a shortcut.</b> A consensus
     * payload's signature covers the body version and every field in it, so re-shaping one would
     * invalidate a signature this peer did not produce and cannot reissue. The right answer for a
     * peer that cannot read the current consensus encoding is therefore not a smaller proposal — it
     * is {@link SessionRole#OBSERVER}: it still meshes, seeds, relays and receives commits, and it
     * holds no committee seat. That boundary is decided once at the handshake rather than guessed at
     * per message.
     *
     * @param msg the message about to be sent.
     * @return the same message, or a reduced form of it.
     * @Thread-context any thread.
     */
    public NoderaMessage shapeForEmit(NoderaMessage msg) {
        if (msg instanceof SessionKeepAlive keepAlive
                && !supports(WireFeature.KEEP_ALIVE_REGION_PROGRESS)
                && !keepAlive.regionProgress().isEmpty()) {
            // Identity and sequence only — the constructor that names that shape, rather than a
            // second spelling of it here. The body version is not what changes: since 0.2.0 tag 23
            // is version 2 either way, and what this peer did not accept is the progress field.
            return new SessionKeepAlive(keepAlive.from(), keepAlive.seq());
        }
        return msg;
    }

    /**
     * Encode a message for this peer, honouring the negotiated profile.
     *
     * <p>The one call sites should use. Going straight to {@link WireCodec} bypasses the profile,
     * which is precisely the mistake this class exists to make impossible.
     *
     * @param msg           the message.
     * @param flags         see {@code FrameFlags}.
     * @param correlationId 0 for events; the request's id for a response.
     * @return the frame.
     * @Thread-context any thread.
     */
    public byte[] encodeFor(NoderaMessage msg, int flags, long correlationId) {
        return WireCodec.encode(shapeForEmit(msg), flags, correlationId);
    }
}
