package dev.nodera.protocol.wire;

import dev.nodera.core.Bytes;
import dev.nodera.protocol.MessageSamples;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.membership.PeerJoin;
import dev.nodera.protocol.session.Nack;
import dev.nodera.protocol.session.RejectCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that decide whether the cross-version programme worked (Task 14 phases 2–4).
 *
 * <p>Each one is the exit condition of a limitation row, phrased as the thing that used to be
 * impossible:
 *
 * <ul>
 *   <li><b>L-86 / R1</b> — a field appended by a newer peer is ignored by this build, at the top
 *       level <em>and</em> inside a nested structure. On the old wire this was not a rough edge, it
 *       was the defining fault: a positional body gives a reader no way to find the end of a field
 *       it does not know, so there was no such thing as a compatible change.</li>
 *   <li><b>L-89 / R4</b> — a consensus payload crosses the tolerant plane as opaque bytes, and no
 *       infrastructure decoder parses a signed structure.</li>
 *   <li><b>D4</b> — an unknown kind is skipped and answered, and the connection survives.</li>
 * </ul>
 *
 * <p>Thread-context: JUnit test; single-threaded.
 */
class ForwardCompatibilityTest {

    /** A field id far above anything this build assigns — "a future release added this". */
    private static final int FUTURE_FIELD = 900;

    /**
     * Splice an extra TLV field onto the end of a body, exactly as a newer build would emit it.
     *
     * <p>Appended rather than inserted because field ids must ascend, and a future field takes a
     * number above every current one.
     */
    private static byte[] withExtraField(byte[] body, int id, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 16);
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

    /** Rebuild a frame around a modified body. */
    private static byte[] reframe(NoderaFrame frame, byte[] body) {
        return new NoderaFrame(frame.epoch(), frame.kind(), frame.flags(), frame.correlationId(),
                Bytes.unsafeWrap(body)).encode();
    }

    // ---------------------------------------------------------------- L-86 / R1

    @Test
    @DisplayName("L-86: a field a newer peer appended is skipped, and the rest of the message survives")
    void aFieldAppendedByANewerPeerIsIgnored() {
        // The message a tracker answers with — nine fields, several of them variable-length lists.
        // If an unknown field were unskippable, everything after it would decode as garbage.
        NoderaMessage original = MessageSamples.byTag().get(MessageCodec.TAG_TRACKER_RESPONSE);
        NoderaFrame frame = NoderaFrame.decode(WireCodec.encode(original));

        byte[] fromTheFuture = reframe(frame,
                withExtraField(frame.body().toArray(), FUTURE_FIELD, new byte[] {1, 2, 3, 4, 5}));

        WireCodec.DecodedFrame decoded = WireCodec.decodeFrame(fromTheFuture);
        assertThat(decoded.message()).contains(original);
    }

    @Test
    @DisplayName("L-86: a field appended inside a NESTED structure is skipped too")
    void aFieldAppendedInsideANestedStructureIsIgnored() {
        // NodeCapabilities and PeerEntry are the two structures the limitation names by hand: they
        // are nested inside membership and discovery messages, so on the old wire growing either of
        // them desynchronised every message that carried one.
        PeerJoin original = (PeerJoin) MessageSamples.byTag().get(MessageCodec.TAG_PEER_JOIN);
        NoderaFrame frame = NoderaFrame.decode(WireCodec.encode(original));

        // Field 3 of PeerJoin is the nested capabilities body; grow it from the inside and re-emit
        // every field verbatim around it.
        List<TlvField> fields = new ArrayList<>(new TlvReader(frame.body()).unknown());
        boolean grew = false;
        TlvWriter out = new TlvWriter();
        for (TlvField f : fields) {
            if (f.id() == 3) {
                out.raw(new TlvField(3, WireType.NESTED, Bytes.unsafeWrap(
                        withExtraField(f.value().toArray(), FUTURE_FIELD, new byte[] {9}))));
                grew = true;
            } else {
                out.raw(f);
            }
        }
        assertThat(grew).withFailMessage("PeerJoin no longer carries capabilities at field 3").isTrue();

        WireCodec.DecodedFrame decoded = WireCodec.decodeFrame(reframe(frame, out.toByteArray()));
        assertThat(decoded.message()).contains(original);
    }

