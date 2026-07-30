package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proving administration of a world, and the three ways a proof must fail.
 *
 * <p>A verifier holds only the world's public key (from a gossiped {@link WorldOwnership}). Nothing
 * else about the exchange is trustworthy, so each test here is one thing a hostile presenter would
 * try: replay a captured proof, forward somebody else's, or answer with the wrong world's key.
 */
final class WorldAdminProofTest {

    private static Bytes worldId(String seed) {
        return new dev.nodera.core.crypto.HashService().sha256(seed.getBytes());
    }

    private static Bytes challenge(String text) {
        return Bytes.unsafeWrap(text.getBytes());
    }

    @Test
    @DisplayName("the administrator answers its own challenge and the world key verifies it")
    void aGenuineProofVerifies() {
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        NodeId admin = NodeIdentity.generate().nodeId();
        Bytes nonce = challenge("nonce-1");

        WorldAdminProof proof = WorldAdminProof.create(key, admin, nonce, 1000L);

        assertThat(proof.verify(key.x509Public(), nonce, admin)).isTrue();
    }

    @Test
    @DisplayName("a proof captured from one exchange does not answer the next challenge")
    void aProofIsNotReplayable() {
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        NodeId admin = NodeIdentity.generate().nodeId();

        WorldAdminProof proof = WorldAdminProof.create(key, admin, challenge("first"), 1L);

        // The verifier issued a fresh nonce; the replayed proof answers the previous one.
        assertThat(proof.verify(key.x509Public(), challenge("second"), admin)).isFalse();
    }

    @Test
    @DisplayName("a proof cannot be forwarded by a node that is not its claimant")
    void aProofIsBoundToItsClaimant() {
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        NodeId admin = NodeIdentity.generate().nodeId();
        NodeId relay = NodeIdentity.generate().nodeId();
        Bytes nonce = challenge("nonce");

        WorldAdminProof proof = WorldAdminProof.create(key, admin, nonce, 1L);

        // The signature is genuine; the presenter is not the node it was made for.
        assertThat(proof.verify(key.x509Public(), nonce, relay)).isFalse();
    }

    @Test
    @DisplayName("another world's key does not verify this world's proof")
    void aProofIsBoundToItsWorldKey() {
        PersistedWorldKey mine = PersistedWorldKey.generate(worldId("mine"));
        PersistedWorldKey theirs = PersistedWorldKey.generate(worldId("theirs"));
        NodeId admin = NodeIdentity.generate().nodeId();
        Bytes nonce = challenge("nonce");

        WorldAdminProof proof = WorldAdminProof.create(mine, admin, nonce, 1L);

        assertThat(proof.verify(theirs.x509Public(), nonce, admin)).isFalse();
    }

    @Test
    @DisplayName("an empty challenge is refused rather than signed")
    void anEmptyChallengeIsRefused() {
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        NodeId admin = NodeIdentity.generate().nodeId();

        // Signing it would make every proof for this world byte-identical, so seeing one proof
        // would be as good as holding the key.
        assertThatThrownBy(() -> WorldAdminProof.create(key, admin, Bytes.empty(), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("challenge");
    }

    @Test
    @DisplayName("canonical round trip keeps the proof verifiable")
    void roundTrip() {
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        NodeId admin = NodeIdentity.generate().nodeId();
        Bytes nonce = challenge("nonce");
        WorldAdminProof proof = WorldAdminProof.create(key, admin, nonce, 99L);

        CanonicalWriter w = new CanonicalWriter();
        proof.encode(w);
        WorldAdminProof back = WorldAdminProof.decode(new CanonicalReader(w.toBytes()));

        assertThat(back).isEqualTo(proof);
        assertThat(back.verify(key.x509Public(), nonce, admin)).isTrue();
        assertThat(back.issuedAtEpoch()).isEqualTo(99L);
    }

    @Test
    @DisplayName("a null expectation verifies nothing rather than throwing")
    void missingExpectationsFailClosed() {
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        NodeId admin = NodeIdentity.generate().nodeId();
        WorldAdminProof proof = WorldAdminProof.create(key, admin, challenge("n"), 1L);

        assertThat(proof.verify(null, challenge("n"), admin)).isFalse();
        assertThat(proof.verify(key.x509Public(), null, admin)).isFalse();
        assertThat(proof.verify(key.x509Public(), challenge("n"), null)).isFalse();
    }
}
