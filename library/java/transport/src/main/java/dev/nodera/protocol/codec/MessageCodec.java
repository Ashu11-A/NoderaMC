package dev.nodera.protocol.codec;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.protocol.wire.WireKind;
import dev.nodera.protocol.wire.WireRegistry;

import java.util.List;

/**
 * The single canonical encoder/decoder for every {@link NoderaMessage} (Task 4 protocol).
 *
 * <p>There is exactly ONE encoding for any given message: the bytes produced here are used for
 * wire transport, hashing, and signing alike — the same discipline as core's
 * {@link Encodable}. Individual message records do not implement {@code Encodable}; this codec
 * owns the outer {@code u16 typeTag + u16 version + body} frame and composes core primitives
 * (and each nested {@code Encodable}'s own {@code encode}) for the body.
 *
 * <p>This class is the front door. The per-tag encoders and decoders live in {@link CodecRegistry},
 * one row each, encoder beside decoder — they used to be a chain of {@code instanceof} arms and a
 * {@code switch} seven hundred lines apart in this file, which is how a message could gain a field
 * on one side and not the other.
 *
 * <h2>Wire contract (FROZEN — append-only)</h2>
 *
 * <p>Every frame begins with a {@code u16 typeTag} (see the constants below) followed by a
 * {@code u16 version}. Most tags use {@value #ENCODING_VERSION}. Five carry a per-message body
 * version because a field was appended to them after they shipped, and tag 23
 * ({@link SessionKeepAlive}) is emitted and accepted at
 * {@value #SESSION_KEEP_ALIVE_ENCODING_VERSION} only. The body that follows is message-specific;
 * it composes core's big-endian fixed-width primitives (no varints, no floats in hashed state).
 * Nested {@code Encodable} values (e.g. {@code NodeId}, {@code RegionId}, {@code StateRoot},
 * {@code SignedVote}, {@code ActionBatch}) are written via their own {@code encode} so their bytes
 * are identical on the wire and in any signed portion.
 *
 * <p><b>Tag assignment is permanent.</b> The constants below are a frozen wire contract:
 * assigning a tag is permanent; <b>never renumber or reuse an existing tag</b>. New message
 * types append a row to {@link WireRegistry}, a codec row to {@link CodecRegistry}, and a tag
 * constant here, in numeric order. The registry snapshot is asserted by
 * {@code MessageCodecTypeTagTest} exactly as core's {@code TypeTags} is asserted in Task 2.
 *
 * <h2>Type-tag table</h2>
 *
 * <p>The names and numbers are {@link WireRegistry}'s, which is the one place a kind is declared;
 * the constants below are how Java code says a number without spelling it. The table is not
 * repeated here, because a second copy of it is a second thing to keep true.
 *
 * <p>Thread-context: stateless; all methods are safe to call from any thread. Each call
 * allocates its own {@link CanonicalWriter} / {@link CanonicalReader}.
 */
public final class MessageCodec {

    private MessageCodec() {}

    /** Global encoding version used by every tag without a message-specific upgrade. */
    public static final int ENCODING_VERSION = 1;

    /** The only version tag 23 is emitted or accepted at. */
    public static final int SESSION_KEEP_ALIVE_ENCODING_VERSION = 2;

