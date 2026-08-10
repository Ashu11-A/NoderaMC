package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.EntityTransferCertificate;
import dev.nodera.core.consensuscert.QuorumCertificate;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.EntityKind;
import dev.nodera.core.state.EntityMutation;
import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.EntityTransferIntent;
import dev.nodera.core.state.FixedVec3;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;
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
import dev.nodera.protocol.simulationmsg.EntityTransferAccept;
import dev.nodera.protocol.simulationmsg.EntityTransferCommit;
import dev.nodera.protocol.simulationmsg.EntityTransferPrepare;
import dev.nodera.protocol.simulationmsg.GroupMigration;
import dev.nodera.protocol.simulationmsg.HaloUpdate;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One message family per nest: each asserts that its own wire types round-trip through
 * {@link dev.nodera.protocol.codec.MessageCodec} and refuse what they should refuse.
 *
 * <p>Five sibling classes over one subject, and the same claim in each: encode, decode, get the
 * value back, and get the same bytes back. The golden-fixture and totality tests
 * ({@code MessageCodecGoldenTest}, {@code MessageCodecTypeTagTest}, {@code WireFixtureTest}) stay
 * separate on purpose — they iterate every tag rather than testing a family, and the difference
 * between "this family works" and "no family is missing" is the difference this repository keeps
 * losing.
 */
final class MessageFamilyCodecTest {

    /**
     * Task 13 increment 9 (L-26): the border-lane protocol messages — tags 56/57 appended to the
     * frozen wire contract. Halo slices travel as opaque encoded column frames (the transport
     * plane never interprets region state); a migration order carries the shared primary and one
     * epoch bump per group region.
     */
    @Nested
    final class BorderLaneMessageCodecTest {

        private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 3, -2);

        @Test
        void haloUpdateRoundTripsByteExactly() {
            HaloUpdate update = new HaloUpdate(
                    REGION, new SnapshotVersion(41),
                    List.of(Bytes.unsafeWrap(new byte[]{1, 2, 3}), Bytes.unsafeWrap(new byte[]{4, 5})));
            byte[] encoded = MessageCodec.encode(update);
            NoderaMessage decoded = MessageCodec.decode(encoded);
            assertThat(decoded).isEqualTo(update);
            assertThat(MessageCodec.encode(decoded)).containsExactly(encoded);
            assertThat(MessageCodec.typeTagOf(update)).isEqualTo(MessageCodec.TAG_HALO_UPDATE);
        }

        @Test
        void groupMigrationRoundTripsByteExactly() {
            GroupMigration migration = new GroupMigration(
                    new NodeId(new UUID(7, 9)),
                    List.of(
                            new GroupMigration.RegionEpochBump(REGION, new RegionEpoch(5)),
                            new GroupMigration.RegionEpochBump(
                                    new RegionId(DimensionKey.overworld(), 4, -2),
                                    new RegionEpoch(3))));
            byte[] encoded = MessageCodec.encode(migration);
            NoderaMessage decoded = MessageCodec.decode(encoded);
            assertThat(decoded).isEqualTo(migration);
            assertThat(MessageCodec.encode(decoded)).containsExactly(encoded);
            assertThat(MessageCodec.typeTagOf(migration))
                    .isEqualTo(MessageCodec.TAG_GROUP_MIGRATION);
        }

