package dev.nodera.protocol.codec;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionReplicaRole;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.EchoTest;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.RelayEnvelope;
import dev.nodera.protocol.assignment.LeaseRenewal;
import dev.nodera.protocol.assignment.RegionAssigned;
import dev.nodera.protocol.assignment.RegionRevoked;
import dev.nodera.protocol.content.ArchiveReplicaAck;
import dev.nodera.protocol.content.ArchiveReplicaAssignment;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.content.WorldManifestAnswer;
import dev.nodera.protocol.content.WorldManifestQuery;
import dev.nodera.protocol.discovery.AnnounceEvent;
import dev.nodera.protocol.discovery.InventoryAdvertisement;
import dev.nodera.protocol.discovery.ManifestSeeders;
import dev.nodera.protocol.discovery.TrackerAnnounce;
import dev.nodera.protocol.discovery.TrackerAnnounceAck;
import dev.nodera.protocol.discovery.TrackerCatalogEntry;
import dev.nodera.protocol.discovery.TrackerCatalogQuery;
import dev.nodera.protocol.discovery.TrackerCatalogResponse;
import dev.nodera.protocol.discovery.TrackerQuery;
import dev.nodera.protocol.discovery.TrackerResponse;
import dev.nodera.protocol.discovery.TrackerRoutesQuery;
import dev.nodera.protocol.discovery.TrackerRoutesResponse;
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
import dev.nodera.protocol.membership.WorldDeletionGossip;
import dev.nodera.protocol.membership.WorldGrantGossip;
import dev.nodera.protocol.membership.WorldOwnershipGossip;
import dev.nodera.protocol.membership.WorldRevivalGossip;
import dev.nodera.protocol.rendezvous.ObservedAddress;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import dev.nodera.protocol.rendezvous.PunchSync;
import dev.nodera.protocol.rendezvous.RelayConnect;
import dev.nodera.protocol.rendezvous.RelayIncoming;
import dev.nodera.protocol.rendezvous.RelayReservation;
import dev.nodera.protocol.rendezvous.RelayReserve;
import dev.nodera.protocol.rendezvous.RendezvousDiscover;
import dev.nodera.protocol.rendezvous.RendezvousPeers;
import dev.nodera.protocol.rendezvous.RendezvousRegister;
import dev.nodera.protocol.rendezvous.SignedPeerRecord;
import dev.nodera.protocol.rendezvous.SignedRecord;
import dev.nodera.protocol.service.ServiceAnnounce;
import dev.nodera.protocol.service.ServiceAnnounceAck;
import dev.nodera.protocol.service.ServiceDirectoryEntry;
import dev.nodera.protocol.service.ServiceDirectoryQuery;
import dev.nodera.protocol.service.ServiceDirectoryResponse;
import dev.nodera.protocol.service.ServiceDrainNotice;
import dev.nodera.protocol.service.ServiceKind;
import dev.nodera.protocol.service.ServiceObservation;
import dev.nodera.protocol.service.ServiceRecord;
import dev.nodera.protocol.service.ServiceScoreReport;
import dev.nodera.protocol.session.Hello;
import dev.nodera.protocol.session.HelloAck;
import dev.nodera.protocol.session.Nack;
import dev.nodera.protocol.simulationmsg.ActionBatchMsg;
import dev.nodera.protocol.simulationmsg.ActionForward;
import dev.nodera.protocol.simulationmsg.CommitAnnounce;
import dev.nodera.protocol.simulationmsg.EntityTransferAccept;
import dev.nodera.protocol.simulationmsg.EntityTransferCommit;
import dev.nodera.protocol.simulationmsg.EntityTransferPrepare;
import dev.nodera.protocol.simulationmsg.EventSyncAnswer;
import dev.nodera.protocol.simulationmsg.EventSyncQuery;
import dev.nodera.protocol.simulationmsg.ExternalDelta;
import dev.nodera.protocol.simulationmsg.GenesisApprovalGrant;
import dev.nodera.protocol.simulationmsg.GenesisApprovalRequest;
import dev.nodera.protocol.simulationmsg.GroupMigration;
import dev.nodera.protocol.simulationmsg.HaloUpdate;
import dev.nodera.protocol.simulationmsg.RegionProposal;
import dev.nodera.protocol.simulationmsg.RegionRefusal;
import dev.nodera.protocol.simulationmsg.ResyncRequest;
import dev.nodera.protocol.simulationmsg.SnapshotAnnounce;
import dev.nodera.protocol.simulationmsg.StreamChunk;
import dev.nodera.protocol.simulationmsg.ValidationVote;
import dev.nodera.protocol.tunnel.TunnelClose;
import dev.nodera.protocol.tunnel.TunnelData;
import dev.nodera.protocol.tunnel.TunnelOpen;
import dev.nodera.protocol.wire.WireKind;
import dev.nodera.protocol.wire.WireRegistry;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static dev.nodera.protocol.codec.MessageCodec.ENCODING_VERSION;
import static dev.nodera.protocol.codec.MessageCodec.SESSION_KEEP_ALIVE_ENCODING_VERSION;

/**
 * The strict canonical codec's dispatch table: one row per type tag, holding that tag's encoder and
 * its decoder <b>together</b> (Plan 11 phase 5, issue #214).
 *
 * <p>This replaces {@link MessageCodec}'s two hand-written dispatchers — a chain of
 * {@code instanceof} arms for encoding and a {@code switch} over the tag for decoding, which sat
 * seven hundred lines apart in the same file. That distance was the defect: a message could gain a
 * field on one side and not the other, or an encode arm and no decode arm, and nothing in the
 * language said so. Here the pair is one registration, exactly as
 * {@code InfrastructureCodec}'s {@code shape(...)} rows already do for the TLV plane.
 *
 * <h2>What this does not change</h2>
 *
 * <p><b>Not one byte.</b> A registry is a way of finding a codec, not a way of spelling a message.
 * Every row writes the same header and the same fields in the same order as the arm it replaced,
 * and {@code fixtures/wire/*.bin} is the proof: the golden corpus is unchanged by this refactor.
 *
 * <p><b>Tags are append-only and are never renumbered.</b> Assigning a tag is permanent; a
 * renumbering is a network split, not a refactor. {@link WireRegistry} remains the one place a kind
 * is declared, and the static initialiser below fails the build if a declared kind has no row here —
 * so a message cannot reach the network with no codec.
 *
 * <h2>Reading a row</h2>
 *
 * <p>A writer is one fluent chain in field order. A decoder is one constructor call whose arguments
 * are the reads, in the same field order — Java evaluates argument expressions strictly left to
 * right (JLS 15.12.4.2), which is what makes that legal, and it is the form this codec already used
 * for its shorter messages. Where a field is conditional on the body version, the row uses a block
 * and names the intermediate values.
 *
 * <h2>Body versions</h2>
 *
 * <p>Most tags are on the global {@link MessageCodec#ENCODING_VERSION}. Four carry a per-message
 * body version because a field was appended to them after they shipped, and one — tag 23 — now
 * accepts <em>only</em> version 2 (see {@link SessionKeepAlive}). Each row states the closed range
 * it accepts, which is why {@link MessageCodec#decode} no longer needs a hand-written chain of
 * per-tag version checks.
 *
 * <p>Thread-context: immutable static state, built once at class initialisation; any thread.
 */
