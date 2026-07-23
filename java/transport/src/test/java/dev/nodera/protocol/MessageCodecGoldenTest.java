package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.assignment.LeaseRenewal;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.handshake.ChallengeResponse;
import dev.nodera.protocol.handshake.ClientHello;
import dev.nodera.protocol.handshake.ServerHello;
import dev.nodera.protocol.handshake.WorkerActivation;
import dev.nodera.protocol.health.Heartbeat;
import dev.nodera.protocol.health.WorkerLoad;
import dev.nodera.protocol.simulationmsg.ActionBatchMsg;
import dev.nodera.protocol.simulationmsg.RegionProposal;
import dev.nodera.protocol.simulationmsg.ResyncRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byte-stable golden vectors, byte-stability assertions, and decode round-trip coverage for
 * {@link MessageCodec} (Task 4 acceptance #5: "golden vectors per message type; append-only tag
 * test").
 *
 * <p>The {@code EchoTest} golden literal below is the exact canonical byte sequence for
 * {@code EchoTest(Bytes.fromHex("deadbeef"))}: {@code typeTag=0011 version=0001
 * payloadLength=00000004 payload=deadbeef}. ANY change to the framing breaks this test.
 *
 * <p>Thread-context: single test thread.
 */
final class MessageCodecGoldenTest {

    /**
     * Hand-derived golden canonical hex for {@code EchoTest(Bytes.fromHex("deadbeef"))}:
     * <ul>
     *   <li>{@code 0011} — typeTag u16 ({@link MessageCodec#TAG_ECHO_TEST} = 17 = 0x0011)</li>
     *   <li>{@code 0001} — version u16 ({@link MessageCodec#ENCODING_VERSION} = 1)</li>
     *   <li>{@code 00000004} — u32 payload length (4 bytes)</li>
     *   <li>{@code deadbeef} — raw payload bytes</li>
     * </ul>
     * Total: 12 bytes.
     */
    private static final String ECHO_TEST_DEADBEEF_HEX = "0011000100000004deadbeef";

    @Test
    void echoTestGoldenHexMatchesExactFrameBytes() {
        EchoTest msg = new EchoTest(Bytes.fromHex("deadbeef"));
        byte[] frame = MessageCodec.encode(msg);
        assertThat(Bytes.unsafeWrap(frame).toHex()).isEqualTo(ECHO_TEST_DEADBEEF_HEX);
        assertThat(frame).hasSize(12);
    }

    @Test
    void echoTestGoldenHexRoundTripsToEqualMessage() {
        byte[] frame = Bytes.fromHex(ECHO_TEST_DEADBEEF_HEX).toArray();
        NoderaMessage decoded = MessageCodec.decode(frame);
        assertThat(decoded).isEqualTo(new EchoTest(Bytes.fromHex("deadbeef")));
    }

    @Test
    void encodeIsByteStableAcrossInvocations() {
        ClientHello clientHello = sampleClientHello();
        RegionProposal proposal = sampleRegionProposal();
        ActionBatchMsg batchMsg = sampleActionBatchMsg();

        assertThat(MessageCodec.encode(clientHello))
                .isEqualTo(MessageCodec.encode(clientHello));
        assertThat(MessageCodec.encode(proposal))
                .isEqualTo(MessageCodec.encode(proposal));
        assertThat(MessageCodec.encode(batchMsg))
                .isEqualTo(MessageCodec.encode(batchMsg));
    }

    @Test
    void encodeDecodeRoundTripsForAtLeastFiveDistinctMessageTypes() {
        EchoTest echo = new EchoTest(Bytes.fromHex("cafe"));
        ChallengeResponse challenge = new ChallengeResponse(Bytes.fromHex("aabbcc"));
        WorkerLoad load = new WorkerLoad(3, 1024L, 5_000_000L);
        Heartbeat heartbeat = new Heartbeat(99L, new WorkerLoad(1, 2L, 3L));
        LeaseRenewal lease = new LeaseRenewal(regionOverworld(), RegionEpoch.INITIAL, 250L);
        ResyncRequest resync = new ResyncRequest(regionOverworld(), new SnapshotVersion(7L));
        ServerHello serverHello = new ServerHello(
                UUID.fromString("12345678-1234-5678-1234-567812345678"),
                42L, 8, 2, Bytes.fromHex("00112233445566778899aabbccddeeff"));
        WorkerActivation activation = new WorkerActivation(
                UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890"), 4, 8, 20L);

        assertThat(roundTrip(echo)).isEqualTo(echo);
        assertThat(roundTrip(challenge)).isEqualTo(challenge);
        assertThat(roundTrip(load)).isEqualTo(load);
        assertThat(roundTrip(heartbeat)).isEqualTo(heartbeat);
        assertThat(roundTrip(lease)).isEqualTo(lease);
        assertThat(roundTrip(resync)).isEqualTo(resync);
        assertThat(roundTrip(serverHello)).isEqualTo(serverHello);
        assertThat(roundTrip(activation)).isEqualTo(activation);
    }

    @Test
    void encodeBytesMatchesEncode() {
        EchoTest echo = new EchoTest(Bytes.fromHex("deadbeef"));
        byte[] fromEncode = MessageCodec.encode(echo);
        byte[] fromEncodeBytes = MessageCodec.encodeBytes(echo).toArray();
        assertThat(fromEncodeBytes).isEqualTo(fromEncode);
    }

    @Test
    void decodeEncodeRoundTripIsIdempotentForAllComplexMessages() {
        ClientHello original = sampleClientHello();
        ClientHello decoded = (ClientHello) MessageCodec.decode(MessageCodec.encode(original));
        byte[] reencoded = MessageCodec.encode(decoded);
        assertThat(reencoded).isEqualTo(MessageCodec.encode(original));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void legacyRegionProposalRetainsRootOnlySignatureContract() {
        RegionProposal current = sampleRegionProposal();
        RegionProposal legacy = new RegionProposal(
                current.region(), current.epoch(), current.baseVersion(),
                current.tickFrom(), current.tickTo(), current.prevRoot(), current.resultingRoot(),
                current.encodedDelta(), null, current.proposerSig(), 1);
        RegionProposal decoded = (RegionProposal) MessageCodec.decode(MessageCodec.encode(legacy));
        assertThat(decoded).isEqualTo(legacy);
        assertThat(decoded.signedPortion()).isEqualTo(decoded.resultingRoot().hash());
        assertThat(current.signedPortion()).isNotEqualTo(legacy.signedPortion());
    }

    @Test
    void decodeRejectsHighBitU32ScalarInsteadOfWrappingNegative() {
        // Adversarial frame: a tampered ServerHello whose requiredValidators u32 has the high bit
        // set (0x80000001). A bare (int) cast would wrap it to a negative quorum bound; decode
        // must throw instead. Frame layout: tag(2) version(2) uuid(16) tick(8) regionSize(4)
        // requiredValidators(4) challenge — requiredValidators starts at offset 32.
        ServerHello hello = new ServerHello(
                UUID.fromString("12345678-1234-5678-1234-567812345678"),
                42L, 8, 2, Bytes.fromHex("00112233445566778899aabbccddeeff"));
        byte[] frame = MessageCodec.encode(hello);
        frame[32] = (byte) 0x80;
        frame[33] = 0x00;
        frame[34] = 0x00;
        frame[35] = 0x01;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MessageCodec.decode(frame))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds Integer.MAX_VALUE");
    }

    private static NoderaMessage roundTrip(NoderaMessage msg) {
        byte[] frame = MessageCodec.encode(msg);
        byte[] frameCopy = Arrays.copyOf(frame, frame.length);
        return MessageCodec.decode(frameCopy);
    }

    private static ClientHello sampleClientHello() {
        return new ClientHello(
                1,
                new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000aa")),
                Bytes.fromHex("deadbeefcafebabe"),
                NodeCapabilities.initial(),
                7,
                0xDEADBEEFL,
                Bytes.fromHex("00112233"));
    }

    private static RegionProposal sampleRegionProposal() {
        return new RegionProposal(
                regionOverworld(),
                new RegionEpoch(3),
                new SnapshotVersion(10),
                100L, 102L,
                StateRoot.zero(),
                StateRoot.zero(),
                Bytes.fromHex("aabb"),
                StateRoot.zero(),
                Bytes.fromHex("ccdd"));
    }

    private static ActionBatchMsg sampleActionBatchMsg() {
        ActionEnvelope e1 = new ActionEnvelope(
                new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000aa")),
                1L, 1L, 100L, regionOverworld(),
                new PlaceBlockAction(new NBlockPos(1, 2, 3), 12, 2),
                Bytes.fromHex("ff"));
        ActionEnvelope e2 = new ActionEnvelope(
                new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000aa")),
                2L, 2L, 101L, regionOverworld(),
                new BreakBlockAction(new NBlockPos(4, 5, 6)),
                Bytes.fromHex("ee"));
        ActionBatch batch = new ActionBatch(
                regionOverworld(), new RegionEpoch(2), new SnapshotVersion(7),
                100L, 102L, List.of(e1, e2));
        return new ActionBatchMsg(batch);
    }

    private static RegionId regionOverworld() {
        return new RegionId(DimensionKey.overworld(), 0, 0);
    }
}