    @Test
    @DisplayName("L-86: a field this build sends and an older one does not is simply absent there")
    void aFieldTheSenderOmitsTakesItsDocumentedDefault() {
        // The other direction: an OLDER peer emits fewer fields. Its frame must still decode, with
        // the missing values taking their defaults — which is what makes appending a field safe in
        // the first place, and what the old wire could not express at all.
        TlvWriter olderPeer = new TlvWriter();
        olderPeer.bytes(1, Bytes.unsafeWrap(new byte[] {0x11, 0x22}));
        byte[] frame = new NoderaFrame(WireRegistry.WIRE_EPOCH, MessageCodec.TAG_TRACKER_RESPONSE,
                FrameFlags.RESPONSE, 7L, olderPeer.toBytes()).encode();

        WireCodec.DecodedFrame decoded = WireCodec.decodeFrame(frame);
        assertThat(decoded.message()).isPresent();
        var response = (dev.nodera.protocol.discovery.TrackerResponse) decoded.message().get();
        assertThat(response.genesisHash()).isEqualTo(Bytes.unsafeWrap(new byte[] {0x11, 0x22}));
        assertThat(response.worldName()).isEmpty();
        assertThat(response.peers()).isEmpty();
        assertThat(response.seeders()).isEmpty();
    }

    // ---------------------------------------------------------------- L-89 / R4

    @Test
    @DisplayName("L-89: a consensus payload crosses the tolerant plane as opaque bytes")
    void aConsensusPayloadCrossesAsOpaqueBytes() {
        NoderaMessage proposal = MessageSamples.byTag().get(MessageCodec.TAG_REGION_PROPOSAL);
        NoderaFrame frame = NoderaFrame.decode(WireCodec.encode(proposal));

        TlvReader body = new TlvReader(frame.body());
        assertThat(body.fieldCount())
                .withFailMessage("a consensus body is exactly one opaque field; anything else means "
                        + "the tolerant plane started interpreting signed bytes")
                .isEqualTo(1);
        TlvField only = body.unknown().get(0);
        assertThat(only.id()).isEqualTo(WireCodec.CONSENSUS_PAYLOAD_FIELD);
        assertThat(only.type()).isEqualTo(WireType.BYTES);
        assertThat(only.value())
                .withFailMessage("the opaque field must carry the strict canonical bytes verbatim — "
                        + "re-spelling a signed payload is how two peers end up disagreeing about a "
                        + "state root")
                .isEqualTo(MessageCodec.encodeBytes(proposal));
    }

    @Test
    @DisplayName("L-89: no consensus kind has a TLV shape, and every infrastructure kind does")
    void theTwoPlanesAreExactlyPopulated() {
        for (WireKind kind : WireRegistry.kinds()) {
            if (kind.plane() == MessagePlane.INFRASTRUCTURE) {
                assertThat(InfrastructureCodec.handles(kind.kind()))
                        .withFailMessage("%s is on the infrastructure plane with no TLV shape",
                                kind.name())
                        .isTrue();
            } else {
                assertThat(InfrastructureCodec.handles(kind.kind()))
                        .withFailMessage("%s is a consensus kind and must not be parsed by the "
                                + "infrastructure codec", kind.name())
                        .isFalse();
            }
        }
    }

    // ---------------------------------------------------------------- D4

    @Test
    @DisplayName("D4: an unknown kind is skipped, answered, and does not kill the connection")
    void anUnknownKindIsSkippedAndAnswered() {
        int fromTheFuture = WireRegistry.NEXT_KIND + 40;
        byte[] frame = new NoderaFrame(WireRegistry.WIRE_EPOCH, fromTheFuture, FrameFlags.REQUEST,
                0xCAFEBABEL, Bytes.unsafeWrap(new byte[] {1, 2, 3, 4})).encode();

        // Decoding does not throw: the body's length said where it ended, so the stream is intact.
        WireCodec.DecodedFrame decoded = WireCodec.decodeFrame(frame);
        assertThat(decoded.unknownKind()).isTrue();
        assertThat(decoded.kind()).isEqualTo(fromTheFuture);
        assertThat(decoded.correlationId()).isEqualTo(0xCAFEBABEL);

        // And the sender is told, rather than left to time out.
        Nack answer = Nack.unsupportedKind(decoded.kind(), decoded.correlationId());
        assertThat(answer.code()).isEqualTo(RejectCode.UNSUPPORTED_KIND);
        assertThat(WireCodec.decode(WireCodec.encode(answer, FrameFlags.RESPONSE,
                decoded.correlationId()))).isEqualTo(answer);
    }

