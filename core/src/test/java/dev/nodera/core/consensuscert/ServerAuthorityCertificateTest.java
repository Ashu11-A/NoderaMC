package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ServerAuthorityCertificate} checks (Task 11): signature exclusion, round-trip over every
 * reason, real Ed25519 verify/tamper-reject (this certificate is what lets replicas apply an
 * {@code ExternalDelta} without voting), and the version-advance guard.
 */
final class ServerAuthorityCertificateTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final StateRoot ROOT = StateRoot.of(Bytes.fromHex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"));

    @Test
    void signedPortionIsStrictPrefixOfEncodeAndExcludesSignature() {
        ServerAuthorityCertificate cert = sample(Bytes.fromHex("deadbeef"));
        byte[] signed = cert.signedPortion().toArray();
        byte[] full = encode(cert);
        assertThat(full.length).isGreaterThan(signed.length);
        for (int i = 0; i < signed.length; i++) {
            assertThat(full[i]).isEqualTo(signed[i]);
        }
        assertThat(cert.signedPortion().toHex()).doesNotContain("deadbeef");
    }

    @Test
    void encodeDecodeRoundTripAllReasons() {
        for (ServerAuthorityCertificate.Reason reason : ServerAuthorityCertificate.Reason.values()) {
            ServerAuthorityCertificate cert = new ServerAuthorityCertificate(
                    REGION, new SnapshotVersion(3), new SnapshotVersion(4), ROOT, reason,
                    Bytes.fromHex("abcd"));
            ServerAuthorityCertificate decoded =
                    ServerAuthorityCertificate.decode(new CanonicalReader(encode(cert)));
            assertThat(decoded).isEqualTo(cert);
        }
    }

    @Test
    void realSignatureVerifiesAndTamperIsRejected() {
        NodeIdentity server = NodeIdentity.generate();
        ServerAuthorityCertificate unsigned = sample(Bytes.empty());
        Bytes signature = server.sign(unsigned.signedPortion());
        ServerAuthorityCertificate cert = sample(signature);

        SignatureService sigs = new SignatureService();
        assertThat(sigs.verify(server.publicKeyBytes(), cert.signedPortion(), cert.serverSignature()))
                .isTrue();

        // Tampering with any signed field (here: the resulting version) invalidates the signature.
        ServerAuthorityCertificate tampered = new ServerAuthorityCertificate(
                cert.region(), cert.baseVersion(), new SnapshotVersion(9), cert.resultingRoot(),
                cert.reason(), cert.serverSignature());
        assertThat(sigs.verify(server.publicKeyBytes(), tampered.signedPortion(), tampered.serverSignature()))
                .isFalse();
    }

    @Test
    void resultingVersionMustAdvance() {
        assertThatThrownBy(() -> new ServerAuthorityCertificate(
                REGION, new SnapshotVersion(4), new SnapshotVersion(4), ROOT,
                ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION, Bytes.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("advance");
    }

    private static ServerAuthorityCertificate sample(Bytes signature) {
        return new ServerAuthorityCertificate(
                REGION, new SnapshotVersion(3), new SnapshotVersion(4), ROOT,
                ServerAuthorityCertificate.Reason.EXTERNAL_MUTATION, signature);
    }

    private static byte[] encode(ServerAuthorityCertificate cert) {
        CanonicalWriter w = new CanonicalWriter();
        cert.encode(w);
        return w.toBytes().toArray();
    }
}
