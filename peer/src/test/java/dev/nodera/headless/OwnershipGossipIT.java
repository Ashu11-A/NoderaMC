package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.membership.WorldOwnershipGossip;
import dev.nodera.storage.PersistedWorldKey;
import dev.nodera.storage.WorldOwnership;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private final HashService hashes = new HashService();
    private final List<LoopbackTransport> transports = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (LoopbackTransport t : transports) {
            t.stop();
        }
    }

    /** One node: its identity, transport and ownership lane. */
    private final class Node {
        private final NodeIdentity identity;
        private final LoopbackTransport transport;
        private final WorldOwnershipService ownership;
        private final List<PeerEntry> peers = new CopyOnWriteArrayList<>();

        private Node(LoopbackNetwork net) {
            this.identity = NodeIdentity.generate();
            this.transport = net.register(identity.nodeId());
            transports.add(transport);
            this.ownership = new WorldOwnershipService(identity.nodeId(), transport, () -> peers);
            transport.setHandler(new MessageHandler() {
                @Override
                public void onMessage(PeerAddress from, byte[] frame) {
                    NoderaMessage message;
                    try {
                        message = WireCodec.decode(frame);
                    } catch (RuntimeException notOurs) {
                        return;
                    }
                    ownership.onMessage(from, message);
                }

                @Override
                public void onPeerDown(PeerAddress peer) {
                    // nothing to clean up
                }
            });
            transport.start();
        }

        private PeerEntry entry() {
            return new PeerEntry(identity.nodeId(), "loopback", NodeCapabilities.initial(), false,
                    identity.publicKeyBytes(), "test");
        }

        private void send(Node to, NoderaMessage message) {
            transport.send(PeerAddress.of(to.identity.nodeId(), "loopback"),
                    WireCodec.encode(message));
        }
    }

    private void mesh(List<Node> nodes) {
        for (Node n : nodes) {
            for (Node other : nodes) {
                if (other != n) {
                    n.peers.add(other.entry());
                }
            }
        }
    }

    private static void awaitKnown(Node node, String worldIdHex) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && node.ownership.ownerOf(worldIdHex).isEmpty()) {
            Thread.sleep(20);
        }
    }

    private static Bytes encode(WorldOwnership claim) {
        CanonicalWriter w = new CanonicalWriter();
        claim.encode(w);
        return w.toBytes();
    }

    @Test
    @DisplayName("every peer serving a world learns who administers it, and verifies it itself")
    void anOwnersClaimReachesEveryPeer() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node owner = new Node(net);
        Node supporterA = new Node(net);
        Node supporterB = new Node(net);
        mesh(List.of(owner, supporterA, supporterB));

        Bytes worldId = hashes.sha256("owned-world".getBytes());
        PersistedWorldKey worldKey = PersistedWorldKey.generate(worldId);
        WorldOwnership claim = WorldOwnership.create(owner.identity, worldKey, 1000L);

        assertThat(owner.ownership.publish(claim)).isTrue();
        awaitKnown(supporterA, worldId.toHex());
        awaitKnown(supporterB, worldId.toHex());

        for (Node n : List.of(owner, supporterA, supporterB)) {
            WorldOwnership seen = n.ownership.ownerOf(worldId.toHex()).orElseThrow();
            assertThat(seen.ownerNodeId()).isEqualTo(owner.identity.nodeId());
            assertThat(seen.worldPublicKey()).isEqualTo(worldKey.x509Public());
            assertThat(seen.verify()).as("each peer decided for itself, from the bytes").isTrue();
        }
    }

    @Test
    @DisplayName("a second peer cannot take over a world by claiming it later")
    void aLaterClaimDoesNotDisplaceTheOwner() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node owner = new Node(net);
        Node supporter = new Node(net);
        Node impostor = new Node(net);
        mesh(List.of(owner, supporter, impostor));

        Bytes worldId = hashes.sha256("contested".getBytes());
        WorldOwnership real = WorldOwnership.create(
                owner.identity, PersistedWorldKey.generate(worldId), 1000L);
        assertThat(owner.ownership.publish(real)).isTrue();
        awaitKnown(supporter, worldId.toHex());

        // The impostor mints its OWN key for the same world id and publishes a claim that is
        // internally perfectly valid — both signatures verify, because it signed both.
        WorldOwnership rival = WorldOwnership.create(
                impostor.identity, PersistedWorldKey.generate(worldId), 2000L);
        assertThat(rival.verify()).as("the forgery is well-formed; that is the point").isTrue();
        impostor.ownership.publish(rival);
        Thread.sleep(300);

        assertThat(supporter.ownership.ownerOf(worldId.toHex()).orElseThrow().ownerNodeId())
                .as("first verified claim wins — an owner cannot be overwritten by a louder peer")
                .isEqualTo(owner.identity.nodeId());
        assertThat(owner.ownership.ownerOf(worldId.toHex()).orElseThrow().ownerNodeId())
                .isEqualTo(owner.identity.nodeId());
    }

    @Test
    @DisplayName("a tampered claim is refused and never relayed")
    void aTamperedClaimIsRefused() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node attacker = new Node(net);
        Node victim = new Node(net);
        Node downstream = new Node(net);
        mesh(List.of(attacker, victim, downstream));

        Bytes worldId = hashes.sha256("tampered".getBytes());
        WorldOwnership genuine = WorldOwnership.create(
                NodeIdentity.generate(), PersistedWorldKey.generate(worldId), 1L);
        // Swap the owner for the attacker, keeping the signatures. Neither verifies now.
        WorldOwnership forged = new WorldOwnership(genuine.worldId(), genuine.worldPublicKey(),
                attacker.identity.nodeId(), attacker.identity.publicKeyBytes(), 1L,
                genuine.worldSignature(), genuine.ownerSignature());

        attacker.send(victim, new WorldOwnershipGossip(worldId, encode(forged)));
        Thread.sleep(300);

        assertThat(victim.ownership.ownerOf(worldId.toHex())).isEmpty();
        assertThat(downstream.ownership.ownerOf(worldId.toHex()))
                .as("a claim refused here is not passed on")
                .isEmpty();
    }

    @Test
    @DisplayName("an envelope that names a different world than the claim it carries is dropped")
    void theEnvelopeCannotFileAClaimUnderAnotherWorld() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node relay = new Node(net);
        Node victim = new Node(net);
        mesh(List.of(relay, victim));

        Bytes realWorld = hashes.sha256("real".getBytes());
        Bytes otherWorld = hashes.sha256("other".getBytes());
        WorldOwnership claim = WorldOwnership.create(
                relay.identity, PersistedWorldKey.generate(realWorld), 1L);

        // The claim itself is genuine. The envelope lies about which world it is for; honouring the
        // envelope would file a valid claim against a world it says nothing about.
        relay.send(victim, new WorldOwnershipGossip(otherWorld, encode(claim)));
        Thread.sleep(300);

        assertThat(victim.ownership.ownerOf(otherWorld.toHex())).isEmpty();
        assertThat(victim.ownership.ownerOf(realWorld.toHex())).isEmpty();
    }

    @Test
    @DisplayName("the same claim arriving twice is accepted once and relayed once")
    void gossipTerminates() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node owner = new Node(net);
        Node a = new Node(net);
        Node b = new Node(net);
        mesh(List.of(owner, a, b));

        Bytes worldId = hashes.sha256("flood".getBytes());
        WorldOwnership claim = WorldOwnership.create(
                owner.identity, PersistedWorldKey.generate(worldId), 1L);
        owner.ownership.publish(claim);
        awaitKnown(a, worldId.toHex());
        awaitKnown(b, worldId.toHex());

        // A repeat of a claim already held must not be re-flooded, or three meshed peers would
        // trade the same message forever.
        assertThat(a.ownership.publish(claim)).isFalse();
        assertThat(a.ownership.knownWorlds()).isEqualTo(1);
        assertThat(b.ownership.knownWorlds()).isEqualTo(1);
    }

    @Test
    @DisplayName("a peer that was offline learns the owner when the owner republishes")
    void republishReachesALatePeer() throws Exception {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        Node owner = new Node(net);
        Bytes worldId = hashes.sha256("late".getBytes());
        WorldOwnership claim = WorldOwnership.create(
                owner.identity, PersistedWorldKey.generate(worldId), 1L);
        owner.ownership.publish(claim); // nobody is listening yet

        Node latecomer = new Node(net);
        mesh(List.of(owner, latecomer));
        owner.ownership.republish();
        awaitKnown(latecomer, worldId.toHex());

        assertThat(latecomer.ownership.ownerOf(worldId.toHex()).orElseThrow().ownerNodeId())
                .isEqualTo(owner.identity.nodeId());
    }
}
