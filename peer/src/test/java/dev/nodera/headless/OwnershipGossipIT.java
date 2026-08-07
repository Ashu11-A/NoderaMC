package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.membership.WorldOwnershipGossip;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.MeshNode;
import dev.nodera.testkit.peer.PeerTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Who administers this world?</b> — answered across a real mesh, by peers that hold the world's
 * bytes and have no reason to trust each other.
 *
 * <p>A supporting peer used to have no answer at all: it held a copy of somebody's world and knew
 * nothing about the authority behind it. These tests are the lane that fixes that, and every
 * negative case is an attack it must not weaken — because a peer that could take a world over by
 * publishing a louder claim would make the whole binding decorative.
 */
final class OwnershipGossipIT {

    private final PeerTestHarness harness = PeerTestHarness.create();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    /** One node: its identity, transport and ownership lane. */
    private MeshNode<WorldOwnershipService> node() {
        return harness.messageNode(
                (identity, transport, peers) ->
                        new WorldOwnershipService(identity.nodeId(), transport, peers),
                ownership -> ownership::onMessage);
    }

    private static void awaitKnown(MeshNode<WorldOwnershipService> node, String worldIdHex) {
        Await.quietly(5_000, () -> node.service().ownerOf(worldIdHex).isPresent());
    }

    private static Bytes encode(WorldOwnership claim) {
        CanonicalWriter w = new CanonicalWriter();
        claim.encode(w);
        return w.toBytes();
    }

    @Test
    @DisplayName("every peer serving a world learns who administers it, and verifies it itself")
    void anOwnersClaimReachesEveryPeer() {
        MeshNode<WorldOwnershipService> owner = node();
        MeshNode<WorldOwnershipService> supporterA = node();
        MeshNode<WorldOwnershipService> supporterB = node();
        harness.mesh(List.of(owner, supporterA, supporterB));

        Bytes worldId = harness.hashes().sha256("owned-world".getBytes());
        PersistedWorldKey worldKey = PersistedWorldKey.generate(worldId);
        WorldOwnership claim = WorldOwnership.create(owner.identity(), worldKey, 1000L);

        assertThat(owner.service().publish(claim)).isTrue();
        awaitKnown(supporterA, worldId.toHex());
        awaitKnown(supporterB, worldId.toHex());

        for (MeshNode<WorldOwnershipService> n : List.of(owner, supporterA, supporterB)) {
            WorldOwnership seen = n.service().ownerOf(worldId.toHex()).orElseThrow();
            assertThat(seen.ownerNodeId()).isEqualTo(owner.nodeId());
            assertThat(seen.worldPublicKey()).isEqualTo(worldKey.x509Public());
            assertThat(seen.verify()).as("each peer decided for itself, from the bytes").isTrue();
        }
    }

    @Test
    @DisplayName("a second peer cannot take over a world by claiming it later")
    void aLaterClaimDoesNotDisplaceTheOwner() throws Exception {
        MeshNode<WorldOwnershipService> owner = node();
        MeshNode<WorldOwnershipService> supporter = node();
        MeshNode<WorldOwnershipService> impostor = node();
        harness.mesh(List.of(owner, supporter, impostor));

        Bytes worldId = harness.hashes().sha256("contested".getBytes());
        WorldOwnership real = WorldOwnership.create(
                owner.identity(), PersistedWorldKey.generate(worldId), 1000L);
        assertThat(owner.service().publish(real)).isTrue();
        awaitKnown(supporter, worldId.toHex());

        // The impostor mints its OWN key for the same world id and publishes a claim that is
        // internally perfectly valid — both signatures verify, because it signed both.
        WorldOwnership rival = WorldOwnership.create(
                impostor.identity(), PersistedWorldKey.generate(worldId), 2000L);
        assertThat(rival.verify()).as("the forgery is well-formed; that is the point").isTrue();
        impostor.service().publish(rival);
        Thread.sleep(300);

        assertThat(supporter.service().ownerOf(worldId.toHex()).orElseThrow().ownerNodeId())
                .as("first verified claim wins — an owner cannot be overwritten by a louder peer")
                .isEqualTo(owner.nodeId());
        assertThat(owner.service().ownerOf(worldId.toHex()).orElseThrow().ownerNodeId())
                .isEqualTo(owner.nodeId());
    }

