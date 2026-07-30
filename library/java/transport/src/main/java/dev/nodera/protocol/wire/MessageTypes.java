package dev.nodera.protocol.wire;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.discovery.InventoryAdvertisement;
import dev.nodera.protocol.membership.GatewayClaim;
import dev.nodera.protocol.membership.MembershipUpdate;
import dev.nodera.protocol.membership.PeerGoodbye;
import dev.nodera.protocol.membership.PeerJoin;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.protocol.simulationmsg.RegionRefusal;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static dev.nodera.protocol.wire.AuthPolicy.ADVISORY_RECHECK;
import static dev.nodera.protocol.wire.AuthPolicy.PUBLIC;
import static dev.nodera.protocol.wire.AuthPolicy.ROLE_AUTHORIZED;
import static dev.nodera.protocol.wire.AuthPolicy.SELF_AUTHENTICATED_COURIER;
import static dev.nodera.protocol.wire.AuthPolicy.TRANSPORT_SENDER_EQUALS;
import static dev.nodera.protocol.wire.MessageType.HandlerMode.BROADCAST;
import static dev.nodera.protocol.wire.MessageType.HandlerMode.EXCLUSIVE;

/**
 * The authorisation table: one row per kind, enforced by the router (Task 14 phase 5).
 *
 * <p>These are not new rules. They are the rules the handlers were <em>supposed</em> to be applying,
 * written down in one place where a missing one is visible. The table is total —
 * {@link #of(NoderaMessage)} fails for a kind with no row — so appending a message forces the
 * question "who may send this?" to be answered before it can travel.
 *
 * <p>Thread-context: immutable static state; any thread.
 */
public final class MessageTypes {

    private MessageTypes() {}

    private static final Map<Integer, MessageType> BY_KIND = new LinkedHashMap<>();

    private static void row(int kind, AuthPolicy policy,
                            Function<NoderaMessage, NodeId> senderField,
                            boolean expectsResponse, MessageType.HandlerMode mode) {
        MessageType type = new MessageType(WireRegistry.require(kind), policy, senderField,
                expectsResponse, mode);
        if (BY_KIND.put(kind, type) != null) {
            throw new IllegalStateException("kind " + kind + " has two descriptors");
        }
    }

    private static <T extends NoderaMessage> Function<NoderaMessage, NodeId> from(
            Class<T> type, Function<T, NodeId> field) {
        return msg -> type.isInstance(msg) ? field.apply(type.cast(msg)) : null;
    }

