package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.identity.WorldHealth;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionReplicaRole;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.assignment.LeaseRenewal;
import dev.nodera.protocol.assignment.RegionAssigned;
import dev.nodera.protocol.assignment.RegionRevoked;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.content.ArchiveReplicaAck;
import dev.nodera.protocol.content.ArchiveReplicaAssignment;
import dev.nodera.protocol.content.ContentAvailability;
import dev.nodera.protocol.content.ContentChunk;
import dev.nodera.protocol.content.ContentRequest;
import dev.nodera.protocol.content.ManifestHolding;
import dev.nodera.protocol.content.PieceBitmap;
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
import dev.nodera.protocol.session.Hello;
import dev.nodera.protocol.session.HelloAck;
import dev.nodera.protocol.session.Nack;
import dev.nodera.protocol.session.RejectCode;
import dev.nodera.protocol.session.SessionRole;
import dev.nodera.protocol.session.WireFeature;
import dev.nodera.protocol.membership.MembershipUpdate;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.PeerGoodbye;
import dev.nodera.protocol.membership.PeerJoin;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.protocol.membership.WorldDeletionGossip;
import dev.nodera.protocol.membership.WorldGrantGossip;
import dev.nodera.protocol.membership.WorldRevivalGossip;
import dev.nodera.protocol.membership.WorldOwnershipGossip;
import dev.nodera.protocol.rendezvous.CandidateKind;
import dev.nodera.protocol.rendezvous.ObservedAddress;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import dev.nodera.protocol.rendezvous.PunchSync;
import dev.nodera.protocol.rendezvous.RegistrationEvent;
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
import dev.nodera.protocol.service.ServiceLifecycle;
import dev.nodera.protocol.service.ServiceObservation;
import dev.nodera.protocol.service.ServiceRecord;
import dev.nodera.protocol.service.ServiceScore;
import dev.nodera.protocol.service.ServiceScoreReport;
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One deterministic sample instance for <b>every</b> tag in the {@link MessageCodec} registry.
 *
 * <p>Before this class existed, the protocol test suite carried three partial, hand-maintained
 * lists of message types — the golden-fixture corpus, the dispatch round-trip array, and the
 * tag-distinctness array — and all three had drifted: 25 of the (then) 66 tags were dispatch-tested
 * and 5 tags were required to have a fixture. A tag could therefore be appended, wired, and shipped
 * with no proof that it round-trips or that its bytes are stable. This registry is the single
 * source those tests now iterate, and {@link #assertTotal()} fails the build when a new tag is
 * appended without a sample, so the coverage gap cannot reopen silently.
 *
 * <p><b>Determinism is a hard requirement.</b> Every value here is hand-written and fixed — no
 * clocks, no random UUIDs, no {@code Set} iteration whose order could vary — because these samples
 * are encoded into committed golden fixtures. A sample that varies between runs turns a
 * wire-contract test into a flaky one.
 *
 * <p>Samples aim to exercise the <i>encoding</i>, not the semantics: signatures, certificates, and
 * proofs are fixed filler bytes. Cryptographic agreement between Java and Rust is proven at runtime
 * by the service integration tests, not here.
 *
 * <p>Thread-context: immutable static data; any thread.
 */
public final class MessageSamples {

    private MessageSamples() {
    }

    // --- fixed building blocks -------------------------------------------------------------

    private static final UUID NETWORK_ID =
            new UUID(0x0102030405060708L, 0x1112131415161718L);

    private static final NodeId NODE_A =
            new NodeId(new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L));
    private static final NodeId NODE_B =
            new NodeId(new UUID(0x1112131415161718L, 0x191A1B1C1D1E1F20L));
    private static final NodeId NODE_C =
            new NodeId(new UUID(0x2122232425262728L, 0x292A2B2C2D2E2F30L));

    private static final RegionId REGION_A =
            new RegionId(DimensionKey.overworld(), 0, 0);
    private static final RegionId REGION_B =
            new RegionId(DimensionKey.overworld(), 1, 0);
    private static final RegionEpoch EPOCH = RegionEpoch.INITIAL;

    private static Bytes filled(int length, int value) {
        byte[] raw = new byte[length];
        Arrays.fill(raw, (byte) value);
        return Bytes.unsafeWrap(raw);
    }

    private static StateRoot root(int value) {
        return new StateRoot(filled(32, value));
    }

    private static SnapshotVersion version(long value) {
        return new SnapshotVersion(value);
    }

    /**
     * Fixed capabilities. Reliability is encoded as raw {@code doubleToLongBits}, so 1.0 keeps the
     * golden bytes readable (0x3FF0000000000000) and platform-independent.
     */
    private static NodeCapabilities capabilities(Set<PeerRole> roles) {
        return new NodeCapabilities(8, 17_179_869_184L, 42, 1.0d, 4, 8, true, roles);
    }

    private static PeerEntry peerEntry(NodeId id, String route, PeerRole role, boolean bootstrap) {
        return new PeerEntry(id, route, capabilities(Set.of(role)), bootstrap);
    }

    private static RegionDelta delta(RegionId region, long base) {
        return new RegionDelta(region, version(base), version(base + 1), List.of(), root(0x31));
    }

    /** A fixed committee endorsement of a halo slice — the attested form of tag 56. */
    private static dev.nodera.core.Bytes haloEndorsement() {
        dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
        new dev.nodera.core.consensuscert.HaloEndorsement(
                NODE_A, REGION_A, EPOCH, version(5L), root(0x59), filled(64, 0x5A)).encode(w);
        return w.toBytes();
    }

    private static SignedVote vote(NodeId voter, RegionId region) {
        return new SignedVote(voter, region, EPOCH, version(4L), root(0x41),
                root(0x42), root(0x43), VoteDecision.ACCEPT, filled(64, 0x44));
    }

    /**
     * A certificate bound to the delta it certifies: {@code EntityTransferCommit} rejects a
     * certificate whose region, base version, or resulting root disagrees with the source delta.
     */
    private static QuorumCertificate actionCertificate(RegionDelta delta) {
        return new QuorumCertificate(delta.region(), EPOCH, delta.baseVersion(), root(0x51),
                delta.resultingRoot(),
                List.of(vote(NODE_A, delta.region()), vote(NODE_B, delta.region())));
    }

    private static final EntityTransferDescriptor TRANSFER = new EntityTransferDescriptor(
            77L, REGION_A, REGION_B, EPOCH, EPOCH,
            new NetworkEntityId(9_001L),
            version(4L), version(5L), root(0x61), root(0x62), root(0x63),
            version(6L), version(7L), root(0x64), root(0x65), root(0x66),
            1_234L);

    /**
     * A quorum certificate that actually satisfies {@link EntityTransferCertificate}'s validation.
     *
     * <p>The certificate cross-checks every vote against the descriptor — region, epoch, base
     * version, both state roots, the transition root, and the batch root, which is the hash of the
     * descriptor itself — so a sample built from unrelated filler values is rejected by its own
     * constructor. Deriving the votes from the descriptor here is what makes the transfer family
     * constructible at all.
     *
     * @param source true for the source side, false for the target side.
     */
    private static QuorumCertificate transferProof(boolean source) {
        RegionId region = source ? TRANSFER.sourceRegion() : TRANSFER.targetRegion();
        RegionEpoch epoch = source ? TRANSFER.sourceEpoch() : TRANSFER.targetEpoch();
        SnapshotVersion base =
                source ? TRANSFER.sourceBaseVersion() : TRANSFER.targetBaseVersion();
        StateRoot prevRoot = source ? TRANSFER.sourcePrevRoot() : TRANSFER.targetPrevRoot();
        StateRoot resultingRoot =
                source ? TRANSFER.sourceResultingRoot() : TRANSFER.targetResultingRoot();
        StateRoot transitionRoot =
                source ? TRANSFER.sourceTransitionRoot() : TRANSFER.targetTransitionRoot();
        StateRoot approvalRoot = StateRoot.of(new HashService().hash(TRANSFER));
        return new QuorumCertificate(region, epoch, base, prevRoot, resultingRoot, List.of(
                new SignedVote(NODE_A, region, epoch, base, approvalRoot,
                        resultingRoot, transitionRoot, VoteDecision.ACCEPT, filled(64, 0x44)),
                new SignedVote(NODE_B, region, epoch, base, approvalRoot,
                        resultingRoot, transitionRoot, VoteDecision.ACCEPT, filled(64, 0x45))));
    }

    private static ServiceRecord serviceRecord(ServiceLifecycle lifecycle, long drainingSince) {
        return new ServiceRecord(
                NODE_A, filled(44, 0x42), ServiceKind.RENDEZVOUS, lifecycle, NETWORK_ID,
                List.of("rdv.example:25601", "tcp://198.51.100.9:25601"),
                "0.1.0", 120, 5_000, 4, 64, 3,
                1_700_000_000_000L, 1_700_000_300_000L, drainingSince);
    }

    private static ServiceDirectoryEntry serviceEntry() {
        return new ServiceDirectoryEntry(
                serviceRecord(ServiceLifecycle.SERVING, 0L),
                filled(64, 0x77),
                new ServiceScore(980, 35, 90, 900, 1_000, 7, 0).withComposite());
    }

    // --- the registry ----------------------------------------------------------------------

    /**
     * Every registry tag mapped to a deterministic sample of the message that owns it.
     *
     * @return an insertion-ordered map, tag ascending; never empty.
     */
    public static Map<Integer, NoderaMessage> byTag() {
        Map<Integer, NoderaMessage> samples = new LinkedHashMap<>();

        // 1–4: the legacy nominal handshake. Dormant in production (no runtime handler
        // constructs these), but still in the registry, so still pinned.
        samples.put(MessageCodec.TAG_CLIENT_HELLO, new ClientHello(
                1, NODE_A, filled(44, 0x66),
                capabilities(Set.of(PeerRole.REGION_EXECUTOR)), 6, 0x0102030405060708L,
                filled(64, 0x77)));
        samples.put(MessageCodec.TAG_SERVER_HELLO,
                new ServerHello(NETWORK_ID, 1_000L, 8, 3, filled(32, 0x11)));
        samples.put(MessageCodec.TAG_CHALLENGE_RESPONSE, new ChallengeResponse(filled(64, 0x88)));
        samples.put(MessageCodec.TAG_WORKER_ACTIVATION,
                new WorkerActivation(NETWORK_ID, 4, 8, 20L));

        // 5–9: assignment and snapshot streaming.
        samples.put(MessageCodec.TAG_REGION_ASSIGNED, new RegionAssigned(
                REGION_A, EPOCH, RegionReplicaRole.PRIMARY, version(3L), 200L,
                List.of(NODE_A, NODE_B, NODE_C)));
        samples.put(MessageCodec.TAG_REGION_REVOKED,
                new RegionRevoked(REGION_A, EPOCH, "lease-expired"));
        samples.put(MessageCodec.TAG_LEASE_RENEWAL, new LeaseRenewal(REGION_A, EPOCH, 400L));
        samples.put(MessageCodec.TAG_SNAPSHOT_ANNOUNCE,
                new SnapshotAnnounce(REGION_A, version(3L), 4_096, 2, root(0x21)));
        samples.put(MessageCodec.TAG_STREAM_CHUNK,
                new StreamChunk(42L, 0, 2, filled(16, 0x0F)));

        // 10–18: simulation, consensus, health, relay.
        samples.put(MessageCodec.TAG_ACTION_BATCH_MSG, new ActionBatchMsg(new ActionBatch(
                REGION_A, EPOCH, version(4L), 100L, 102L,
                List.of(new ActionEnvelope(NODE_A, 1L, 2L, 101L, REGION_A,
                        new PlaceBlockAction(new NBlockPos(16, 64, 16), 3, 1),
                        filled(64, 0x55))))));
        samples.put(MessageCodec.TAG_REGION_PROPOSAL, new RegionProposal(
                REGION_A, EPOCH, version(4L), 100L, 102L, root(0x41), root(0x42),
                filled(24, 0x43), root(0x44), filled(64, 0x45),
                List.of(new RegionProposal.HaloPin(REGION_B, version(3L)))));
        samples.put(MessageCodec.TAG_VALIDATION_VOTE,
                new ValidationVote(REGION_A, EPOCH, version(5L), vote(NODE_B, REGION_A)));
        samples.put(MessageCodec.TAG_COMMIT_ANNOUNCE,
                new CommitAnnounce(REGION_A, version(5L), root(0x52), filled(48, 0x53)));
        samples.put(MessageCodec.TAG_RESYNC_REQUEST, new ResyncRequest(REGION_A, version(2L)));
        samples.put(MessageCodec.TAG_HEARTBEAT, new Heartbeat(1_000L, new WorkerLoad(7, 99L, 123L)));
        samples.put(MessageCodec.TAG_WORKER_LOAD, new WorkerLoad(7, 99L, 123L));
        samples.put(MessageCodec.TAG_ECHO_TEST, new EchoTest(filled(8, 0xEE)));
        samples.put(MessageCodec.TAG_RELAY_ENVELOPE,
                new RelayEnvelope(NODE_B, filled(12, 0x1E)));

        // 19–23: membership and liveness.
        samples.put(MessageCodec.TAG_PEER_JOIN, new PeerJoin(
                NODE_A, "198.51.100.4:25599",
                capabilities(Set.of(PeerRole.FULL_ARCHIVE)), true,
                filled(44, 0x66), "nodera/0.1.0"));
        samples.put(MessageCodec.TAG_MEMBERSHIP_UPDATE, new MembershipUpdate(
                3L, NODE_A, List.of(
                        peerEntry(NODE_A, "198.51.100.4:25599", PeerRole.FULL_ARCHIVE, true),
                        peerEntry(NODE_B, "203.0.113.7:25599", PeerRole.PARTIAL_ARCHIVE, false))));
        samples.put(MessageCodec.TAG_PEER_GOODBYE, new PeerGoodbye(NODE_B, 4L, "transport-down"));
        samples.put(MessageCodec.TAG_GATEWAY_CLAIM, new GatewayClaim(NODE_A, 5L));
        samples.put(MessageCodec.TAG_SESSION_KEEP_ALIVE, new SessionKeepAlive(
                NODE_A, 9L, List.of(new RegionProgress(REGION_A, EPOCH, NODE_A, 1_024L))));

        // 24–31: the content plane.
        samples.put(MessageCodec.TAG_CONTENT_REQUEST,
                new ContentRequest(filled(32, 0x22), List.of(0, 3, 7)));
        samples.put(MessageCodec.TAG_CONTENT_CHUNK,
                new ContentChunk(filled(32, 0x22), 2, filled(64, 0x23)));
        samples.put(MessageCodec.TAG_CONTENT_AVAILABILITY, new ContentAvailability(
                NODE_A, List.of(new ManifestHolding(filled(32, 0x22),
                        PieceBitmap.of(List.of(0, 3, 9))))));
        samples.put(MessageCodec.TAG_TRACKER_QUERY, new TrackerQuery(filled(32, 0x11)));
        samples.put(MessageCodec.TAG_TRACKER_RESPONSE, new TrackerResponse(
                filled(32, 0x11), "nodera-overworld",
                List.of(peerEntry(NODE_A, "198.51.100.4:25599", PeerRole.WORLD_SEEDER, true)),
                List.of(new ManifestSeeders(filled(32, 0x22), List.of(NODE_A))),
                3L, 4_096L, 9_400, WorldHealth.HEALTHY, 0L));
        samples.put(MessageCodec.TAG_INVENTORY_ADVERTISEMENT, new InventoryAdvertisement(
                filled(32, 0x11), NODE_A,
                List.of(new ManifestHolding(filled(32, 0x22), PieceBitmap.of(List.of(0, 1))))));
        samples.put(MessageCodec.TAG_ARCHIVE_REPLICA_ASSIGNMENT,
                new ArchiveReplicaAssignment(filled(32, 0x22), NODE_B, List.of(0, 2)));
        samples.put(MessageCodec.TAG_ARCHIVE_REPLICA_ACK,
                new ArchiveReplicaAck(filled(32, 0x22), NODE_B, List.of(0, 2)));

        // 32–34: external authority and the tracker announce family.
        samples.put(MessageCodec.TAG_EXTERNAL_DELTA, new ExternalDelta(
                REGION_A, version(6L), filled(24, 0x71), filled(48, 0x72), 1_500L,
                ExternalDelta.EXTERNAL_DELTA_ENCODING_VERSION));
        samples.put(MessageCodec.TAG_TRACKER_ANNOUNCE, new TrackerAnnounce(
                filled(32, 0x11), NODE_A, filled(44, 0x66), AnnounceEvent.STARTED,
                List.of("198.51.100.4:25599", "[2001:db8::4]:25599"),
                capabilities(Set.of(PeerRole.WORLD_SEEDER)),
                List.of(new ManifestHolding(filled(32, 0x22),
                        Bytes.unsafeWrap(new byte[] {(byte) 0xFF, (byte) 0b1100_0000}))),
                "nodera-overworld", 0L, 9_400, 3L, 1_700_000_000_000L, filled(64, 0x77)));
        samples.put(MessageCodec.TAG_TRACKER_ANNOUNCE_ACK, TrackerAnnounceAck.accepted(120));

        // 35–43: rendezvous, relay, hole punching.
        SignedRecord signed = new SignedRecord(new SignedPeerRecord(
                NETWORK_ID, filled(32, 0x11), NODE_A, filled(44, 0x66),
                RegistrationEvent.REGISTER,
                List.of(new PeerCandidate(CandidateKind.HOST, "10.0.0.4:25566", 100),
                        new PeerCandidate(CandidateKind.RELAY, "198.51.100.9:25601", 1)),
                capabilities(Set.of(PeerRole.PARTIAL_ARCHIVE)),
                1_700_000_000_000L, 1_700_000_300_000L), filled(64, 0x77));
        samples.put(MessageCodec.TAG_RENDEZVOUS_REGISTER, new RendezvousRegister(signed));
        samples.put(MessageCodec.TAG_RENDEZVOUS_DISCOVER,
                new RendezvousDiscover(NETWORK_ID, filled(32, 0x11), 0, 50));
        samples.put(MessageCodec.TAG_RENDEZVOUS_PEERS, new RendezvousPeers(7, List.of(signed)));
        samples.put(MessageCodec.TAG_RELAY_RESERVE,
                new RelayReserve(NETWORK_ID, filled(32, 0x11), NODE_A));
        samples.put(MessageCodec.TAG_RELAY_RESERVATION, new RelayReservation(
                true, "198.51.100.9:25601", 1_700_000_300_000L, 67_108_864L, 300_000L,
                filled(32, 0x55), ""));
        samples.put(MessageCodec.TAG_RELAY_CONNECT,
                new RelayConnect(NETWORK_ID, filled(32, 0x11), NODE_B, NODE_A));
        samples.put(MessageCodec.TAG_RELAY_INCOMING, new RelayIncoming(
                NETWORK_ID, filled(32, 0x11), NODE_B, NODE_A, filled(32, 0x55)));
        samples.put(MessageCodec.TAG_PUNCH_SYNC, new PunchSync(
                NETWORK_ID, filled(32, 0x11), NODE_B, NODE_A,
                List.of(new PeerCandidate(CandidateKind.SERVER_REFLEXIVE,
                        "198.51.100.7:40000", 50)),
                1_700_000_001_000L));
        samples.put(MessageCodec.TAG_OBSERVED_ADDRESS,
                new ObservedAddress(NODE_B, "198.51.100.7:40000"));

        // 44–53: catalog, entity transfer, routes, manifests, action forwarding.
        samples.put(MessageCodec.TAG_TRACKER_CATALOG_QUERY, new TrackerCatalogQuery(25));
        samples.put(MessageCodec.TAG_TRACKER_CATALOG_RESPONSE, new TrackerCatalogResponse(
                List.of(new TrackerCatalogEntry(filled(32, 0x11), "nodera-overworld",
                        3, 4_096, 9_750, WorldHealth.HEALTHY, 0L))));
        samples.put(MessageCodec.TAG_ENTITY_TRANSFER_PREPARE, new EntityTransferPrepare(
                TRANSFER, delta(REGION_A, 4L), delta(REGION_B, 6L)));
        samples.put(MessageCodec.TAG_ENTITY_TRANSFER_ACCEPT, new EntityTransferAccept(
                TRANSFER.transferId(), REGION_A, transferProof(true).votes().get(0)));
        samples.put(MessageCodec.TAG_ENTITY_TRANSFER_COMMIT, new EntityTransferCommit(
                new EntityTransferCertificate(TRANSFER, transferProof(true),
                        transferProof(false)),
                actionCertificate(delta(REGION_A, 4L)),
                delta(REGION_A, 4L), delta(REGION_B, 6L)));
        samples.put(MessageCodec.TAG_TRACKER_ROUTES_QUERY, new TrackerRoutesQuery(filled(32, 0x11)));
        samples.put(MessageCodec.TAG_TRACKER_ROUTES_RESPONSE, new TrackerRoutesResponse(
                filled(32, 0x11), List.of(new TrackerRoutesResponse.PeerRoutes(
                        NODE_A, List.of("192.168.0.9:25566", "mc/192.168.0.9:25565")))));
        samples.put(MessageCodec.TAG_WORLD_MANIFEST_QUERY,
                new WorldManifestQuery(filled(32, 0x11)));
        samples.put(MessageCodec.TAG_WORLD_MANIFEST_ANSWER, new WorldManifestAnswer(
                filled(32, 0x11), List.of(filled(16, 0x12), filled(24, 0x13))));
        samples.put(MessageCodec.TAG_ACTION_FORWARD,
                new ActionForward(REGION_A, filled(72, 0x53)));

        // 54–62: event sync, halo, migration, genesis approval, gossip lanes.
        samples.put(MessageCodec.TAG_EVENT_SYNC_QUERY, new EventSyncQuery(REGION_A, 12L));
        samples.put(MessageCodec.TAG_EVENT_SYNC_ANSWER, new EventSyncAnswer(
                REGION_A, List.of(filled(32, 0x54), filled(32, 0x55)),
                List.of(filled(48, 0x56))));
        samples.put(MessageCodec.TAG_HALO_UPDATE, new HaloUpdate(
                REGION_A, version(5L), List.of(filled(16, 0x57), filled(16, 0x58)),
                haloEndorsement()));
        samples.put(MessageCodec.TAG_GROUP_MIGRATION, new GroupMigration(NODE_C, List.of(
                new GroupMigration.RegionEpochBump(REGION_A, EPOCH.bump()),
                new GroupMigration.RegionEpochBump(REGION_B, EPOCH.bump()))));
        samples.put(MessageCodec.TAG_GENESIS_APPROVAL_REQUEST, new GenesisApprovalRequest(
                root(0x58), List.of(
                        new GenesisApprovalRequest.FounderEntry(NODE_A, filled(44, 0x66)),
                        new GenesisApprovalRequest.FounderEntry(NODE_B, filled(44, 0x67)))));
        samples.put(MessageCodec.TAG_GENESIS_APPROVAL_GRANT,
                new GenesisApprovalGrant(root(0x58), NODE_A, filled(64, 0x59)));
        samples.put(MessageCodec.TAG_WORLD_GRANT_GOSSIP,
                new WorldGrantGossip(filled(32, 0x11), filled(96, 0x60)));
        samples.put(MessageCodec.TAG_REGION_REFUSAL,
                new RegionRefusal(REGION_A, RegionRefusal.Reason.CHUNKS_NOT_LOADED));
        samples.put(MessageCodec.TAG_WORLD_OWNERSHIP_GOSSIP,
                new WorldOwnershipGossip(filled(32, 0x11), filled(112, 0x62)));

        // 63–66: the LAN tunnel and world deletion.
        samples.put(MessageCodec.TAG_TUNNEL_OPEN, new TunnelOpen(filled(16, 0x63), 1L));
        samples.put(MessageCodec.TAG_TUNNEL_DATA, new TunnelData(1L, filled(128, 0x64)));
        samples.put(MessageCodec.TAG_TUNNEL_CLOSE, new TunnelClose(1L, "peer-closed"));
        samples.put(MessageCodec.TAG_WORLD_DELETION_GOSSIP,
                new WorldDeletionGossip(filled(32, 0x11), filled(160, 0x66)));
        samples.put(MessageCodec.TAG_WORLD_REVIVAL_GOSSIP,
                new WorldRevivalGossip(filled(32, 0x11), filled(160, 0x67)));

        // 67–72: the service directory.
        samples.put(MessageCodec.TAG_SERVICE_ANNOUNCE, new ServiceAnnounce(
                serviceRecord(ServiceLifecycle.SERVING, 0L), filled(64, 0x77)));
        samples.put(MessageCodec.TAG_SERVICE_ANNOUNCE_ACK,
                new ServiceAnnounceAck(true, 120, "", List.of(serviceEntry())));
        samples.put(MessageCodec.TAG_SERVICE_DIRECTORY_QUERY,
                new ServiceDirectoryQuery(ServiceKind.RENDEZVOUS, NETWORK_ID, 8));
        samples.put(MessageCodec.TAG_SERVICE_DIRECTORY_RESPONSE,
                new ServiceDirectoryResponse(List.of(serviceEntry())));
        samples.put(MessageCodec.TAG_SERVICE_SCORE_REPORT, new ServiceScoreReport(
                NODE_B, filled(44, 0x66), NETWORK_ID,
                List.of(new ServiceObservation(NODE_A, ServiceKind.RENDEZVOUS,
                        20, 19, 35, 90, 1_700_000_060_000L)),
                1_700_000_060_000L, filled(64, 0x88)));
        samples.put(MessageCodec.TAG_SERVICE_DRAIN_NOTICE, new ServiceDrainNotice(
                serviceRecord(ServiceLifecycle.DRAINING, 1_700_000_030_000L),
                filled(64, 0x77), List.of(serviceEntry()), "update"));

        // --- session control (Task 14): the negotiated handshake and the coded refusal ---
        samples.put(MessageCodec.TAG_NACK, new Nack(
                MessageCodec.TAG_TRACKER_QUERY, RejectCode.UNSUPPORTED_KIND, 0x0102030405060708L,
                "this build does not know kind 27"));

        samples.put(MessageCodec.TAG_HELLO, new Hello(
                2, "nodera/0.1.0",
                Set.of(WireFeature.KEEP_ALIVE_REGION_PROGRESS.code(),
                        WireFeature.SERVICE_DIRECTORY.code()),
                7, 0x0BADC0DE0BADC0DEL,
                new UUID(0x1122334455667788L, 0x99AABBCCDDEEFF00L),
                NODE_A, filled(44, 0x66),
                capabilities(Set.of(PeerRole.FULL_ARCHIVE)),
                filled(64, 0x77)));

        samples.put(MessageCodec.TAG_HELLO_ACK, new HelloAck(
                2, Set.of(WireFeature.KEEP_ALIVE_REGION_PROGRESS.code()),
                SessionRole.OBSERVER,
                new UUID(0x1122334455667788L, 0x99AABBCCDDEEFF00L),
                RejectCode.RULES_VERSION_MISMATCH,
                "rules version 7 != 6"));

        return samples;
    }

    /**
     * Fail if the registry and this class have drifted apart.
     *
     * <p>Called by the tests that iterate {@link #byTag()}: appending a tag to
     * {@link MessageCodec} without adding a sample here must break the build at the point the tag
     * is added, not at the point somebody notices the gap.
     *
     * @throws AssertionError if a known tag has no sample, or a sample does not encode under the
     *     tag it is filed against.
     */
    public static void assertTotal() {
        Map<Integer, NoderaMessage> samples = byTag();
        for (int tag : MessageCodec.KNOWN_TAGS) {
            NoderaMessage sample = samples.get(tag);
            if (sample == null) {
                throw new AssertionError("no sample for tag " + tag + " ("
                        + MessageCodec.typeName(tag) + "): append one to MessageSamples so the "
                        + "golden-fixture and round-trip tests cover it");
            }
            int actual = MessageCodec.typeTagOf(sample);
            if (actual != tag) {
                throw new AssertionError("sample filed under tag " + tag + " ("
                        + MessageCodec.typeName(tag) + ") encodes as tag " + actual);
            }
        }
        for (Integer tag : samples.keySet()) {
            if (!MessageCodec.KNOWN_TAGS.contains(tag)) {
                throw new AssertionError("sample for tag " + tag + " which the registry does "
                        + "not know");
            }
        }
    }

    /**
     * The fixture file name a tag's golden bytes are stored under.
     *
     * @param tag a known registry tag.
     * @return a kebab-case file name, e.g. {@code region-proposal.bin}.
     */
    public static String fixtureName(int tag) {
        String name = MessageCodec.typeName(tag);
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('-');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.append(".bin").toString();
    }
}