final class CodecRegistry {

    private CodecRegistry() {}

    // ------------------------------------------------------------------ the table

    /** Writes a message's frame — header first, then its body — into the canonical sink. */
    @FunctionalInterface
    interface FrameWriter<T extends NoderaMessage> {
        void write(CanonicalWriter w, T msg);
    }

    /** Rebuilds a message from a body positioned just after the {@code tag + version} header. */
    @FunctionalInterface
    interface BodyReader<T extends NoderaMessage> {
        T read(CanonicalReader r, int version);
    }

    /**
     * One tag's codec.
     *
     * @param tag        the frozen wire tag.
     * @param type       the record it decodes to.
     * @param minVersion lowest body version this build accepts.
     * @param maxVersion highest body version this build accepts.
     * @param global     {@code true} when the tag has no per-message version of its own, which only
     *                   changes the wording of the rejection.
     * @param writer     writes the whole frame, header included.
     * @param reader     reads the body.
     */
    private record Entry<T extends NoderaMessage>(int tag, Class<T> type, int minVersion,
                                                  int maxVersion, boolean global,
                                                  FrameWriter<T> writer, BodyReader<T> reader) {

        /** Reject a version outside the closed range, in the wording this tag has always used. */
        void requireVersion(int version) {
            if (version < minVersion || version > maxVersion) {
                throw new IllegalStateException(global
                        ? "unsupported message encoding version " + version
                        : "unsupported " + type.getSimpleName() + " encoding version " + version);
            }
        }
    }

    private static final Map<Integer, Entry<?>> BY_TAG = new LinkedHashMap<>();
    private static final Map<Class<?>, Entry<?>> BY_TYPE = new LinkedHashMap<>();

    private static <T extends NoderaMessage> void put(Entry<T> entry) {
        WireKind row = WireRegistry.require(entry.tag());
        if (row.type() != entry.type()) {
            throw new IllegalStateException("tag " + entry.tag() + " is "
                    + row.type().getSimpleName() + " in the schema but a codec was registered for "
                    + entry.type().getSimpleName());
        }
        if (BY_TAG.put(entry.tag(), entry) != null) {
            throw new IllegalStateException("tag " + entry.tag() + " has two codecs");
        }
        BY_TYPE.put(entry.type(), entry);
    }

    /** A tag on the global encoding version, whose body never changed shape. */
    private static <T extends NoderaMessage> void kind(int tag, Class<T> type, FrameWriter<T> writer,
                                                       Function<CanonicalReader, T> reader) {
        put(new Entry<>(tag, type, ENCODING_VERSION, ENCODING_VERSION, true,
                (w, m) -> writer.write(w.writeU16(tag).writeU16(ENCODING_VERSION), m),
                (r, v) -> reader.apply(r)));
    }

    /**
     * A tag that carries its own body version, because a field was appended after it shipped. The
     * decoded version rides on the record and is written back out, which is what keeps a re-encode
     * byte-identical to the frame it came from.
     */
    private static <T extends NoderaMessage> void versioned(int tag, Class<T> type,
                                                            ToIntFunction<T> emitted, int min,
                                                            int max, FrameWriter<T> writer,
                                                            BodyReader<T> reader) {
        put(new Entry<>(tag, type, min, max, false,
                (w, m) -> writer.write(w.writeU16(tag).writeU16(emitted.applyAsInt(m)), m),
                reader));
    }

    /**
     * A tag whose record owns its own header, because its signature covers a prefix of the frame.
     * The codec must not spell those bytes a second time: the record's {@code writeSignedPortion}
     * is the single definition of where the signature starts.
     */
    private static <T extends NoderaMessage> void signed(int tag, Class<T> type,
                                                         FrameWriter<T> writer,
                                                         Function<CanonicalReader, T> reader) {
        put(new Entry<>(tag, type, ENCODING_VERSION, ENCODING_VERSION, true, writer,
                (r, v) -> reader.apply(r)));
    }

    /**
     * A world-gossip tag: a world id and one blob that proves itself. Four kinds share this shape —
     * grant, ownership, deletion and its undo — and differ only in what the blob means.
     */
    private static <T extends NoderaMessage> void gossip(int tag, Class<T> type,
                                                         Function<T, Bytes> worldId,
                                                         Function<T, Bytes> payload,
                                                         BiFunction<Bytes, Bytes, T> ctor) {
        kind(tag, type, (w, m) -> w.writeBytes(worldId.apply(m)).writeBytes(payload.apply(m)),
                r -> ctor.apply(r.readBytesValue(), r.readBytesValue()));
    }

    // ------------------------------------------------------------------ dispatch

    /** Encode {@code msg}'s complete canonical frame into {@code w}. */
    @SuppressWarnings("unchecked")
    static void encodeInto(CanonicalWriter w, NoderaMessage msg) {
        Entry<NoderaMessage> entry = (Entry<NoderaMessage>) BY_TYPE.get(msg.getClass());
        if (entry == null) {
            throw new IllegalStateException("unknown NoderaMessage subtype: " + msg.getClass());
        }
        entry.writer().write(w, msg);
    }

    /** Reject a version this build cannot read, before any body byte is consumed. */
    static void requireVersion(int tag, int version) {
        Entry<?> entry = BY_TAG.get(tag);
        if (entry == null) {
            // An unknown tag is still held to the global version, so a frame that is wrong in both
            // ways is reported exactly as it always was; the tag itself is reported by decodeBody,
            // which owns that wording.
            if (version != ENCODING_VERSION) {
                throw new IllegalStateException("unsupported message encoding version " + version);
            }
            return;
        }
        entry.requireVersion(version);
    }

    /** Decode the body of {@code tag}, positioned just after the header. */
    static NoderaMessage decodeBody(CanonicalReader r, int tag, int version) {
        Entry<?> entry = BY_TAG.get(tag);
        if (entry == null) {
            throw new IllegalStateException("unknown NoderaMessage typeTag: " + tag);
        }
        return entry.reader().read(r, version);
    }

    // ------------------------------------------------------------------ shared body pieces

