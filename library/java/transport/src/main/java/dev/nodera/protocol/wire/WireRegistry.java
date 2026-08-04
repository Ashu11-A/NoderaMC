package dev.nodera.protocol.wire;

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
import dev.nodera.protocol.content.WorldManifestAnswer;
import dev.nodera.protocol.content.WorldManifestQuery;
import dev.nodera.protocol.discovery.InventoryAdvertisement;
import dev.nodera.protocol.discovery.TrackerAnnounce;
import dev.nodera.protocol.discovery.TrackerAnnounceAck;
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
import dev.nodera.protocol.membership.PeerGoodbye;
import dev.nodera.protocol.membership.PeerJoin;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.protocol.membership.WorldDeletionGossip;
import dev.nodera.protocol.membership.WorldRevivalGossip;
import dev.nodera.protocol.membership.WorldGrantGossip;
import dev.nodera.protocol.membership.WorldOwnershipGossip;
import dev.nodera.protocol.rendezvous.ObservedAddress;
import dev.nodera.protocol.rendezvous.PunchSync;
import dev.nodera.protocol.rendezvous.RelayConnect;
import dev.nodera.protocol.rendezvous.RelayIncoming;
import dev.nodera.protocol.rendezvous.RelayReservation;
import dev.nodera.protocol.rendezvous.RelayReserve;
import dev.nodera.protocol.rendezvous.RendezvousDiscover;
import dev.nodera.protocol.rendezvous.RendezvousPeers;
import dev.nodera.protocol.rendezvous.RendezvousRegister;
import dev.nodera.protocol.service.ServiceAnnounce;
import dev.nodera.protocol.service.ServiceAnnounceAck;
import dev.nodera.protocol.service.ServiceDirectoryQuery;
import dev.nodera.protocol.service.ServiceDirectoryResponse;
import dev.nodera.protocol.service.ServiceDrainNotice;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.nodera.protocol.wire.MessagePlane.CONSENSUS;
import static dev.nodera.protocol.wire.MessagePlane.INFRASTRUCTURE;

/**
 * The wire schema: one row per message kind, and the only place a kind is declared
 * (Task 14, {@code Plan.7} decision D6).
 *
 * <p>Everything the protocol needs to know about a kind is derived from this table — the kind
 * number, the display name, the plane that governs its body, the record it decodes to, the fixture
 * file that pins its bytes, and the Rust mirror. Appending a message is <b>one edit here</b>; the
 * lists that used to be maintained in parallel are now views over this map, so they cannot
 * disagree with each other or with the fixture corpus.
 *
 * <h2>Two rules that are load-bearing</h2>
 *
 * <p><b>Kind numbers are permanent.</b> Assigning one is forever: never renumber, never reuse.
 * A change that an existing peer cannot absorb allocates a <em>new kind</em> rather than bumping a
 * version ({@code Plan.7} D3) — which is why there is no version column here.
 *
 * <p><b>The plane is a classification, not a preference.</b> See {@link MessagePlane}: hashed or
 * signed means {@link MessagePlane#CONSENSUS}, and so does anything ambiguous.
 *
 * <p>Thread-context: immutable static state, built once at class initialisation; any thread.
 */
public final class WireRegistry {

    private WireRegistry() {}

    /**
     * The wire epoch this build speaks. It appears in every frame's header so a peer from a
     * different epoch fails immediately with a readable error instead of misparsing a body.
     *
     * <p>Epoch 1 was the {@code u16 tag + u16 version + positional body} frame. Epoch 2 is the
     * {@code NDR2} frame with TLV infrastructure bodies. It is expected to stay at 2: TLV absorbs
     * additive change, and anything it cannot absorb is a new kind rather than a new epoch.
     */
    public static final int WIRE_EPOCH = 2;