        @Test
        void emptyMigrationIsRejected() {
            assertThatThrownBy(() -> new GroupMigration(new NodeId(new UUID(1, 1)), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one region");
        }
    }

    @Nested
    final class EntityTransferMessageCodecTest {

        private static final HashService HASHES = new HashService();
        private static final RegionId SOURCE = new RegionId(DimensionKey.overworld(), 0, 0);
        private static final RegionId TARGET = new RegionId(DimensionKey.overworld(), 1, 0);
        private static final RegionEpoch SOURCE_EPOCH = new RegionEpoch(1);
        private static final RegionEpoch TARGET_EPOCH = new RegionEpoch(2);

        @Test
        void prepareAcceptAndCommitRoundTripByteExactly() {
            Fixture fixture = fixture();
            EntityTransferPrepare prepare = new EntityTransferPrepare(
                    fixture.descriptor, fixture.sourceDelta, fixture.targetDelta);
            EntityTransferAccept accept = new EntityTransferAccept(
                    fixture.descriptor.transferId(), SOURCE,
                    fixture.certificate.sourceProof().votes().getFirst());
            EntityTransferCommit commit = new EntityTransferCommit(
                    fixture.certificate, fixture.certificate.sourceProof(),
                    fixture.sourceDelta, fixture.targetDelta);
            for (NoderaMessage message : List.of(prepare, accept, commit)) {
                byte[] encoded = MessageCodec.encode(message);
                NoderaMessage decoded = MessageCodec.decode(encoded);
                assertThat(decoded).isEqualTo(message);
                assertThat(MessageCodec.encode(decoded)).containsExactly(encoded);
            }
            assertThat(MessageCodec.typeTagOf(prepare))
                    .isEqualTo(MessageCodec.TAG_ENTITY_TRANSFER_PREPARE);
            assertThat(MessageCodec.typeTagOf(accept))
                    .isEqualTo(MessageCodec.TAG_ENTITY_TRANSFER_ACCEPT);
            assertThat(MessageCodec.typeTagOf(commit))
                    .isEqualTo(MessageCodec.TAG_ENTITY_TRANSFER_COMMIT);
        }

        private static Fixture fixture() {
            PersistedEntityState sourceEntity = entity(FixedVec3.ofBlock(127, 5, 1));
            PersistedEntityState targetEntity = entity(FixedVec3.ofBlock(128, 5, 1));
            SnapshotVersion base = SnapshotVersion.INITIAL;
            SnapshotVersion next = base.next();
            RegionDelta sourceDelta = new RegionDelta(
                    SOURCE, base, next, List.of(), root(2),
                    List.of(new EntityMutation(sourceEntity.id(), sourceEntity, null)), List.of(),
                    List.of(new EntityTransferIntent(TARGET, targetEntity)));
            RegionDelta targetDelta = new RegionDelta(
                    TARGET, base, next, List.of(), root(4),
                    List.of(new EntityMutation(targetEntity.id(), null, targetEntity)), List.of());
            EntityTransferDescriptor descriptor = new EntityTransferDescriptor(
                    7, SOURCE, TARGET, SOURCE_EPOCH, TARGET_EPOCH, sourceEntity.id(),
                    base, next, root(1), sourceDelta.resultingRoot(),
                    StateRoot.of(HASHES.hash(sourceDelta)), base, next, root(3),
                    targetDelta.resultingRoot(), StateRoot.of(HASHES.hash(targetDelta)), 50);
            StateRoot approvalRoot = StateRoot.of(HASHES.hash(descriptor));
            QuorumCertificate sourceProof = proof(descriptor, approvalRoot, true);
            QuorumCertificate targetProof = proof(descriptor, approvalRoot, false);
            return new Fixture(descriptor, sourceDelta, targetDelta,
                    new EntityTransferCertificate(descriptor, sourceProof, targetProof));
        }

        private static QuorumCertificate proof(
                EntityTransferDescriptor descriptor, StateRoot approvalRoot, boolean source) {
            var members = List.of(NodeIdentity.generate(), NodeIdentity.generate(), NodeIdentity.generate());
            var region = source ? descriptor.sourceRegion() : descriptor.targetRegion();
            var epoch = source ? descriptor.sourceEpoch() : descriptor.targetEpoch();
            var base = source ? descriptor.sourceBaseVersion() : descriptor.targetBaseVersion();
            var previous = source ? descriptor.sourcePrevRoot() : descriptor.targetPrevRoot();
            var result = source ? descriptor.sourceResultingRoot() : descriptor.targetResultingRoot();
            var transition = source
                    ? descriptor.sourceTransitionRoot() : descriptor.targetTransitionRoot();
            List<SignedVote> votes = members.stream().map(member -> {
                SignedVote unsigned = new SignedVote(
                        member.nodeId(), region, epoch, base, approvalRoot,
                        result, transition, VoteDecision.ACCEPT, Bytes.empty());
                return new SignedVote(
                        member.nodeId(), region, epoch, base, approvalRoot,
                        result, transition, VoteDecision.ACCEPT,
                        member.sign(unsigned.signedPortion()));
            }).toList();
            return new QuorumCertificate(region, epoch, base, previous, result, votes);
        }

        private static PersistedEntityState entity(FixedVec3 position) {
            return new PersistedEntityState(
                    new NetworkEntityId(9), EntityKind.ITEM, 4, position, FixedVec3.ZERO,
                    2, 6_000, Bytes.unsafeWrap(new byte[]{0, 0, 0, 4, 1}));
        }

        private static StateRoot root(int fill) {
            byte[] bytes = new byte[32];
            java.util.Arrays.fill(bytes, (byte) fill);
            return StateRoot.of(Bytes.unsafeWrap(bytes));
        }

        private record Fixture(
                EntityTransferDescriptor descriptor,
                RegionDelta sourceDelta,
                RegionDelta targetDelta,
                EntityTransferCertificate certificate) {
        }
    }

    /** The service-directory family's canonical encoding, and the score arithmetic peers depend on. */
    @Nested
    final class ServiceMessageCodecTest {

        private static final UUID NETWORK = new UUID(0x0102030405060708L, 0x1112131415161718L);
        private static final NodeId SERVICE = new NodeId(new UUID(9, 10));
        private static final NodeId REPORTER = new NodeId(new UUID(3, 4));

        private static Bytes filled(int length, int value) {
            byte[] bytes = new byte[length];
            java.util.Arrays.fill(bytes, (byte) value);
            return Bytes.unsafeWrap(bytes);
        }

        private static ServiceRecord record() {
            return new ServiceRecord(SERVICE, filled(44, 0x42), ServiceKind.RENDEZVOUS,
                    ServiceLifecycle.SERVING, NETWORK,
                    List.of("rdv.example:25601", "tcp://198.51.100.9:25601"), "0.1.0",
                    120, 5_000, 4, 64, 3,
                    1_700_000_000_000L, 1_700_000_300_000L, 0L);
        }

        private static ServiceScore score() {
            return new ServiceScore(980, 35, 90, 900, 1_000, 7, 0).withComposite();
        }

        private static ServiceDirectoryEntry entry() {
            return new ServiceDirectoryEntry(record(), filled(64, 0x77), score());
        }

        private static List<NoderaMessage> samples() {
            return List.of(
                    new ServiceAnnounce(record(), filled(64, 0x77)),
                    new ServiceAnnounceAck(true, 120, "", List.of(entry())),
                    new ServiceAnnounceAck(false, 120, "quota", List.of()),
                    new ServiceDirectoryQuery(ServiceKind.RENDEZVOUS, NETWORK, 8),
                    new ServiceDirectoryQuery(ServiceKind.TRACKER, NETWORK, 0),
                    new ServiceDirectoryResponse(List.of(entry())),
                    new ServiceDirectoryResponse(List.of()),
                    new ServiceScoreReport(REPORTER, filled(44, 0x66), NETWORK,
                            List.of(new ServiceObservation(SERVICE, ServiceKind.RENDEZVOUS,
                                    20, 19, 35, 90, 1_700_000_060_000L)),
                            1_700_000_060_000L, filled(64, 0x88)),
                    new ServiceDrainNotice(
                            new ServiceRecord(SERVICE, filled(44, 0x42), ServiceKind.RENDEZVOUS,
                                    ServiceLifecycle.DRAINING, NETWORK, List.of("rdv.example:25601"),
                                    "0.1.0", 120, 5_000, 4, 64, 3,
                                    1_700_000_000_000L, 1_700_000_300_000L, 1_700_000_030_000L),
                            filled(64, 0x77), List.of(entry()), ServiceDrainNotice.REASON_UPDATE));
        }

        @Test
        void everyServiceMessageRoundTripsByteExactly() {
            for (NoderaMessage message : samples()) {
                byte[] encoded = MessageCodec.encode(message);
                NoderaMessage decoded = MessageCodec.decode(encoded);
                assertThat(decoded)
                        .as("%s round-trips to an equal value", message.getClass().getSimpleName())
                        .isEqualTo(message);
                assertThat(MessageCodec.encode(decoded))
                        .as("%s re-encodes to identical bytes", message.getClass().getSimpleName())
                        .isEqualTo(encoded);
            }
        }

        @Test
        void aScoreReportsSignatureCoversTheFrameMinusItself() {
            // The same rule TrackerAnnounce follows: the signed portion owns the frame header, so a
            // captured report cannot be replayed as a different message type.
            ServiceScoreReport report = new ServiceScoreReport(REPORTER, filled(44, 0x66), NETWORK,
                    List.of(new ServiceObservation(SERVICE, ServiceKind.RENDEZVOUS,
                            20, 19, 35, 90, 1_700_000_060_000L)),
                    1_700_000_060_000L, filled(64, 0x88));
            byte[] frame = MessageCodec.encode(report);
            Bytes signed = report.signedPortion();
            assertThat(java.util.Arrays.copyOfRange(frame, 0, signed.length()))
                    .isEqualTo(signed.toArray());
            // Tag first, so the signature is bound to "this is a score report".
            assertThat(signed.toArray()[0]).isZero();
            assertThat(signed.toArray()[1]).isEqualTo((byte) MessageCodec.TAG_SERVICE_SCORE_REPORT);
        }

        @Test
        void aRecordsSignedBytesAreIdenticalInEveryCarrier() {
            // The property that stops a tracker re-wording a record it is merely carrying: a peer that
            // verified a directory row verified the record the service announced.
            ServiceRecord shared = record();
            Bytes fromAnnounce = decodeAnnounce(new ServiceAnnounce(shared, filled(64, 0x77)))
                    .record().signedBytes();
            Bytes fromDirectory = ((ServiceDirectoryResponse) MessageCodec.decode(MessageCodec.encode(
                    new ServiceDirectoryResponse(List.of(
                            new ServiceDirectoryEntry(shared, filled(64, 0x77), score()))))))
                    .entries().get(0).record().signedBytes();
            Bytes fromDrain = ((ServiceDrainNotice) MessageCodec.decode(MessageCodec.encode(
                    new ServiceDrainNotice(shared, filled(64, 0x77), List.of(), "operator"))))
                    .record().signedBytes();
            assertThat(fromAnnounce).isEqualTo(shared.signedBytes());
            assertThat(fromDirectory).isEqualTo(shared.signedBytes());
            assertThat(fromDrain).isEqualTo(shared.signedBytes());
        }

        private static ServiceAnnounce decodeAnnounce(ServiceAnnounce announce) {
            return (ServiceAnnounce) MessageCodec.decode(MessageCodec.encode(announce));
        }

        @Test
        void theCompositeIsAFunctionOfItsComponentsAndNothingElse() {
            assertThat(ServiceScore.composite(1_000, 1, 1_000, 1_000)).isEqualTo(999);
            // Nothing reachable, nothing fresh: only the capacity term survives, weighted 20 of 100 — so a
            // service nobody can reach cannot outrank a working one.
            assertThat(ServiceScore.composite(0, 10_000, 1_000, 0)).isEqualTo(200);
            assertThat(ServiceScore.composite(0, 0, 0, 0)).isZero();
            // Out-of-range inputs are clamped rather than allowed to overflow the scale.
            assertThat(ServiceScore.composite(5_000, 1, 5_000, 5_000)).isEqualTo(999);
            // An unmeasured RTT contributes nothing rather than everything.
            assertThat(ServiceScore.composite(1_000, 0, 1_000, 1_000)).isEqualTo(700);
        }

        @Test
        void theLatencyTermIsLinearToTheCeilingAndThenZero() {
            assertThat(ServiceScore.latencyPermille(0))
                    .as("zero is the unmeasured sentinel, not an instant round trip")
                    .isZero();
            assertThat(ServiceScore.latencyPermille(1)).isEqualTo(999);
            assertThat(ServiceScore.latencyPermille(ServiceScore.LATENCY_CEILING_MILLIS / 2))
                    .isEqualTo(500);
            assertThat(ServiceScore.latencyPermille(ServiceScore.LATENCY_CEILING_MILLIS)).isZero();
            assertThat(ServiceScore.latencyPermille(-1))
                    .as("an unmeasured RTT scores nothing rather than perfectly")
                    .isZero();
        }

        @Test
        void anUnstatedCeilingReadsAsFreeRatherThanFull() {
            // Penalising silence would make "publish no limits" the winning move for every operator.
            ServiceRecord unstated = new ServiceRecord(SERVICE, filled(44, 0x42),
                    ServiceKind.RENDEZVOUS, ServiceLifecycle.SERVING, NETWORK, List.of("h:1"), "0.1.0",
                    9_999, 0, 9_999, 0, 0, 1L, 2L, 0L);
            assertThat(unstated.capacityPermille()).isEqualTo(1_000);
        }

        @Test
        void capacityTakesTheTighterOfTheTwoCeilings() {
            ServiceRecord tight = new ServiceRecord(SERVICE, filled(44, 0x42), ServiceKind.RENDEZVOUS,
                    ServiceLifecycle.SERVING, NETWORK, List.of("h:1"), "0.1.0",
                    100, 1_000, 60, 64, 0, 1L, 2L, 0L);
            assertThat(tight.capacityPermille()).isEqualTo(62);
        }

        @Test
        void aTransmittedCompositeIsCheckableAgainstItsComponents() {
            assertThat(score().compositePermille()).isEqualTo(score().recomputedComposite());
            ServiceScore liar = new ServiceScore(0, 0, 5_000, 0, 0, 1, 1_000);
            assertThat(liar.compositePermille()).isNotEqualTo(liar.recomputedComposite());
        }

        @Test
        void onlyServingAcceptsNewWork() {
            assertThat(ServiceLifecycle.SERVING.acceptsNewWork()).isTrue();
            assertThat(ServiceLifecycle.STARTING.acceptsNewWork()).isFalse();
            assertThat(ServiceLifecycle.DRAINING.acceptsNewWork()).isFalse();
            assertThat(ServiceLifecycle.STOPPED.acceptsNewWork()).isFalse();
        }

        @Test
        void anObservationCannotClaimMoreSuccessesThanProbes() {
            assertThatThrownBy(() -> new ServiceObservation(SERVICE, ServiceKind.RENDEZVOUS,
                    5, 6, 10, 10, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds probes");
        }

        @Test
        void anObservationWithNoProbesIsNotEvidence() {
            ServiceObservation empty =
                    new ServiceObservation(SERVICE, ServiceKind.RENDEZVOUS, 0, 0, 0, 0, 1L);
            assertThat(empty.availabilityPermille()).isZero();
        }

        @Test
        void everyServiceTagHasAStableDisplayName() {
            assertThat(MessageCodec.typeName(MessageCodec.TAG_SERVICE_ANNOUNCE))
                    .isEqualTo("ServiceAnnounce");
            assertThat(MessageCodec.typeName(MessageCodec.TAG_SERVICE_ANNOUNCE_ACK))
                    .isEqualTo("ServiceAnnounceAck");
            assertThat(MessageCodec.typeName(MessageCodec.TAG_SERVICE_DIRECTORY_QUERY))
                    .isEqualTo("ServiceDirectoryQuery");
            assertThat(MessageCodec.typeName(MessageCodec.TAG_SERVICE_DIRECTORY_RESPONSE))
                    .isEqualTo("ServiceDirectoryResponse");
            assertThat(MessageCodec.typeName(MessageCodec.TAG_SERVICE_SCORE_REPORT))
                    .isEqualTo("ServiceScoreReport");
            assertThat(MessageCodec.typeName(MessageCodec.TAG_SERVICE_DRAIN_NOTICE))
                    .isEqualTo("ServiceDrainNotice");
        }

        @Test
        void aTrailingByteInvalidatesEveryServiceFrame() {
            for (NoderaMessage message : samples()) {
                byte[] encoded = MessageCodec.encode(message);
                byte[] extended = java.util.Arrays.copyOf(encoded, encoded.length + 1);
                assertThatThrownBy(() -> MessageCodec.decode(extended))
                        .as("%s must reject a trailing byte", message.getClass().getSimpleName())
                        .isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        void anInvalidOrdinalIsRejectedRatherThanDefaulted() {
            byte[] encoded = MessageCodec.encode(
                    new ServiceDirectoryQuery(ServiceKind.RENDEZVOUS, NETWORK, 1));
            // The kind ordinal is the first body byte after the 4-byte tag+version header.
            encoded[4] = 0x7F;
            assertThatThrownBy(() -> MessageCodec.decode(encoded))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ServiceKind");
        }
    }

    /** Focused compatibility and canonical-encoding coverage for the tag 23 version-2 upgrade. */
    @Nested
    final class SessionKeepAliveCodecTest {

        private static final NodeId FROM = node(1);
        private static final NodeId PRIMARY = node(4);
        private static final RegionId OVERWORLD_ZERO =
                new RegionId(DimensionKey.overworld(), 0, 0);

        /** Hand-built retired frame: tag 23, v1, sender node 1, sequence 2, and no further body. */
        private static final String RETIRED_V1_HEX =
                "00170001"
                        + "0001000100000000000000000000000000000001"
                        + "0000000000000002";

        /** Hand-derived v2 frame: sender, sequence, then overworld (0,0) / epoch 3 / node 4 / tick 5. */
        private static final String V2_GOLDEN_HEX =
                "00170002"
                        + "0001000100000000000000000000000000000001"
                        + "0000000000000002"
                        + "00000001"
                        + "000b0001000a0001000000096d696e656372616674"
                        + "000000096f766572776f726c640000000000000000"
                        + "000c00010000000000000003"
                        + "0001000100000000000000000000000000000004"
                        + "0000000000000005";

        /**
         * Version 1 is gone as of 0.2.0 (issue #214). It ended after the sequence number and carried no
         * per-region progress; nothing has emitted it since the {@code NDR2} flag day, and on this wire
         * a keep-alive travels as an infrastructure TLV body rather than through this codec at all. A
         * v1 frame is now refused at the version, before a byte of body is read — the same treatment
         * every other retired spelling gets, rather than being silently reinterpreted.
         */
        @Test
        void refusesTheRetiredV1FrameRatherThanReadingItAsEmptyProgress() {
            byte[] retired = Bytes.fromHex(RETIRED_V1_HEX).toArray();

            assertThatThrownBy(() -> MessageCodec.decode(retired))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unsupported SessionKeepAlive encoding version 1");
        }

        @Test
        void v2GoldenFrameMatchesExactCanonicalBytes() {
            SessionKeepAlive message = new SessionKeepAlive(FROM, 2L, List.of(
                    new RegionProgress(OVERWORLD_ZERO, new RegionEpoch(3L), PRIMARY, 5L)));

            byte[] encoded = MessageCodec.encode(message);

            assertThat(Bytes.unsafeWrap(encoded).toHex()).isEqualTo(V2_GOLDEN_HEX);
            assertThat(encoded).hasSize(118);
        }

        @Test
        void v2RoundTripsAllProgressFields() {
            SessionKeepAlive original = new SessionKeepAlive(FROM, 19L, List.of(
                    progress(new RegionId(DimensionKey.overworld(), 4, -2), 8L, node(9), 300L),
                    progress(new RegionId(DimensionKey.of("minecraft", "the_nether"), -1, 7),
                            9L, node(10), 299L)));

            SessionKeepAlive decoded =
                    (SessionKeepAlive) MessageCodec.decode(MessageCodec.encode(original));

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.regionProgress()).hasSize(2);
        }

        @Test
        void progressIsImmutableAndCanonicallySortedByRegionId() {
            RegionProgress high = progress(new RegionId(DimensionKey.overworld(), 1, 0), 1, node(7), 9);
            RegionProgress low = progress(new RegionId(DimensionKey.overworld(), -1, 4), 1, node(7), 7);
            RegionProgress middle = progress(new RegionId(DimensionKey.overworld(), 0, -3), 1, node(7), 8);
            List<RegionProgress> input = new ArrayList<>(List.of(high, low, middle));

            SessionKeepAlive shuffled = new SessionKeepAlive(FROM, 3L, input);
            SessionKeepAlive alreadySorted =
                    new SessionKeepAlive(FROM, 3L, List.of(low, middle, high));
            input.clear();

            assertThat(shuffled.regionProgress()).containsExactly(low, middle, high);
            assertThat(MessageCodec.encode(shuffled)).isEqualTo(MessageCodec.encode(alreadySorted));
            assertThatThrownBy(() -> shuffled.regionProgress().add(high))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void rejectsDuplicateRegionsAndNegativeTicks() {
            RegionProgress first = progress(OVERWORLD_ZERO, 1L, node(2), 10L);
            RegionProgress contradictory = progress(OVERWORLD_ZERO, 2L, node(3), 11L);

            assertThatThrownBy(() -> new SessionKeepAlive(
                    FROM, 4L, List.of(first, contradictory)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate region");
            assertThatThrownBy(() -> progress(OVERWORLD_ZERO, 1L, node(2), -1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastAppliedTick");
        }

        @Test
        void decoderRejectsDuplicateRegionsAndNegativeTicksInV2Frames() {
            byte[] golden = Bytes.fromHex(V2_GOLDEN_HEX).toArray();
            byte[] duplicate = duplicateOnlyProgressEntry(golden);
            byte[] negativeTick = golden.clone();
            Arrays.fill(negativeTick, negativeTick.length - Long.BYTES, negativeTick.length, (byte) 0xff);

            assertThatThrownBy(() -> MessageCodec.decode(duplicate))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate region");
            assertThatThrownBy(() -> MessageCodec.decode(negativeTick))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastAppliedTick");
        }

        @Test
        void rejectsVersionsEitherSideOfTwoAndTrailingData() {
            byte[] tooNew = Bytes.fromHex(V2_GOLDEN_HEX).toArray();
            tooNew[3] = 3;
            byte[] tooOld = Bytes.fromHex(V2_GOLDEN_HEX).toArray();
            tooOld[3] = 1;
            byte[] v2 = Bytes.fromHex(V2_GOLDEN_HEX).toArray();
            byte[] v2Trailing = Arrays.copyOf(v2, v2.length + 1);

            assertThatThrownBy(() -> MessageCodec.decode(tooNew))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unsupported SessionKeepAlive encoding version 3");
            assertThatThrownBy(() -> MessageCodec.decode(tooOld))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unsupported SessionKeepAlive encoding version 1");
            assertThatThrownBy(() -> MessageCodec.decode(v2Trailing))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("trailing");
        }

        @Test
        void globalVersionAndTagRegistryRemainUnchanged() {
            assertThat(Bytes.unsafeWrap(MessageCodec.encode(new EchoTest(Bytes.empty()))).toHex())
                    .isEqualTo("0011000100000000");
            assertThatThrownBy(() -> MessageCodec.decode(Bytes.fromHex("0011000200000000").toArray()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unsupported message encoding version 2");
            assertThat(MessageCodec.ENCODING_VERSION).isEqualTo(1);
            assertThat(MessageCodec.TAG_SESSION_KEEP_ALIVE).isEqualTo(23);
            // The watermark moves only when a tag is appended; existing tags never move, which is what
            // this assertion is really guarding.
            assertThat(MessageCodec.NEXT_TAG).isEqualTo(76);
            assertThat(MessageCodec.KNOWN_TAGS).hasSize(76).doesNotHaveDuplicates();
        }

        private static RegionProgress progress(
                RegionId region, long epoch, NodeId primary, long lastAppliedTick) {
            return new RegionProgress(region, new RegionEpoch(epoch), primary, lastAppliedTick);
        }

        private static NodeId node(long value) {
            return new NodeId(new UUID(0L, value));
        }

        private static byte[] duplicateOnlyProgressEntry(byte[] singleEntryFrame) {
            int listCountOffset = 4 + 20 + Long.BYTES;
            int entryOffset = listCountOffset + Integer.BYTES;
            int entryLength = singleEntryFrame.length - entryOffset;
            byte[] duplicate = Arrays.copyOf(singleEntryFrame, singleEntryFrame.length + entryLength);
            duplicate[listCountOffset + Integer.BYTES - 1] = 2;
            System.arraycopy(singleEntryFrame, entryOffset, duplicate, singleEntryFrame.length, entryLength);
            return duplicate;
        }
    }

    /**
     * {@link RelayEnvelope} encode/decode round-trip coverage (Task 4 §"NeoForgeRelayTransport"):
     * the inner frame is opaque bytes, untouched by the codec.
     *
     * <p>Thread-context: single test thread.
     */
    @Nested
    final class RelayEnvelopeTest {

        @Test
        void relayEnvelopeRoundTripsWithOpaqueInnerFrame() {
            NodeId target = new NodeId(UUID.fromString("00000000-0000-0000-0000-0000000000ab"));
            byte[] innerFrame = MessageCodec.encode(new EchoTest(Bytes.fromHex("cafebabe")));

            RelayEnvelope env = new RelayEnvelope(target, Bytes.unsafeWrap(innerFrame));

            RelayEnvelope decoded = (RelayEnvelope) MessageCodec.decode(MessageCodec.encode(env));
            assertThat(decoded).isEqualTo(env);
            assertThat(decoded.target()).isEqualTo(target);
            assertThat(decoded.innerFrame().toArray()).isEqualTo(innerFrame);
        }

        @Test
        void relayEnvelopeRoundTripsWithArbitraryBytes() {
            NodeId target = new NodeId(UUID.fromString("12345678-1234-5678-1234-567812345678"));
            Bytes inner = Bytes.fromHex("00112233445566778899aabbccddeeff");

            RelayEnvelope env = new RelayEnvelope(target, inner);

            RelayEnvelope decoded = (RelayEnvelope) MessageCodec.decode(MessageCodec.encode(env));
            assertThat(decoded).isEqualTo(env);
            assertThat(decoded.innerFrame()).isEqualTo(inner);
        }

        @Test
        void typeTagOfRelayEnvelopeMatchesConstant() {
            RelayEnvelope env = new RelayEnvelope(
                    new NodeId(UUID.randomUUID()), Bytes.fromHex("00"));
            assertThat(MessageCodec.typeTagOf(env)).isEqualTo(MessageCodec.TAG_RELAY_ENVELOPE);
        }

        @Test
        void decodeOfEncodeReturnsExactSameClass() {
            RelayEnvelope env = new RelayEnvelope(
                    new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                    Bytes.fromHex("abcd"));
            NoderaMessage decoded = MessageCodec.decode(MessageCodec.encode(env));
            assertThat(decoded.getClass()).isEqualTo(RelayEnvelope.class);
        }
    }
}
