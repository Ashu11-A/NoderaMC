package dev.nodera.protocol.wire;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.MessageSamples;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.membership.GatewayClaim;
import dev.nodera.protocol.session.Nack;
import dev.nodera.protocol.session.RejectCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The router, the authorisation table, and the correlation table (Task 14 phase 5).
 *
 * <p>Each test here is a defect that used to be reachable, phrased as the thing that now stops it:
 * a peer speaking in another peer's name, an answer nobody asked for reaching a handler, a service
 * silently replacing another service's handler, and a kind this build has never heard of taking down
 * a connection.
 *
 * <p>Thread-context: JUnit test; single-threaded.
 */
class MessageRouterTest {

    private static NodeId node(long msb, long lsb) {
        return new NodeId(new UUID(msb, lsb));
    }

    @Test
    @DisplayName("a peer cannot speak in another peer's name")
    void aClaimedSenderMustMatchTheAuthenticatedPeer() {
        // GatewayClaim was accepted from any connected socket, so any peer could rewrite the mesh's
        // idea of who the gateway was simply by saying so.
        MessageRouter router = new MessageRouter();
        List<NoderaMessage> delivered = new ArrayList<>();
        router.register(MessageCodec.TAG_GATEWAY_CLAIM, (from, msg) -> delivered.add(msg));

        NodeId realGateway = node(1, 1);
        NodeId impostor = node(2, 2);
        byte[] frame = WireCodec.encode(new GatewayClaim(realGateway, 7L));

        MessageRouter.Outcome refused = router.accept(frame, impostor);
        assertThat(refused).isInstanceOf(MessageRouter.Outcome.Refused.class);
        assertThat(((MessageRouter.Outcome.Refused) refused).reason())
                .isEqualTo(RejectCode.NOT_AUTHORISED);
        assertThat(delivered).isEmpty();

        // And the sender is told, rather than left to wonder why nothing happened.
        assertThat(MessageRouter.answerFor(refused)).isPresent();

        assertThat(router.accept(frame, realGateway))
                .isInstanceOf(MessageRouter.Outcome.Delivered.class);
        assertThat(delivered).hasSize(1);
    }

    @Test
    @DisplayName("a carrier that does not authenticate defers the check rather than inventing one")
    void anUnauthenticatedCarrierDefersTheCheck() {
        MessageRouter router = new MessageRouter();
        router.register(MessageCodec.TAG_GATEWAY_CLAIM, (from, msg) -> { });
        byte[] frame = WireCodec.encode(new GatewayClaim(node(1, 1), 7L));
        assertThat(router.accept(frame, null))
                .isInstanceOf(MessageRouter.Outcome.Delivered.class);
    }

    @Test
    @DisplayName("an unknown kind is answered and the connection survives")
    void anUnknownKindIsAnsweredNotDropped() {
        MessageRouter router = new MessageRouter();
        int fromTheFuture = WireRegistry.NEXT_KIND + 11;
        byte[] frame = new NoderaFrame(WireRegistry.WIRE_EPOCH, fromTheFuture, FrameFlags.REQUEST,
                4242L, Bytes.unsafeWrap(new byte[0])).encode();

        MessageRouter.Outcome outcome = router.accept(frame, node(1, 1));
        assertThat(outcome).isInstanceOf(MessageRouter.Outcome.UnknownKind.class);

        byte[] answer = MessageRouter.answerFor(outcome).orElseThrow();
        Nack nack = (Nack) WireCodec.decode(answer);
        assertThat(nack.kind()).isEqualTo(fromTheFuture);
        assertThat(nack.code()).isEqualTo(RejectCode.UNSUPPORTED_KIND);
        assertThat(nack.correlationId())
                .withFailMessage("the answer must name the request it refuses, or the sender still "
                        + "has to wait the timeout out")
                .isEqualTo(4242L);
    }