    static {
        // --- session control: anyone may open a conversation, and the handshake itself is what
        // establishes who they are. ---
        row(MessageCodec.TAG_CLIENT_HELLO, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_SERVER_HELLO, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_CHALLENGE_RESPONSE, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_WORKER_ACTIVATION, ROLE_AUTHORIZED, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_HELLO, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_HELLO_ACK, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_NACK, PUBLIC, null, false, BROADCAST);

        // --- region assignment: a lease is granted by a coordinator, not requested by its holder.
        row(MessageCodec.TAG_REGION_ASSIGNED, ROLE_AUTHORIZED, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_REGION_REVOKED, ROLE_AUTHORIZED, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_LEASE_RENEWAL, ROLE_AUTHORIZED, null, false, EXCLUSIVE);

        // --- simulation: every payload carries its own signature, so a relay is legitimate. ---
        row(MessageCodec.TAG_SNAPSHOT_ANNOUNCE, SELF_AUTHENTICATED_COURIER, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_STREAM_CHUNK, SELF_AUTHENTICATED_COURIER, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_ACTION_BATCH_MSG, SELF_AUTHENTICATED_COURIER, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_REGION_PROPOSAL, SELF_AUTHENTICATED_COURIER, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_VALIDATION_VOTE, SELF_AUTHENTICATED_COURIER, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_COMMIT_ANNOUNCE, SELF_AUTHENTICATED_COURIER, null, false, BROADCAST);
        row(MessageCodec.TAG_RESYNC_REQUEST, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_EXTERNAL_DELTA, SELF_AUTHENTICATED_COURIER, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_ENTITY_TRANSFER_PREPARE, SELF_AUTHENTICATED_COURIER, null, false,
                EXCLUSIVE);
        row(MessageCodec.TAG_ENTITY_TRANSFER_ACCEPT, SELF_AUTHENTICATED_COURIER, null, false,
                EXCLUSIVE);
        row(MessageCodec.TAG_ENTITY_TRANSFER_COMMIT, SELF_AUTHENTICATED_COURIER, null, false,
                EXCLUSIVE);
        row(MessageCodec.TAG_ACTION_FORWARD, SELF_AUTHENTICATED_COURIER, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_EVENT_SYNC_QUERY, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_EVENT_SYNC_ANSWER, SELF_AUTHENTICATED_COURIER, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_HALO_UPDATE, SELF_AUTHENTICATED_COURIER, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_GROUP_MIGRATION, ROLE_AUTHORIZED, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_GENESIS_APPROVAL_REQUEST, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_GENESIS_APPROVAL_GRANT, SELF_AUTHENTICATED_COURIER, null, false,
                EXCLUSIVE);

        // Advisory by construction: the recipient re-checks the condition against its own config
        // before revoking anything, so a lying peer costs it one re-examination.
        row(MessageCodec.TAG_REGION_REFUSAL, ADVISORY_RECHECK, null, false, EXCLUSIVE);

        // --- health + liveness ---
        row(MessageCodec.TAG_HEARTBEAT, PUBLIC, null, false, BROADCAST);
        row(MessageCodec.TAG_WORKER_LOAD, PUBLIC, null, false, BROADCAST);
        row(MessageCodec.TAG_ECHO_TEST, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_RELAY_ENVELOPE, PUBLIC, null, false, EXCLUSIVE);

        // --- membership. Every row here was accepted from any connected socket before this table
        // existed, which meant any peer could rewrite the mesh's idea of who the gateway was. ---
        row(MessageCodec.TAG_PEER_JOIN, TRANSPORT_SENDER_EQUALS,
                from(PeerJoin.class, PeerJoin::joiner), true, BROADCAST);
        row(MessageCodec.TAG_MEMBERSHIP_UPDATE, ROLE_AUTHORIZED, null, false, BROADCAST);
        row(MessageCodec.TAG_PEER_GOODBYE, TRANSPORT_SENDER_EQUALS,
                from(PeerGoodbye.class, PeerGoodbye::who), false, BROADCAST);
        row(MessageCodec.TAG_GATEWAY_CLAIM, TRANSPORT_SENDER_EQUALS,
                from(GatewayClaim.class, GatewayClaim::gatewayId), false, BROADCAST);
        row(MessageCodec.TAG_SESSION_KEEP_ALIVE, TRANSPORT_SENDER_EQUALS,
                from(SessionKeepAlive.class, SessionKeepAlive::from), false, BROADCAST);

        // --- content. A holder claiming to hold something must be the peer that connected. ---
        row(MessageCodec.TAG_CONTENT_REQUEST, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_CONTENT_CHUNK, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_CONTENT_AVAILABILITY, TRANSPORT_SENDER_EQUALS,
                from(ContentAvailability.class, ContentAvailability::holder), false, BROADCAST);
        row(MessageCodec.TAG_ARCHIVE_REPLICA_ASSIGNMENT, ROLE_AUTHORIZED, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_ARCHIVE_REPLICA_ACK, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_WORLD_MANIFEST_QUERY, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_WORLD_MANIFEST_ANSWER, PUBLIC, null, false, EXCLUSIVE);

        // --- discovery: public by design. A peer you have never met must be able to ask. ---
        row(MessageCodec.TAG_TRACKER_QUERY, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_TRACKER_RESPONSE, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_INVENTORY_ADVERTISEMENT, TRANSPORT_SENDER_EQUALS,
                from(InventoryAdvertisement.class, InventoryAdvertisement::holder), false,
                BROADCAST);
        row(MessageCodec.TAG_TRACKER_ANNOUNCE, SELF_AUTHENTICATED_COURIER, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_TRACKER_ANNOUNCE_ACK, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_TRACKER_CATALOG_QUERY, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_TRACKER_CATALOG_RESPONSE, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_TRACKER_ROUTES_QUERY, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_TRACKER_ROUTES_RESPONSE, PUBLIC, null, false, EXCLUSIVE);

        // --- rendezvous / relay: the records carry their own signatures. ---
        row(MessageCodec.TAG_RENDEZVOUS_REGISTER, SELF_AUTHENTICATED_COURIER, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_RENDEZVOUS_DISCOVER, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_RENDEZVOUS_PEERS, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_RELAY_RESERVE, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_RELAY_RESERVATION, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_RELAY_CONNECT, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_RELAY_INCOMING, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_PUNCH_SYNC, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_OBSERVED_ADDRESS, PUBLIC, null, false, EXCLUSIVE);

        // --- gossip: self-authenticating by design, so relaying is the point. ---
        row(MessageCodec.TAG_WORLD_GRANT_GOSSIP, SELF_AUTHENTICATED_COURIER, null, false, BROADCAST);
        row(MessageCodec.TAG_WORLD_OWNERSHIP_GOSSIP, SELF_AUTHENTICATED_COURIER, null, false,
                BROADCAST);
        row(MessageCodec.TAG_WORLD_DELETION_GOSSIP, SELF_AUTHENTICATED_COURIER, null, false,
                BROADCAST);

        // --- LAN tunnel: the session id is the capability. ---
        row(MessageCodec.TAG_TUNNEL_OPEN, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_TUNNEL_DATA, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_TUNNEL_CLOSE, PUBLIC, null, false, EXCLUSIVE);

        // --- service directory: signed records, public queries. ---
        row(MessageCodec.TAG_SERVICE_ANNOUNCE, SELF_AUTHENTICATED_COURIER, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_SERVICE_ANNOUNCE_ACK, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_SERVICE_DIRECTORY_QUERY, PUBLIC, null, true, EXCLUSIVE);
        row(MessageCodec.TAG_SERVICE_DIRECTORY_RESPONSE, PUBLIC, null, false, EXCLUSIVE);
        row(MessageCodec.TAG_SERVICE_SCORE_REPORT, SELF_AUTHENTICATED_COURIER, null, false,
                EXCLUSIVE);
        row(MessageCodec.TAG_SERVICE_DRAIN_NOTICE, SELF_AUTHENTICATED_COURIER, null, false,
                BROADCAST);

        // Totality: a kind with no row would reach the router with no stated policy, and the router
        // would have to invent one. Failing here means the question was never answered.
        for (WireKind k : WireRegistry.kinds()) {
            if (!BY_KIND.containsKey(k.kind())) {
                throw new IllegalStateException(k.name() + " has no authorisation policy; every "
                        + "kind must state who may send it before it can travel");
            }
        }
    }

    /** The descriptor for a kind. */
    public static MessageType of(int kind) {
        MessageType type = BY_KIND.get(kind);
        if (type == null) {
            throw new IllegalArgumentException("no descriptor for kind " + kind);
        }
        return type;
    }

    /** The descriptor for a message instance. */
    public static MessageType of(NoderaMessage msg) {
        return of(WireRegistry.kindOf(msg));
    }

    /** Every descriptor, in kind order. */
    public static List<MessageType> all() {
        return List.copyOf(BY_KIND.values());
    }

    /** How many kinds carry each policy — used by the table's own snapshot test. */
    public static Map<AuthPolicy, Integer> policyCounts() {
        Map<AuthPolicy, Integer> counts = new EnumMap<>(AuthPolicy.class);
        for (MessageType t : BY_KIND.values()) {
            counts.merge(t.authPolicy(), 1, Integer::sum);
        }
        return counts;
    }

    /** Referenced so the advisory row above cannot drift from the record it describes. */
    static final Class<?> ADVISORY_EXAMPLE = RegionRefusal.class;

    /** Referenced so the membership rows cannot drift from the records they describe. */
    static final Class<?> MEMBERSHIP_EXAMPLE = MembershipUpdate.class;
}