    // The frozen tag numbers, in assignment order. A kind's NAME, its plane and the record it
    // decodes to live in WireRegistry — the one place a kind is declared — so they are not restated
    // here: these constants exist only so Java code can say a number without spelling it, and
    // CodecRegistry can say which codec answers to it.
    //
    // 1-4 handshake (superseded by the negotiated Hello/HelloAck pair) - 5-7 region assignment -
    // 8-14 simulation - 15-17 health and liveness - 18-23 relay and membership - 24-26 content
    // (Task 19) - 27-29 tracker and discovery (Task 20) - 30-31 archival replication (Task 21) -
    // 32 external delta (Task 11) - 33-34 tracker announce (Task 28) - 35-43 rendezvous and relay
    // (Task 29) - 44-45 tracker directory - 46-48 entity transfer (Task 12c) - 49-50 join-flow
    // routes - 51-52 world continuity - 53 no-host submission - 54-55 event sync (Task 9 / L-30) -
    // 56-57 border lane (Task 13) - 58-59 certified genesis (Task 16 / L-20) - 60 world grants
    // (issue #36 / L-54) - 61 region refusal (L-60) - 62 ownership - 63-65 the LAN tunnel, which
    // carries somebody else's protocol and is deliberately opaque here - 66 deletion and 76 its
    // undo - 67-72 the service directory - 73-75 session control, which is what tags 1-4 were
    // supposed to be and never became.
    public static final int TAG_CLIENT_HELLO = 1;
    public static final int TAG_SERVER_HELLO = 2;
    public static final int TAG_CHALLENGE_RESPONSE = 3;
    public static final int TAG_WORKER_ACTIVATION = 4;
    public static final int TAG_REGION_ASSIGNED = 5;
    public static final int TAG_REGION_REVOKED = 6;
    public static final int TAG_LEASE_RENEWAL = 7;
    public static final int TAG_SNAPSHOT_ANNOUNCE = 8;
    public static final int TAG_STREAM_CHUNK = 9;
    public static final int TAG_ACTION_BATCH_MSG = 10;
    public static final int TAG_REGION_PROPOSAL = 11;
    public static final int TAG_VALIDATION_VOTE = 12;
    public static final int TAG_COMMIT_ANNOUNCE = 13;
    public static final int TAG_RESYNC_REQUEST = 14;
    public static final int TAG_HEARTBEAT = 15;
    public static final int TAG_WORKER_LOAD = 16;
    public static final int TAG_ECHO_TEST = 17;
    public static final int TAG_RELAY_ENVELOPE = 18;
    public static final int TAG_PEER_JOIN = 19;
    public static final int TAG_MEMBERSHIP_UPDATE = 20;
    public static final int TAG_PEER_GOODBYE = 21;
    public static final int TAG_GATEWAY_CLAIM = 22;
    public static final int TAG_SESSION_KEEP_ALIVE = 23;
    public static final int TAG_CONTENT_REQUEST = 24;
    public static final int TAG_CONTENT_CHUNK = 25;
    public static final int TAG_CONTENT_AVAILABILITY = 26;
    public static final int TAG_TRACKER_QUERY = 27;
    public static final int TAG_TRACKER_RESPONSE = 28;
    public static final int TAG_INVENTORY_ADVERTISEMENT = 29;
    public static final int TAG_ARCHIVE_REPLICA_ASSIGNMENT = 30;
    public static final int TAG_ARCHIVE_REPLICA_ACK = 31;
    public static final int TAG_EXTERNAL_DELTA = 32;
    public static final int TAG_TRACKER_ANNOUNCE = 33;
    public static final int TAG_TRACKER_ANNOUNCE_ACK = 34;
    public static final int TAG_RENDEZVOUS_REGISTER = 35;
    public static final int TAG_RENDEZVOUS_DISCOVER = 36;
    public static final int TAG_RENDEZVOUS_PEERS = 37;
    public static final int TAG_RELAY_RESERVE = 38;
    public static final int TAG_RELAY_RESERVATION = 39;
    public static final int TAG_RELAY_CONNECT = 40;
    public static final int TAG_RELAY_INCOMING = 41;
    public static final int TAG_PUNCH_SYNC = 42;
    public static final int TAG_OBSERVED_ADDRESS = 43;
    public static final int TAG_TRACKER_CATALOG_QUERY = 44;
    public static final int TAG_TRACKER_CATALOG_RESPONSE = 45;
    public static final int TAG_ENTITY_TRANSFER_PREPARE = 46;
    public static final int TAG_ENTITY_TRANSFER_ACCEPT = 47;
    public static final int TAG_ENTITY_TRANSFER_COMMIT = 48;
    public static final int TAG_TRACKER_ROUTES_QUERY = 49;
    public static final int TAG_TRACKER_ROUTES_RESPONSE = 50;
    public static final int TAG_WORLD_MANIFEST_QUERY = 51;
    public static final int TAG_WORLD_MANIFEST_ANSWER = 52;
    public static final int TAG_ACTION_FORWARD = 53;
    public static final int TAG_EVENT_SYNC_QUERY = 54;
    public static final int TAG_EVENT_SYNC_ANSWER = 55;
    public static final int TAG_HALO_UPDATE = 56;
    public static final int TAG_GROUP_MIGRATION = 57;
    public static final int TAG_GENESIS_APPROVAL_REQUEST = 58;
    public static final int TAG_GENESIS_APPROVAL_GRANT = 59;
    public static final int TAG_WORLD_GRANT_GOSSIP = 60;
    public static final int TAG_REGION_REFUSAL = 61;
    public static final int TAG_WORLD_OWNERSHIP_GOSSIP = 62;
    public static final int TAG_TUNNEL_OPEN = 63;
    public static final int TAG_TUNNEL_DATA = 64;
    public static final int TAG_TUNNEL_CLOSE = 65;
    public static final int TAG_WORLD_DELETION_GOSSIP = 66;
    public static final int TAG_SERVICE_ANNOUNCE = 67;
    public static final int TAG_SERVICE_ANNOUNCE_ACK = 68;
    public static final int TAG_SERVICE_DIRECTORY_QUERY = 69;
    public static final int TAG_SERVICE_DIRECTORY_RESPONSE = 70;
    public static final int TAG_SERVICE_SCORE_REPORT = 71;
    public static final int TAG_SERVICE_DRAIN_NOTICE = 72;
    public static final int TAG_NACK = 73;
    public static final int TAG_HELLO = 74;
    public static final int TAG_HELLO_ACK = 75;
    public static final int TAG_WORLD_REVIVAL_GOSSIP = 76;

