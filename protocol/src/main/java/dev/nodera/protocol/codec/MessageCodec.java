package dev.nodera.protocol.codec;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.RelayEnvelope;
import dev.nodera.protocol.EchoTest;
import dev.nodera.protocol.assignment.LeaseRenewal;
import dev.nodera.protocol.assignment.RegionAssigned;
import dev.nodera.protocol.assignment.RegionRevoked;
import dev.nodera.protocol.content.ArchiveReplicaAck;
import dev.nodera.protocol.content.ArchiveReplicaAssignment;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.discovery.InventoryAdvertisement;
import dev.nodera.protocol.discovery.ManifestSeeders;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.handshake.ChallengeResponse;
import dev.nodera.protocol.handshake.ClientHello;
import dev.nodera.protocol.handshake.ServerHello;
import dev.nodera.protocol.handshake.WorkerActivation;
import dev.nodera.protocol.health.Heartbeat;
import dev.nodera.protocol.health.WorkerLoad;
import dev.nodera.protocol.membership.GatewayClaim;
import dev.nodera.protocol.membership.MembershipUpdate;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.PeerGoodbye;
import dev.nodera.protocol.membership.PeerJoin;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.protocol.simulationmsg.ActionBatchMsg;
import dev.nodera.protocol.simulationmsg.CommitAnnounce;
import dev.nodera.protocol.simulationmsg.RegionProposal;
import dev.nodera.protocol.simulationmsg.ResyncRequest;
import dev.nodera.protocol.simulationmsg.SnapshotAnnounce;
import dev.nodera.protocol.simulationmsg.StreamChunk;
import dev.nodera.protocol.simulationmsg.ValidationVote;

import java.util.UUID;

/**
 * The single canonical encoder/decoder for every {@link NoderaMessage} (Task 4 protocol).
 *
 * <p>There is exactly ONE encoding for any given message: the bytes produced here are used for
 * wire transport, hashing, and signing alike — the same discipline as core's
 * {@link Encodable}. Individual message records do not implement {@code Encodable}; this codec
 * owns the outer {@code u16 typeTag + u16 version + body} frame and composes core primitives
 * (and each nested {@code Encodable}'s own {@code encode}) for the body.
 *
 * <h2>Wire contract (FROZEN — append-only)</h2>
 *
 * <p>Every frame begins with a {@code u16 typeTag} (see the constants below) followed by a
 * {@code u16 version}. All tags except {@link SessionKeepAlive} use
 * {@value #ENCODING_VERSION}; tag 23 is emitted as
 * {@value #SESSION_KEEP_ALIVE_ENCODING_VERSION} and its decoder also accepts legacy version 1.
 * The body that follows is message-specific; it composes core's big-endian fixed-width primitives
 * (no varints, no floats in hashed state). Nested {@code Encodable} values (e.g. {@code NodeId},
 * {@code RegionId}, {@code StateRoot}, {@code SignedVote}, {@code ActionBatch}) are written via
 * their own {@code encode} so their bytes are identical on the wire and in any signed portion.
 *
 * <p><b>Tag assignment is permanent.</b> The constants below are a frozen wire contract:
 * assigning a tag is permanent; <b>never renumber or reuse an existing tag</b>. New message
 * types append both a new record to {@code NoderaMessage}'s {@code permits} list and a new tag
 * constant here, in numeric order. The registry snapshot is asserted by
 * {@code MessageCodecTypeTagTest} exactly as core's {@code TypeTags} is asserted in Task 2.
 *
 * <h2>Type-tag table</h2>
 * <pre>
 *  tag  message
 *  ---  ------------------------------------
 *   1   ClientHello
 *   2   ServerHello
 *   3   ChallengeResponse
 *   4   WorkerActivation
 *   5   RegionAssigned
 *   6   RegionRevoked
 *   7   LeaseRenewal
 *   8   SnapshotAnnounce
 *   9   StreamChunk
 *  10   ActionBatchMsg
 *  11   RegionProposal
 *  12   ValidationVote
 *  13   CommitAnnounce
 *  14   ResyncRequest
 *  15   Heartbeat
 *  16   WorkerLoad
 *  17   EchoTest
 *  18   RelayEnvelope
 *  19   PeerJoin
 *  20   MembershipUpdate
 *  21   PeerGoodbye
 *  22   GatewayClaim
 *  23   SessionKeepAlive
 *  24   ContentRequest
 *  25   ContentChunk
 *  26   ContentAvailability
 *  27   TrackerQuery
 *  28   TrackerResponse
 *  29   InventoryAdvertisement
 *  30   ArchiveReplicaAssignment
 *  31   ArchiveReplicaAck
 * </pre>
 *
 * <p>Thread-context: stateless; all methods are safe to call from any thread. Each call
 * allocates its own {@link CanonicalWriter} / {@link CanonicalReader}.
 */
public final class MessageCodec {

    private MessageCodec() {}

    /** Global encoding version used by every tag without a message-specific upgrade. */
    public static final int ENCODING_VERSION = 1;

    /** Version emitted for tag 23; its decoder also accepts legacy version 1 frames. */
    public static final int SESSION_KEEP_ALIVE_ENCODING_VERSION = 2;