    @Test
    @DisplayName("an EXCLUSIVE kind refuses a second handler instead of replacing the first")
    void anExclusiveKindRefusesASecondHandler() {
        MessageRouter router = new MessageRouter();
        router.register(MessageCodec.TAG_TRACKER_QUERY, (from, msg) -> { });
        assertThatThrownBy(() -> router.register(MessageCodec.TAG_TRACKER_QUERY, (from, msg) -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("silently replace");
    }

    @Test
    @DisplayName("a BROADCAST kind reaches every registered service")
    void aBroadcastKindReachesEveryService() {
        // The six-way manual fan-out existed because the runtime could only deliver a kind to one
        // place. Two services that both care about membership now both get it.
        MessageRouter router = new MessageRouter();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        router.register(MessageCodec.TAG_WORLD_GRANT_GOSSIP, (from, msg) -> first.incrementAndGet());
        router.register(MessageCodec.TAG_WORLD_GRANT_GOSSIP, (from, msg) -> second.incrementAndGet());

        byte[] frame = WireCodec.encode(
                MessageSamples.byTag().get(MessageCodec.TAG_WORLD_GRANT_GOSSIP));
        MessageRouter.Outcome outcome = router.accept(frame, node(1, 1));

        assertThat(outcome).isInstanceOf(MessageRouter.Outcome.Delivered.class);
        assertThat(((MessageRouter.Outcome.Delivered) outcome).handlers()).isEqualTo(2);
        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a known kind with no handler is reported, not silently swallowed")
    void anUnhandledKindIsReported() {
        MessageRouter router = new MessageRouter();
        byte[] frame = WireCodec.encode(MessageSamples.byTag().get(MessageCodec.TAG_TRACKER_QUERY));
        assertThat(router.accept(frame, null)).isInstanceOf(MessageRouter.Outcome.Unhandled.class);
    }

    @Test
    @DisplayName("a malformed frame is a reported outcome, not an exception out of the read loop")
    void aMalformedFrameIsAnOutcome() {
        MessageRouter router = new MessageRouter();
        assertThat(router.accept(new byte[] {1, 2, 3}, null))
                .isInstanceOf(MessageRouter.Outcome.Malformed.class);
    }

    @Test
    @DisplayName("answerFor answers only the two outcomes that carry a NACK")
    void answerForOnlyAnswersTheOutcomesThatCarryANack() {
        // Pins the instanceof chain in answerFor against the original switch: the two NACK-bearing
        // outcomes are answered, and the other three fall through to empty exactly as before.
        MessageRouter router = new MessageRouter();

        int futureKind = WireRegistry.NEXT_KIND + 11;
        byte[] unknownFrame = new NoderaFrame(WireRegistry.WIRE_EPOCH, futureKind, FrameFlags.REQUEST,
                7L, Bytes.unsafeWrap(new byte[0])).encode();
        MessageRouter.Outcome unknown = router.accept(unknownFrame, node(1, 1));
        assertThat(unknown).isInstanceOf(MessageRouter.Outcome.UnknownKind.class);
        assertThat(MessageRouter.answerFor(unknown)).isPresent();

        byte[] claimedFrame = WireCodec.encode(new GatewayClaim(node(1, 1), 7L));
        MessageRouter.Outcome refused = router.accept(claimedFrame, node(2, 2));
        assertThat(refused).isInstanceOf(MessageRouter.Outcome.Refused.class);
        assertThat(MessageRouter.answerFor(refused)).isPresent();

        router.register(MessageCodec.TAG_WORLD_GRANT_GOSSIP, (from, msg) -> { });
        byte[] deliveredFrame = WireCodec.encode(
                MessageSamples.byTag().get(MessageCodec.TAG_WORLD_GRANT_GOSSIP));
        MessageRouter.Outcome delivered = router.accept(deliveredFrame, node(1, 1));
        assertThat(delivered).isInstanceOf(MessageRouter.Outcome.Delivered.class);
        assertThat(MessageRouter.answerFor(delivered)).isEmpty();

        byte[] unhandledFrame = WireCodec.encode(
                MessageSamples.byTag().get(MessageCodec.TAG_TRACKER_QUERY));
        MessageRouter.Outcome unhandled = router.accept(unhandledFrame, null);
        assertThat(unhandled).isInstanceOf(MessageRouter.Outcome.Unhandled.class);
        assertThat(MessageRouter.answerFor(unhandled)).isEmpty();

        MessageRouter.Outcome malformed = router.accept(new byte[] {1, 2, 3}, null);
        assertThat(malformed).isInstanceOf(MessageRouter.Outcome.Malformed.class);
        assertThat(MessageRouter.answerFor(malformed)).isEmpty();
    }

    // ---------------------------------------------------------------- correlation

    @Test
    @DisplayName("a response nobody asked for matches nothing")
    void anUnsolicitedResponseMatchesNothing() {
        // The shape behind three separately-reported findings: a tracker answer for the wrong world
        // merged into a pending fetch, an unsolicited manifest answer filed against a request that
        // never went out, and an event-sync answer from a peer nobody asked.
        CorrelationTable<String> table = new CorrelationTable<>(1_000L);
        assertThat(table.claim(999L, MessageCodec.TAG_TRACKER_RESPONSE)).isEmpty();
        assertThat(table.claim(0L, MessageCodec.TAG_TRACKER_RESPONSE)).isEmpty();
    }

    @Test
    @DisplayName("an answer is matched to the question that asked for it, exactly once")
    void anAnswerIsClaimedOnce() {
        CorrelationTable<String> table = new CorrelationTable<>(1_000L);
        long id = table.issue(MessageCodec.TAG_TRACKER_QUERY, "world-a");
        long other = table.issue(MessageCodec.TAG_TRACKER_QUERY, "world-b");

        assertThat(id).isNotEqualTo(other);
        assertThat(table.outstanding()).isEqualTo(2);
        assertThat(table.claim(id, MessageCodec.TAG_TRACKER_RESPONSE)).contains("world-a");
        // A replayed answer finds nothing the second time.
        assertThat(table.claim(id, MessageCodec.TAG_TRACKER_RESPONSE)).isEmpty();
        assertThat(table.outstanding()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unanswered request expires instead of leaking")
    void anUnansweredRequestExpires() {
        CorrelationTable<String> table = new CorrelationTable<>(1_000L);
        long id = table.issue(MessageCodec.TAG_TRACKER_QUERY, "world-a");

        assertThat(table.expire(30_000L, System.currentTimeMillis())).isEmpty();
        assertThat(table.expire(0L, System.currentTimeMillis() + 1)).containsExactly("world-a");
        assertThat(table.outstanding()).isZero();
        assertThat(table.claim(id, MessageCodec.TAG_TRACKER_RESPONSE)).isEmpty();
    }

    @Test
    @DisplayName("the authorisation table covers every kind, and states the split it claims")
    void theAuthorisationTableIsTotal() {
        assertThat(MessageTypes.all()).hasSameSizeAs(WireRegistry.kinds());
        for (WireKind kind : WireRegistry.kinds()) {
            assertThat(MessageTypes.of(kind.kind()).kind()).isEqualTo(kind);
        }
        // Every policy is actually used; a policy nothing carries is a policy nobody has thought
        // about, and the table would be describing a design that does not exist.
        assertThat(MessageTypes.policyCounts().keySet())
                .containsExactlyInAnyOrder(AuthPolicy.values());
    }

    @Test
    @DisplayName("the kinds that used to be accepted from anyone now name their sender")
    void theHistoricallyOpenKindsAreBound() {
        for (int kind : List.of(MessageCodec.TAG_GATEWAY_CLAIM,
                MessageCodec.TAG_CONTENT_AVAILABILITY,
                MessageCodec.TAG_INVENTORY_ADVERTISEMENT,
                MessageCodec.TAG_PEER_JOIN,
                MessageCodec.TAG_SESSION_KEEP_ALIVE)) {
            assertThat(MessageTypes.of(kind).authPolicy())
                    .withFailMessage("%s was accepted from any connected socket; it must now be "
                            + "bound to the peer it names", WireRegistry.nameOf(kind))
                    .isEqualTo(AuthPolicy.TRANSPORT_SENDER_EQUALS);
        }
        assertThat(MessageTypes.of(MessageCodec.TAG_MEMBERSHIP_UPDATE).authPolicy())
                .isEqualTo(AuthPolicy.ROLE_AUTHORIZED);
        assertThat(MessageTypes.of(MessageCodec.TAG_REGION_REFUSAL).authPolicy())
                .isEqualTo(AuthPolicy.ADVISORY_RECHECK);
    }
}