    @Test
    @DisplayName("a tampered claim is refused and never relayed")
    void aTamperedClaimIsRefused() throws Exception {
        MeshNode<WorldOwnershipService> attacker = node();
        MeshNode<WorldOwnershipService> victim = node();
        MeshNode<WorldOwnershipService> downstream = node();
        harness.mesh(List.of(attacker, victim, downstream));

        Bytes worldId = harness.hashes().sha256("tampered".getBytes());
        WorldOwnership genuine = WorldOwnership.create(
                NodeIdentity.generate(), PersistedWorldKey.generate(worldId), 1L);
        // Swap the owner for the attacker, keeping the signatures. Neither verifies now.
        WorldOwnership forged = new WorldOwnership(genuine.worldId(), genuine.worldPublicKey(),
                attacker.nodeId(), attacker.identity().publicKeyBytes(), 1L,
                genuine.worldSignature(), genuine.ownerSignature());

        attacker.send(victim, new WorldOwnershipGossip(worldId, encode(forged)));
        Thread.sleep(300);

        assertThat(victim.service().ownerOf(worldId.toHex())).isEmpty();
        assertThat(downstream.service().ownerOf(worldId.toHex()))
                .as("a claim refused here is not passed on")
                .isEmpty();
    }

    @Test
    @DisplayName("an envelope that names a different world than the claim it carries is dropped")
    void theEnvelopeCannotFileAClaimUnderAnotherWorld() throws Exception {
        MeshNode<WorldOwnershipService> relay = node();
        MeshNode<WorldOwnershipService> victim = node();
        harness.mesh(List.of(relay, victim));

        Bytes realWorld = harness.hashes().sha256("real".getBytes());
        Bytes otherWorld = harness.hashes().sha256("other".getBytes());
        WorldOwnership claim = WorldOwnership.create(
                relay.identity(), PersistedWorldKey.generate(realWorld), 1L);

        // The claim itself is genuine. The envelope lies about which world it is for; honouring the
        // envelope would file a valid claim against a world it says nothing about.
        relay.send(victim, new WorldOwnershipGossip(otherWorld, encode(claim)));
        Thread.sleep(300);

        assertThat(victim.service().ownerOf(otherWorld.toHex())).isEmpty();
        assertThat(victim.service().ownerOf(realWorld.toHex())).isEmpty();
    }

    @Test
    @DisplayName("the same claim arriving twice is accepted once and relayed once")
    void gossipTerminates() {
        MeshNode<WorldOwnershipService> owner = node();
        MeshNode<WorldOwnershipService> a = node();
        MeshNode<WorldOwnershipService> b = node();
        harness.mesh(List.of(owner, a, b));

        Bytes worldId = harness.hashes().sha256("flood".getBytes());
        WorldOwnership claim = WorldOwnership.create(
                owner.identity(), PersistedWorldKey.generate(worldId), 1L);
        owner.service().publish(claim);
        awaitKnown(a, worldId.toHex());
        awaitKnown(b, worldId.toHex());

        // A repeat of a claim already held must not be re-flooded, or three meshed peers would
        // trade the same message forever.
        assertThat(a.service().publish(claim)).isFalse();
        assertThat(a.service().knownWorlds()).isEqualTo(1);
        assertThat(b.service().knownWorlds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a peer that was offline learns the owner when the owner republishes")
    void republishReachesALatePeer() {
        MeshNode<WorldOwnershipService> owner = node();
        Bytes worldId = harness.hashes().sha256("late".getBytes());
        WorldOwnership claim = WorldOwnership.create(
                owner.identity(), PersistedWorldKey.generate(worldId), 1L);
        owner.service().publish(claim); // nobody is listening yet

        MeshNode<WorldOwnershipService> latecomer = node();
        harness.mesh(List.of(owner, latecomer));
        owner.service().republish();
        awaitKnown(latecomer, worldId.toHex());

        assertThat(latecomer.service().ownerOf(worldId.toHex()).orElseThrow().ownerNodeId())
                .isEqualTo(owner.nodeId());
    }
}