    private static final List<WireKind> KINDS = List.of(
            // --- handshake: superseded by the negotiated Hello/HelloAck pair (Plan.7 §4.3) ---
            new WireKind(1, "ClientHello", INFRASTRUCTURE, ClientHello.class),
            new WireKind(2, "ServerHello", INFRASTRUCTURE, ServerHello.class),
            new WireKind(3, "ChallengeResponse", INFRASTRUCTURE, ChallengeResponse.class),
            new WireKind(4, "WorkerActivation", INFRASTRUCTURE, WorkerActivation.class),

            // --- region assignment: carries the epoch a committee's votes are validated against,
            // so a peer that reads it differently disagrees about which votes count ---
            new WireKind(5, "RegionAssigned", CONSENSUS, RegionAssigned.class),
            new WireKind(6, "RegionRevoked", CONSENSUS, RegionRevoked.class),
            new WireKind(7, "LeaseRenewal", CONSENSUS, LeaseRenewal.class),

            // --- simulation: proposals, votes, certificates, deltas, snapshots ---
            new WireKind(8, "SnapshotAnnounce", CONSENSUS, SnapshotAnnounce.class),
            new WireKind(9, "StreamChunk", CONSENSUS, StreamChunk.class),
            new WireKind(10, "ActionBatchMsg", CONSENSUS, ActionBatchMsg.class),
            new WireKind(11, "RegionProposal", CONSENSUS, RegionProposal.class),
            new WireKind(12, "ValidationVote", CONSENSUS, ValidationVote.class),
            new WireKind(13, "CommitAnnounce", CONSENSUS, CommitAnnounce.class),
            new WireKind(14, "ResyncRequest", CONSENSUS, ResyncRequest.class),

            // --- health + liveness ---
            new WireKind(15, "Heartbeat", INFRASTRUCTURE, Heartbeat.class),
            new WireKind(16, "WorkerLoad", INFRASTRUCTURE, WorkerLoad.class),
            new WireKind(17, "EchoTest", INFRASTRUCTURE, EchoTest.class),

            // --- relay + membership ---
            new WireKind(18, "RelayEnvelope", INFRASTRUCTURE, RelayEnvelope.class),
            new WireKind(19, "PeerJoin", INFRASTRUCTURE, PeerJoin.class),
            new WireKind(20, "MembershipUpdate", INFRASTRUCTURE, MembershipUpdate.class),
            new WireKind(21, "PeerGoodbye", INFRASTRUCTURE, PeerGoodbye.class),
            new WireKind(22, "GatewayClaim", INFRASTRUCTURE, GatewayClaim.class),
            new WireKind(23, "SessionKeepAlive", INFRASTRUCTURE, SessionKeepAlive.class),

            // --- content distribution ---
            new WireKind(24, "ContentRequest", INFRASTRUCTURE, ContentRequest.class),
            new WireKind(25, "ContentChunk", INFRASTRUCTURE, ContentChunk.class),
            new WireKind(26, "ContentAvailability", INFRASTRUCTURE, ContentAvailability.class),

            // --- tracker / discovery ---
            new WireKind(27, "TrackerQuery", INFRASTRUCTURE, TrackerQuery.class),
            new WireKind(28, "TrackerResponse", INFRASTRUCTURE, TrackerResponse.class),
            new WireKind(29, "InventoryAdvertisement", INFRASTRUCTURE, InventoryAdvertisement.class),

            // --- archival replication ---
            new WireKind(30, "ArchiveReplicaAssignment", INFRASTRUCTURE,
                    ArchiveReplicaAssignment.class),
            new WireKind(31, "ArchiveReplicaAck", INFRASTRUCTURE, ArchiveReplicaAck.class),

            new WireKind(32, "ExternalDelta", CONSENSUS, ExternalDelta.class),

            new WireKind(33, "TrackerAnnounce", INFRASTRUCTURE, TrackerAnnounce.class),
            new WireKind(34, "TrackerAnnounceAck", INFRASTRUCTURE, TrackerAnnounceAck.class),

            // --- rendezvous / relay (Task 29) ---
            new WireKind(35, "RendezvousRegister", INFRASTRUCTURE, RendezvousRegister.class),
            new WireKind(36, "RendezvousDiscover", INFRASTRUCTURE, RendezvousDiscover.class),
            new WireKind(37, "RendezvousPeers", INFRASTRUCTURE, RendezvousPeers.class),
            new WireKind(38, "RelayReserve", INFRASTRUCTURE, RelayReserve.class),
            new WireKind(39, "RelayReservation", INFRASTRUCTURE, RelayReservation.class),
            new WireKind(40, "RelayConnect", INFRASTRUCTURE, RelayConnect.class),
            new WireKind(41, "RelayIncoming", INFRASTRUCTURE, RelayIncoming.class),
            new WireKind(42, "PunchSync", INFRASTRUCTURE, PunchSync.class),
            new WireKind(43, "ObservedAddress", INFRASTRUCTURE, ObservedAddress.class),

            new WireKind(44, "TrackerCatalogQuery", INFRASTRUCTURE, TrackerCatalogQuery.class),
            new WireKind(45, "TrackerCatalogResponse", INFRASTRUCTURE, TrackerCatalogResponse.class),

            // --- entity transfer: paired, certificate-bearing region transitions ---
            new WireKind(46, "EntityTransferPrepare", CONSENSUS, EntityTransferPrepare.class),
            new WireKind(47, "EntityTransferAccept", CONSENSUS, EntityTransferAccept.class),
            new WireKind(48, "EntityTransferCommit", CONSENSUS, EntityTransferCommit.class),

            new WireKind(49, "TrackerRoutesQuery", INFRASTRUCTURE, TrackerRoutesQuery.class),
            new WireKind(50, "TrackerRoutesResponse", INFRASTRUCTURE, TrackerRoutesResponse.class),
            new WireKind(51, "WorldManifestQuery", INFRASTRUCTURE, WorldManifestQuery.class),
            new WireKind(52, "WorldManifestAnswer", INFRASTRUCTURE, WorldManifestAnswer.class),

            new WireKind(53, "ActionForward", CONSENSUS, ActionForward.class),
            new WireKind(54, "EventSyncQuery", CONSENSUS, EventSyncQuery.class),
            new WireKind(55, "EventSyncAnswer", CONSENSUS, EventSyncAnswer.class),
            new WireKind(56, "HaloUpdate", CONSENSUS, HaloUpdate.class),
            new WireKind(57, "GroupMigration", CONSENSUS, GroupMigration.class),
            new WireKind(58, "GenesisApprovalRequest", CONSENSUS, GenesisApprovalRequest.class),
            new WireKind(59, "GenesisApprovalGrant", CONSENSUS, GenesisApprovalGrant.class),
            new WireKind(60, "WorldGrantGossip", CONSENSUS, WorldGrantGossip.class),
            new WireKind(61, "RegionRefusal", CONSENSUS, RegionRefusal.class),
            new WireKind(62, "WorldOwnershipGossip", CONSENSUS, WorldOwnershipGossip.class),

            // --- LAN tunnel: carries somebody else's protocol, deliberately opaque here ---
            new WireKind(63, "TunnelOpen", INFRASTRUCTURE, TunnelOpen.class),
            new WireKind(64, "TunnelData", INFRASTRUCTURE, TunnelData.class),
            new WireKind(65, "TunnelClose", INFRASTRUCTURE, TunnelClose.class),

            new WireKind(66, "WorldDeletionGossip", CONSENSUS, WorldDeletionGossip.class),

            // --- service directory ---
            new WireKind(67, "ServiceAnnounce", INFRASTRUCTURE, ServiceAnnounce.class),
            new WireKind(68, "ServiceAnnounceAck", INFRASTRUCTURE, ServiceAnnounceAck.class),
            new WireKind(69, "ServiceDirectoryQuery", INFRASTRUCTURE, ServiceDirectoryQuery.class),
            new WireKind(70, "ServiceDirectoryResponse", INFRASTRUCTURE,
                    ServiceDirectoryResponse.class),
            new WireKind(71, "ServiceScoreReport", INFRASTRUCTURE, ServiceScoreReport.class),
            new WireKind(72, "ServiceDrainNotice", INFRASTRUCTURE, ServiceDrainNotice.class),

            // --- session control: the negotiated handshake and the answer to an unknown kind.
            // These are what tags 1-4 were supposed to be and never became. ---
            new WireKind(73, "Nack", INFRASTRUCTURE, Nack.class),
            new WireKind(74, "Hello", INFRASTRUCTURE, Hello.class),
            new WireKind(75, "HelloAck", INFRASTRUCTURE, HelloAck.class),

            // --- undoing a deletion: same plane, same evidence rules, opposite instruction ---
            new WireKind(76, "WorldRevivalGossip", CONSENSUS, WorldRevivalGossip.class));

