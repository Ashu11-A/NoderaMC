package dev.nodera.core.identity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PersistedNodeIdentity} is secret-bearing disk material, so it must round-trip, restore to
 * a signing identity with the same {@code NodeId}, and never render its private key.
 *
 * <p>Thread-context: single test thread.
 */
final class PersistedNodeIdentityTest {

    @Test
    void generateThenRestoreProducesTheSameNodeIdAndKeys() {
        PersistedNodeIdentity.Generated fresh = PersistedNodeIdentity.generate();
        NodeIdentity restored = fresh.persisted().restore();

        assertThat(restored.nodeId()).isEqualTo(fresh.identity().nodeId());
        assertThat(restored.publicKeyBytes()).isEqualTo(fresh.identity().publicKeyBytes());
    }

    @Test
    void roundTripsThroughCanonicalEncoding() {
        PersistedNodeIdentity original = PersistedNodeIdentity.generate().persisted();
        CanonicalWriter w = new CanonicalWriter();
        original.encode(w);
        byte[] frame = w.toByteArray();

        CanonicalReader peek = new CanonicalReader(frame);
        assertThat(peek.readU16()).isEqualTo(TypeTags.NODE_IDENTITY_SECRET);

        PersistedNodeIdentity decoded = PersistedNodeIdentity.decode(new CanonicalReader(frame));
        assertThat(decoded.nodeId()).isEqualTo(original.nodeId());
        assertThat(decoded.x509Public()).isEqualTo(original.x509Public());
        assertThat(decoded.pkcs8Private()).isEqualTo(original.pkcs8Private());
    }

    @Test
    void restoredIdentityCanSignAndBeVerified() {
        PersistedNodeIdentity.Generated fresh = PersistedNodeIdentity.generate();
        NodeIdentity restored = fresh.persisted().restore();

        Bytes message = Bytes.fromHex("00112233445566778899aabbccddeeff");
        Bytes signature = restored.sign(message);

        SignatureService verifier = new SignatureService();
        assertThat(verifier.verify(restored.publicKeyBytes(), message, signature)).isTrue();
    }

    @Test
    void toStringRedactsThePrivateKey() {
        String rendered = PersistedNodeIdentity.generate().persisted().toString();
        assertThat(rendered).contains("private=<redacted>");
    }

    @Test
    void rejectsEmptyKeyMaterial() {
        NodeIdentity id = NodeIdentity.generate();
        assertThatThrownBy(() -> new PersistedNodeIdentity(id.nodeId(), Bytes.empty(), id.publicKeyBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersistedNodeIdentity(id.nodeId(), id.publicKeyBytes(), Bytes.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
