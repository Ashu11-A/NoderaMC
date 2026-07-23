package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.BreakBlockAction;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionReplicaRole;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.assignment.LeaseRenewal;
import dev.nodera.protocol.assignment.RegionAssigned;
import dev.nodera.protocol.assignment.RegionRevoked;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.handshake.ChallengeResponse;
import dev.nodera.protocol.handshake.ClientHello;
import dev.nodera.protocol.handshake.ServerHello;
import dev.nodera.protocol.handshake.WorkerActivation;
import dev.nodera.protocol.health.Heartbeat;
import dev.nodera.protocol.health.WorkerLoad;
import dev.nodera.protocol.simulationmsg.ActionBatchMsg;
import dev.nodera.protocol.simulationmsg.RegionProposal;
import dev.nodera.protocol.simulationmsg.CommitAnnounce;
import dev.nodera.protocol.simulationmsg.ResyncRequest;
import dev.nodera.protocol.simulationmsg.SnapshotAnnounce;
import dev.nodera.protocol.simulationmsg.StreamChunk;
import dev.nodera.protocol.simulationmsg.ValidationVote;
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

    /**
     * Machine-captured golden hex per frozen message type (captured once from the canonical
     * encoder on 2026-07-23, then pinned). Unlike round-trip or {@code encode(x)==encode(x)}
     * assertions — which pass even if the wire format changes uniformly — these literals fail on
     * ANY encoding drift. The frozen contract is append-only: these bytes must never change.
     */
    private static final String CLIENT_HELLO_GOLDEN_HEX =
            "000100010000000100010001000000000000000000000000000000aa00000008deadbeefcafebabe"
            + "00020001000000040000000100000000000000323fee666666666666000000040000000801000000"
            + "000000000700000000deadbeef0000000400112233";
    private static final String SERVER_HELLO_GOLDEN_HEX =
            "0002000112345678123456781234567812345678000000000000002a000000080000000200000010"
            + "00112233445566778899aabbccddeeff";
    private static final String REGION_ASSIGNED_GOLDEN_HEX =
            "00050001000b0001000a0001000000096d696e656372616674000000096f766572776f726c640000"
            + "000000000000000c00010000000000000003000e000100001e0001000000000000000a0000000000"
            + "0001f40000000100010001000000000000000000000000000000aa";
    private static final String REGION_REVOKED_GOLDEN_HEX =
            "00060001000b0001000a0001000000096d696e656372616674000000096f766572776f726c640000"
            + "000000000000000c000100000000000000030000000d6c656173652d65787069726564";
    private static final String SNAPSHOT_ANNOUNCE_GOLDEN_HEX =
            "00080001000b0001000a0001000000096d696e656372616674000000096f766572776f726c640000"
            + "000000000000001e0001000000000000000a0000004000000002001f000100000020000000000000"
            + "0000000000000000000000000000000000000000000000000000";
    private static final String STREAM_CHUNK_GOLDEN_HEX =
            "000900010000000000000007000000000000000200000004deadbeef";
    private static final String ACTION_BATCH_MSG_GOLDEN_HEX =
            "000a000100160001000b0001000a0001000000096d696e656372616674000000096f766572776f72"
            + "6c640000000000000000000c00010000000000000002001e00010000000000000007000000000000"
            + "00640000000000000066000000020015000100010001000000000000000000000000000000aa0000"
            + "00000000000100000000000000010000000000000064000b0001000a0001000000096d696e656372"
            + "616674000000096f766572776f726c64000000000000000000170001001400010000000100000002"
            + "000000030000000c0200000001ff0015000100010001000000000000000000000000000000aa0000"
            + "00000000000200000000000000020000000000000065000b0001000a0001000000096d696e656372"
            + "616674000000096f766572776f726c64000000000000000000180001001400010000000400000005"
            + "0000000600000001ee";
    private static final String REGION_PROPOSAL_GOLDEN_HEX =
            "000b0003000b0001000a0001000000096d696e656372616674000000096f766572776f726c640000"
            + "000000000000000c00010000000000000003001e0001000000000000000a00000000000000640000"
            + "000000000066001f0001000000200000000000000000000000000000000000000000000000000000"
            + "000000000000001f0001000000200000000000000000000000000000000000000000000000000000"
            + "00000000000000000002aabb001f0001000000200000000000000000000000000000000000000000"
            + "00000000000000000000000000000002ccdd";
    private static final String VALIDATION_VOTE_GOLDEN_HEX =
            "000c0001000b0001000a0001000000096d696e656372616674000000096f766572776f726c640000"
            + "000000000000000c00010000000000000003001e0001000000000000000a00320001000100010000"
            + "00000000000000000000000000aa001f000100000020000000000000000000000000000000000000"
            + "0000000000000000000000000000003300010000000002ccdd";
    private static final String COMMIT_ANNOUNCE_GOLDEN_HEX =
            "000d0001000b0001000a0001000000096d696e656372616674000000096f766572776f726c640000"
            + "000000000000001e0001000000000000000a001f0001000000200000000000000000000000000000"
            + "00000000000000000000000000000000000000000002aabb";
    private static final String RELAY_ENVELOPE_GOLDEN_HEX =
            "0012000100010001000000000000000000000000000000aa0000000c0011000100000004deadbeef";

    @Test
    void goldenHexPinsEveryFrozenMessageType() {
        assertGolden(sampleClientHello(), CLIENT_HELLO_GOLDEN_HEX);
        assertGolden(sampleServerHello(), SERVER_HELLO_GOLDEN_HEX);
        assertGolden(sampleRegionAssigned(), REGION_ASSIGNED_GOLDEN_HEX);
        assertGolden(sampleRegionRevoked(), REGION_REVOKED_GOLDEN_HEX);
        assertGolden(sampleSnapshotAnnounce(), SNAPSHOT_ANNOUNCE_GOLDEN_HEX);
        assertGolden(sampleStreamChunk(), STREAM_CHUNK_GOLDEN_HEX);
        assertGolden(sampleActionBatchMsg(), ACTION_BATCH_MSG_GOLDEN_HEX);
        assertGolden(sampleRegionProposal(), REGION_PROPOSAL_GOLDEN_HEX);
        assertGolden(sampleValidationVote(), VALIDATION_VOTE_GOLDEN_HEX);
        assertGolden(sampleCommitAnnounce(), COMMIT_ANNOUNCE_GOLDEN_HEX);
        assertGolden(sampleRelayEnvelope(), RELAY_ENVELOPE_GOLDEN_HEX);
    }

    /** Encode-side pin AND decode-side pin: the literal must round back to the sample. */
    private static void assertGolden(NoderaMessage sample, String goldenHex) {
        assertThat(Bytes.unsafeWrap(MessageCodec.encode(sample)).toHex())
                .as("golden encode drift for %s", sample.getClass().getSimpleName())
                .isEqualTo(goldenHex);
        assertThat(MessageCodec.decode(Bytes.fromHex(goldenHex).toArray()))
                .as("golden decode drift for %s", sample.getClass().getSimpleName())
                .isEqualTo(sample);
    }

    private static ServerHello sampleServerHello() {
        return new ServerHello(
                UUID.fromString("12345678-1234-5678-1234-567812345678"),
                42L, 8, 2, Bytes.fromHex("00112233445566778899aabbccddeeff"));
    }

    private static RegionAssigned sampleRegionAssigned() {
        return new RegionAssigned(
                regionOverworld(), new RegionEpoch(3), RegionReplicaRole.PRIMARY,
                new SnapshotVersion(10), 500L,
                List.of(new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000aa"))));
    }

    private static RegionRevoked sampleRegionRevoked() {
        return new RegionRevoked(regionOverworld(), new RegionEpoch(3), "lease-expired");
    }

    private static SnapshotAnnounce sampleSnapshotAnnounce() {
        return new SnapshotAnnounce(
                regionOverworld(), new SnapshotVersion(10), 64, 2, StateRoot.zero());
    }

    private static StreamChunk sampleStreamChunk() {
        return new StreamChunk(7L, 0, 2, Bytes.fromHex("deadbeef"));
    }

    private static ValidationVote sampleValidationVote() {
        return new ValidationVote(
                regionOverworld(), new RegionEpoch(3), new SnapshotVersion(10),
                new SignedVote(
                        new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000aa")),
                        StateRoot.zero(), VoteDecision.ACCEPT, Bytes.fromHex("ccdd")));
    }

    private static CommitAnnounce sampleCommitAnnounce() {
        return new CommitAnnounce(
                regionOverworld(), new SnapshotVersion(10), StateRoot.zero(),
                Bytes.fromHex("aabb"));
    }

    private static RelayEnvelope sampleRelayEnvelope() {
        return new RelayEnvelope(
                new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000aa")),
                Bytes.fromHex(ECHO_TEST_DEADBEEF_HEX));
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