    /** {@link ClientHello} tag. */ public static final int TAG_CLIENT_HELLO      = 1;
    /** {@link ServerHello} tag. */ public static final int TAG_SERVER_HELLO      = 2;
    /** {@link ChallengeResponse} tag. */ public static final int TAG_CHALLENGE_RESPONSE = 3;
    /** {@link WorkerActivation} tag. */ public static final int TAG_WORKER_ACTIVATION   = 4;
    /** {@link RegionAssigned} tag. */ public static final int TAG_REGION_ASSIGNED = 5;
    /** {@link RegionRevoked} tag. */ public static final int TAG_REGION_REVOKED  = 6;
    /** {@link LeaseRenewal} tag. */ public static final int TAG_LEASE_RENEWAL    = 7;
    /** {@link SnapshotAnnounce} tag. */ public static final int TAG_SNAPSHOT_ANNOUNCE = 8;
    /** {@link StreamChunk} tag. */ public static final int TAG_STREAM_CHUNK      = 9;
    /** {@link ActionBatchMsg} tag. */ public static final int TAG_ACTION_BATCH_MSG = 10;
    /** {@link RegionProposal} tag. */ public static final int TAG_REGION_PROPOSAL = 11;
    /** {@link ValidationVote} tag. */ public static final int TAG_VALIDATION_VOTE = 12;
    /** {@link CommitAnnounce} tag. */ public static final int TAG_COMMIT_ANNOUNCE = 13;
    /** {@link ResyncRequest} tag. */ public static final int TAG_RESYNC_REQUEST   = 14;
    /** {@link Heartbeat} tag. */ public static final int TAG_HEARTBEAT           = 15;
    /** {@link WorkerLoad} tag. */ public static final int TAG_WORKER_LOAD         = 16;
    /** {@link EchoTest} tag. */ public static final int TAG_ECHO_TEST            = 17;
    /** {@link RelayEnvelope} tag. */ public static final int TAG_RELAY_ENVELOPE  = 18;
    /** {@link PeerJoin} tag. */ public static final int TAG_PEER_JOIN            = 19;
    /** {@link MembershipUpdate} tag. */ public static final int TAG_MEMBERSHIP_UPDATE = 20;
    /** {@link PeerGoodbye} tag. */ public static final int TAG_PEER_GOODBYE       = 21;
    /** {@link GatewayClaim} tag. */ public static final int TAG_GATEWAY_CLAIM     = 22;
    /** {@link SessionKeepAlive} tag. */ public static final int TAG_SESSION_KEEP_ALIVE = 23;
    /** {@link ContentRequest} tag (Task 19). */ public static final int TAG_CONTENT_REQUEST = 24;
    /** {@link ContentChunk} tag (Task 19). */ public static final int TAG_CONTENT_CHUNK    = 25;
    /** {@link ContentAvailability} tag (Task 19). */
    public static final int TAG_CONTENT_AVAILABILITY = 26;
    /** {@link TrackerQuery} tag (Task 20). */ public static final int TAG_TRACKER_QUERY = 27;
    /** {@link TrackerResponse} tag (Task 20). */ public static final int TAG_TRACKER_RESPONSE = 28;
    /** {@link InventoryAdvertisement} tag (Task 20). */
    public static final int TAG_INVENTORY_ADVERTISEMENT = 29;
    /** {@link ArchiveReplicaAssignment} tag (Task 21). */
    public static final int TAG_ARCHIVE_REPLICA_ASSIGNMENT = 30;
    /** {@link ArchiveReplicaAck} tag (Task 21). */
    public static final int TAG_ARCHIVE_REPLICA_ACK = 31;

    /** Highest assigned tag; new tags start at {@code NEXT_TAG + 1}. Update when appending. */
    public static final int NEXT_TAG = 31;

    /**
     * The known type tags in ascending order (Task 18 telemetry). Append-only like the tag
     * constants: a new message type appends its tag here too. Used by diagnostics to enumerate
     * the per-type traffic breakdown so a type with zero traffic still appears in the table.
     *
     * @Thread-context any thread; immutable list.
     */
    public static final java.util.List<Integer> KNOWN_TAGS = java.util.List.of(
            TAG_CLIENT_HELLO, TAG_SERVER_HELLO, TAG_CHALLENGE_RESPONSE, TAG_WORKER_ACTIVATION,
            TAG_REGION_ASSIGNED, TAG_REGION_REVOKED, TAG_LEASE_RENEWAL, TAG_SNAPSHOT_ANNOUNCE,
            TAG_STREAM_CHUNK, TAG_ACTION_BATCH_MSG, TAG_REGION_PROPOSAL, TAG_VALIDATION_VOTE,
            TAG_COMMIT_ANNOUNCE, TAG_RESYNC_REQUEST, TAG_HEARTBEAT, TAG_WORKER_LOAD,
            TAG_ECHO_TEST, TAG_RELAY_ENVELOPE, TAG_PEER_JOIN, TAG_MEMBERSHIP_UPDATE,
            TAG_PEER_GOODBYE, TAG_GATEWAY_CLAIM, TAG_SESSION_KEEP_ALIVE,
            TAG_CONTENT_REQUEST, TAG_CONTENT_CHUNK, TAG_CONTENT_AVAILABILITY,
            TAG_TRACKER_QUERY, TAG_TRACKER_RESPONSE, TAG_INVENTORY_ADVERTISEMENT,
            TAG_ARCHIVE_REPLICA_ASSIGNMENT, TAG_ARCHIVE_REPLICA_ACK);

