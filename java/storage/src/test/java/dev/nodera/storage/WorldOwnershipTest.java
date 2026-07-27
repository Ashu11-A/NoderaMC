package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim that answers "who administers this world", and the ways it must refuse to be forged.
 *
 * <p>Every negative case here is an attack that would otherwise work. The record is gossiped, so it
 * is handled by peers that have no reason to trust each other and every reason to try lifting a
 * valid signature onto a claim that suits them.
 */
final class WorldOwnershipTest {

    private static Bytes worldId(String seed) {
        return new dev.nodera.core.crypto.HashService().sha256(seed.getBytes());
    }

    @Test
    @DisplayName("a claim minted by the world's creator verifies, and names them")
    void aGenuineClaimVerifies() {
        NodeIdentity owner = NodeIdentity.generate();
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));

        WorldOwnership claim = WorldOwnership.create(owner, key, 1000L);

        assertThat(claim.verify()).isTrue();
        assertThat(claim.isOwner(owner.nodeId())).isTrue();
        assertThat(claim.isOwner(NodeIdentity.generate().nodeId())).isFalse();
        assertThat(claim.worldPublicKey()).isEqualTo(key.x509Public());
    }

    @Test
    @DisplayName("canonical round trip keeps both signatures intact")
    void roundTrip() {
        WorldOwnership claim = WorldOwnership.create(
                NodeIdentity.generate(), PersistedWorldKey.generate(worldId("w")), 7L);

        CanonicalWriter w = new CanonicalWriter();
        claim.encode(w);
        WorldOwnership back = WorldOwnership.decode(new CanonicalReader(w.toBytes()));

        assertThat(back).isEqualTo(claim);
        assertThat(back.verify()).isTrue();
    }

    @Test
    @DisplayName("a peer cannot claim a world key it does not hold the private half of")
    void anotherPeersWorldKeyCannotBeClaimed() {
        NodeIdentity author = NodeIdentity.generate();
        PersistedWorldKey realKey = PersistedWorldKey.generate(worldId("w"));
        WorldOwnership genuine = WorldOwnership.create(author, realKey, 1L);

        // The attacker republishes the world's public key under its own node id, signing with its
        // own node key. It cannot produce the world signature, so it re-uses the one it saw.
        NodeIdentity attacker = NodeIdentity.generate();
        WorldOwnership forged = new WorldOwnership(genuine.worldId(), genuine.worldPublicKey(),
                attacker.nodeId(), attacker.publicKeyBytes(), 1L, genuine.worldSignature(),
                Bytes.empty());
        Bytes attackerSignature = attacker.sign(forged.signedPortion());
        forged = new WorldOwnership(genuine.worldId(), genuine.worldPublicKey(), attacker.nodeId(),
                attacker.publicKeyBytes(), 1L, genuine.worldSignature(), attackerSignature);

        // The attacker's own signature is fine. The world signature is not: it covers the owner's
        // node id, so a signature made for the real owner does not verify for a different one.
        assertThat(forged.verify()).isFalse();
    }

    @Test
    @DisplayName("a world signature cannot be lifted onto a different world")
    void aSignatureCannotBeLiftedToAnotherWorld() {
        NodeIdentity owner = NodeIdentity.generate();
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("world-a"));
        WorldOwnership claim = WorldOwnership.create(owner, key, 5L);

        WorldOwnership moved = new WorldOwnership(worldId("world-b"), claim.worldPublicKey(),
                claim.ownerNodeId(), claim.ownerPublicKey(), 5L, claim.worldSignature(),
                claim.ownerSignature());

        assertThat(moved.verify()).isFalse();
    }

    @Test
    @DisplayName("a missing half is not a weaker claim — it is refused outright")
    void bothSignaturesAreRequired() {
        NodeIdentity owner = NodeIdentity.generate();
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        WorldOwnership claim = WorldOwnership.create(owner, key, 3L);

        assertThat(new WorldOwnership(claim.worldId(), claim.worldPublicKey(), claim.ownerNodeId(),
                claim.ownerPublicKey(), 3L, Bytes.empty(), claim.ownerSignature()).verify())
                .as("no world signature: nothing proves the publisher holds the world key")
                .isFalse();
        assertThat(new WorldOwnership(claim.worldId(), claim.worldPublicKey(), claim.ownerNodeId(),
                claim.ownerPublicKey(), 3L, claim.worldSignature(), Bytes.empty()).verify())
                .as("no owner signature: nothing binds the world key to a node")
                .isFalse();
    }

    @Test
    @DisplayName("changing the creation time invalidates the claim")
    void theSignedPortionCoversEveryField() {
        NodeIdentity owner = NodeIdentity.generate();
        PersistedWorldKey key = PersistedWorldKey.generate(worldId("w"));
        WorldOwnership claim = WorldOwnership.create(owner, key, 1000L);

        WorldOwnership retimed = new WorldOwnership(claim.worldId(), claim.worldPublicKey(),
                claim.ownerNodeId(), claim.ownerPublicKey(), 2000L, claim.worldSignature(),
                claim.ownerSignature());

        assertThat(retimed.verify()).isFalse();
    }

    @Test
    @DisplayName("two worlds get two different keys")
    void everyWorldGetsItsOwnKey() {
        NodeIdentity owner = NodeIdentity.generate();
        WorldOwnership a = WorldOwnership.create(owner, PersistedWorldKey.generate(worldId("a")), 1L);
        WorldOwnership b = WorldOwnership.create(owner, PersistedWorldKey.generate(worldId("b")), 1L);

        // The whole reason a world key is not the node key: authority over one world must not be
        // presentable as authority over another.
        assertThat(a.worldPublicKey()).isNotEqualTo(b.worldPublicKey());
        assertThat(a.ownerPublicKey()).isEqualTo(b.ownerPublicKey());
    }
}
