package dev.nodera.protocol.wire;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.protocol.MessageSamples;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.session.Hello;
import dev.nodera.protocol.session.HelloAck;
import dev.nodera.protocol.session.Negotiation;
import dev.nodera.protocol.session.PeerSession;
import dev.nodera.protocol.session.RejectCode;
import dev.nodera.protocol.session.SessionRole;
import dev.nodera.protocol.session.WireFeature;
import dev.nodera.protocol.tunnel.TunnelData;
import dev.nodera.protocol.tunnel.TunnelOpen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two builds from different releases, on one network (Task 14 phase 7).
 *
 * <p>This is the programme's headline claim, tested end to end rather than argued for. The "older"
 * peer here is a real one — it advertises a smaller feature set, does not know the newest kinds, and
 * runs a different rule set — and the assertion is that everything except a committee seat still
 * works between them:
 *
 * <ul>
 *   <li>they complete a handshake and each learns exactly what the other can do;</li>
 *   <li>the older peer is admitted as an {@link SessionRole#OBSERVER} with a coded reason, rather
 *       than being refused or (worse) admitted and then throwing from inside the engine;</li>
 *   <li>they exchange discovery, content and tunnel traffic in both directions;</li>
 *   <li>a kind the older peer has never heard of is skipped and answered, and the connection
 *       survives to carry the next message;</li>
 *   <li>a field the newer peer added is invisible to the older one and survives being relayed
 *       <em>through</em> it.</li>
 * </ul>
 *
 * <p>The CI job in {@code .github/workflows/cross-version.yml} runs the same shape against a real
 * previous release; this test is the part that can run on every commit without a second checkout.
 *
 * <p>Thread-context: JUnit test; single-threaded.
 */
class CrossVersionIT {

    private static final UUID NETWORK = new UUID(0x1122334455667788L, 0x99AABBCCDDEEFF00L);
    private static final SignatureService SIGNATURES = new SignatureService();

    /** A field id from a future release — one this build does not know either. */
    private static final int FUTURE_FIELD = 777;

    private static NodeCapabilities capabilities() {
        return new NodeCapabilities(8, 1L << 34, 42, 1.0d, 4, 8, true,
                Set.of(PeerRole.FULL_ARCHIVE, PeerRole.WORLD_SEEDER));
    }

    /** This build: every feature, current rules. */
    private static Negotiation.LocalProfile current() {
        return new Negotiation.LocalProfile("nodera/current", 6, 0xF00DL, NETWORK,
                WireFeature.all(), capabilities());
    }

    /**
     * A previous release: it predates the service directory and the LAN tunnel, and its rule set has
     * since moved on.
     */
    private static Negotiation.LocalProfile previousRelease() {
        Set<Integer> older = new LinkedHashSet<>(WireFeature.all());
        older.remove(WireFeature.SERVICE_DIRECTORY.code());
        older.remove(WireFeature.LAN_TUNNEL.code());
        older.remove(WireFeature.KEEP_ALIVE_REGION_PROGRESS.code());
        return new Negotiation.LocalProfile("nodera/previous", 5, 0xF00DL, NETWORK, older,
                capabilities());
    }

    /** A router standing in for a peer: it accepts what it is given and records what it got. */
    private static final class Peer {
        final NodeIdentity identity = NodeIdentity.generate();
        final MessageRouter router = new MessageRouter();
        final List<NoderaMessage> received = new ArrayList<>();

        Peer(int... kinds) {
            for (int kind : kinds) {
                router.register(kind, (from, msg) -> received.add(msg));
            }
        }
    }

    @Test
    @DisplayName("a peer from a previous release meshes, seeds and tunnels — it just cannot vote")
    void aPreviousReleaseStaysOnTheNetwork() {
        Peer newer = new Peer(MessageCodec.TAG_TRACKER_QUERY, MessageCodec.TAG_CONTENT_REQUEST,
                MessageCodec.TAG_TUNNEL_DATA);
        Peer older = new Peer(MessageCodec.TAG_TRACKER_QUERY, MessageCodec.TAG_CONTENT_CHUNK,
                MessageCodec.TAG_TUNNEL_OPEN);

        // --- the handshake, in both directions ---
        Hello fromOlder = Negotiation.hello(older.identity, previousRelease());
        HelloAck toOlder = Negotiation.respond(fromOlder, current(), older.identity.nodeId(),
                SIGNATURES);

        assertThat(toOlder.role())
                .withFailMessage("a version-skewed peer is still a seeder, a relay and a tunnel "
                        + "endpoint; refusing it costs the network and buys nothing")
                .isEqualTo(SessionRole.OBSERVER);
        assertThat(toOlder.reject()).isEqualTo(RejectCode.RULES_VERSION_MISMATCH);
        assertThat(toOlder.detail()).isNotEmpty();

        PeerSession olderAsSeenByNewer = PeerSession.of(older.identity.nodeId(), toOlder);
        assertThat(olderAsSeenByNewer.consensusCompatible()).isFalse();
        assertThat(olderAsSeenByNewer.supports(WireFeature.SERVICE_DIRECTORY)).isFalse();
        assertThat(olderAsSeenByNewer.supports(WireFeature.TRACKER_ROUTE_LISTS))
                .withFailMessage("a feature both sides have must survive the intersection")
                .isTrue();

        // --- discovery, content and tunnel traffic still flow, both ways ---
        byte[] query = olderAsSeenByNewer.encodeFor(new TrackerQuery(filled(32, 0x11)),
                FrameFlags.REQUEST, 1L);
        assertThat(older.router.accept(query, newer.identity.nodeId()))
                .isInstanceOf(MessageRouter.Outcome.Delivered.class);

        byte[] chunk = WireCodec.encode(new ContentChunk(filled(32, 0x22), 3, filled(64, 0x33)));
        assertThat(newer.router.accept(chunk, older.identity.nodeId()))
                .isInstanceOf(MessageRouter.Outcome.Unhandled.class);

        byte[] open = WireCodec.encode(new TunnelOpen(filled(16, 0x44), 9L));
        assertThat(older.router.accept(open, newer.identity.nodeId()))
                .isInstanceOf(MessageRouter.Outcome.Delivered.class);

        byte[] request = WireCodec.encode(new ContentRequest(filled(32, 0x22), List.of(0, 1, 2)));
        assertThat(newer.router.accept(request, older.identity.nodeId()))
                .isInstanceOf(MessageRouter.Outcome.Delivered.class);

        byte[] data = WireCodec.encode(new TunnelData(9L, filled(128, 0x55)));
        assertThat(newer.router.accept(data, older.identity.nodeId()))
                .isInstanceOf(MessageRouter.Outcome.Delivered.class);

        assertThat(older.received).hasSize(2);
        assertThat(newer.received).hasSize(2);
    }

    @Test
    @DisplayName("a kind from a later release is answered, and the connection carries on")
    void anUnknownKindDoesNotEndTheConversation() {
        Peer older = new Peer(MessageCodec.TAG_TRACKER_QUERY);

        int fromTheFuture = WireRegistry.NEXT_KIND + 25;
        byte[] unknown = new NoderaFrame(WireRegistry.WIRE_EPOCH, fromTheFuture,
                FrameFlags.REQUEST, 55L, Bytes.unsafeWrap(new byte[] {1, 2, 3, 4})).encode();

        MessageRouter.Outcome outcome = older.router.accept(unknown, NodeId.random());
        assertThat(outcome).isInstanceOf(MessageRouter.Outcome.UnknownKind.class);
        assertThat(MessageRouter.answerFor(outcome))
                .withFailMessage("the sender must be told, or its only recovery is a timeout")
                .isPresent();

        // The next message still lands. This is the property the whole frame redesign is for: an
        // unreadable message costs one frame, not the connection.
        byte[] next = WireCodec.encode(new TrackerQuery(filled(32, 0x11)));
        assertThat(older.router.accept(next, NodeId.random()))
                .isInstanceOf(MessageRouter.Outcome.Delivered.class);
        assertThat(older.received).hasSize(1);
    }

    @Test
    @DisplayName("a field a newer release added survives being relayed through an older peer")
    void aFutureFieldSurvivesARelay() {
        // The failure this closes is subtle and was real: a peer in the middle of a version spread
        // that drops what it cannot read turns itself into a lossy relay between two peers that
        // understand each other perfectly well.
        NoderaMessage original = MessageSamples.byTag().get(MessageCodec.TAG_TRACKER_RESPONSE);
        NoderaFrame asSent = NoderaFrame.decode(WireCodec.encode(original));

        byte[] withFutureField = new NoderaFrame(asSent.epoch(), asSent.kind(), asSent.flags(),
                asSent.correlationId(), Bytes.unsafeWrap(appendField(asSent.body().toArray(),
                        FUTURE_FIELD, new byte[] {0x0A, 0x0B, 0x0C})))
                .encode();

        // The middle peer reads what it understands...
        WireCodec.DecodedFrame atTheRelay = WireCodec.decodeFrame(withFutureField);
        assertThat(atTheRelay.message()).contains(original);
        assertThat(atTheRelay.overlay().preserved())
                .withFailMessage("the relay must keep what it could not read")
                .isNotEmpty();

        // ...and forwards exactly what it was given.
        byte[] forwarded = WireCodec.encode(atTheRelay.message().orElseThrow(),
                atTheRelay.overlay(), atTheRelay.flags(), atTheRelay.correlationId());
        assertThat(forwarded).isEqualTo(withFutureField);
    }

    @Test
    @DisplayName("a peer still speaking the previous wire generation is told so, not misparsed")
    void aPreviousGenerationFrameIsRefusedClearly() {
        Peer peer = new Peer(MessageCodec.TAG_TRACKER_QUERY);
        byte[] v1 = MessageCodec.encode(new TrackerQuery(filled(32, 0x11)));

        MessageRouter.Outcome outcome = peer.router.accept(v1, NodeId.random());
        assertThat(outcome).isInstanceOf(MessageRouter.Outcome.Malformed.class);
        assertThat(((MessageRouter.Outcome.Malformed) outcome).detail())
                .withFailMessage("the diagnosis must name the generation gap; 'malformed' alone "
                        + "sends whoever reads the log looking for a network fault")
                .contains("NDR2");
        assertThat(peer.received).isEmpty();
    }

    @Test
    @DisplayName("two peers on the same release agree on everything, including a committee seat")
    void twoCurrentPeersAreFullyCompatible() {
        NodeIdentity peer = NodeIdentity.generate();
        HelloAck ack = Negotiation.respond(Negotiation.hello(peer, current()), current(),
                peer.nodeId(), SIGNATURES);

        assertThat(ack.role()).isEqualTo(SessionRole.ADMITTED);
        assertThat(ack.reject()).isEqualTo(RejectCode.NONE);
        assertThat(ack.selectedFeatures()).isEqualTo(WireFeature.all());
    }

    private static Bytes filled(int length, int value) {
        byte[] raw = new byte[length];
        java.util.Arrays.fill(raw, (byte) value);
        return Bytes.unsafeWrap(raw);
    }

    private static byte[] appendField(byte[] body, int id, byte[] value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(body, 0, body.length);
        out.write((id >>> 8) & 0xFF);
        out.write(id & 0xFF);
        out.write(WireType.BYTES.code());
        out.write((value.length >>> 24) & 0xFF);
        out.write((value.length >>> 16) & 0xFF);
        out.write((value.length >>> 8) & 0xFF);
        out.write(value.length & 0xFF);
        out.write(value, 0, value.length);
        return out.toByteArray();
    }
}