    private static void writeUuid(CanonicalWriter w, UUID uuid) {
        w.writeU64(uuid.getMostSignificantBits()).writeU64(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(CanonicalReader r) {
        return new UUID(r.readU64(), r.readU64());
    }

    /** {@code u32} written from an {@code int} without sign extension, as every count here is. */
    private static long u32(int value) {
        return Integer.toUnsignedLong(value);
    }

    /**
     * A {@link WorkerLoad} body inline. Used both for the standalone {@link WorkerLoad} message and
     * for the one nested inside a {@link Heartbeat}; the layout is identical, so a load value's
     * bytes are the same regardless of how it is wrapped.
     */
    private static void writeWorkerLoad(CanonicalWriter w, WorkerLoad load) {
        w.writeU32(u32(load.queueDepth())).writeU64(load.memBytes()).writeU64(load.execNanos());
    }

    private static WorkerLoad readWorkerLoad(CanonicalReader r) {
        return new WorkerLoad(r.readU32AsInt(), r.readU64(), r.readU64());
    }

    /**
     * A {@link PeerEntry} in the <b>discovery</b> layout:
     * {@code nodeId + route(string) + capabilities + bootstrap(bool)}.
     *
     * <p><b>Frozen cross-language contract.</b> These bytes are nested inside
     * {@link TrackerResponse}, which the Rust {@code nodera-tracker} encodes and decodes. The peer's
     * {@code publicKey} is deliberately NOT written here: a discovery record says where a peer is,
     * not what it may sign for, and appending a field would silently desynchronise every deployed
     * Rust service. Membership carries the key instead — see {@link #MEMBER}.
     */
    private static final BiConsumer<CanonicalWriter, PeerEntry> PEER =
            (w, e) -> w.writeEncodable(e.nodeId()).writeString(e.route())
                    .writeEncodable(e.capabilities()).writeBoolean(e.bootstrap());

    /** Inverse of {@link #PEER}; yields an entry with no public key. */
    private static final Function<CanonicalReader, PeerEntry> PEER_R =
            r -> new PeerEntry(NodeId.decode(r), r.readString(), NodeCapabilities.decode(r),
                    r.readBoolean());

    /**
     * A {@link PeerEntry} in the <b>membership</b> layout: the discovery fields plus
     * {@code publicKey(bytes)} and {@code clientVersion(string)}. Used only by
     * {@link MembershipUpdate}, which is a Java-to-Java message the Rust services never parse — so
     * it is free to carry both.
     *
     * <p>That key is what lets a session member be given a committee seat: a headless peer must be
     * able to verify the primary's proposals and the hosting world must be able to verify that
     * peer's votes, and membership gossip is the only place both sides already meet. The client
     * agent rides alongside it for the same reason — membership is the one place the whole mesh
     * meets, so it is where "what software is that peer running" belongs. Unlike {@code PeerJoin}'s
     * trailing field, both are written unconditionally: entries are length-free elements of a list,
     * so a reader cannot use "bytes remain" to decide whether a field is present.
     */
    private static final BiConsumer<CanonicalWriter, PeerEntry> MEMBER = (w, e) -> {
        PEER.accept(w, e);
        w.writeBytes(e.publicKey()).writeString(e.clientVersion());
    };

    /** Inverse of {@link #MEMBER}. */
    private static final Function<CanonicalReader, PeerEntry> MEMBER_R = r -> {
        PeerEntry base = PEER_R.apply(r);
        return new PeerEntry(base.nodeId(), base.route(), base.capabilities(), base.bootstrap(),
                r.readBytesValue(), r.readString());
    };

    private static final BiConsumer<CanonicalWriter, ManifestHolding> HOLDING =
            (w, h) -> w.writeBytes(h.manifestRoot()).writeBytes(h.pieceBitmap());

    private static final Function<CanonicalReader, ManifestHolding> HOLDING_R =
            r -> new ManifestHolding(r.readBytesValue(), r.readBytesValue());

    private static final BiConsumer<CanonicalWriter, TrackerCatalogEntry> CATALOG =
            (w, e) -> w.writeBytes(e.genesisHash()).writeString(e.worldName())
                    .writeU64(e.worldPlayerCount()).writeU64(e.storedChunks())
                    .writeU32(u32(e.reliabilityBps())).writeEncodable(e.health())
                    .writeU64(e.retentionDeadlineEpochMillis());

    private static final Function<CanonicalReader, TrackerCatalogEntry> CATALOG_R =
            r -> new TrackerCatalogEntry(r.readBytesValue(), r.readString(), r.readU64(),
                    r.readU64(), r.readU32AsInt(), WorldHealth.decode(r), r.readU64());

    /**
     * A {@link SignedRecord}: the canonical {@link SignedPeerRecord} (its own frame) followed by the
     * signature over exactly those bytes. Used inside a {@link RendezvousRegister} and in each
     * element of a {@link RendezvousPeers} page, so the identical signature validates on both the
     * relay and every discovering peer.
     */
    private static final BiConsumer<CanonicalWriter, SignedRecord> RECORD =
            (w, s) -> w.writeEncodable(s.record()).writeBytes(s.signature());

    /** Inverse of {@link #RECORD}. */
    private static final Function<CanonicalReader, SignedRecord> RECORD_R =
            r -> new SignedRecord(SignedPeerRecord.decode(r), r.readBytesValue());

    /**
     * A piece index. The record constructors already de-duplicated and sorted, so the encoded order
     * is canonical without sorting again here.
     */
    private static final BiConsumer<CanonicalWriter, Integer> INDEX =
            (w, i) -> w.writeU32(u32(i));

    private static final BiConsumer<CanonicalWriter, RegionProgress> PROGRESS =
            (w, p) -> w.writeEncodable(p.region()).writeEncodable(p.epoch())
                    .writeEncodable(p.primary()).writeU64(p.lastAppliedTick());

    private static final Function<CanonicalReader, RegionProgress> PROGRESS_R =
            r -> new RegionProgress(RegionId.decode(r), RegionEpoch.decode(r), NodeId.decode(r),
                    r.readU64());

    /**
     * Read a piece-index list and require it to already be in canonical form: ascending, with no
     * duplicates.
     *
     * <p>The record constructors sort and de-duplicate what they are given, which is right for
     * callers and wrong for the wire — it means {@code [3,0,3]} and {@code [0,3]} are two byte
     * strings that decode to the same value, contradicting the one-encoding-per-message contract.
     * Encoders always emit the canonical order, so requiring it costs valid traffic nothing.
     *
     * @throws IllegalStateException if the list is not strictly ascending.
     */
    private static List<Integer> readIndexes(CanonicalReader r) {
        List<Integer> indexes = r.readList(CanonicalReader::readU32AsInt);
        for (int i = 1; i < indexes.size(); i++) {
            if (indexes.get(i) <= indexes.get(i - 1)) {
                throw new IllegalStateException(
                        "piece indexes must be strictly ascending; got " + indexes.get(i - 1)
                                + " then " + indexes.get(i));
            }
        }
        return indexes;
    }

    /** A feature set is a strictly ascending code list on the wire, like every other set here. */
    private static Set<Integer> readFeatures(CanonicalReader r) {
        return new LinkedHashSet<>(readIndexes(r));
    }

    // ------------------------------------------------------------------ the rows

    static {
        // --- handshake ---------------------------------------------------------------------
        kind(MessageCodec.TAG_CLIENT_HELLO, ClientHello.class,
                (w, m) -> w.writeU32(u32(m.protocolVersion())).writeEncodable(m.nodeId())
                        .writeBytes(m.publicKey()).writeEncodable(m.capabilities())
                        .writeU32(u32(m.rulesVersion())).writeU64(m.registryFingerprint())
                        .writeBytes(m.signature()),
                r -> new ClientHello(r.readU32AsInt(), NodeId.decode(r), r.readBytesValue(),
                        NodeCapabilities.decode(r), r.readU32AsInt(), r.readU64(),
                        r.readBytesValue()));
        kind(MessageCodec.TAG_SERVER_HELLO, ServerHello.class,
                (w, m) -> {
                    writeUuid(w, m.networkId());
                    w.writeU64(m.currentTick()).writeU32(u32(m.regionSizeChunks()))
                            .writeU32(u32(m.requiredValidators())).writeBytes(m.challenge());
                },
                r -> new ServerHello(readUuid(r), r.readU64(), r.readU32AsInt(), r.readU32AsInt(),
                        r.readBytesValue()));
        kind(MessageCodec.TAG_CHALLENGE_RESPONSE, ChallengeResponse.class,
                (w, m) -> w.writeBytes(m.signature()),
                r -> new ChallengeResponse(r.readBytesValue()));
        kind(MessageCodec.TAG_WORKER_ACTIVATION, WorkerActivation.class,
                (w, m) -> {
                    writeUuid(w, m.sessionId());
                    w.writeU32(u32(m.maxPrimary())).writeU32(u32(m.maxReplica()))
                            .writeU64(m.heartbeatTicks());
                },
                r -> new WorkerActivation(readUuid(r), r.readU32AsInt(), r.readU32AsInt(),
                        r.readU64()));

        // --- region assignment. Per-message body version, retrofitted: this kind hardcoded the
        // global one, so there was no way to append a field. ---
        versioned(MessageCodec.TAG_REGION_ASSIGNED, RegionAssigned.class,
                RegionAssigned::bodyVersion, ENCODING_VERSION, RegionAssigned.V2_BASE_INDEX,
                (w, m) -> {
                    w.writeEncodable(m.region()).writeEncodable(m.epoch()).writeEncodable(m.role())
                            .writeEncodable(m.snapshotVersion()).writeU64(m.leaseExpiryTick())
                            .writeList(m.committee(), CanonicalWriter::writeEncodable);
                    if (m.bodyVersion() >= RegionAssigned.V2_BASE_INDEX) {
                        w.writeBytes(m.baseIndexRoot());
                    }
                },
                (r, v) -> {
                    RegionId region = RegionId.decode(r);
                    RegionEpoch epoch = RegionEpoch.decode(r);
                    RegionReplicaRole role = RegionReplicaRole.decode(r);
                    SnapshotVersion snapshot = SnapshotVersion.decode(r);
                    long leaseExpiryTick = r.readU64();
                    List<NodeId> committee = r.readList(NodeId::decode);
                    Bytes base = v >= RegionAssigned.V2_BASE_INDEX ? r.readBytesValue() : null;
                    return new RegionAssigned(region, epoch, role, snapshot, leaseExpiryTick,
                            committee, base, v);
                });
        kind(MessageCodec.TAG_REGION_REVOKED, RegionRevoked.class,
                (w, m) -> w.writeEncodable(m.region()).writeEncodable(m.epoch())
                        .writeString(m.reason()),
                r -> new RegionRevoked(RegionId.decode(r), RegionEpoch.decode(r), r.readString()));
        kind(MessageCodec.TAG_LEASE_RENEWAL, LeaseRenewal.class,
                (w, m) -> w.writeEncodable(m.region()).writeEncodable(m.epoch())
                        .writeU64(m.newExpiryTick()),
                r -> new LeaseRenewal(RegionId.decode(r), RegionEpoch.decode(r), r.readU64()));

        // --- simulation --------------------------------------------------------------------
        kind(MessageCodec.TAG_SNAPSHOT_ANNOUNCE, SnapshotAnnounce.class,
                (w, m) -> w.writeEncodable(m.region()).writeEncodable(m.version())
                        .writeU32(u32(m.contentLength())).writeU32(u32(m.chunkCount()))
                        .writeEncodable(m.root()),
                r -> new SnapshotAnnounce(RegionId.decode(r), SnapshotVersion.decode(r),
                        r.readU32AsInt(), r.readU32AsInt(), StateRoot.decode(r)));
        kind(MessageCodec.TAG_STREAM_CHUNK, StreamChunk.class,
                (w, m) -> w.writeU64(m.streamId()).writeU32(u32(m.index()))
                        .writeU32(u32(m.total())).writeBytes(m.payload()),
                r -> new StreamChunk(r.readU64(), r.readU32AsInt(), r.readU32AsInt(),
                        r.readBytesValue()));
        kind(MessageCodec.TAG_ACTION_BATCH_MSG, ActionBatchMsg.class,
                (w, m) -> w.writeEncodable(m.batch()),
                r -> new ActionBatchMsg(ActionBatch.decode(r)));
        versioned(MessageCodec.TAG_REGION_PROPOSAL, RegionProposal.class,
                RegionProposal::bodyVersion, ENCODING_VERSION,
                RegionProposal.PROPOSAL_ENCODING_VERSION,
                (w, m) -> {
                    w.writeEncodable(m.region()).writeEncodable(m.epoch())
                            .writeEncodable(m.baseVersion()).writeU64(m.tickFrom())
                            .writeU64(m.tickTo()).writeEncodable(m.prevRoot())
                            .writeEncodable(m.resultingRoot()).writeBytes(m.encodedDelta());
                    if (m.bodyVersion() >= 3) {
                        w.writeEncodable(m.batchRoot());
                    }
                    if (m.bodyVersion() >= 4) {
                        w.writeList(m.haloPins(), (ww, pin) ->
                                ww.writeEncodable(pin.source()).writeEncodable(pin.version()));
                    }
                    w.writeBytes(m.proposerSig());
                },
                (r, v) -> {
                    RegionId region = RegionId.decode(r);
                    RegionEpoch epoch = RegionEpoch.decode(r);
                    SnapshotVersion baseVersion = SnapshotVersion.decode(r);
                    long tickFrom = r.readU64();
                    long tickTo = r.readU64();
                    StateRoot prevRoot = StateRoot.decode(r);
                    StateRoot resultingRoot = StateRoot.decode(r);
                    Bytes encodedDelta = r.readBytesValue();
                    StateRoot batchRoot = v >= 3 ? StateRoot.decode(r) : null;
                    List<RegionProposal.HaloPin> pins = v >= 4
                            ? r.readList(rr -> new RegionProposal.HaloPin(RegionId.decode(rr),
                                    SnapshotVersion.decode(rr)))
                            : null;
                    return new RegionProposal(region, epoch, baseVersion, tickFrom, tickTo, prevRoot,
                            resultingRoot, encodedDelta, batchRoot, r.readBytesValue(), v, pins);
                });
        kind(MessageCodec.TAG_VALIDATION_VOTE, ValidationVote.class,
                (w, m) -> w.writeEncodable(m.region()).writeEncodable(m.epoch())
                        .writeEncodable(m.version()).writeEncodable(m.vote()),
                r -> new ValidationVote(RegionId.decode(r), RegionEpoch.decode(r),
                        SnapshotVersion.decode(r), SignedVote.decode(r)));
        kind(MessageCodec.TAG_COMMIT_ANNOUNCE, CommitAnnounce.class,
                (w, m) -> w.writeEncodable(m.region()).writeEncodable(m.version())
                        .writeEncodable(m.resultingRoot()).writeBytes(m.certificateBytes()),
                r -> new CommitAnnounce(RegionId.decode(r), SnapshotVersion.decode(r),
                        StateRoot.decode(r), r.readBytesValue()));
        kind(MessageCodec.TAG_RESYNC_REQUEST, ResyncRequest.class,
                (w, m) -> w.writeEncodable(m.region()).writeEncodable(m.haveVersion()),
                r -> new ResyncRequest(RegionId.decode(r), SnapshotVersion.decode(r)));

        // --- health + liveness -------------------------------------------------------------
        kind(MessageCodec.TAG_HEARTBEAT, Heartbeat.class,
                (w, m) -> writeWorkerLoad(w.writeU64(m.tick()), m.load()),
                r -> new Heartbeat(r.readU64(), readWorkerLoad(r)));
        kind(MessageCodec.TAG_WORKER_LOAD, WorkerLoad.class,
                CodecRegistry::writeWorkerLoad, CodecRegistry::readWorkerLoad);
        kind(MessageCodec.TAG_ECHO_TEST, EchoTest.class,
                (w, m) -> w.writeBytes(m.payload()),
                r -> new EchoTest(r.readBytesValue()));
        kind(MessageCodec.TAG_RELAY_ENVELOPE, RelayEnvelope.class,
                (w, m) -> w.writeEncodable(m.target()).writeBytes(m.innerFrame()),
                r -> new RelayEnvelope(NodeId.decode(r), r.readBytesValue()));

        // --- membership --------------------------------------------------------------------
        kind(MessageCodec.TAG_PEER_JOIN, PeerJoin.class,
                (w, m) -> w.writeEncodable(m.joiner()).writeString(m.listenRoute())
                        .writeEncodable(m.capabilities()).writeBoolean(m.bootstrap())
                        .writeBytes(m.publicKey()).writeString(m.clientVersion()),
                r -> {
                    NodeId joiner = NodeId.decode(r);
                    String listenRoute = r.readString();
                    NodeCapabilities capabilities = NodeCapabilities.decode(r);
                    boolean bootstrap = r.readBoolean();
                    Bytes joinerKey = r.readBytesValue();
                    // Trailing, tolerantly-read field: a peer built before the client-agent field
                    // simply announces without one, and joins exactly as it did before.
                    //
                    // This is the construct `CanonicalMutationFuzzTest`'s DOCUMENTED_EXCEPTIONS
                    // Javadoc describes: a positional body with an optional tail makes no statement
                    // of its own length, so a PeerJoin cut just before this field is byte-for-byte a
                    // valid older PeerJoin and no decoder can tell the two apart. `MessageCodec`'s
                    // trailing-byte rejection does not help — such a frame has no trailing bytes.
                    //
                    // It is left exactly as it is because the ambiguity is not reachable. PeerJoin
                    // is kind 19 on the INFRASTRUCTURE plane, so every frame off the network is
                    // decoded by `InfrastructureCodec`'s TLV shape (id 6, present or absent, and a
                    // truncated body fails). The only production callers of `MessageCodec.decode`
                    // are `WireCodec.decodeFrame` for CONSENSUS-plane kinds — which 19 is not — and
                    // `InfrastructureCodec.wholeSignedFrame`, which is used for kinds 33 and 71 only
                    // and type-checks its result. Making this strict would change no byte any peer
                    // sends and could only break the legacy frames still pinned in the fixtures.
                    String clientVersion = r.available() > 0 ? r.readString() : "";
                    return new PeerJoin(joiner, listenRoute, capabilities, bootstrap, joinerKey,
                            clientVersion);
                });
        kind(MessageCodec.TAG_MEMBERSHIP_UPDATE, MembershipUpdate.class,
                (w, m) -> w.writeU64(m.epoch()).writeEncodable(m.gatewayId())
                        .writeList(m.members(), MEMBER),
                r -> new MembershipUpdate(r.readU64(), NodeId.decode(r), r.readList(MEMBER_R)));
        kind(MessageCodec.TAG_PEER_GOODBYE, PeerGoodbye.class,
                (w, m) -> w.writeEncodable(m.who()).writeU64(m.epoch()).writeString(m.reason()),
                r -> new PeerGoodbye(NodeId.decode(r), r.readU64(), r.readString()));
        kind(MessageCodec.TAG_GATEWAY_CLAIM, GatewayClaim.class,
                (w, m) -> w.writeEncodable(m.gatewayId()).writeU64(m.epoch()),
                r -> new GatewayClaim(NodeId.decode(r), r.readU64()));
        // Version 2 only. Version 1 ended after the sequence number and carried no per-region
        // progress; nothing has emitted it since the NDR2 flag day, and on this wire a keep-alive
        // travels as an infrastructure TLV body rather than through this codec at all. The record
        // constructor rejects duplicate regions and sorts by RegionId, so the list is canonical
        // before it reaches the wire.
        versioned(MessageCodec.TAG_SESSION_KEEP_ALIVE, SessionKeepAlive.class,
                m -> SESSION_KEEP_ALIVE_ENCODING_VERSION, SESSION_KEEP_ALIVE_ENCODING_VERSION,
                SESSION_KEEP_ALIVE_ENCODING_VERSION,
                (w, m) -> w.writeEncodable(m.from()).writeU64(m.seq())
                        .writeList(m.regionProgress(), PROGRESS),
                (r, v) -> new SessionKeepAlive(NodeId.decode(r), r.readU64(),
                        r.readList(PROGRESS_R)));

        // --- content distribution ----------------------------------------------------------
        kind(MessageCodec.TAG_CONTENT_REQUEST, ContentRequest.class,
                (w, m) -> w.writeBytes(m.manifestRoot()).writeList(m.pieceIndexes(), INDEX),
                r -> new ContentRequest(r.readBytesValue(), readIndexes(r)));
        kind(MessageCodec.TAG_CONTENT_CHUNK, ContentChunk.class,
                (w, m) -> w.writeBytes(m.manifestRoot()).writeU32(u32(m.index()))
                        .writeBytes(m.payload()),
                r -> new ContentChunk(r.readBytesValue(), r.readU32AsInt(), r.readBytesValue()));
        kind(MessageCodec.TAG_CONTENT_AVAILABILITY, ContentAvailability.class,
                (w, m) -> w.writeEncodable(m.holder()).writeList(m.holdings(), HOLDING),
                r -> new ContentAvailability(NodeId.decode(r), r.readList(HOLDING_R)));

        // --- tracker / discovery -----------------------------------------------------------
        kind(MessageCodec.TAG_TRACKER_QUERY, TrackerQuery.class,
                (w, m) -> w.writeBytes(m.genesisHash()),
                r -> new TrackerQuery(r.readBytesValue()));
        kind(MessageCodec.TAG_TRACKER_RESPONSE, TrackerResponse.class,
                (w, m) -> w.writeBytes(m.genesisHash()).writeString(m.worldName())
                        .writeList(m.peers(), PEER)
                        .writeList(m.seeders(), (ww, s) -> ww.writeBytes(s.manifestRoot())
                                .writeList(s.seeders(), CanonicalWriter::writeEncodable))
                        .writeU64(m.worldPlayerCount()).writeU64(m.storedChunks())
                        .writeU32(u32(m.reliabilityBps())).writeEncodable(m.health())
                        .writeU64(m.retentionDeadlineEpochMillis()),
                r -> new TrackerResponse(r.readBytesValue(), r.readString(), r.readList(PEER_R),
                        r.readList(rr -> new ManifestSeeders(rr.readBytesValue(),
                                rr.readList(NodeId::decode))),
                        r.readU64(), r.readU64(), r.readU32AsInt(), WorldHealth.decode(r),
                        r.readU64()));
        kind(MessageCodec.TAG_INVENTORY_ADVERTISEMENT, InventoryAdvertisement.class,
                (w, m) -> w.writeBytes(m.genesisHash()).writeEncodable(m.holder())
                        .writeList(m.holdings(), HOLDING),
                r -> new InventoryAdvertisement(r.readBytesValue(), NodeId.decode(r),
                        r.readList(HOLDING_R)));

        // --- archival replication ----------------------------------------------------------
        kind(MessageCodec.TAG_ARCHIVE_REPLICA_ASSIGNMENT, ArchiveReplicaAssignment.class,
                (w, m) -> w.writeBytes(m.manifestRoot()).writeEncodable(m.assignee())
                        .writeList(m.pieceIndexes(), INDEX),
                r -> new ArchiveReplicaAssignment(r.readBytesValue(), NodeId.decode(r),
                        readIndexes(r)));
        kind(MessageCodec.TAG_ARCHIVE_REPLICA_ACK, ArchiveReplicaAck.class,
                (w, m) -> w.writeBytes(m.manifestRoot()).writeEncodable(m.assignee())
                        .writeList(m.pieceIndexes(), INDEX),
                r -> new ArchiveReplicaAck(r.readBytesValue(), NodeId.decode(r), readIndexes(r)));

        versioned(MessageCodec.TAG_EXTERNAL_DELTA, ExternalDelta.class, ExternalDelta::bodyVersion,
                ENCODING_VERSION, ExternalDelta.EXTERNAL_DELTA_ENCODING_VERSION,
                (w, m) -> {
                    w.writeEncodable(m.region()).writeEncodable(m.baseVersion())
                            .writeBytes(m.encodedDelta()).writeBytes(m.certificateBytes());
                    if (m.bodyVersion() >= 2) {
                        w.writeU64(m.tick());
                    }
                },
                (r, v) -> {
                    RegionId region = RegionId.decode(r);
                    SnapshotVersion baseVersion = SnapshotVersion.decode(r);
                    Bytes encodedDelta = r.readBytesValue();
                    Bytes certificateBytes = r.readBytesValue();
                    return new ExternalDelta(region, baseVersion, encodedDelta, certificateBytes,
                            v >= 2 ? r.readU64() : 0L, v);
                });

        // The record owns the signed-portion layout so the codec and the signer can never disagree
        // about where the signature starts.
        signed(MessageCodec.TAG_TRACKER_ANNOUNCE, TrackerAnnounce.class,
                (w, m) -> {
                    m.writeSignedPortion(w);
                    w.writeBytes(m.signature());
                },
                r -> {
                    Bytes genesisHash = r.readBytesValue();
                    NodeId peer = NodeId.decode(r);
                    Bytes publicKey = r.readBytesValue();
                    AnnounceEvent event = AnnounceEvent.decodeOrdinal(r);
                    List<String> routes = r.readList(CanonicalReader::readString);
                    NodeCapabilities capabilities = NodeCapabilities.decode(r);
                    List<ManifestHolding> holdings = r.readList(HOLDING_R);
                    String worldName = r.readString();
                    long retentionDeadline = r.readU64();
                    int reliabilityBps = r.readU32AsInt();
                    // Offset by one on the wire so "unknown" (-1) survives an unsigned field; 0 is
                    // the sentinel, n+1 is a real count of n. See TrackerAnnounce.worldPlayerCount.
                    long worldPlayerCount = r.readU64() - 1;
                    return new TrackerAnnounce(genesisHash, peer, publicKey, event, routes,
                            capabilities, holdings, worldName, retentionDeadline, reliabilityBps,
                            worldPlayerCount, r.readU64(), r.readBytesValue());
                });
        kind(MessageCodec.TAG_TRACKER_ANNOUNCE_ACK, TrackerAnnounceAck.class,
                (w, m) -> w.writeBoolean(m.accepted()).writeU32(u32(m.nextAnnounceAfterSeconds()))
                        .writeString(m.reason()),
                r -> new TrackerAnnounceAck(r.readBoolean(), r.readU32AsInt(), r.readString()));

        // --- rendezvous / relay ------------------------------------------------------------
        kind(MessageCodec.TAG_RENDEZVOUS_REGISTER, RendezvousRegister.class,
                (w, m) -> RECORD.accept(w, m.signed()),
                r -> new RendezvousRegister(RECORD_R.apply(r)));
        kind(MessageCodec.TAG_RENDEZVOUS_DISCOVER, RendezvousDiscover.class,
                (w, m) -> {
                    writeUuid(w, m.networkId());
                    w.writeBytes(m.genesisHash()).writeU32(u32(m.cursor()))
                            .writeU32(u32(m.limit()));
                },
                r -> new RendezvousDiscover(readUuid(r), r.readBytesValue(), r.readU32AsInt(),
                        r.readU32AsInt()));
        kind(MessageCodec.TAG_RENDEZVOUS_PEERS, RendezvousPeers.class,
                (w, m) -> w.writeU32(u32(m.nextCursor())).writeList(m.records(), RECORD),
                r -> new RendezvousPeers(r.readU32AsInt(), r.readList(RECORD_R)));
        kind(MessageCodec.TAG_RELAY_RESERVE, RelayReserve.class,
                (w, m) -> {
                    writeUuid(w, m.networkId());
                    w.writeBytes(m.genesisHash()).writeEncodable(m.peer());
                },
                r -> new RelayReserve(readUuid(r), r.readBytesValue(), NodeId.decode(r)));
        kind(MessageCodec.TAG_RELAY_RESERVATION, RelayReservation.class,
                (w, m) -> w.writeBoolean(m.accepted()).writeString(m.relayRoute())
                        .writeU64(m.expiresAtEpochMillis()).writeU64(m.maxBytes())
                        .writeU64(m.maxDurationMillis()).writeBytes(m.proof())
                        .writeString(m.reason()),
                r -> new RelayReservation(r.readBoolean(), r.readString(), r.readU64(), r.readU64(),
                        r.readU64(), r.readBytesValue(), r.readString()));
        kind(MessageCodec.TAG_RELAY_CONNECT, RelayConnect.class,
                (w, m) -> {
                    writeUuid(w, m.networkId());
                    w.writeBytes(m.genesisHash()).writeEncodable(m.source())
                            .writeEncodable(m.target());
                },
                r -> new RelayConnect(readUuid(r), r.readBytesValue(), NodeId.decode(r),
                        NodeId.decode(r)));
        kind(MessageCodec.TAG_RELAY_INCOMING, RelayIncoming.class,
                (w, m) -> {
                    writeUuid(w, m.networkId());
                    w.writeBytes(m.genesisHash()).writeEncodable(m.source())
                            .writeEncodable(m.target()).writeBytes(m.proof());
                },
                r -> new RelayIncoming(readUuid(r), r.readBytesValue(), NodeId.decode(r),
                        NodeId.decode(r), r.readBytesValue()));
        kind(MessageCodec.TAG_PUNCH_SYNC, PunchSync.class,
                (w, m) -> {
                    writeUuid(w, m.networkId());
                    w.writeBytes(m.genesisHash()).writeEncodable(m.source())
                            .writeEncodable(m.target())
                            .writeList(m.observedCandidates(), CanonicalWriter::writeEncodable)
                            .writeU64(m.goSignalEpochMillis());
                },
                r -> new PunchSync(readUuid(r), r.readBytesValue(), NodeId.decode(r),
                        NodeId.decode(r), r.readList(PeerCandidate::decode), r.readU64()));
        kind(MessageCodec.TAG_OBSERVED_ADDRESS, ObservedAddress.class,
                (w, m) -> w.writeEncodable(m.peer()).writeString(m.observedRoute()),
                r -> new ObservedAddress(NodeId.decode(r), r.readString()));

        // --- tracker directory / browse ----------------------------------------------------
        kind(MessageCodec.TAG_TRACKER_CATALOG_QUERY, TrackerCatalogQuery.class,
                (w, m) -> w.writeU32(u32(m.limit())),
                r -> new TrackerCatalogQuery(r.readU32AsInt()));
        kind(MessageCodec.TAG_TRACKER_CATALOG_RESPONSE, TrackerCatalogResponse.class,
                (w, m) -> w.writeList(m.worlds(), CATALOG),
                r -> new TrackerCatalogResponse(r.readList(CATALOG_R)));

        // --- entity transfer ---------------------------------------------------------------
        kind(MessageCodec.TAG_ENTITY_TRANSFER_PREPARE, EntityTransferPrepare.class,
                (w, m) -> w.writeEncodable(m.descriptor()).writeEncodable(m.sourceDelta())
                        .writeEncodable(m.targetDelta()),
                r -> new EntityTransferPrepare(EntityTransferDescriptor.decode(r),
                        RegionDelta.decode(r), RegionDelta.decode(r)));
        kind(MessageCodec.TAG_ENTITY_TRANSFER_ACCEPT, EntityTransferAccept.class,
                (w, m) -> w.writeU64(m.transferId()).writeEncodable(m.side())
                        .writeEncodable(m.vote()),
                r -> new EntityTransferAccept(r.readU64(), RegionId.decode(r),
                        SignedVote.decode(r)));
        kind(MessageCodec.TAG_ENTITY_TRANSFER_COMMIT, EntityTransferCommit.class,
                (w, m) -> w.writeEncodable(m.certificate())
                        .writeEncodable(m.sourceActionCertificate())
                        .writeEncodable(m.sourceDelta()).writeEncodable(m.targetDelta()),
                r -> new EntityTransferCommit(EntityTransferCertificate.decode(r),
                        QuorumCertificate.decode(r), RegionDelta.decode(r), RegionDelta.decode(r)));

        // --- join flow: full dial-route lists ----------------------------------------------
        kind(MessageCodec.TAG_TRACKER_ROUTES_QUERY, TrackerRoutesQuery.class,
                (w, m) -> w.writeBytes(m.genesisHash()),
                r -> new TrackerRoutesQuery(r.readBytesValue()));
        kind(MessageCodec.TAG_TRACKER_ROUTES_RESPONSE, TrackerRoutesResponse.class,
                (w, m) -> w.writeBytes(m.genesisHash())
                        .writeList(m.peers(), (ww, p) -> ww.writeEncodable(p.peer())
                                .writeList(p.routes(), CanonicalWriter::writeString)),
                r -> new TrackerRoutesResponse(r.readBytesValue(), r.readList(rr ->
                        new TrackerRoutesResponse.PeerRoutes(NodeId.decode(rr),
                                rr.readList(CanonicalReader::readString)))));

        // --- world continuity --------------------------------------------------------------
        kind(MessageCodec.TAG_WORLD_MANIFEST_QUERY, WorldManifestQuery.class,
                (w, m) -> w.writeBytes(m.worldId()),
                r -> new WorldManifestQuery(r.readBytesValue()));
        kind(MessageCodec.TAG_WORLD_MANIFEST_ANSWER, WorldManifestAnswer.class,
                (w, m) -> w.writeBytes(m.worldId())
                        .writeList(m.manifests(), CanonicalWriter::writeBytes),
                r -> new WorldManifestAnswer(r.readBytesValue(),
                        r.readList(CanonicalReader::readBytesValue)));

        // --- no-host submission, event sync, border lane -----------------------------------
        kind(MessageCodec.TAG_ACTION_FORWARD, ActionForward.class,
                (w, m) -> w.writeEncodable(m.region()).writeBytes(m.encodedEnvelope()),
                r -> new ActionForward(RegionId.decode(r), r.readBytesValue()));
        kind(MessageCodec.TAG_EVENT_SYNC_QUERY, EventSyncQuery.class,
                (w, m) -> w.writeEncodable(m.region()).writeU64(m.sinceEventId()),
                r -> new EventSyncQuery(RegionId.decode(r), r.readU64()));
        kind(MessageCodec.TAG_EVENT_SYNC_ANSWER, EventSyncAnswer.class,
                (w, m) -> w.writeEncodable(m.region())
                        .writeList(m.encodedEvents(), CanonicalWriter::writeBytes)
                        .writeList(m.encodedCertificates(), CanonicalWriter::writeBytes),
                r -> new EventSyncAnswer(RegionId.decode(r),
                        r.readList(CanonicalReader::readBytesValue),
                        r.readList(CanonicalReader::readBytesValue)));
        versioned(MessageCodec.TAG_HALO_UPDATE, HaloUpdate.class, HaloUpdate::bodyVersion,
                ENCODING_VERSION, HaloUpdate.HALO_UPDATE_ENCODING_VERSION,
                (w, m) -> {
                    w.writeEncodable(m.region()).writeEncodable(m.version())
                            .writeList(m.encodedEdgeColumns(), CanonicalWriter::writeBytes);
                    if (m.bodyVersion() >= 2) {
                        w.writeBytes(m.encodedEndorsement());
                    }
                },
                (r, v) -> {
                    RegionId region = RegionId.decode(r);
                    SnapshotVersion version = SnapshotVersion.decode(r);
                    List<Bytes> columns = r.readList(CanonicalReader::readBytesValue);
                    return v >= 2 ? new HaloUpdate(region, version, columns, r.readBytesValue())
                            : new HaloUpdate(region, version, columns);
                });
        kind(MessageCodec.TAG_GROUP_MIGRATION, GroupMigration.class,
                (w, m) -> w.writeEncodable(m.newPrimary())
                        .writeList(m.bumps(), (ww, b) -> ww.writeEncodable(b.region())
                                .writeEncodable(b.newEpoch())),
                r -> new GroupMigration(NodeId.decode(r), r.readList(rr ->
                        new GroupMigration.RegionEpochBump(RegionId.decode(rr),
                                RegionEpoch.decode(rr)))));

        // --- certified genesis -------------------------------------------------------------
        kind(MessageCodec.TAG_GENESIS_APPROVAL_REQUEST, GenesisApprovalRequest.class,
                (w, m) -> w.writeEncodable(m.genesisRoot())
                        .writeList(m.founders(), (ww, f) -> ww.writeEncodable(f.nodeId())
                                .writeBytes(f.publicKey())),
                r -> new GenesisApprovalRequest(StateRoot.decode(r), r.readList(rr ->
                        new GenesisApprovalRequest.FounderEntry(NodeId.decode(rr),
                                rr.readBytesValue()))));
        kind(MessageCodec.TAG_GENESIS_APPROVAL_GRANT, GenesisApprovalGrant.class,
                (w, m) -> w.writeEncodable(m.genesisRoot()).writeEncodable(m.founder())
                        .writeBytes(m.signature()),
                r -> new GenesisApprovalGrant(StateRoot.decode(r), NodeId.decode(r),
                        r.readBytesValue()));

        // --- world gossip: an id and one self-proving blob, four times over ------------------
        gossip(MessageCodec.TAG_WORLD_GRANT_GOSSIP, WorldGrantGossip.class,
                WorldGrantGossip::worldId, WorldGrantGossip::encodedGrant, WorldGrantGossip::new);
        gossip(MessageCodec.TAG_WORLD_OWNERSHIP_GOSSIP, WorldOwnershipGossip.class,
                WorldOwnershipGossip::worldId, WorldOwnershipGossip::encodedOwnership,
                WorldOwnershipGossip::new);
        gossip(MessageCodec.TAG_WORLD_DELETION_GOSSIP, WorldDeletionGossip.class,
                WorldDeletionGossip::worldId, WorldDeletionGossip::encodedTombstone,
                WorldDeletionGossip::new);
        gossip(MessageCodec.TAG_WORLD_REVIVAL_GOSSIP, WorldRevivalGossip.class,
                WorldRevivalGossip::worldId, WorldRevivalGossip::encodedRevival,
                WorldRevivalGossip::new);

        // The raw refusal code, not a resolved constant: a reason this build has never heard of
        // must re-encode as itself rather than as UNKNOWN's own number.
        kind(MessageCodec.TAG_REGION_REFUSAL, RegionRefusal.class,
                (w, m) -> w.writeEncodable(m.region()).writeU16(m.reasonCode()),
                r -> new RegionRefusal(RegionId.decode(r), r.readU16()));

        // --- LAN tunnel: carries somebody else's protocol, deliberately opaque here ----------
        kind(MessageCodec.TAG_TUNNEL_OPEN, TunnelOpen.class,
                (w, m) -> w.writeBytes(m.sessionId()).writeU64(m.streamId()),
                r -> new TunnelOpen(r.readBytesValue(), r.readU64()));
        kind(MessageCodec.TAG_TUNNEL_DATA, TunnelData.class,
                (w, m) -> w.writeU64(m.streamId()).writeBytes(m.payload()),
                r -> new TunnelData(r.readU64(), r.readBytesValue()));
        kind(MessageCodec.TAG_TUNNEL_CLOSE, TunnelClose.class,
                (w, m) -> w.writeU64(m.streamId()).writeString(m.reason()),
                r -> new TunnelClose(r.readU64(), r.readString()));

        // --- service directory --------------------------------------------------------------
        kind(MessageCodec.TAG_SERVICE_ANNOUNCE, ServiceAnnounce.class,
                (w, m) -> w.writeEncodable(m.record()).writeBytes(m.signature()),
                r -> new ServiceAnnounce(ServiceRecord.decode(r), r.readBytesValue()));
        kind(MessageCodec.TAG_SERVICE_ANNOUNCE_ACK, ServiceAnnounceAck.class,
                (w, m) -> w.writeBoolean(m.accepted()).writeU32(u32(m.nextAnnounceAfterSeconds()))
                        .writeString(m.reason())
                        .writeList(m.directory(), CanonicalWriter::writeEncodable),
                r -> new ServiceAnnounceAck(r.readBoolean(), r.readU32AsInt(), r.readString(),
                        r.readList(ServiceDirectoryEntry::decode)));
        kind(MessageCodec.TAG_SERVICE_DIRECTORY_QUERY, ServiceDirectoryQuery.class,
                (w, m) -> {
                    w.writeU8(m.kind().ordinal());
                    writeUuid(w, m.networkId());
                    w.writeU32(u32(m.limit()));
                },
                r -> new ServiceDirectoryQuery(ServiceKind.decodeOrdinal(r), readUuid(r),
                        r.readU32AsInt()));
        kind(MessageCodec.TAG_SERVICE_DIRECTORY_RESPONSE, ServiceDirectoryResponse.class,
                (w, m) -> w.writeList(m.entries(), CanonicalWriter::writeEncodable),
                r -> new ServiceDirectoryResponse(r.readList(ServiceDirectoryEntry::decode)));
        // As with TrackerAnnounce: the record owns the signed-portion layout.
        signed(MessageCodec.TAG_SERVICE_SCORE_REPORT, ServiceScoreReport.class,
                (w, m) -> {
                    m.writeSignedPortion(w);
                    w.writeBytes(m.signature());
                },
                r -> new ServiceScoreReport(NodeId.decode(r), r.readBytesValue(), readUuid(r),
                        r.readList(ServiceObservation::decode), r.readU64(), r.readBytesValue()));
        kind(MessageCodec.TAG_SERVICE_DRAIN_NOTICE, ServiceDrainNotice.class,
                (w, m) -> w.writeEncodable(m.record()).writeBytes(m.signature())
                        .writeList(m.replacements(), CanonicalWriter::writeEncodable)
                        .writeString(m.reason()),
                r -> new ServiceDrainNotice(ServiceRecord.decode(r), r.readBytesValue(),
                        r.readList(ServiceDirectoryEntry::decode), r.readString()));

        // --- session control ----------------------------------------------------------------
        kind(MessageCodec.TAG_NACK, Nack.class,
                (w, m) -> w.writeU16(m.kind()).writeU16(m.reasonCode()).writeU64(m.correlationId())
                        .writeString(m.detail()),
                r -> new Nack(r.readU16(), r.readU16(), r.readU64(), r.readString()));
        // The record owns the signed-portion layout so the codec and the signer cannot disagree
        // about where the signature starts.
        signed(MessageCodec.TAG_HELLO, Hello.class,
                (w, m) -> {
                    m.writeSignedPortion(w);
                    w.writeBytes(m.signature());
                },
                r -> new Hello(r.readU16(), r.readString(), readFeatures(r), r.readU32AsInt(),
                        r.readU64(), readUuid(r), NodeId.decode(r), r.readBytesValue(),
                        NodeCapabilities.decode(r), r.readBytesValue()));
        kind(MessageCodec.TAG_HELLO_ACK, HelloAck.class,
                (w, m) -> {
                    w.writeU16(m.wireEpoch()).writeList(m.selectedFeatures(), INDEX)
                            .writeU16(m.roleCode());
                    writeUuid(w, m.networkId());
                    w.writeU16(m.rejectCode()).writeString(m.detail());
                },
                r -> new HelloAck(r.readU16(), readFeatures(r), r.readU16(), readUuid(r),
                        r.readU16(), r.readString()));

        // Totality: a kind the schema declares but nothing here can encode would reach the network
        // with no bytes to send. Failing at class initialisation is the earliest this can be said.
        for (WireKind k : WireRegistry.kinds()) {
            if (!BY_TAG.containsKey(k.kind())) {
                throw new IllegalStateException(k.name() + " (kind " + k.kind() + ") is declared in "
                        + "WireRegistry but has no codec; add a row to CodecRegistry");
            }
        }
    }
}