    /**
     * The stable display name of a message type tag (Task 18 telemetry) — the simple name of the
     * message record that the tag decodes to. Purely additive: a new tag adds a {@code case} here.
     * Used to label the per-type traffic breakdown in diagnostics views.
     *
     * @param tag a tag from the table above.
     * @return the display name (e.g. {@code "SessionKeepAlive"}).
     * @throws IllegalArgumentException if the tag is unknown.
     * @Thread-context any thread.
     */
    public static String typeName(int tag) {
        return switch (tag) {
            case TAG_CLIENT_HELLO -> "ClientHello";
            case TAG_SERVER_HELLO -> "ServerHello";
            case TAG_CHALLENGE_RESPONSE -> "ChallengeResponse";
            case TAG_WORKER_ACTIVATION -> "WorkerActivation";
            case TAG_REGION_ASSIGNED -> "RegionAssigned";
            case TAG_REGION_REVOKED -> "RegionRevoked";
            case TAG_LEASE_RENEWAL -> "LeaseRenewal";
            case TAG_SNAPSHOT_ANNOUNCE -> "SnapshotAnnounce";
            case TAG_STREAM_CHUNK -> "StreamChunk";
            case TAG_ACTION_BATCH_MSG -> "ActionBatchMsg";
            case TAG_REGION_PROPOSAL -> "RegionProposal";
            case TAG_VALIDATION_VOTE -> "ValidationVote";
            case TAG_COMMIT_ANNOUNCE -> "CommitAnnounce";
            case TAG_RESYNC_REQUEST -> "ResyncRequest";
            case TAG_HEARTBEAT -> "Heartbeat";
            case TAG_WORKER_LOAD -> "WorkerLoad";
            case TAG_ECHO_TEST -> "EchoTest";
            case TAG_RELAY_ENVELOPE -> "RelayEnvelope";
            case TAG_PEER_JOIN -> "PeerJoin";
            case TAG_MEMBERSHIP_UPDATE -> "MembershipUpdate";
            case TAG_PEER_GOODBYE -> "PeerGoodbye";
            case TAG_GATEWAY_CLAIM -> "GatewayClaim";
            case TAG_SESSION_KEEP_ALIVE -> "SessionKeepAlive";
            case TAG_CONTENT_REQUEST -> "ContentRequest";
            case TAG_CONTENT_CHUNK -> "ContentChunk";
            case TAG_CONTENT_AVAILABILITY -> "ContentAvailability";
            case TAG_TRACKER_QUERY -> "TrackerQuery";
            case TAG_TRACKER_RESPONSE -> "TrackerResponse";
            case TAG_INVENTORY_ADVERTISEMENT -> "InventoryAdvertisement";
            case TAG_ARCHIVE_REPLICA_ASSIGNMENT -> "ArchiveReplicaAssignment";
            case TAG_ARCHIVE_REPLICA_ACK -> "ArchiveReplicaAck";
            default -> throw new IllegalArgumentException("unknown message type tag: " + tag);
        };
    }

    /**
     * Canonical-encode {@code msg} into a fresh, caller-owned {@code byte[]}.
     *
     * @param msg the message to encode.
     * @return the canonical frame ({@code typeTag + version + body}).
     * @throws IllegalArgumentException if {@code msg} is null.
     * @Thread-context any thread; allocates a fresh {@link CanonicalWriter}.
     */
    public static byte[] encode(NoderaMessage msg) {
        if (msg == null) {
            throw new IllegalArgumentException("msg must not be null");
        }
        CanonicalWriter w = new CanonicalWriter();
        encodeInto(w, msg);
        return w.toByteArray();
    }

    /**
     * Canonical-encode {@code msg} and return the result as an immutable {@link Bytes}.
     *
     * @param msg the message to encode.
     * @return the canonical frame as {@link Bytes}.
     * @Thread-context any thread.
     */
    public static Bytes encodeBytes(NoderaMessage msg) {
        if (msg == null) {
            throw new IllegalArgumentException("msg must not be null");
        }
        CanonicalWriter w = new CanonicalWriter();
        encodeInto(w, msg);
        return w.toBytes();
    }

