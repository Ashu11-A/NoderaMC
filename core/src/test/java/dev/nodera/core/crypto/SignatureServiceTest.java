package dev.nodera.core.crypto;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SignatureService}: Ed25519 verify against signatures produced by
 * {@link NodeIdentity}, plus tamper and wrong-key rejection.
 */
class SignatureServiceTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void verifyAcceptsGenuineSignature() {
        NodeIdentity identity = NodeIdentity.generate();
        byte[] data = bytes("nodera-canonical-payload");
        Bytes sig = identity.sign(Bytes.unsafeWrap(data));

        SignatureService svc = new SignatureService();
        assertThat(svc.verify(identity.publicKeyBytes(),
                Bytes.unsafeWrap(data), sig)).isTrue();
    }

    @Test
    void verifyRejectsTamperedData() {
        NodeIdentity identity = NodeIdentity.generate();
        byte[] data = bytes("nodera-canonical-payload");
        Bytes sig = identity.sign(Bytes.unsafeWrap(data));

        byte[] tampered = data.clone();
        tampered[0] ^= 0x01;

        SignatureService svc = new SignatureService();
        assertThat(svc.verify(identity.publicKeyBytes(),
                Bytes.unsafeWrap(tampered), sig)).isFalse();
    }

    @Test
    void verifyRejectsTamperedSignature() {
        NodeIdentity identity = NodeIdentity.generate();
        byte[] data = bytes("nodera-canonical-payload");
        byte[] sig = identity.sign(Bytes.unsafeWrap(data)).toArray();

        sig[0] ^= 0xFF;

        SignatureService svc = new SignatureService();
        assertThat(svc.verify(identity.publicKeyBytes(),
                Bytes.unsafeWrap(data), Bytes.unsafeWrap(sig))).isFalse();
    }

    @Test
    void verifyRejectsWrongKey() {
        NodeIdentity signer = NodeIdentity.generate();
        NodeIdentity other = NodeIdentity.generate();
        byte[] data = bytes("nodera-canonical-payload");
        Bytes sig = signer.sign(Bytes.unsafeWrap(data));

        SignatureService svc = new SignatureService();
        assertThat(svc.verify(other.publicKeyBytes(),
                Bytes.unsafeWrap(data), sig)).isFalse();
    }

    /** Realistic ~256-byte canonical payload built via the canonical encoding. */
    private static byte[] realisticPayload() {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU16(TypeTags.ACTION_ENVELOPE).writeU16(Encodable.ENCODING_VERSION);
        w.writeBytes(new byte[248]);
        return w.toByteArray();
    }

    @Test
    void verifyRoundTripsRealisticCanonicalPayload() {
        NodeIdentity identity = NodeIdentity.generate();
        byte[] payload = realisticPayload();
        assertThat(payload.length).isEqualTo(256);

        Bytes sig = identity.sign(Bytes.unsafeWrap(payload));

        SignatureService svc = new SignatureService();
        assertThat(svc.verify(identity.publicKeyBytes(),
                Bytes.unsafeWrap(payload), sig)).isTrue();
    }

    @Test
    void encodePublicKeyMatchesIdentityBytes() {
        NodeIdentity identity = NodeIdentity.generate();
        SignatureService svc = new SignatureService();
        assertThat(svc.encodePublicKey(identity.publicKey())).isEqualTo(identity.publicKeyBytes());
    }
}
