package dev.nodera.protocol;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.codec.MessageCodec;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The service-directory family's canonical encoding, and the score arithmetic peers depend on. */
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