    /**
     * Decode a frame produced by {@link #encode(NoderaMessage)} back into the matching
     * {@link NoderaMessage} subtype.
     *
     * @param frame the canonical frame.
     * @return the reconstructed message.
     * @throws IllegalArgumentException if {@code frame} is null.
     * @throws IllegalStateException if the tag/version is unknown or the body is malformed.
     * @Thread-context any thread; allocates a fresh {@link CanonicalReader}.
     */
    public static NoderaMessage decode(byte[] frame) {
        if (frame == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        CanonicalReader r = new CanonicalReader(frame);
        int tag = r.readU16();
        int version = r.readU16();
        if (tag == TAG_SESSION_KEEP_ALIVE) {
            if (version != ENCODING_VERSION
                    && version != SESSION_KEEP_ALIVE_ENCODING_VERSION) {
                throw new IllegalStateException(
                        "unsupported SessionKeepAlive encoding version " + version);
            }
        } else if (version != ENCODING_VERSION) {
            throw new IllegalStateException("unsupported message encoding version " + version);
        }
        NoderaMessage msg = decodeBody(r, tag, version);
        // Reject frames with trailing bytes: a valid message followed by extra bytes must not
        // decode silently (append-only wire contract — an over-long frame is malformed, and
        // accepting it would let two distinct byte strings map to the same message, breaking the
        // hash/sign-over-canonical-bytes assumption).
        int trailing = r.available();
        if (trailing != 0) {
            throw new IllegalStateException(
                    "message frame has " + trailing + " unconsumed trailing byte(s) after tag " + tag);
        }
        return msg;
    }

    /**
     * The frozen type tag for the given message (for debug / audit / registry snapshots).
     *
     * @param msg the message.
     * @return the constant from the table above.
     * @Thread-context any thread.
     */
    public static int typeTagOf(NoderaMessage msg) {
        // Not an exhaustive sealed switch (see NoderaMessage Javadoc): the type is non-sealed
        // because its permitted records live in sibling subpackages of an unnamed module.
        // Exhaustiveness is enforced structurally by MessageCodecTypeTagTest instead.
        if (msg instanceof ClientHello) return TAG_CLIENT_HELLO;
        if (msg instanceof ServerHello) return TAG_SERVER_HELLO;
        if (msg instanceof ChallengeResponse) return TAG_CHALLENGE_RESPONSE;
        if (msg instanceof WorkerActivation) return TAG_WORKER_ACTIVATION;
        if (msg instanceof RegionAssigned) return TAG_REGION_ASSIGNED;
        if (msg instanceof RegionRevoked) return TAG_REGION_REVOKED;
        if (msg instanceof LeaseRenewal) return TAG_LEASE_RENEWAL;
        if (msg instanceof SnapshotAnnounce) return TAG_SNAPSHOT_ANNOUNCE;
        if (msg instanceof StreamChunk) return TAG_STREAM_CHUNK;
        if (msg instanceof ActionBatchMsg) return TAG_ACTION_BATCH_MSG;
        if (msg instanceof RegionProposal) return TAG_REGION_PROPOSAL;
        if (msg instanceof ValidationVote) return TAG_VALIDATION_VOTE;
        if (msg instanceof CommitAnnounce) return TAG_COMMIT_ANNOUNCE;
        if (msg instanceof ResyncRequest) return TAG_RESYNC_REQUEST;
        if (msg instanceof Heartbeat) return TAG_HEARTBEAT;
        if (msg instanceof WorkerLoad) return TAG_WORKER_LOAD;
        if (msg instanceof EchoTest) return TAG_ECHO_TEST;
        if (msg instanceof RelayEnvelope) return TAG_RELAY_ENVELOPE;
        if (msg instanceof PeerJoin) return TAG_PEER_JOIN;
        if (msg instanceof MembershipUpdate) return TAG_MEMBERSHIP_UPDATE;
        if (msg instanceof PeerGoodbye) return TAG_PEER_GOODBYE;
        if (msg instanceof GatewayClaim) return TAG_GATEWAY_CLAIM;
        if (msg instanceof SessionKeepAlive) return TAG_SESSION_KEEP_ALIVE;
        if (msg instanceof ContentRequest) return TAG_CONTENT_REQUEST;
        if (msg instanceof ContentChunk) return TAG_CONTENT_CHUNK;
        if (msg instanceof ContentAvailability) return TAG_CONTENT_AVAILABILITY;
        if (msg instanceof TrackerQuery) return TAG_TRACKER_QUERY;
        if (msg instanceof TrackerResponse) return TAG_TRACKER_RESPONSE;
        if (msg instanceof InventoryAdvertisement) return TAG_INVENTORY_ADVERTISEMENT;
        if (msg instanceof ArchiveReplicaAssignment) return TAG_ARCHIVE_REPLICA_ASSIGNMENT;
        if (msg instanceof ArchiveReplicaAck) return TAG_ARCHIVE_REPLICA_ACK;
        throw new IllegalStateException("unknown NoderaMessage subtype: " + msg.getClass());
    }

    private static void encodeInto(CanonicalWriter w, NoderaMessage msg) {
        switch (msg) {
            case ClientHello m -> {
                w.writeU16(TAG_CLIENT_HELLO).writeU16(ENCODING_VERSION);
                w.writeU32(Integer.toUnsignedLong(m.protocolVersion()));
                m.nodeId().encode(w);
                w.writeBytes(m.publicKey());
                m.capabilities().encode(w);
                w.writeU32(Integer.toUnsignedLong(m.rulesVersion()));
                w.writeU64(m.registryFingerprint());
                w.writeBytes(m.signature());
            }
            case ServerHello m -> {
                w.writeU16(TAG_SERVER_HELLO).writeU16(ENCODING_VERSION);
                writeUuid(w, m.networkId());
                w.writeU64(m.currentTick());
                w.writeU32(Integer.toUnsignedLong(m.regionSizeChunks()));
                w.writeU32(Integer.toUnsignedLong(m.requiredValidators()));
                w.writeBytes(m.challenge());
            }
            case ChallengeResponse m -> {
                w.writeU16(TAG_CHALLENGE_RESPONSE).writeU16(ENCODING_VERSION);
                w.writeBytes(m.signature());
            }
            case WorkerActivation m -> {
                w.writeU16(TAG_WORKER_ACTIVATION).writeU16(ENCODING_VERSION);
                writeUuid(w, m.sessionId());
                w.writeU32(Integer.toUnsignedLong(m.maxPrimary()));
                w.writeU32(Integer.toUnsignedLong(m.maxReplica()));
                w.writeU64(m.heartbeatTicks());
            }
            case RegionAssigned m -> {
                w.writeU16(TAG_REGION_ASSIGNED).writeU16(ENCODING_VERSION);
                m.region().encode(w);
                m.epoch().encode(w);
                m.role().encode(w);
                m.snapshotVersion().encode(w);
                w.writeU64(m.leaseExpiryTick());
                w.writeList(m.committee(), CanonicalWriter::writeEncodable);
            }
            case RegionRevoked m -> {
                w.writeU16(TAG_REGION_REVOKED).writeU16(ENCODING_VERSION);
                m.region().encode(w);
                m.epoch().encode(w);
                w.writeString(m.reason());
            }
            case LeaseRenewal m -> {
                w.writeU16(TAG_LEASE_RENEWAL).writeU16(ENCODING_VERSION);
                m.region().encode(w);
                m.epoch().encode(w);
                w.writeU64(m.newExpiryTick());
            }
            case SnapshotAnnounce m -> {
                w.writeU16(TAG_SNAPSHOT_ANNOUNCE).writeU16(ENCODING_VERSION);
                m.region().encode(w);
                m.version().encode(w);
                w.writeU32(Integer.toUnsignedLong(m.contentLength()));
                w.writeU32(Integer.toUnsignedLong(m.chunkCount()));
                m.root().encode(w);
            }
            case StreamChunk m -> {
                w.writeU16(TAG_STREAM_CHUNK).writeU16(ENCODING_VERSION);
                w.writeU64(m.streamId());
                w.writeU32(Integer.toUnsignedLong(m.index()));
                w.writeU32(Integer.toUnsignedLong(m.total()));
                w.writeBytes(m.payload());
            }
            case ActionBatchMsg m -> {
                w.writeU16(TAG_ACTION_BATCH_MSG).writeU16(ENCODING_VERSION);
                m.batch().encode(w);
            }
            case RegionProposal m -> {
                w.writeU16(TAG_REGION_PROPOSAL).writeU16(ENCODING_VERSION);
                m.region().encode(w);
                m.epoch().encode(w);
                m.baseVersion().encode(w);
                w.writeU64(m.tickFrom());
                w.writeU64(m.tickTo());
                m.prevRoot().encode(w);
                m.resultingRoot().encode(w);
                w.writeBytes(m.encodedDelta());
                w.writeBytes(m.proposerSig());
            }
            case ValidationVote m -> {
                w.writeU16(TAG_VALIDATION_VOTE).writeU16(ENCODING_VERSION);
                m.region().encode(w);
                m.epoch().encode(w);
                m.version().encode(w);
                m.vote().encode(w);
            }
            case CommitAnnounce m -> {
                w.writeU16(TAG_COMMIT_ANNOUNCE).writeU16(ENCODING_VERSION);
                m.region().encode(w);
                m.version().encode(w);
                m.resultingRoot().encode(w);
                w.writeBytes(m.certificateBytes());
            }
            case ResyncRequest m -> {
                w.writeU16(TAG_RESYNC_REQUEST).writeU16(ENCODING_VERSION);
                m.region().encode(w);
                m.haveVersion().encode(w);
            }
            case Heartbeat m -> {
                w.writeU16(TAG_HEARTBEAT).writeU16(ENCODING_VERSION);
                w.writeU64(m.tick());
                encodeWorkerLoadBody(w, m.load());
            }
            case WorkerLoad m -> {
                w.writeU16(TAG_WORKER_LOAD).writeU16(ENCODING_VERSION);
                encodeWorkerLoadBody(w, m);
            }
            case EchoTest m -> {
                w.writeU16(TAG_ECHO_TEST).writeU16(ENCODING_VERSION);
                w.writeBytes(m.payload());
            }
            case RelayEnvelope m -> {
                w.writeU16(TAG_RELAY_ENVELOPE).writeU16(ENCODING_VERSION);
                m.target().encode(w);
                w.writeBytes(m.innerFrame());
            }
            case PeerJoin m -> {
                w.writeU16(TAG_PEER_JOIN).writeU16(ENCODING_VERSION);
                m.joiner().encode(w);
                w.writeString(m.listenRoute());
                m.capabilities().encode(w);
                w.writeBoolean(m.bootstrap());
            }
            case MembershipUpdate m -> {
                w.writeU16(TAG_MEMBERSHIP_UPDATE).writeU16(ENCODING_VERSION);
                w.writeU64(m.epoch());
                m.gatewayId().encode(w);
                w.writeList(m.members(), MessageCodec::writePeerEntry);
            }
            case PeerGoodbye m -> {
                w.writeU16(TAG_PEER_GOODBYE).writeU16(ENCODING_VERSION);
                m.who().encode(w);
                w.writeU64(m.epoch());
                w.writeString(m.reason());
            }
            case GatewayClaim m -> {
                w.writeU16(TAG_GATEWAY_CLAIM).writeU16(ENCODING_VERSION);
                m.gatewayId().encode(w);
                w.writeU64(m.epoch());
            }
            case SessionKeepAlive m -> {
                w.writeU16(TAG_SESSION_KEEP_ALIVE)
                        .writeU16(SESSION_KEEP_ALIVE_ENCODING_VERSION);
                m.from().encode(w);
                w.writeU64(m.seq());
                // The record constructor rejects duplicate regions and sorts by RegionId, so this
                // list is canonical before it reaches the wire.
                w.writeList(m.regionProgress(), (ww, progress) -> {
                    progress.region().encode(ww);
                    progress.epoch().encode(ww);
                    progress.primary().encode(ww);
                    ww.writeU64(progress.lastAppliedTick());
                });
            }
            case ContentRequest m -> {
                w.writeU16(TAG_CONTENT_REQUEST).writeU16(ENCODING_VERSION);
                w.writeBytes(m.manifestRoot());
                // The record's compact constructor already de-duplicated and sorted the indexes,
                // so the encoded order is canonical without sorting again here.
                w.writeList(m.pieceIndexes(), (ww, i) -> ww.writeU32(Integer.toUnsignedLong(i)));
            }
            case ContentChunk m -> {
                w.writeU16(TAG_CONTENT_CHUNK).writeU16(ENCODING_VERSION);
                w.writeBytes(m.manifestRoot());
                w.writeU32(Integer.toUnsignedLong(m.index()));
                w.writeBytes(m.payload());
            }
            case ContentAvailability m -> {
                w.writeU16(TAG_CONTENT_AVAILABILITY).writeU16(ENCODING_VERSION);
                m.holder().encode(w);
                w.writeList(m.holdings(), (ww, h) -> {
                    ww.writeBytes(h.manifestRoot());
                    ww.writeBytes(h.pieceBitmap());
                });
            }
            case TrackerQuery m -> {
                w.writeU16(TAG_TRACKER_QUERY).writeU16(ENCODING_VERSION);
                w.writeBytes(m.genesisHash());
            }
            case TrackerResponse m -> {
                w.writeU16(TAG_TRACKER_RESPONSE).writeU16(ENCODING_VERSION);
                w.writeBytes(m.genesisHash());
                w.writeString(m.worldName());
                w.writeList(m.peers(), MessageCodec::writePeerEntry);
                w.writeList(m.seeders(), (ww, seeded) -> {
                    ww.writeBytes(seeded.manifestRoot());
                    ww.writeList(seeded.seeders(), CanonicalWriter::writeEncodable);
                });
                w.writeU64(m.worldPlayerCount());
                w.writeU64(m.storedChunks());
                w.writeU32(Integer.toUnsignedLong(m.reliabilityBps()));
                m.health().encode(w);
                w.writeU64(m.retentionDeadlineEpochMillis());
            }
            case InventoryAdvertisement m -> {
                w.writeU16(TAG_INVENTORY_ADVERTISEMENT).writeU16(ENCODING_VERSION);
                w.writeBytes(m.genesisHash());
                m.holder().encode(w);
                w.writeList(m.holdings(), (ww, h) -> {
                    ww.writeBytes(h.manifestRoot());
                    ww.writeBytes(h.pieceBitmap());
                });
            }
            case ArchiveReplicaAssignment m -> {
                w.writeU16(TAG_ARCHIVE_REPLICA_ASSIGNMENT).writeU16(ENCODING_VERSION);
                w.writeBytes(m.manifestRoot());
                m.assignee().encode(w);
                w.writeList(m.pieceIndexes(), (ww, i) -> ww.writeU32(Integer.toUnsignedLong(i)));
            }
            case ArchiveReplicaAck m -> {
                w.writeU16(TAG_ARCHIVE_REPLICA_ACK).writeU16(ENCODING_VERSION);
                w.writeBytes(m.manifestRoot());
                m.assignee().encode(w);
                w.writeList(m.pieceIndexes(), (ww, i) -> ww.writeU32(Integer.toUnsignedLong(i)));
            }
            default -> throw new IllegalStateException("unknown NoderaMessage subtype: " + msg.getClass());
        }
    }

    /**
     * Write a {@link WorkerLoad} body inline. Used both for the standalone
     * {@link WorkerLoad} message and for the {@link WorkerLoad} nested inside a
     * {@link Heartbeat}; the body layout is identical so the on-wire bytes of a load value are
     * stable regardless of how it is wrapped.
     */
    private static void encodeWorkerLoadBody(CanonicalWriter w, WorkerLoad load) {
        w.writeU32(Integer.toUnsignedLong(load.queueDepth()));
        w.writeU64(load.memBytes());
        w.writeU64(load.execNanos());
    }

    /**
     * Write a {@link PeerEntry} body inline (used inside {@link MembershipUpdate}'s member list).
     * Layout: {@code nodeId + route(string) + capabilities + bootstrap(bool)}. Kept as a private
     * helper — like {@link #encodeWorkerLoadBody} — so {@link PeerEntry} does not need its own
     * type tag; its bytes are only ever nested inside a tagged membership message.
     */
    private static void writePeerEntry(CanonicalWriter w, PeerEntry e) {
        e.nodeId().encode(w);
        w.writeString(e.route());
        e.capabilities().encode(w);
        w.writeBoolean(e.bootstrap());
    }

    /** Inverse of {@link #writePeerEntry}. */
    private static PeerEntry readPeerEntry(CanonicalReader r) {
        dev.nodera.core.identity.NodeId nodeId = dev.nodera.core.identity.NodeId.decode(r);
        String route = r.readString();
        dev.nodera.core.identity.NodeCapabilities capabilities =
                dev.nodera.core.identity.NodeCapabilities.decode(r);
        boolean bootstrap = r.readBoolean();
        return new PeerEntry(nodeId, route, capabilities, bootstrap);
    }

    private static void writeUuid(CanonicalWriter w, UUID uuid) {
        w.writeU64(uuid.getMostSignificantBits());
        w.writeU64(uuid.getLeastSignificantBits());
    }

    private static NoderaMessage decodeBody(CanonicalReader r, int tag, int encodingVersion) {
        return switch (tag) {
            case TAG_CLIENT_HELLO -> {
                int protocolVersion = (int) r.readU32();
                dev.nodera.core.identity.NodeId nodeId = dev.nodera.core.identity.NodeId.decode(r);
                Bytes publicKey = r.readBytesValue();
                dev.nodera.core.identity.NodeCapabilities capabilities =
                        dev.nodera.core.identity.NodeCapabilities.decode(r);
                int rulesVersion = (int) r.readU32();
                long fingerprint = r.readU64();
                Bytes signature = r.readBytesValue();
                yield new ClientHello(protocolVersion, nodeId, publicKey, capabilities,
                        rulesVersion, fingerprint, signature);
            }
            case TAG_SERVER_HELLO -> {
                UUID networkId = readUuid(r);
                long currentTick = r.readU64();
                int regionSizeChunks = (int) r.readU32();
                int requiredValidators = (int) r.readU32();
                Bytes challenge = r.readBytesValue();
                yield new ServerHello(networkId, currentTick, regionSizeChunks,
                        requiredValidators, challenge);
            }
            case TAG_CHALLENGE_RESPONSE -> {
                Bytes signature = r.readBytesValue();
                yield new ChallengeResponse(signature);
            }
            case TAG_WORKER_ACTIVATION -> {
                UUID sessionId = readUuid(r);
                int maxPrimary = (int) r.readU32();
                int maxReplica = (int) r.readU32();
                long heartbeatTicks = r.readU64();
                yield new WorkerActivation(sessionId, maxPrimary, maxReplica, heartbeatTicks);
            }
            case TAG_REGION_ASSIGNED -> {
                dev.nodera.core.region.RegionId region = dev.nodera.core.region.RegionId.decode(r);
                dev.nodera.core.region.RegionEpoch epoch = dev.nodera.core.region.RegionEpoch.decode(r);
                dev.nodera.core.region.RegionReplicaRole role =
                        dev.nodera.core.region.RegionReplicaRole.decode(r);
                dev.nodera.core.state.SnapshotVersion snapshotVersion =
                        dev.nodera.core.state.SnapshotVersion.decode(r);
                long leaseExpiryTick = r.readU64();
                java.util.List<dev.nodera.core.identity.NodeId> committee =
                        r.readList(dev.nodera.core.identity.NodeId::decode);
                yield new RegionAssigned(region, epoch, role, snapshotVersion,
                        leaseExpiryTick, committee);
            }
            case TAG_REGION_REVOKED -> {
                dev.nodera.core.region.RegionId region = dev.nodera.core.region.RegionId.decode(r);
                dev.nodera.core.region.RegionEpoch epoch = dev.nodera.core.region.RegionEpoch.decode(r);
                String reason = r.readString();
                yield new RegionRevoked(region, epoch, reason);
            }
            case TAG_LEASE_RENEWAL -> {
                dev.nodera.core.region.RegionId region = dev.nodera.core.region.RegionId.decode(r);
                dev.nodera.core.region.RegionEpoch epoch = dev.nodera.core.region.RegionEpoch.decode(r);
                long newExpiryTick = r.readU64();
                yield new LeaseRenewal(region, epoch, newExpiryTick);
            }
            case TAG_SNAPSHOT_ANNOUNCE -> {
                dev.nodera.core.region.RegionId region = dev.nodera.core.region.RegionId.decode(r);
                dev.nodera.core.state.SnapshotVersion version =
                        dev.nodera.core.state.SnapshotVersion.decode(r);
                int contentLength = (int) r.readU32();
                int chunkCount = (int) r.readU32();
                dev.nodera.core.state.StateRoot root = dev.nodera.core.state.StateRoot.decode(r);
                yield new SnapshotAnnounce(region, version, contentLength, chunkCount, root);
            }
            case TAG_STREAM_CHUNK -> {
                long streamId = r.readU64();
                int index = (int) r.readU32();
                int total = (int) r.readU32();
                Bytes payload = r.readBytesValue();
                yield new StreamChunk(streamId, index, total, payload);
            }
            case TAG_ACTION_BATCH_MSG -> {
                dev.nodera.core.action.ActionBatch batch = dev.nodera.core.action.ActionBatch.decode(r);
                yield new ActionBatchMsg(batch);
            }
            case TAG_REGION_PROPOSAL -> {
                dev.nodera.core.region.RegionId region = dev.nodera.core.region.RegionId.decode(r);
                dev.nodera.core.region.RegionEpoch epoch = dev.nodera.core.region.RegionEpoch.decode(r);
                dev.nodera.core.state.SnapshotVersion baseVersion =
                        dev.nodera.core.state.SnapshotVersion.decode(r);
                long tickFrom = r.readU64();
                long tickTo = r.readU64();
                dev.nodera.core.state.StateRoot prevRoot = dev.nodera.core.state.StateRoot.decode(r);
                dev.nodera.core.state.StateRoot resultingRoot = dev.nodera.core.state.StateRoot.decode(r);
                Bytes encodedDelta = r.readBytesValue();
                Bytes proposerSig = r.readBytesValue();
                yield new RegionProposal(region, epoch, baseVersion, tickFrom, tickTo,
                        prevRoot, resultingRoot, encodedDelta, proposerSig);
            }
            case TAG_VALIDATION_VOTE -> {
                dev.nodera.core.region.RegionId region = dev.nodera.core.region.RegionId.decode(r);
                dev.nodera.core.region.RegionEpoch epoch = dev.nodera.core.region.RegionEpoch.decode(r);
                dev.nodera.core.state.SnapshotVersion version =
                        dev.nodera.core.state.SnapshotVersion.decode(r);
                dev.nodera.core.consensuscert.SignedVote vote =
                        dev.nodera.core.consensuscert.SignedVote.decode(r);
                yield new ValidationVote(region, epoch, version, vote);
            }
            case TAG_COMMIT_ANNOUNCE -> {
                dev.nodera.core.region.RegionId region = dev.nodera.core.region.RegionId.decode(r);
                dev.nodera.core.state.SnapshotVersion version =
                        dev.nodera.core.state.SnapshotVersion.decode(r);
                dev.nodera.core.state.StateRoot resultingRoot =
                        dev.nodera.core.state.StateRoot.decode(r);
                Bytes certificateBytes = r.readBytesValue();
                yield new CommitAnnounce(region, version, resultingRoot, certificateBytes);
            }
            case TAG_RESYNC_REQUEST -> {
                dev.nodera.core.region.RegionId region = dev.nodera.core.region.RegionId.decode(r);
                dev.nodera.core.state.SnapshotVersion haveVersion =
                        dev.nodera.core.state.SnapshotVersion.decode(r);
                yield new ResyncRequest(region, haveVersion);
            }
            case TAG_HEARTBEAT -> {
                long tick = r.readU64();
                WorkerLoad load = decodeWorkerLoadBody(r);
                yield new Heartbeat(tick, load);
            }
            case TAG_WORKER_LOAD -> decodeWorkerLoadBody(r);
            case TAG_ECHO_TEST -> {
                Bytes payload = r.readBytesValue();
                yield new EchoTest(payload);
            }
            case TAG_RELAY_ENVELOPE -> {
                dev.nodera.core.identity.NodeId target = dev.nodera.core.identity.NodeId.decode(r);
                Bytes innerFrame = r.readBytesValue();
                yield new RelayEnvelope(target, innerFrame);
            }
            case TAG_PEER_JOIN -> {
                dev.nodera.core.identity.NodeId joiner = dev.nodera.core.identity.NodeId.decode(r);
                String listenRoute = r.readString();
                dev.nodera.core.identity.NodeCapabilities capabilities =
                        dev.nodera.core.identity.NodeCapabilities.decode(r);
                boolean bootstrap = r.readBoolean();
                yield new PeerJoin(joiner, listenRoute, capabilities, bootstrap);
            }
            case TAG_MEMBERSHIP_UPDATE -> {
                long epoch = r.readU64();
                dev.nodera.core.identity.NodeId gatewayId = dev.nodera.core.identity.NodeId.decode(r);
                java.util.List<PeerEntry> members = r.readList(MessageCodec::readPeerEntry);
                yield new MembershipUpdate(epoch, gatewayId, members);
            }
            case TAG_PEER_GOODBYE -> {
                dev.nodera.core.identity.NodeId who = dev.nodera.core.identity.NodeId.decode(r);
                long epoch = r.readU64();
                String reason = r.readString();
                yield new PeerGoodbye(who, epoch, reason);
            }
            case TAG_GATEWAY_CLAIM -> {
                dev.nodera.core.identity.NodeId gatewayId = dev.nodera.core.identity.NodeId.decode(r);
                long epoch = r.readU64();
                yield new GatewayClaim(gatewayId, epoch);
            }
            case TAG_SESSION_KEEP_ALIVE -> {
                dev.nodera.core.identity.NodeId from = dev.nodera.core.identity.NodeId.decode(r);
                long seq = r.readU64();
                if (encodingVersion == ENCODING_VERSION) {
                    // Legacy v1 ended after seq; expose the new field as an immutable empty list.
                    yield new SessionKeepAlive(from, seq);
                }
                java.util.List<RegionProgress> progress = r.readList(rr -> new RegionProgress(
                        dev.nodera.core.region.RegionId.decode(rr),
                        dev.nodera.core.region.RegionEpoch.decode(rr),
                        dev.nodera.core.identity.NodeId.decode(rr),
                        rr.readU64()));
                yield new SessionKeepAlive(from, seq, progress);
            }
            case TAG_CONTENT_REQUEST -> {
                Bytes manifestRoot = r.readBytesValue();
                java.util.List<Integer> indexes = r.readList(rr -> (int) rr.readU32());
                yield new ContentRequest(manifestRoot, indexes);
            }
            case TAG_CONTENT_CHUNK -> {
                Bytes manifestRoot = r.readBytesValue();
                int index = (int) r.readU32();
                Bytes payload = r.readBytesValue();
                yield new ContentChunk(manifestRoot, index, payload);
            }
            case TAG_CONTENT_AVAILABILITY -> {
                dev.nodera.core.identity.NodeId holder = dev.nodera.core.identity.NodeId.decode(r);
                java.util.List<ManifestHolding> holdings = r.readList(rr -> {
                    Bytes root = rr.readBytesValue();
                    Bytes bitmap = rr.readBytesValue();
                    return new ManifestHolding(root, bitmap);
                });
                yield new ContentAvailability(holder, holdings);
            }
            case TAG_TRACKER_QUERY -> new TrackerQuery(r.readBytesValue());
            case TAG_TRACKER_RESPONSE -> {
                Bytes genesisHash = r.readBytesValue();
                String worldName = r.readString();
                java.util.List<PeerEntry> peers = r.readList(MessageCodec::readPeerEntry);
                java.util.List<ManifestSeeders> seeders = r.readList(rr -> {
                    Bytes root = rr.readBytesValue();
                    java.util.List<dev.nodera.core.identity.NodeId> ids =
                            rr.readList(dev.nodera.core.identity.NodeId::decode);
                    return new ManifestSeeders(root, ids);
                });
                long playerCount = r.readU64();
                long storedChunks = r.readU64();
                int reliabilityBps = (int) r.readU32();
                dev.nodera.core.identity.WorldHealth health =
                        dev.nodera.core.identity.WorldHealth.decode(r);
                long retentionDeadline = r.readU64();
                yield new TrackerResponse(genesisHash, worldName, peers, seeders, playerCount,
                        storedChunks, reliabilityBps, health, retentionDeadline);
            }
            case TAG_INVENTORY_ADVERTISEMENT -> {
                Bytes genesisHash = r.readBytesValue();
                dev.nodera.core.identity.NodeId holder = dev.nodera.core.identity.NodeId.decode(r);
                java.util.List<ManifestHolding> holdings = r.readList(rr -> {
                    Bytes root = rr.readBytesValue();
                    Bytes bitmap = rr.readBytesValue();
                    return new ManifestHolding(root, bitmap);
                });
                yield new InventoryAdvertisement(genesisHash, holder, holdings);
            }
            case TAG_ARCHIVE_REPLICA_ASSIGNMENT -> {
                Bytes manifestRoot = r.readBytesValue();
                dev.nodera.core.identity.NodeId assignee = dev.nodera.core.identity.NodeId.decode(r);
                java.util.List<Integer> indexes = r.readList(rr -> (int) rr.readU32());
                yield new ArchiveReplicaAssignment(manifestRoot, assignee, indexes);
            }
            case TAG_ARCHIVE_REPLICA_ACK -> {
                Bytes manifestRoot = r.readBytesValue();
                dev.nodera.core.identity.NodeId assignee = dev.nodera.core.identity.NodeId.decode(r);
                java.util.List<Integer> indexes = r.readList(rr -> (int) rr.readU32());
                yield new ArchiveReplicaAck(manifestRoot, assignee, indexes);
            }
            default -> throw new IllegalStateException("unknown NoderaMessage typeTag: " + tag);
        };
    }

    private static WorkerLoad decodeWorkerLoadBody(CanonicalReader r) {
        int queueDepth = (int) r.readU32();
        long memBytes = r.readU64();
        long execNanos = r.readU64();
        return new WorkerLoad(queueDepth, memBytes, execNanos);
    }

    private static UUID readUuid(CanonicalReader r) {
        long msb = r.readU64();
        long lsb = r.readU64();
        return new UUID(msb, lsb);
    }
}