    private static final Map<Integer, WireKind> BY_KIND;
    private static final Map<Class<?>, WireKind> BY_TYPE;

    static {
        Map<Integer, WireKind> byKind = new LinkedHashMap<>();
        Map<Class<?>, WireKind> byType = new LinkedHashMap<>();
        int expected = 1;
        for (WireKind k : KINDS) {
            if (k.kind() != expected) {
                throw new IllegalStateException("wire kinds must be contiguous and ascending; "
                        + "expected " + expected + " but found " + k.kind() + " (" + k.name() + ")");
            }
            if (byType.put(k.type(), k) != null) {
                throw new IllegalStateException(k.type() + " is claimed by two kinds");
            }
            byKind.put(k.kind(), k);
            expected++;
        }
        BY_KIND = Map.copyOf(byKind);
        BY_TYPE = Map.copyOf(byType);
    }

    /** Highest assigned kind; a new message takes {@code NEXT_KIND + 1}. */
    public static final int NEXT_KIND = KINDS.get(KINDS.size() - 1).kind();

    /**
     * Every kind, ascending. The one enumeration diagnostics, the fixture corpus, and the Rust
     * generator all read.
     *
     * @return an immutable ascending list.
     * @Thread-context any thread.
     */
    public static List<WireKind> kinds() {
        return KINDS;
    }