    @Test
    @DisplayName("a Nack preserves a refusal reason this build has never heard of")
    void anUnknownRefusalReasonSurvivesAReEncode() {
        // The lossy-enum failure, in miniature: resolving an unknown code to a stand-in and then
        // re-encoding the stand-in turns one refusal into a different one.
        Nack fromTheFuture = new Nack(27, 4242, 99L, "reason from a later release");
        assertThat(fromTheFuture.reasonRecognised()).isFalse();
        assertThat(fromTheFuture.code()).isEqualTo(RejectCode.UNAVAILABLE);

        byte[] frame = WireCodec.encode(fromTheFuture);
        assertThat(WireCodec.decode(frame)).isEqualTo(fromTheFuture);
        assertThat(((Nack) WireCodec.decode(frame)).reasonCode()).isEqualTo(4242);
    }

    // ---------------------------------------------------------------- frame

    @Test
    @DisplayName("a frame from the previous wire generation fails at the magic, not deep in a body")
    void aLegacyFrameIsRejectedAtTheFirstByte() {
        // The pre-NDR2 frame opened straight in on a u16 tag, so a peer from a different generation
        // did not fail — it misparsed, and reported whatever the misparse produced.
        byte[] legacy = MessageCodec.encode(MessageSamples.byTag().get(MessageCodec.TAG_TRACKER_QUERY));
        assertThatThrownBy(() -> NoderaFrame.decode(legacy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NDR2");
    }

    @Test
    @DisplayName("a frame from a future wire epoch is refused before its body is trusted")
    void aFutureEpochIsRefused() {
        byte[] frame = WireCodec.encode(MessageSamples.byTag().get(MessageCodec.TAG_TRACKER_QUERY));
        frame[5] = (byte) (WireRegistry.WIRE_EPOCH + 1);
        assertThatThrownBy(() -> NoderaFrame.decode(frame))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wire epoch");
    }

    @Test
    @DisplayName("every kind in the schema round-trips through the NDR2 frame")
    void everyKindRoundTripsThroughTheFrame() {
        Map<Integer, NoderaMessage> samples = MessageSamples.byTag();
        MessageSamples.assertTotal();
        for (WireKind kind : WireRegistry.kinds()) {
            NoderaMessage original = samples.get(kind.kind());
            byte[] frame = WireCodec.encode(original, FrameFlags.EVENT, 0L);
            assertThat(NoderaFrame.peekKind(frame)).isEqualTo(kind.kind());
            assertThat(WireCodec.decode(frame))
                    .withFailMessage("%s did not survive a frame round trip", kind.name())
                    .isEqualTo(original);
            // And the bytes are stable: encode(decode(x)) == encode(x).
            assertThat(WireCodec.encode(WireCodec.decode(frame), FrameFlags.EVENT, 0L))
                    .withFailMessage("%s re-encodes to different bytes", kind.name())
                    .isEqualTo(frame);
        }
    }

    @Test
    @DisplayName("correlation and flags survive the round trip, so a response can be matched")
    void correlationAndFlagsSurvive() {
        NoderaMessage query = MessageSamples.byTag().get(MessageCodec.TAG_TRACKER_QUERY);
        byte[] frame = WireCodec.encode(query, FrameFlags.REQUEST | FrameFlags.NO_REPLY_EXPECTED,
                0x0123456789ABCDEFL);
        WireCodec.DecodedFrame decoded = WireCodec.decodeFrame(frame);
        assertThat(decoded.correlationId()).isEqualTo(0x0123456789ABCDEFL);
        assertThat(FrameFlags.has(decoded.flags(), FrameFlags.REQUEST)).isTrue();
        assertThat(FrameFlags.has(decoded.flags(), FrameFlags.NO_REPLY_EXPECTED)).isTrue();
        assertThat(FrameFlags.has(decoded.flags(), FrameFlags.RESPONSE)).isFalse();
    }
}
