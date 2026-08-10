package dev.nodera.protocol.wire;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.EchoTest;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.RelayEnvelope;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ArchiveReplicaAck;
import dev.nodera.protocol.content.ArchiveReplicaAssignment;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.content.WorldManifestAnswer;
import dev.nodera.protocol.content.WorldManifestQuery;
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
import dev.nodera.protocol.service.ServiceRecord;
import dev.nodera.protocol.service.ServiceScore;
import dev.nodera.protocol.service.ServiceScoreReport;
import dev.nodera.protocol.session.Hello;
import dev.nodera.protocol.session.HelloAck;
import dev.nodera.protocol.session.Nack;
import dev.nodera.protocol.tunnel.TunnelClose;
import dev.nodera.protocol.tunnel.TunnelData;
import dev.nodera.protocol.tunnel.TunnelOpen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The infrastructure plane's codec: canonical TLV bodies for every message a peer needs in order to
 * <em>reach</em> other peers (Task 14 phases 2–3, retiring {@code Plan.7} R1 and R4).
 *
 * <h2>What changed and why it matters</h2>
 *
 * <p>These bodies used to be fixed-width fields written back to back, under the same strictness rule
 * as signed consensus payloads. That rule is right for a payload whose bytes are its identity and
 * pointless for a tracker query — and it is the direct cause of version lock, because a positional
 * body cannot be extended: a reader that meets an unknown field cannot find the end of it, so it
 * cannot find the start of the next one. There was no such thing as a compatible change. Now every
 * field carries its own length, so a field a peer has never heard of is skipped, and a field a newer
 * peer added is simply absent for an older one, which takes the documented default.
 *
 * <h2>One row per message</h2>
 *
 * <p>Each {@code shape(...)} call below registers the encoder and the decoder <b>together</b>. They
 * used to live in two switches a thousand lines apart, which is how a message could gain an encode
 * arm and not a decode arm, or gain a field on one side only. Adjacent is the point: the pair is one
 * edit, and a message with no shape fails {@link #encode} loudly rather than silently taking a
 * fallback.
 *
 * <h2>Signed structures stay opaque</h2>
 *
 * <p>Where a payload is Ed25519-signed — {@code TrackerAnnounce}, {@code SignedPeerRecord},
 * {@code ServiceRecord}, {@code ServiceScoreReport} — the signed bytes cross this plane as an opaque
 * {@code BYTES} field carrying their original canonical encoding ({@code Plan.7} D5). Re-spelling
 * them here would invalidate every signature over them, and no infrastructure decoder should be
 * parsing a structure whose meaning is "somebody else vouched for these exact bytes".
 *
 * <p>Thread-context: immutable static registry; any thread.
 */
public final class InfrastructureCodec {

    private InfrastructureCodec() {}

    // ------------------------------------------------------------------ registry

    /** Writes a message's fields into a TLV body. */
    @FunctionalInterface
    private interface Enc<T extends NoderaMessage> {
        void write(TlvWriter w, T msg);
    }

    /** Rebuilds a message from a TLV body. */
    @FunctionalInterface
    private interface Dec<T extends NoderaMessage> {
        T read(TlvReader r);
    }

    private record Shape<T extends NoderaMessage>(Enc<T> encoder, Dec<T> decoder) {}

    private static final Map<Integer, Shape<?>> SHAPES = new LinkedHashMap<>();

    private static <T extends NoderaMessage> void shape(int kind, Class<T> type,
                                                        Enc<T> encoder, Dec<T> decoder) {
        WireKind row = WireRegistry.require(kind);
        if (row.plane() != MessagePlane.INFRASTRUCTURE) {
            throw new IllegalStateException(row.name() + " is on the " + row.plane()
                    + " plane and must not have a TLV shape");
        }
        if (row.type() != type) {
            throw new IllegalStateException("kind " + kind + " is " + row.type().getSimpleName()
                    + " but a shape was registered for " + type.getSimpleName());
        }
        if (SHAPES.put(kind, new Shape<>(encoder, decoder)) != null) {
            throw new IllegalStateException("kind " + kind + " has two shapes");
        }
    }

    /**
     * Encode an infrastructure message's TLV body, re-emitting fields this build does not know.
     *
     * @param msg     the message.
     * @param overlay how the frame's field set differed from this build's; see {@link TlvOverlay}.
     * @return the body bytes, without a frame header.
     * @Thread-context any thread.
     */
    @SuppressWarnings("unchecked")
    public static byte[] encodeBody(NoderaMessage msg, TlvOverlay overlay) {
        WireKind row = WireRegistry.of(msg);
        Shape<NoderaMessage> shape = (Shape<NoderaMessage>) SHAPES.get(row.kind());
        if (shape == null) {
            throw new IllegalStateException(row.name() + " is on the infrastructure plane but has "
                    + "no TLV shape; register one in InfrastructureCodec");
        }
        TlvWriter w = new TlvWriter();
        shape.encoder().write(w, msg);
        return overlay.applyTo(w.toByteArray());
    }

    /**
     * The result of decoding a body: the message, plus whatever fields this build could not read.
     *
     * @param message   the reconstructed message.
     * @param overlay how the received field set differed from the one this build writes, so a
     *                re-encode reproduces the frame rather than this build's idea of it.
     */
    public record Decoded(NoderaMessage message, TlvOverlay overlay) {}

    /**
     * Decode an infrastructure message's TLV body.
     *
     * @param kind the kind from the frame header.
     * @param body the body bytes.
     * @return the reconstructed message and the fields this build did not understand.
     * @throws IllegalStateException if the kind has no shape or the body is malformed.
     * @Thread-context any thread.
     */
    public static Decoded decodeBody(int kind, byte[] body) {
        Shape<?> shape = SHAPES.get(kind);
        if (shape == null) {
            throw new IllegalStateException("kind " + kind + " (" + WireRegistry.nameOf(kind)
                    + ") has no TLV shape");
        }
        TlvReader reader = new TlvReader(body);
        NoderaMessage message = shape.decoder().read(reader);
        return new Decoded(message, reader.overlay());
    }

    /** @return {@code true} if this build can encode and decode {@code kind}. */
    public static boolean handles(int kind) {
        return SHAPES.containsKey(kind);
    }

    // ------------------------------------------------------------------ helpers

    /** The canonical bytes of a signed structure, carried opaquely (D5). */
    private static Bytes canonical(Encodable value) {
        CanonicalWriter w = new CanonicalWriter();
        value.encode(w);
        return w.toBytes();
    }

    /** Parse an opaque canonical structure, refusing trailing bytes. */
    private static <T> T fromCanonical(Bytes raw, Function<CanonicalReader, T> decoder) {
        CanonicalReader r = new CanonicalReader(raw.toArray());
        T value = decoder.apply(r);
        if (r.available() != 0) {
            throw new IllegalStateException("opaque canonical payload has " + r.available()
                    + " trailing byte(s)");
        }
        return value;
    }

    /** The whole legacy canonical frame of a message whose signature covers it. */
    private static Bytes wholeSignedFrame(NoderaMessage msg) {
        return MessageCodec.encodeBytes(msg);
    }

    private static <T extends NoderaMessage> T wholeSignedFrame(Bytes raw, Class<T> type) {
        NoderaMessage decoded = MessageCodec.decode(raw.toArray());
        if (!type.isInstance(decoded)) {
            throw new IllegalStateException("opaque signed payload decoded to "
                    + decoded.getClass().getSimpleName() + ", expected " + type.getSimpleName());
        }
        return type.cast(decoded);
    }

    /**
     * Read an enum by wire code, recording the field as lossy when the code is one this build does
     * not define — see {@link TlvReader#markVerbatim(int)}.
     */
    private static <E extends Enum<E>> E codedU16(TlvReader r, int id, WireEnum<E> table,
                                                  E fallback) {
        int code = r.u16(id, table.code(fallback));
        java.util.Optional<E> found = table.find(code);
        if (found.isEmpty()) {
            r.markVerbatim(id);
            return fallback;
        }
        return found.get();
    }

    // --- nested structures -------------------------------------------------

    private static void writeCapabilities(TlvWriter w, NodeCapabilities c) {
        w.i32(1, c.logicalCores());
        w.u64(2, c.memoryBytes());
        w.i32(3, c.latencyMs());
        // Raw IEEE-754 bits, exactly as the canonical encoding carries them: a decimal rendering
        // would not round-trip, and this value feeds selection decisions on both sides.
        w.u64(4, Double.doubleToLongBits(c.reliability()));
        w.i32(5, c.maxPrimaryRegions());
        w.i32(6, c.maxValidatorRegions());
        w.bool(7, c.acceptsWorker());
        List<Integer> codes = new ArrayList<>();
        for (PeerRole role : PeerRole.values()) {
            if (c.roles().contains(role)) {
                codes.add(WireEnums.PEER_ROLE.code(role));
            }
        }
        w.u32Array(8, codes);
    }

    private static NodeCapabilities readCapabilities(TlvReader r) {
        Set<PeerRole> roles = new LinkedHashSet<>();
        for (int code : r.u32Array(8)) {
            // A role this build does not know is dropped rather than fatal: it describes what the
            // peer offers, and a capability we cannot use is one we simply do not use. But dropping
            // it from the VALUE must not drop it from the BYTES — a peer that re-advertises this
            // structure would otherwise strip a capability the rest of the mesh can use.
            if (WireEnums.PEER_ROLE.find(code).isPresent()) {
                roles.add(WireEnums.PEER_ROLE.find(code).orElseThrow());
            } else {
                r.markVerbatim(8);
            }
        }
        return new NodeCapabilities(
                r.i32(1, 0), r.u64(2, 0L), r.i32(3, 0),
                Double.longBitsToDouble(r.u64(4, Double.doubleToLongBits(1.0d))),
                r.i32(5, 0), r.i32(6, 0), r.bool(7, false), roles);
    }

    /**
     * One membership / discovery peer entry.
     *
     * <p>There used to be two layouts of this structure — a short one for discovery and a long one
     * for membership — because a positional body has no way to say "this field is absent". With
     * per-field lengths the distinction evaporates: the public key and client version are simply
     * fields, present when they are known.
     */
    private static void writePeerEntry(TlvWriter w, PeerEntry e) {
        w.uuid(1, e.nodeId().value());
        w.string(2, e.route());
        w.nested(3, n -> writeCapabilities(n, e.capabilities()));
        w.bool(4, e.bootstrap());
        w.bytes(5, e.publicKey());
        w.string(6, e.clientVersion());
    }

    private static PeerEntry readPeerEntry(TlvReader r) {
        return new PeerEntry(
                new NodeId(r.uuid(1, new UUID(0, 0))),
                r.string(2, ""),
                r.nested(3, InfrastructureCodec::readCapabilities, NodeCapabilities.initial()),
                r.bool(4, false),
                r.bytes(5, Bytes.empty()),
                r.string(6, ""));
    }

    private static void writeHolding(TlvWriter w, ManifestHolding h) {
        w.bytes(1, h.manifestRoot());
        w.bytes(2, h.pieceBitmap());
    }

    private static ManifestHolding readHolding(TlvReader r) {
        return new ManifestHolding(r.bytes(1, Bytes.empty()), r.bytes(2, Bytes.empty()));
    }

    private static void writeSeeders(TlvWriter w, ManifestSeeders s) {
        w.bytes(1, s.manifestRoot());
        w.list(2, s.seeders(), (n, id) -> n.uuid(1, id.value()));
    }

    private static ManifestSeeders readSeeders(TlvReader r) {
        return new ManifestSeeders(r.bytes(1, Bytes.empty()),
                r.list(2, e -> new NodeId(e.uuid(1, new UUID(0, 0)))));
    }

    private static void writeCatalogEntry(TlvWriter w, TrackerCatalogEntry e) {
        w.bytes(1, e.genesisHash());
        w.string(2, e.worldName());
        w.u64(3, e.worldPlayerCount());
        w.u64(4, e.storedChunks());
        w.i32(5, e.reliabilityBps());
        w.u16(6, WireEnums.WORLD_HEALTH.code(e.health()));
        w.u64(7, e.retentionDeadlineEpochMillis());
    }

    private static TrackerCatalogEntry readCatalogEntry(TlvReader r) {
        return new TrackerCatalogEntry(r.bytes(1, Bytes.empty()), r.string(2, ""), r.u64(3, 0L),
                r.u64(4, 0L), r.i32(5, 0),
                codedU16(r, 6, WireEnums.WORLD_HEALTH, WorldHealth.DEGRADED),
                r.u64(7, 0L));
    }

    private static void writeRegionId(TlvWriter w, RegionId region) {
        w.string(1, region.dimension().namespace());
        w.string(2, region.dimension().path());
        w.i32(3, region.regionX());
        w.i32(4, region.regionZ());
    }

    private static RegionId readRegionId(TlvReader r) {
        return new RegionId(new DimensionKey(r.string(1, "minecraft"), r.string(2, "overworld")),
                r.i32(3, 0), r.i32(4, 0));
    }

    private static void writeProgress(TlvWriter w, RegionProgress p) {
        w.nested(1, n -> writeRegionId(n, p.region()));
        w.u64(2, p.epoch().value());
        w.uuid(3, p.primary().value());
        w.u64(4, p.lastAppliedTick());
    }

    private static RegionProgress readProgress(TlvReader r) {
        return new RegionProgress(r.nested(1, InfrastructureCodec::readRegionId),
                new RegionEpoch(r.u64(2, 0L)), new NodeId(r.uuid(3, new UUID(0, 0))),
                r.u64(4, 0L));
    }

    private static void writeCandidate(TlvWriter w, PeerCandidate c) {
        w.u16(1, WireEnums.CANDIDATE_KIND.code(c.kind()));
        w.string(2, c.address());
        w.i32(3, c.priority());
    }

    private static PeerCandidate readCandidate(TlvReader r) {
        return new PeerCandidate(
                WireEnums.CANDIDATE_KIND.require(r.u16(1, 0)), r.string(2, ""), r.i32(3, 0));
    }

    private static void writeWorkerLoad(TlvWriter w, WorkerLoad load) {
        w.i32(1, load.queueDepth());
        w.u64(2, load.memBytes());
        w.u64(3, load.execNanos());
    }

    private static WorkerLoad readWorkerLoad(TlvReader r) {
        return new WorkerLoad(r.i32(1, 0), r.u64(2, 0L), r.u64(3, 0L));
    }

    private static void writeSignedRecord(TlvWriter w, SignedRecord s) {
        w.bytes(1, canonical(s.record()));
        w.bytes(2, s.signature());
    }

    private static SignedRecord readSignedRecord(TlvReader r) {
        return new SignedRecord(fromCanonical(r.bytes(1), SignedPeerRecord::decode),
                r.bytes(2, Bytes.empty()));
    }

    private static void writeScore(TlvWriter w, ServiceScore s) {
        w.i32(1, s.availabilityPermille());
        w.i32(2, s.rttP50Millis());
        w.i32(3, s.rttP95Millis());
        w.i32(4, s.capacityPermille());
        w.i32(5, s.freshnessPermille());
        w.i32(6, s.reporterCount());
        w.i32(7, s.compositePermille());
    }

    private static ServiceScore readScore(TlvReader r) {
        return new ServiceScore(r.i32(1, 0), r.i32(2, 0), r.i32(3, 0), r.i32(4, 0), r.i32(5, 0),
                r.i32(6, 0), r.i32(7, 0));
    }

    private static void writeDirectoryEntry(TlvWriter w, ServiceDirectoryEntry e) {
        w.bytes(1, canonical(e.record()));
        w.bytes(2, e.signature());
        w.nested(3, n -> writeScore(n, e.score()));
    }

    private static ServiceDirectoryEntry readDirectoryEntry(TlvReader r) {
        return new ServiceDirectoryEntry(fromCanonical(r.bytes(1), ServiceRecord::decode),
                r.bytes(2, Bytes.empty()), r.nested(3, InfrastructureCodec::readScore));
    }

    private static <T> BiConsumer<TlvWriter, T> bytesElement(Function<T, Bytes> get) {
        return (w, v) -> w.bytes(1, get.apply(v));
    }

    // ------------------------------------------------------------------ shapes

    static {
        // --- handshake ---
        shape(1, ClientHello.class,
                (w, m) -> {
                    w.i32(1, m.protocolVersion());
                    w.uuid(2, m.nodeId().value());
                    w.bytes(3, m.publicKey());
                    w.nested(4, n -> writeCapabilities(n, m.capabilities()));
                    w.i32(5, m.rulesVersion());
                    w.u64(6, m.registryFingerprint());
                    w.bytes(7, m.signature());
                },
                r -> new ClientHello(r.i32(1, 0), new NodeId(r.uuid(2, new UUID(0, 0))),
                        r.bytes(3, Bytes.empty()),
                        r.nested(4, InfrastructureCodec::readCapabilities, NodeCapabilities.initial()),
                        r.i32(5, 0), r.u64(6, 0L), r.bytes(7, Bytes.empty())));

        shape(2, ServerHello.class,
                (w, m) -> {
                    w.uuid(1, m.networkId());
                    w.u64(2, m.currentTick());
                    w.i32(3, m.regionSizeChunks());
                    w.i32(4, m.requiredValidators());
                    w.bytes(5, m.challenge());
                },
                r -> new ServerHello(r.uuid(1, new UUID(0, 0)), r.u64(2, 0L), r.i32(3, 8),
                        r.i32(4, 1), r.bytes(5, Bytes.empty())));

        shape(3, ChallengeResponse.class,
                (w, m) -> w.bytes(1, m.signature()),
                r -> new ChallengeResponse(r.bytes(1, Bytes.empty())));

        shape(4, WorkerActivation.class,
                (w, m) -> {
                    w.uuid(1, m.sessionId());
                    w.i32(2, m.maxPrimary());
                    w.i32(3, m.maxReplica());
                    w.u64(4, m.heartbeatTicks());
                },
                r -> new WorkerActivation(r.uuid(1, new UUID(0, 0)), r.i32(2, 0), r.i32(3, 0),
                        r.u64(4, 0L)));

        // --- health + liveness ---
        shape(15, Heartbeat.class,
                (w, m) -> {
                    w.u64(1, m.tick());
                    w.nested(2, n -> writeWorkerLoad(n, m.load()));
                },
                r -> new Heartbeat(r.u64(1, 0L), r.nested(2, InfrastructureCodec::readWorkerLoad)));

        shape(16, WorkerLoad.class,
                InfrastructureCodec::writeWorkerLoad,
                InfrastructureCodec::readWorkerLoad);

        shape(17, EchoTest.class,
                (w, m) -> w.bytes(1, m.payload()),
                r -> new EchoTest(r.bytes(1, Bytes.empty())));

        // --- relay + membership ---
        shape(18, RelayEnvelope.class,
                (w, m) -> {
                    w.uuid(1, m.target().value());
                    w.bytes(2, m.innerFrame());
                },
                r -> new RelayEnvelope(new NodeId(r.uuid(1, new UUID(0, 0))),
                        r.bytes(2, Bytes.empty())));

        // `clientVersion` used to be a trailing optional field, which is why a PeerJoin truncated
        // just before it was byte-for-byte a valid legacy frame and no decoder could tell the two
        // apart. As a TLV field it is either present or absent, and a truncated body fails.
        shape(19, PeerJoin.class,
                (w, m) -> {
                    w.uuid(1, m.joiner().value());
                    w.string(2, m.listenRoute());
                    w.nested(3, n -> writeCapabilities(n, m.capabilities()));
                    w.bool(4, m.bootstrap());
                    w.bytes(5, m.publicKey());
                    w.string(6, m.clientVersion());
                },
                r -> new PeerJoin(new NodeId(r.uuid(1, new UUID(0, 0))), r.string(2, ""),
                        r.nested(3, InfrastructureCodec::readCapabilities, NodeCapabilities.initial()),
                        r.bool(4, false), r.bytes(5, Bytes.empty()), r.string(6, "")));

        shape(20, MembershipUpdate.class,
                (w, m) -> {
                    w.u64(1, m.epoch());
                    w.uuid(2, m.gatewayId().value());
                    w.list(3, m.members(), InfrastructureCodec::writePeerEntry);
                },
                r -> new MembershipUpdate(r.u64(1, 0L), new NodeId(r.uuid(2, new UUID(0, 0))),
                        r.list(3, InfrastructureCodec::readPeerEntry)));

        shape(21, PeerGoodbye.class,
                (w, m) -> {
                    w.uuid(1, m.who().value());
                    w.u64(2, m.epoch());
                    w.string(3, m.reason());
                },
                r -> new PeerGoodbye(new NodeId(r.uuid(1, new UUID(0, 0))), r.u64(2, 0L),
                        r.string(3, "")));

        shape(22, GatewayClaim.class,
                (w, m) -> {
                    w.uuid(1, m.gatewayId().value());
                    w.u64(2, m.epoch());
                },
                r -> new GatewayClaim(new NodeId(r.uuid(1, new UUID(0, 0))), r.u64(2, 0L)));

        // The v1/v2 split this message carried is gone: `regionProgress` is a field like any other,
        // absent for a peer that does not send it. That is what a body version was standing in for.
        shape(23, SessionKeepAlive.class,
                (w, m) -> {
                    w.uuid(1, m.from().value());
                    w.u64(2, m.seq());
                    w.list(3, m.regionProgress(), InfrastructureCodec::writeProgress);
                },
                r -> new SessionKeepAlive(new NodeId(r.uuid(1, new UUID(0, 0))), r.u64(2, 0L),
                        r.list(3, InfrastructureCodec::readProgress)));

        // --- content ---
        shape(24, ContentRequest.class,
                (w, m) -> {
                    w.bytes(1, m.manifestRoot());
                    w.u32Array(2, m.pieceIndexes());
                },
                r -> new ContentRequest(r.bytes(1, Bytes.empty()), r.u32Array(2)));

        shape(25, ContentChunk.class,
                (w, m) -> {
                    w.bytes(1, m.manifestRoot());
                    w.i32(2, m.index());
                    w.bytes(3, m.payload());
                },
                r -> new ContentChunk(r.bytes(1, Bytes.empty()), r.i32(2, 0), r.bytes(3, Bytes.empty())));

        shape(26, ContentAvailability.class,
                (w, m) -> {
                    w.uuid(1, m.holder().value());
                    w.list(2, m.holdings(), InfrastructureCodec::writeHolding);
                },
                r -> new ContentAvailability(new NodeId(r.uuid(1, new UUID(0, 0))),
                        r.list(2, InfrastructureCodec::readHolding)));

        // --- tracker / discovery ---
        shape(27, TrackerQuery.class,
                (w, m) -> w.bytes(1, m.genesisHash()),
                r -> new TrackerQuery(r.bytes(1, Bytes.empty())));

        shape(28, TrackerResponse.class,
                (w, m) -> {
                    w.bytes(1, m.genesisHash());
                    w.string(2, m.worldName());
                    w.list(3, m.peers(), InfrastructureCodec::writePeerEntry);
                    w.list(4, m.seeders(), InfrastructureCodec::writeSeeders);
                    w.u64(5, m.worldPlayerCount());
                    w.u64(6, m.storedChunks());
                    w.i32(7, m.reliabilityBps());
                    w.u16(8, WireEnums.WORLD_HEALTH.code(m.health()));
                    w.u64(9, m.retentionDeadlineEpochMillis());
                },
                r -> new TrackerResponse(r.bytes(1, Bytes.empty()), r.string(2, ""),
                        r.list(3, InfrastructureCodec::readPeerEntry),
                        r.list(4, InfrastructureCodec::readSeeders),
                        r.u64(5, 0L), r.u64(6, 0L), r.i32(7, 0),
                        codedU16(r, 8, WireEnums.WORLD_HEALTH, WorldHealth.DEGRADED),
                        r.u64(9, 0L)));

        shape(29, InventoryAdvertisement.class,
                (w, m) -> {
                    w.bytes(1, m.genesisHash());
                    w.uuid(2, m.holder().value());
                    w.list(3, m.holdings(), InfrastructureCodec::writeHolding);
                },
                r -> new InventoryAdvertisement(r.bytes(1, Bytes.empty()),
                        new NodeId(r.uuid(2, new UUID(0, 0))),
                        r.list(3, InfrastructureCodec::readHolding)));

        shape(30, ArchiveReplicaAssignment.class,
                (w, m) -> {
                    w.bytes(1, m.manifestRoot());
                    w.uuid(2, m.assignee().value());
                    w.u32Array(3, m.pieceIndexes());
                },
                r -> new ArchiveReplicaAssignment(r.bytes(1, Bytes.empty()),
                        new NodeId(r.uuid(2, new UUID(0, 0))), r.u32Array(3)));

        shape(31, ArchiveReplicaAck.class,
                (w, m) -> {
                    w.bytes(1, m.manifestRoot());
                    w.uuid(2, m.assignee().value());
                    w.u32Array(3, m.pieceIndexes());
                },
                r -> new ArchiveReplicaAck(r.bytes(1, Bytes.empty()),
                        new NodeId(r.uuid(2, new UUID(0, 0))), r.u32Array(3)));

        // The announce is signed over its own canonical bytes, so it crosses this plane whole and
        // opaque. The tracker parses it with the strict codec, which is the plane boundary doing its
        // job: the tolerant layer routes it, and the layer that must verify a signature reads it.
        shape(33, TrackerAnnounce.class,
                (w, m) -> w.bytes(1, wholeSignedFrame(m)),
                r -> wholeSignedFrame(r.bytes(1), TrackerAnnounce.class));

        shape(34, TrackerAnnounceAck.class,
                (w, m) -> {
                    w.bool(1, m.accepted());
                    w.i32(2, m.nextAnnounceAfterSeconds());
                    w.string(3, m.reason());
                },
                r -> new TrackerAnnounceAck(r.bool(1, false), r.i32(2, 0), r.string(3, "")));

        // --- rendezvous / relay ---
        shape(35, RendezvousRegister.class,
                (w, m) -> w.nested(1, n -> writeSignedRecord(n, m.signed())),
                r -> new RendezvousRegister(r.nested(1, InfrastructureCodec::readSignedRecord)));

        shape(36, RendezvousDiscover.class,
                (w, m) -> {
                    w.uuid(1, m.networkId());
                    w.bytes(2, m.genesisHash());
                    w.i32(3, m.cursor());
                    w.i32(4, m.limit());
                },
                r -> new RendezvousDiscover(r.uuid(1, new UUID(0, 0)), r.bytes(2, Bytes.empty()),
                        r.i32(3, 0), r.i32(4, 0)));

        shape(37, RendezvousPeers.class,
                (w, m) -> {
                    w.i32(1, m.nextCursor());
                    w.list(2, m.records(), InfrastructureCodec::writeSignedRecord);
                },
                r -> new RendezvousPeers(r.i32(1, 0),
                        r.list(2, InfrastructureCodec::readSignedRecord)));

        shape(38, RelayReserve.class,
                (w, m) -> {
                    w.uuid(1, m.networkId());
                    w.bytes(2, m.genesisHash());
                    w.uuid(3, m.peer().value());
                },
                r -> new RelayReserve(r.uuid(1, new UUID(0, 0)), r.bytes(2, Bytes.empty()),
                        new NodeId(r.uuid(3, new UUID(0, 0)))));

        shape(39, RelayReservation.class,
                (w, m) -> {
                    w.bool(1, m.accepted());
                    w.string(2, m.relayRoute());
                    w.u64(3, m.expiresAtEpochMillis());
                    w.u64(4, m.maxBytes());
                    w.u64(5, m.maxDurationMillis());
                    w.bytes(6, m.proof());
                    w.string(7, m.reason());
                },
                r -> new RelayReservation(r.bool(1, false), r.string(2, ""), r.u64(3, 0L),
                        r.u64(4, 0L), r.u64(5, 0L), r.bytes(6, Bytes.empty()), r.string(7, "")));

        shape(40, RelayConnect.class,
                (w, m) -> {
                    w.uuid(1, m.networkId());
                    w.bytes(2, m.genesisHash());
                    w.uuid(3, m.source().value());
                    w.uuid(4, m.target().value());
                },
                r -> new RelayConnect(r.uuid(1, new UUID(0, 0)), r.bytes(2, Bytes.empty()),
                        new NodeId(r.uuid(3, new UUID(0, 0))),
                        new NodeId(r.uuid(4, new UUID(0, 0)))));

        shape(41, RelayIncoming.class,
                (w, m) -> {
                    w.uuid(1, m.networkId());
                    w.bytes(2, m.genesisHash());
                    w.uuid(3, m.source().value());
                    w.uuid(4, m.target().value());
                    w.bytes(5, m.proof());
                },
                r -> new RelayIncoming(r.uuid(1, new UUID(0, 0)), r.bytes(2, Bytes.empty()),
                        new NodeId(r.uuid(3, new UUID(0, 0))),
                        new NodeId(r.uuid(4, new UUID(0, 0))), r.bytes(5, Bytes.empty())));

        shape(42, PunchSync.class,
                (w, m) -> {
                    w.uuid(1, m.networkId());
                    w.bytes(2, m.genesisHash());
                    w.uuid(3, m.source().value());
                    w.uuid(4, m.target().value());
                    w.list(5, m.observedCandidates(), InfrastructureCodec::writeCandidate);
                    w.u64(6, m.goSignalEpochMillis());
                },
                r -> new PunchSync(r.uuid(1, new UUID(0, 0)), r.bytes(2, Bytes.empty()),
                        new NodeId(r.uuid(3, new UUID(0, 0))),
                        new NodeId(r.uuid(4, new UUID(0, 0))),
                        r.list(5, InfrastructureCodec::readCandidate), r.u64(6, 0L)));

        shape(43, ObservedAddress.class,
                (w, m) -> {
                    w.uuid(1, m.peer().value());
                    w.string(2, m.observedRoute());
                },
                r -> new ObservedAddress(new NodeId(r.uuid(1, new UUID(0, 0))), r.string(2, "")));

        shape(44, TrackerCatalogQuery.class,
                (w, m) -> w.i32(1, m.limit()),
                r -> new TrackerCatalogQuery(r.i32(1, 0)));

        shape(45, TrackerCatalogResponse.class,
                (w, m) -> w.list(1, m.worlds(), InfrastructureCodec::writeCatalogEntry),
                r -> new TrackerCatalogResponse(r.list(1, InfrastructureCodec::readCatalogEntry)));

        shape(49, TrackerRoutesQuery.class,
                (w, m) -> w.bytes(1, m.genesisHash()),
                r -> new TrackerRoutesQuery(r.bytes(1, Bytes.empty())));

        shape(50, TrackerRoutesResponse.class,
                (w, m) -> {
                    w.bytes(1, m.genesisHash());
                    w.list(2, m.peers(), (n, p) -> {
                        n.uuid(1, p.peer().value());
                        n.list(2, p.routes(), (e, route) -> e.string(1, route));
                    });
                },
                r -> new TrackerRoutesResponse(r.bytes(1, Bytes.empty()),
                        r.list(2, e -> new TrackerRoutesResponse.PeerRoutes(
                                new NodeId(e.uuid(1, new UUID(0, 0))),
                                e.list(2, s -> s.string(1, ""))))));

        // --- world manifests ---
        shape(51, WorldManifestQuery.class,
                (w, m) -> w.bytes(1, m.worldId()),
                r -> new WorldManifestQuery(r.bytes(1, Bytes.empty())));

        shape(52, WorldManifestAnswer.class,
                (w, m) -> {
                    w.bytes(1, m.worldId());
                    w.list(2, m.manifests(), bytesElement(b -> b));
                },
                r -> new WorldManifestAnswer(r.bytes(1, Bytes.empty()),
                        r.list(2, e -> e.bytes(1, Bytes.empty()))));

        // --- LAN tunnel ---
        shape(63, TunnelOpen.class,
                (w, m) -> {
                    w.bytes(1, m.sessionId());
                    w.u64(2, m.streamId());
                },
                r -> new TunnelOpen(r.bytes(1, Bytes.empty()), r.u64(2, 0L)));

        shape(64, TunnelData.class,
                (w, m) -> {
                    w.u64(1, m.streamId());
                    w.bytes(2, m.payload());
                },
                r -> new TunnelData(r.u64(1, 0L), r.bytes(2, Bytes.empty())));

        shape(65, TunnelClose.class,
                (w, m) -> {
                    w.u64(1, m.streamId());
                    w.string(2, m.reason());
                },
                r -> new TunnelClose(r.u64(1, 0L), r.string(2, "")));

        // --- service directory ---
        shape(67, ServiceAnnounce.class,
                (w, m) -> {
                    w.bytes(1, canonical(m.record()));
                    w.bytes(2, m.signature());
                },
                r -> new ServiceAnnounce(fromCanonical(r.bytes(1), ServiceRecord::decode),
                        r.bytes(2, Bytes.empty())));

        shape(68, ServiceAnnounceAck.class,
                (w, m) -> {
                    w.bool(1, m.accepted());
                    w.i32(2, m.nextAnnounceAfterSeconds());
                    w.string(3, m.reason());
                    w.list(4, m.directory(), InfrastructureCodec::writeDirectoryEntry);
                },
                r -> new ServiceAnnounceAck(r.bool(1, false), r.i32(2, 0), r.string(3, ""),
                        r.list(4, InfrastructureCodec::readDirectoryEntry)));

        shape(69, ServiceDirectoryQuery.class,
                (w, m) -> {
                    w.u16(1, WireEnums.SERVICE_KIND.code(m.kind()));
                    w.uuid(2, m.networkId());
                    w.i32(3, m.limit());
                },
                r -> new ServiceDirectoryQuery(WireEnums.SERVICE_KIND.require(r.u16(1, 0)),
                        r.uuid(2, new UUID(0, 0)), r.i32(3, 0)));

        shape(70, ServiceDirectoryResponse.class,
                (w, m) -> w.list(1, m.entries(), InfrastructureCodec::writeDirectoryEntry),
                r -> new ServiceDirectoryResponse(
                        r.list(1, InfrastructureCodec::readDirectoryEntry)));

        // Signed over its own canonical frame, like the tracker announce.
        shape(71, ServiceScoreReport.class,
                (w, m) -> w.bytes(1, wholeSignedFrame(m)),
                r -> wholeSignedFrame(r.bytes(1), ServiceScoreReport.class));

        shape(72, ServiceDrainNotice.class,
                (w, m) -> {
                    w.bytes(1, canonical(m.record()));
                    w.bytes(2, m.signature());
                    w.list(3, m.replacements(), InfrastructureCodec::writeDirectoryEntry);
                    w.string(4, m.reason());
                },
                r -> new ServiceDrainNotice(fromCanonical(r.bytes(1), ServiceRecord::decode),
                        r.bytes(2, Bytes.empty()),
                        r.list(3, InfrastructureCodec::readDirectoryEntry), r.string(4, "")));

        // --- session control ---
        shape(73, Nack.class,
                (w, m) -> {
                    w.u16(1, m.kind());
                    w.u16(2, m.reasonCode());
                    w.u64(3, m.correlationId());
                    w.string(4, m.detail());
                },
                r -> new Nack(r.u16(1, 0), r.u16(2, 0), r.u64(3, 0L), r.string(4, "")));

        shape(74, Hello.class,
                (w, m) -> {
                    w.u16(1, m.wireEpoch());
                    w.string(2, m.productVersion());
                    w.u32Array(3, m.features());
                    w.i32(4, m.rulesVersion());
                    w.u64(5, m.registryFingerprint());
                    w.uuid(6, m.networkId());
                    w.uuid(7, m.nodeId().value());
                    w.bytes(8, m.publicKey());
                    w.nested(9, n -> writeCapabilities(n, m.capabilities()));
                    w.bytes(10, m.signature());
                },
                r -> new Hello(r.u16(1, WireRegistry.WIRE_EPOCH), r.string(2, ""),
                        new LinkedHashSet<>(r.u32Array(3)), r.i32(4, 0), r.u64(5, 0L),
                        r.uuid(6, new UUID(0, 0)), new NodeId(r.uuid(7, new UUID(0, 0))),
                        r.bytes(8, Bytes.empty()),
                        r.nested(9, InfrastructureCodec::readCapabilities, NodeCapabilities.initial()),
                        r.bytes(10, Bytes.empty())));

        shape(75, HelloAck.class,
                (w, m) -> {
                    w.u16(1, m.wireEpoch());
                    w.u32Array(2, m.selectedFeatures());
                    w.u16(3, m.roleCode());
                    w.uuid(4, m.networkId());
                    w.u16(5, m.rejectCode());
                    w.string(6, m.detail());
                },
                r -> new HelloAck(r.u16(1, WireRegistry.WIRE_EPOCH),
                        new LinkedHashSet<>(r.u32Array(2)), r.u16(3, 0),
                        r.uuid(4, new UUID(0, 0)), r.u16(5, 0), r.string(6, "")));
    }
}
