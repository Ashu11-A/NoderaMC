package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.simulationmsg.ExternalDelta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(MessageCodec.TAG_CONTENT_REQUEST).isEqualTo(24);
        assertThat(MessageCodec.TAG_CONTENT_CHUNK).isEqualTo(25);
        assertThat(MessageCodec.TAG_CONTENT_AVAILABILITY).isEqualTo(26);
        assertThat(MessageCodec.TAG_TRACKER_QUERY).isEqualTo(27);
        assertThat(MessageCodec.TAG_TRACKER_RESPONSE).isEqualTo(28);
        assertThat(MessageCodec.TAG_INVENTORY_ADVERTISEMENT).isEqualTo(29);
        assertThat(MessageCodec.TAG_ARCHIVE_REPLICA_ASSIGNMENT).isEqualTo(30);
        assertThat(MessageCodec.TAG_ARCHIVE_REPLICA_ACK).isEqualTo(31);
        assertThat(MessageCodec.TAG_EXTERNAL_DELTA).isEqualTo(32);
        // Task 28 appended the tracker announce family; the Rust nodera-codec mirror pins the same
        // numbers (rust/nodera-codec/tests/tag_mirror.rs), so a one-sided change fails both builds.
        assertThat(MessageCodec.TAG_TRACKER_ANNOUNCE).isEqualTo(33);
        assertThat(MessageCodec.TAG_TRACKER_ANNOUNCE_ACK).isEqualTo(34);
        // Task 29 appended the rendezvous/relay family; the Rust nodera-codec mirror pins the same
        // numbers (rust/nodera-codec/tests/tag_mirror.rs), so a one-sided change fails both builds.
        assertThat(MessageCodec.TAG_RENDEZVOUS_REGISTER).isEqualTo(35);
        assertThat(MessageCodec.TAG_RENDEZVOUS_DISCOVER).isEqualTo(36);
        assertThat(MessageCodec.TAG_RENDEZVOUS_PEERS).isEqualTo(37);
        assertThat(MessageCodec.TAG_RELAY_RESERVE).isEqualTo(38);
        assertThat(MessageCodec.TAG_RELAY_RESERVATION).isEqualTo(39);
        assertThat(MessageCodec.TAG_RELAY_CONNECT).isEqualTo(40);
        assertThat(MessageCodec.TAG_RELAY_INCOMING).isEqualTo(41);
        assertThat(MessageCodec.TAG_PUNCH_SYNC).isEqualTo(42);
        assertThat(MessageCodec.TAG_OBSERVED_ADDRESS).isEqualTo(43);
        assertThat(MessageCodec.TAG_TRACKER_CATALOG_QUERY).isEqualTo(44);
        assertThat(MessageCodec.TAG_TRACKER_CATALOG_RESPONSE).isEqualTo(45);
        assertThat(MessageCodec.TAG_ENTITY_TRANSFER_PREPARE).isEqualTo(46);
        assertThat(MessageCodec.TAG_ENTITY_TRANSFER_ACCEPT).isEqualTo(47);
        assertThat(MessageCodec.TAG_ENTITY_TRANSFER_COMMIT).isEqualTo(48);
        assertThat(MessageCodec.TAG_TRACKER_ROUTES_QUERY).isEqualTo(49);
        assertThat(MessageCodec.TAG_TRACKER_ROUTES_RESPONSE).isEqualTo(50);
        // The world-continuity lane appended the manifest-exchange pair (peer↔peer archive fetch).
        assertThat(MessageCodec.TAG_WORLD_MANIFEST_QUERY).isEqualTo(51);
        assertThat(MessageCodec.TAG_WORLD_MANIFEST_ANSWER).isEqualTo(52);
        // The no-host submission path appended the action-forward message.
        assertThat(MessageCodec.TAG_ACTION_FORWARD).isEqualTo(53);
        assertThat(MessageCodec.TAG_EVENT_SYNC_QUERY).isEqualTo(54);
        assertThat(MessageCodec.TAG_EVENT_SYNC_ANSWER).isEqualTo(55);
        assertThat(MessageCodec.TAG_HALO_UPDATE).isEqualTo(56);
        assertThat(MessageCodec.TAG_GROUP_MIGRATION).isEqualTo(57);
        assertThat(MessageCodec.TAG_GENESIS_APPROVAL_REQUEST).isEqualTo(58);
        assertThat(MessageCodec.TAG_GENESIS_APPROVAL_GRANT).isEqualTo(59);
        assertThat(MessageCodec.TAG_WORLD_GRANT_GOSSIP).isEqualTo(60);
        assertThat(MessageCodec.TAG_REGION_REFUSAL).isEqualTo(61);
        // World ownership travels the same application lane the permission grants do.
        assertThat(MessageCodec.TAG_WORLD_OWNERSHIP_GOSSIP).isEqualTo(62);
        // The LAN-tunnel family — a guest's game client reaching a host's LAN world.
        assertThat(MessageCodec.TAG_TUNNEL_OPEN).isEqualTo(63);
        assertThat(MessageCodec.TAG_TUNNEL_DATA).isEqualTo(64);
        assertThat(MessageCodec.TAG_TUNNEL_CLOSE).isEqualTo(65);
        assertThat(MessageCodec.TAG_WORLD_DELETION_GOSSIP).isEqualTo(66);
        // The service-directory family: how a peer learns which rendezvous exist, how good each is,
        // and when one is leaving.
        assertThat(MessageCodec.TAG_SERVICE_ANNOUNCE).isEqualTo(67);
        assertThat(MessageCodec.TAG_SERVICE_ANNOUNCE_ACK).isEqualTo(68);
        assertThat(MessageCodec.TAG_SERVICE_DIRECTORY_QUERY).isEqualTo(69);
        assertThat(MessageCodec.TAG_SERVICE_DIRECTORY_RESPONSE).isEqualTo(70);
        assertThat(MessageCodec.TAG_SERVICE_SCORE_REPORT).isEqualTo(71);
        assertThat(MessageCodec.TAG_SERVICE_DRAIN_NOTICE).isEqualTo(72);
        assertThat(MessageCodec.TAG_NACK).isEqualTo(73);
        assertThat(MessageCodec.TAG_HELLO).isEqualTo(74);
        assertThat(MessageCodec.TAG_HELLO_ACK).isEqualTo(75);
        assertThat(MessageCodec.TAG_WORLD_REVIVAL_GOSSIP).isEqualTo(76);
        assertThat(MessageCodec.NEXT_TAG).isEqualTo(76);
    }

    @Test
    void typeTagOfMatchesItsRegistryTagForEveryMessageType() {
        MessageSamples.assertTotal();
        for (Map.Entry<Integer, NoderaMessage> entry : MessageSamples.byTag().entrySet()) {
            assertThat(MessageCodec.typeTagOf(entry.getValue()))
                    .as("typeTagOf for %s", MessageCodec.typeName(entry.getKey()))
                    .isEqualTo(entry.getKey());
        }
    }

    @Test
    void worldManifestMessagesRoundTrip() {
        var query = new dev.nodera.protocol.content.WorldManifestQuery(Bytes.fromHex("deadbeef"));
        assertThat(MessageCodec.decode(MessageCodec.encode(query))).isEqualTo(query);
        assertThat(MessageCodec.typeTagOf(query)).isEqualTo(MessageCodec.TAG_WORLD_MANIFEST_QUERY);

        var answer = new dev.nodera.protocol.content.WorldManifestAnswer(
                Bytes.fromHex("deadbeef"),
                java.util.List.of(Bytes.fromHex("0102"), Bytes.fromHex("aabbccdd")));
        assertThat(MessageCodec.decode(MessageCodec.encode(answer))).isEqualTo(answer);
        assertThat(MessageCodec.typeTagOf(answer)).isEqualTo(MessageCodec.TAG_WORLD_MANIFEST_ANSWER);

        var empty = new dev.nodera.protocol.content.WorldManifestAnswer(
                Bytes.fromHex("deadbeef"), java.util.List.of());
        assertThat(MessageCodec.decode(MessageCodec.encode(empty))).isEqualTo(empty);
    }

    @Test
    void trackerCatalogMessagesRoundTrip() {
        var query = new dev.nodera.protocol.discovery.TrackerCatalogQuery(25);
        assertThat(MessageCodec.decode(MessageCodec.encode(query))).isEqualTo(query);

        var entry = new dev.nodera.protocol.discovery.TrackerCatalogEntry(
                Bytes.fromHex("deadbeef"), "My World", 3, 4096, 9750,
                dev.nodera.core.identity.WorldHealth.HEALTHY, 0L);
        var response = new dev.nodera.protocol.discovery.TrackerCatalogResponse(
                java.util.List.of(entry));
        var decoded = MessageCodec.decode(MessageCodec.encode(response));
        assertThat(decoded).isEqualTo(response);
    }

    @Test
    void trackerRoutesMessagesRoundTrip() {
        var query = new dev.nodera.protocol.discovery.TrackerRoutesQuery(Bytes.fromHex("deadbeef"));
        assertThat(MessageCodec.decode(MessageCodec.encode(query))).isEqualTo(query);

        var response = new dev.nodera.protocol.discovery.TrackerRoutesResponse(
                Bytes.fromHex("deadbeef"),
                java.util.List.of(new dev.nodera.protocol.discovery.TrackerRoutesResponse.PeerRoutes(
                        new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000042")),
                        java.util.List.of("192.168.0.9:25566", "mc/192.168.0.9:25565"))));
        assertThat(MessageCodec.decode(MessageCodec.encode(response))).isEqualTo(response);
        assertThat(((dev.nodera.protocol.discovery.TrackerRoutesResponse)
                MessageCodec.decode(MessageCodec.encode(response)))
                .firstRouteWithPrefix("mc/")).contains("192.168.0.9:25565");
    }

    @Test
    void encodeThenDecodeReturnsSameClassForEveryMessageType() {
        // Every tag, not a hand-maintained subset: this test used to list 25 classes, which is how
        // a message reached production without its dispatch arm ever being exercised.
        MessageSamples.assertTotal();
        for (Map.Entry<Integer, NoderaMessage> entry : MessageSamples.byTag().entrySet()) {
            NoderaMessage original = entry.getValue();
            NoderaMessage decoded = MessageCodec.decode(MessageCodec.encode(original));
            assertThat(decoded.getClass())
                    .as("decode of encode(%s) must yield same class",
                            MessageCodec.typeName(entry.getKey()))
                    .isEqualTo(original.getClass());
            assertThat(decoded)
                    .as("%s must round-trip by value", MessageCodec.typeName(entry.getKey()))
                    .isEqualTo(original);
        }
    }

    @Test
    void typeNameAndKnownTagsCoverEveryAssignedTag() {
        assertThat(MessageCodec.KNOWN_TAGS).hasSize(MessageCodec.NEXT_TAG);
        assertThat(MessageCodec.KNOWN_TAGS).doesNotHaveDuplicates();
        for (int tag : MessageCodec.KNOWN_TAGS) {
            assertThat(MessageCodec.typeName(tag))
                    .as("typeName(%d)", tag)
                    .isNotNull()
                    .isNotEmpty();
        }
        assertThat(MessageCodec.typeName(MessageCodec.TAG_SESSION_KEEP_ALIVE)).isEqualTo("SessionKeepAlive");
        assertThatThrownBy(() -> MessageCodec.typeName(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageCodec.typeName(99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void externalDeltaRoundTripsAllFields() {
        ExternalDelta original =
                (ExternalDelta) MessageSamples.byTag().get(MessageCodec.TAG_EXTERNAL_DELTA);
        ExternalDelta decoded = (ExternalDelta) MessageCodec.decode(MessageCodec.encode(original));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void everyRegistryTagIsDistinctAndContiguous() {
        List<Integer> tags = new ArrayList<>(MessageCodec.KNOWN_TAGS);
        assertThat(tags).doesNotHaveDuplicates();
        assertThat(tags).hasSize(MessageCodec.NEXT_TAG);
        for (int expected = 1; expected <= MessageCodec.NEXT_TAG; expected++) {
            assertThat(tags).as("tag %d must be assigned", expected).contains(expected);
        }
    }
}