    /** Highest assigned tag; new tags start at {@code NEXT_TAG + 1}. Derived from the schema. */
    public static final int NEXT_TAG = WireRegistry.NEXT_KIND;

    /**
     * The known type tags in ascending order (Task 18 telemetry), used by diagnostics to enumerate
     * the per-type traffic breakdown so a type with zero traffic still appears in the table.
     *
     * <p><b>Derived, not maintained</b> (Task 14). This used to be a hand-written list appended in
     * parallel with the tag constants, the {@link #typeName} switch, and the Rust mirror — four
     * places to remember and four places to forget. It is now a view over
     * {@link WireRegistry#kinds()}, so a kind that exists is in it by construction.
     *
     * @Thread-context any thread; immutable list.
     */
    public static final List<Integer> KNOWN_TAGS =
            WireRegistry.kinds().stream().map(WireKind::kind).toList();

    /**
     * The stable display name of a message type tag (Task 18 telemetry) — the simple name of the
     * message record that the tag decodes to. Used to label the per-type traffic breakdown in
     * diagnostics views.
     *
     * @param tag a tag from the schema.
     * @return the display name (e.g. {@code "SessionKeepAlive"}).
     * @throws IllegalArgumentException if the tag is unknown.
     * @Thread-context any thread.
     */
    public static String typeName(int tag) {
        return WireRegistry.nameOf(tag);
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
        return writer(msg).toByteArray();
    }

    /**
     * Canonical-encode {@code msg} and return the result as an immutable {@link Bytes}.
     *
     * @param msg the message to encode.
     * @return the canonical frame as {@link Bytes}.
     * @Thread-context any thread.
     */
    public static Bytes encodeBytes(NoderaMessage msg) {
        return writer(msg).toBytes();
    }

    private static CanonicalWriter writer(NoderaMessage msg) {
        if (msg == null) {
            throw new IllegalArgumentException("msg must not be null");
        }
        CanonicalWriter w = new CanonicalWriter();
        CodecRegistry.encodeInto(w, msg);
        return w;
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
        // Each tag states the closed range of body versions it accepts, so this is one lookup
        // rather than the chain of per-tag checks it used to be.
        CodecRegistry.requireVersion(tag, version);
        NoderaMessage msg = CodecRegistry.decodeBody(r, tag, version);
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
        return WireRegistry.kindOf(msg);
    }
}
