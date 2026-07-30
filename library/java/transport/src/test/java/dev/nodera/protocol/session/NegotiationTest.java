package dev.nodera.protocol.session;

import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.membership.RegionProgress;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.wire.WireRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The negotiation, and the compatibility matrix behind it (Task 14 phase 4).
 *
 * <p>These are the exit conditions of two limitation rows:
 *
 * <ul>
 *   <li><b>L-87 / R2</b> — a peer whose rules version differs is admitted as
 *       {@link SessionRole#OBSERVER} with a coded reason at the handshake, and never reaches an
 *       engine throw. Before this, nothing was compared at all: the carrier was authenticated, a
 *       {@code PeerJoin} was sent, and the peer was in.</li>
 *   <li><b>L-88 / R3</b> — peer(features = A) × peer(features = B): the emitted set is the
 *       intersection, and a message carrying a feature the peer did not accept is demoted before it
 *       is encoded rather than sent and silently dropped.</li>
 * </ul>
 *
 * <p>Thread-context: JUnit test; single-threaded.
 */
class NegotiationTest {

    private static final UUID NETWORK = new UUID(0x1122334455667788L, 0x99AABBCCDDEEFF00L);
    private static final SignatureService SIGNATURES = new SignatureService();

    private static NodeCapabilities capabilities() {
        return new NodeCapabilities(8, 1L << 34, 42, 1.0d, 4, 8, true,
                Set.of(PeerRole.FULL_ARCHIVE));
    }

    private static Negotiation.LocalProfile profile(int rulesVersion, long fingerprint,
                                                    Set<Integer> features) {
        return new Negotiation.LocalProfile("nodera/test", rulesVersion, fingerprint, NETWORK,
                features, capabilities());
    }

    private static Negotiation.LocalProfile profile(int rulesVersion) {
        return profile(rulesVersion, 0xF00DL, WireFeature.all());
    }

    // ---------------------------------------------------------------- L-87

    @Test
    @DisplayName("L-87: matching peers are admitted, with the reason field empty")
    void matchingPeersAreAdmitted() {
        NodeIdentity joiner = NodeIdentity.generate();
        HelloAck ack = Negotiation.respond(Negotiation.hello(joiner, profile(6)), profile(6),
                joiner.nodeId(), SIGNATURES);

        assertThat(ack.role()).isEqualTo(SessionRole.ADMITTED);
        assertThat(ack.reject()).isEqualTo(RejectCode.NONE);
        assertThat(ack.consensusCompatible()).isTrue();
        assertThat(Negotiation.accept(joiner.nodeId(), ack).consensusCompatible()).isTrue();
    }

    @Test
    @DisplayName("L-87: a rules-version mismatch yields OBSERVER with a coded reason, not a refusal")
    void aRulesVersionMismatchYieldsObserver() {
        NodeIdentity joiner = NodeIdentity.generate();
        HelloAck ack = Negotiation.respond(Negotiation.hello(joiner, profile(7)), profile(6),
                joiner.nodeId(), SIGNATURES);

        assertThat(ack.role())
                .withFailMessage("a version-skewed peer is still a seeder, a relay and a tunnel "
                        + "endpoint; refusing it costs the network and buys nothing")
                .isEqualTo(SessionRole.OBSERVER);
        assertThat(ack.reject()).isEqualTo(RejectCode.RULES_VERSION_MISMATCH);
        assertThat(ack.detail()).contains("7").contains("6");
        assertThat(ack.consensusCompatible()).isFalse();
        assertThat(ack.refused()).isFalse();

        PeerSession session = Negotiation.accept(joiner.nodeId(), ack);
        assertThat(session.role()).isEqualTo(SessionRole.OBSERVER);
    }

    @Test
    @DisplayName("L-87: a registry-fingerprint mismatch takes the same route")
    void aRegistryFingerprintMismatchYieldsObserver() {
        NodeIdentity joiner = NodeIdentity.generate();
        HelloAck ack = Negotiation.respond(
                Negotiation.hello(joiner, profile(6, 0xBEEFL, WireFeature.all())),
                profile(6, 0xF00DL, WireFeature.all()), joiner.nodeId(), SIGNATURES);

        assertThat(ack.role()).isEqualTo(SessionRole.OBSERVER);
        assertThat(ack.reject()).isEqualTo(RejectCode.REGISTRY_FINGERPRINT_MISMATCH);
    }

    @Test
    @DisplayName("L-87: a peer cannot name itself — the body is checked against the carrier")
    void aClaimedIdentityIsCheckedAgainstTheTransport() {
        NodeIdentity joiner = NodeIdentity.generate();
        NodeId whoTheCarrierProved = NodeIdentity.generate().nodeId();

        HelloAck ack = Negotiation.respond(Negotiation.hello(joiner, profile(6)), profile(6),
                whoTheCarrierProved, SIGNATURES);

        assertThat(ack.refused()).isTrue();
        assertThat(ack.reject()).isEqualTo(RejectCode.IDENTITY_MISMATCH);
        assertThatThrownBy(() -> Negotiation.accept(joiner.nodeId(), ack))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IDENTITY_MISMATCH");
    }

    @Test
    @DisplayName("L-87: a forged signature is refused")
    void aForgedSignatureIsRefused() {
        NodeIdentity joiner = NodeIdentity.generate();
        NodeIdentity impostor = NodeIdentity.generate();
        Hello genuine = Negotiation.hello(joiner, profile(6));
        Hello forged = new Hello(genuine.wireEpoch(), genuine.productVersion(), genuine.features(),
                genuine.rulesVersion(), genuine.registryFingerprint(), genuine.networkId(),
                genuine.nodeId(), impostor.publicKeyBytes(), genuine.capabilities(),
                genuine.signature());

        HelloAck ack = Negotiation.respond(forged, profile(6), joiner.nodeId(), SIGNATURES);
        assertThat(ack.reject()).isEqualTo(RejectCode.BAD_SIGNATURE);
    }

    @Test
    @DisplayName("L-87: a peer dialling the wrong network is told so, in one frame")
    void theWrongNetworkIsRefusedExplicitly() {
        NodeIdentity joiner = NodeIdentity.generate();
        Negotiation.LocalProfile elsewhere = new Negotiation.LocalProfile("nodera/test", 6, 0xF00DL,
                new UUID(1, 2), WireFeature.all(), capabilities());

        HelloAck ack = Negotiation.respond(Negotiation.hello(joiner, elsewhere), profile(6),
                joiner.nodeId(), SIGNATURES);
        assertThat(ack.reject()).isEqualTo(RejectCode.WRONG_NETWORK);
    }

    @Test
    @DisplayName("L-87: an epoch this build does not speak is refused at the handshake")
    void aForeignEpochIsRefused() {
        NodeIdentity joiner = NodeIdentity.generate();
        Hello genuine = Negotiation.hello(joiner, profile(6));
        Hello fromAnotherGeneration = new Hello(WireRegistry.WIRE_EPOCH + 1,
                genuine.productVersion(), genuine.features(), genuine.rulesVersion(),
                genuine.registryFingerprint(), genuine.networkId(), genuine.nodeId(),
                genuine.publicKey(), genuine.capabilities(), genuine.signature());

        HelloAck ack = Negotiation.respond(fromAnotherGeneration, profile(6), joiner.nodeId(),
                SIGNATURES);
        assertThat(ack.reject()).isEqualTo(RejectCode.UNSUPPORTED_EPOCH);
    }

    // ---------------------------------------------------------------- L-88

    @Test
    @DisplayName("L-88: the compatibility matrix — the selected set is the intersection, never a union")
    void theSelectedFeatureSetIsTheIntersection() {
        Set<Integer> a = Set.of(WireFeature.KEEP_ALIVE_REGION_PROGRESS.code(),
                WireFeature.PROPOSAL_BATCH_ROOT.code(),
                WireFeature.EXTERNAL_DELTA_TICK.code(),
                WireFeature.LAN_TUNNEL.code());
        Set<Integer> b = Set.of(WireFeature.PROPOSAL_BATCH_ROOT.code(),
                WireFeature.EXTERNAL_DELTA_TICK.code(),
                WireFeature.SERVICE_DIRECTORY.code());

        assertThat(WireFeature.intersect(a, b))
                .containsExactlyInAnyOrder(WireFeature.PROPOSAL_BATCH_ROOT.code(),
                        WireFeature.EXTERNAL_DELTA_TICK.code());
        // Symmetric: both ends compute the same profile, which is what makes it an agreement.
        assertThat(WireFeature.intersect(b, a)).isEqualTo(WireFeature.intersect(a, b));
        assertThat(WireFeature.intersect(a, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("L-88: a negotiated session emits only what the peer accepted")
    void emissionHonoursTheNegotiatedProfile() {
        // The exact failure: this build always emitted keep-alive body version 2, so a peer that
        // only understood version 1 could not read it and eventually declared the sender dead.
        NodeIdentity joiner = NodeIdentity.generate();
        Set<Integer> older = WireFeature.all();
        older.remove(WireFeature.KEEP_ALIVE_REGION_PROGRESS.code());

        HelloAck ack = Negotiation.respond(
                Negotiation.hello(joiner, profile(6, 0xF00DL, older)),
                profile(6), joiner.nodeId(), SIGNATURES);
        PeerSession session = Negotiation.accept(joiner.nodeId(), ack);

        assertThat(session.supports(WireFeature.KEEP_ALIVE_REGION_PROGRESS)).isFalse();

        SessionKeepAlive rich = new SessionKeepAlive(joiner.nodeId(), 42L,
                List.of(new RegionProgress(new RegionId(DimensionKey.overworld(), 1, 2),
                        new RegionEpoch(3L), joiner.nodeId(), 99L)));

        SessionKeepAlive shaped = (SessionKeepAlive) session.shapeForEmit(rich);
        assertThat(shaped.regionProgress())
                .withFailMessage("the peer said it cannot read region progress, so it must not be "
                        + "sent any — that is the whole point of negotiating")
                .isEmpty();
        assertThat(shaped.from()).isEqualTo(rich.from());
        assertThat(shaped.seq()).isEqualTo(rich.seq());

        // And the frame the peer actually receives carries the reduced form.
        assertThat(WireCodec.decode(session.encodeFor(rich, 0, 0L))).isEqualTo(shaped);
    }

    @Test
    @DisplayName("L-88: a peer that accepted the feature still receives the full message")
    void afullyCapablePeerReceivesEverything() {
        NodeIdentity joiner = NodeIdentity.generate();
        HelloAck ack = Negotiation.respond(Negotiation.hello(joiner, profile(6)), profile(6),
                joiner.nodeId(), SIGNATURES);
        PeerSession session = Negotiation.accept(joiner.nodeId(), ack);

        SessionKeepAlive rich = new SessionKeepAlive(joiner.nodeId(), 42L,
                List.of(new RegionProgress(new RegionId(DimensionKey.overworld(), 1, 2),
                        new RegionEpoch(3L), joiner.nodeId(), 99L)));
        assertThat(session.shapeForEmit(rich)).isEqualTo(rich);
    }

    @Test
    @DisplayName("a session with no handshake assumes nothing")
    void anUnnegotiatedSessionAssumesNothing() {
        PeerSession session = PeerSession.conservative(NodeIdentity.generate().nodeId());
        assertThat(session.features()).isEmpty();
        assertThat(session.consensusCompatible()).isFalse();
        for (WireFeature f : WireFeature.values()) {
            assertThat(session.supports(f)).isFalse();
        }
    }

    @Test
    @DisplayName("a Hello round-trips through the new wire with its signature intact")
    void aHelloSurvivesTheWire() {
        NodeIdentity joiner = NodeIdentity.generate();
        Hello hello = Negotiation.hello(joiner, profile(6));
        Hello decoded = (Hello) WireCodec.decode(WireCodec.encode(hello));

        assertThat(decoded).isEqualTo(hello);
        assertThat(SIGNATURES.verify(decoded.publicKey(), decoded.signedPortion(),
                decoded.signature()))
                .withFailMessage("the signed portion must survive the round trip byte-exactly, or "
                        + "no peer can verify a hello it received")
                .isTrue();
    }
}