    /**
     * Look up a kind by its wire number.
     *
     * @param kind the number from a frame header.
     * @return the row, or empty when this build does not know the kind — which is a routine event,
     *         not an error: a newer peer may legitimately send a kind this build has never heard
     *         of, and the frame is skipped and answered rather than killing the connection
     *         ({@code Plan.7} D4).
     * @Thread-context any thread.
     */
    public static Optional<WireKind> find(int kind) {
        return Optional.ofNullable(BY_KIND.get(kind));
    }

    /**
     * Look up a kind by its wire number, requiring it to be known.
     *
     * @param kind the number from a frame header.
     * @return the row.
     * @throws IllegalArgumentException if no kind holds that number.
     * @Thread-context any thread.
     */
    public static WireKind require(int kind) {
        WireKind found = BY_KIND.get(kind);
        if (found == null) {
            throw new IllegalArgumentException("unknown message kind: " + kind);
        }
        return found;
    }

    /**
     * The kind that owns a message instance.
     *
     * @param msg the message.
     * @return its row.
     * @throws IllegalStateException if the type is not in the schema — which means a message record
     *         was added without a row here, and would otherwise have reached the network with no
     *         kind, no fixture, and no Rust mirror.
     * @Thread-context any thread.
     */
    public static WireKind of(NoderaMessage msg) {
        if (msg == null) {
            throw new IllegalArgumentException("msg must not be null");
        }
        WireKind found = BY_TYPE.get(msg.getClass());
        if (found == null) {
            throw new IllegalStateException("no wire kind declares " + msg.getClass().getName()
                    + "; add a row to WireRegistry");
        }
        return found;
    }

    /** The kind number for a message instance. */
    public static int kindOf(NoderaMessage msg) {
        return of(msg).kind();
    }

    /** The stable display name of a kind. */
    public static String nameOf(int kind) {
        return require(kind).name();
    }

    /** Which plane governs a kind's body. */
    public static MessagePlane planeOf(int kind) {
        return require(kind).plane();
    }

    /** The kinds on one plane, ascending. */
    public static List<WireKind> onPlane(MessagePlane plane) {
        return KINDS.stream().filter(k -> k.plane() == plane).toList();
    }
}
