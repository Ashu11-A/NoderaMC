package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.assignment.LeaseRenewal;
import dev.nodera.protocol.assignment.RegionRevoked;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.handshake.ChallengeResponse;
import dev.nodera.protocol.handshake.WorkerActivation;
import dev.nodera.protocol.health.Heartbeat;
import dev.nodera.protocol.health.WorkerLoad;
import dev.nodera.protocol.membership.GatewayClaim;
import dev.nodera.protocol.membership.MembershipUpdate;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.PeerGoodbye;
import dev.nodera.protocol.membership.PeerJoin;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.protocol.simulationmsg.CommitAnnounce;
import dev.nodera.protocol.simulationmsg.ResyncRequest;
import dev.nodera.protocol.simulationmsg.StreamChunk;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Append-only type-tag registry snapshot for {@link MessageCodec} (Task 4 acceptance #5:
 * "registry snapshot committed, like Task 2").
 *
 * <p>This test pins two things: (1) the numeric value of every tag (so a future renumbering
 * fails loudly here, not at the wire boundary), and (2) that {@link MessageCodec#decode(byte[])}
 * of a frame produced by {@link MessageCodec#encode(NoderaMessage)} returns an instance whose
 * runtime class is the same as the source — i.e. tags are distinct and dispatch is total over
 * the current {@code permits} list.
 *
 * <p>Thread-context: single test thread.
 */
final class MessageCodecTypeTagTest {

    @Test
    void everyAssignedTagMatchesItsExpectedConstantValue() {
        assertThat(MessageCodec.TAG_CLIENT_HELLO).isEqualTo(1);
        assertThat(MessageCodec.TAG_SERVER_HELLO).isEqualTo(2);
        assertThat(MessageCodec.TAG_CHALLENGE_RESPONSE).isEqualTo(3);
        assertThat(MessageCodec.TAG_WORKER_ACTIVATION).isEqualTo(4);
        assertThat(MessageCodec.TAG_REGION_ASSIGNED).isEqualTo(5);
        assertThat(MessageCodec.TAG_REGION_REVOKED).isEqualTo(6);
        assertThat(MessageCodec.TAG_LEASE_RENEWAL).isEqualTo(7);
        assertThat(MessageCodec.TAG_SNAPSHOT_ANNOUNCE).isEqualTo(8);
        assertThat(MessageCodec.TAG_STREAM_CHUNK).isEqualTo(9);
        assertThat(MessageCodec.TAG_ACTION_BATCH_MSG).isEqualTo(10);
        assertThat(MessageCodec.TAG_REGION_PROPOSAL).isEqualTo(11);
        assertThat(MessageCodec.TAG_VALIDATION_VOTE).isEqualTo(12);
        assertThat(MessageCodec.TAG_COMMIT_ANNOUNCE).isEqualTo(13);
        assertThat(MessageCodec.TAG_RESYNC_REQUEST).isEqualTo(14);
        assertThat(MessageCodec.TAG_HEARTBEAT).isEqualTo(15);
        assertThat(MessageCodec.TAG_WORKER_LOAD).isEqualTo(16);
        assertThat(MessageCodec.TAG_ECHO_TEST).isEqualTo(17);
        assertThat(MessageCodec.TAG_RELAY_ENVELOPE).isEqualTo(18);
        assertThat(MessageCodec.TAG_PEER_JOIN).isEqualTo(19);
        assertThat(MessageCodec.TAG_MEMBERSHIP_UPDATE).isEqualTo(20);
        assertThat(MessageCodec.TAG_PEER_GOODBYE).isEqualTo(21);
        assertThat(MessageCodec.TAG_GATEWAY_CLAIM).isEqualTo(22);
        assertThat(MessageCodec.TAG_SESSION_KEEP_ALIVE).isEqualTo(23);
        assertThat(MessageCodec.NEXT_TAG).isEqualTo(23);
    }

    @Test
    void typeTagOfMatchesConstantForEveryMessageType() {
        Map<Class<?>, Integer> expected = new LinkedHashMap<>();
        expected.put(ChallengeResponse.class, MessageCodec.TAG_CHALLENGE_RESPONSE);
        expected.put(WorkerActivation.class, MessageCodec.TAG_WORKER_ACTIVATION);
        expected.put(Heartbeat.class, MessageCodec.TAG_HEARTBEAT);
        expected.put(WorkerLoad.class, MessageCodec.TAG_WORKER_LOAD);
        expected.put(LeaseRenewal.class, MessageCodec.TAG_LEASE_RENEWAL);
        expected.put(RegionRevoked.class, MessageCodec.TAG_REGION_REVOKED);
        expected.put(ResyncRequest.class, MessageCodec.TAG_RESYNC_REQUEST);
        expected.put(CommitAnnounce.class, MessageCodec.TAG_COMMIT_ANNOUNCE);
        expected.put(StreamChunk.class, MessageCodec.TAG_STREAM_CHUNK);
        expected.put(RelayEnvelope.class, MessageCodec.TAG_RELAY_ENVELOPE);
        expected.put(EchoTest.class, MessageCodec.TAG_ECHO_TEST);
        expected.put(PeerJoin.class, MessageCodec.TAG_PEER_JOIN);
        expected.put(MembershipUpdate.class, MessageCodec.TAG_MEMBERSHIP_UPDATE);
        expected.put(PeerGoodbye.class, MessageCodec.TAG_PEER_GOODBYE);
        expected.put(GatewayClaim.class, MessageCodec.TAG_GATEWAY_CLAIM);
        expected.put(SessionKeepAlive.class, MessageCodec.TAG_SESSION_KEEP_ALIVE);

        for (Map.Entry<Class<?>, Integer> e : expected.entrySet()) {
            assertThat(MessageCodec.typeTagOf(sampleOf(e.getKey())))
                    .as("typeTagOf for %s", e.getKey().getSimpleName())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    void encodeThenDecodeReturnsSameClassForEveryMessageType() {
        Class<?>[] classes = {
                ChallengeResponse.class,
                WorkerActivation.class,
                Heartbeat.class,
                WorkerLoad.class,
                LeaseRenewal.class,
                RegionRevoked.class,
                ResyncRequest.class,
                CommitAnnounce.class,
                StreamChunk.class,
                RelayEnvelope.class,
                EchoTest.class,
                PeerJoin.class,
                MembershipUpdate.class,
                PeerGoodbye.class,
                GatewayClaim.class,
                SessionKeepAlive.class,
        };
        for (Class<?> cls : classes) {
            NoderaMessage original = sampleOf(cls);
            NoderaMessage decoded = MessageCodec.decode(MessageCodec.encode(original));
            assertThat(decoded.getClass())
                    .as("decode of encode(%s) must yield same class", cls.getSimpleName())
                    .isEqualTo(cls);
        }
    }

    @Test
    void allTagsAreDistinct() {
        int[] tags = {
                MessageCodec.TAG_CLIENT_HELLO,
                MessageCodec.TAG_SERVER_HELLO,
                MessageCodec.TAG_CHALLENGE_RESPONSE,
                MessageCodec.TAG_WORKER_ACTIVATION,
                MessageCodec.TAG_REGION_ASSIGNED,
                MessageCodec.TAG_REGION_REVOKED,
                MessageCodec.TAG_LEASE_RENEWAL,
                MessageCodec.TAG_SNAPSHOT_ANNOUNCE,
                MessageCodec.TAG_STREAM_CHUNK,
                MessageCodec.TAG_ACTION_BATCH_MSG,
                MessageCodec.TAG_REGION_PROPOSAL,
                MessageCodec.TAG_VALIDATION_VOTE,
                MessageCodec.TAG_COMMIT_ANNOUNCE,
                MessageCodec.TAG_RESYNC_REQUEST,
                MessageCodec.TAG_HEARTBEAT,
                MessageCodec.TAG_WORKER_LOAD,
                MessageCodec.TAG_ECHO_TEST,
                MessageCodec.TAG_RELAY_ENVELOPE,
                MessageCodec.TAG_PEER_JOIN,
                MessageCodec.TAG_MEMBERSHIP_UPDATE,
                MessageCodec.TAG_PEER_GOODBYE,
                MessageCodec.TAG_GATEWAY_CLAIM,
                MessageCodec.TAG_SESSION_KEEP_ALIVE,
        };
        long distinct = java.util.Arrays.stream(tags).distinct().count();
        assertThat(distinct).isEqualTo(tags.length);
    }

    private static NoderaMessage sampleOf(Class<?> cls) {
        NodeId nodeId = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        if (cls == ChallengeResponse.class) {
            return new ChallengeResponse(Bytes.fromHex("aabb"));
        }
        if (cls == WorkerActivation.class) {
            return new WorkerActivation(UUID.fromString("00000000-0000-0000-0000-000000000011"),
                    1, 2, 20L);
        }
        if (cls == Heartbeat.class) {
            return new Heartbeat(5L, new WorkerLoad(0, 0L, 0L));
        }
        if (cls == WorkerLoad.class) {
            return new WorkerLoad(7, 99L, 123L);
        }
        if (cls == LeaseRenewal.class) {
            return new LeaseRenewal(
                    new dev.nodera.core.region.RegionId(
                            dev.nodera.core.region.DimensionKey.overworld(), 0, 0),
                    dev.nodera.core.region.RegionEpoch.INITIAL, 200L);
        }
        if (cls == RegionRevoked.class) {
            return new RegionRevoked(
                    new dev.nodera.core.region.RegionId(
                            dev.nodera.core.region.DimensionKey.overworld(), 0, 0),
                    dev.nodera.core.region.RegionEpoch.INITIAL, "test");
        }
        if (cls == ResyncRequest.class) {
            return new ResyncRequest(
                    new dev.nodera.core.region.RegionId(
                            dev.nodera.core.region.DimensionKey.overworld(), 0, 0),
                    new dev.nodera.core.state.SnapshotVersion(0L));
        }
        if (cls == CommitAnnounce.class) {
            return new CommitAnnounce(
                    new dev.nodera.core.region.RegionId(
                            dev.nodera.core.region.DimensionKey.overworld(), 0, 0),
                    new dev.nodera.core.state.SnapshotVersion(1L),
                    dev.nodera.core.state.StateRoot.zero(),
                    Bytes.fromHex("00"));
        }
        if (cls == StreamChunk.class) {
            return new StreamChunk(42L, 0, 1, Bytes.fromHex("0102"));
        }
        if (cls == RelayEnvelope.class) {
            return new RelayEnvelope(nodeId, Bytes.fromHex("090a"));
        }
        if (cls == EchoTest.class) {
            return new EchoTest(Bytes.fromHex("deadbeef"));
        }
        if (cls == PeerJoin.class) {
            return new PeerJoin(nodeId, "127.0.0.1:25566", NodeCapabilities.initial(), true);
        }
        if (cls == MembershipUpdate.class) {
            return new MembershipUpdate(3L, nodeId, java.util.List.of(
                    new PeerEntry(nodeId, "127.0.0.1:25566", NodeCapabilities.initial(), true)));
        }
        if (cls == PeerGoodbye.class) {
            return new PeerGoodbye(nodeId, 4L, "transport-down");
        }
        if (cls == GatewayClaim.class) {
            return new GatewayClaim(nodeId, 5L);
        }
        if (cls == SessionKeepAlive.class) {
            return new SessionKeepAlive(nodeId, 9L);
        }
        throw new IllegalArgumentException("no sample for " + cls);
    }
}
