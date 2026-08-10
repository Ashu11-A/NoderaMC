package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.WorldRole;
import dev.nodera.protocol.membership.WorldGrantGossip;
import dev.nodera.storage.WorldPermissionGrant;
import dev.nodera.storage.WorldPermissions;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.MeshNode;
import dev.nodera.testkit.peer.PeerTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-54 — <b>a grant issued by the author reaches every co-hosting peer over the mesh and is applied
 * identically.</b>
 *
 * <p>Grants used to live only in the author's {@code nodera-permissions.dat}: a co-hosting peer's
 * permission set was author-local until it happened to fetch the world, so an operator promotion or
 * a ban did not exist for the rest of the mesh — and a banned peer could still be admitted by any
 * peer that had not been told.
 *
 * <p>The tests below deliberately include the attacks the gossip lane must not weaken. A grant is
 * self-authenticating, so a relayed grant is exactly as trustworthy as a locally-minted one: it is
 * the {@link WorldPermissions} applier, not the transport, that decides.
 */
final class GrantGossipIT {

    private final PeerTestHarness harness = PeerTestHarness.create();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    /** One node: identity, transport, gossip service, and the grants it accepted. */
    private record Node(MeshNode<WorldGrantGossipService> mesh,
                        List<WorldPermissionGrant> accepted) {

        WorldGrantGossipService gossip() {
            return mesh.service();
        }

        NodeIdentity identity() {
            return mesh.identity();
        }
    }

    private Node node() {
        List<WorldPermissionGrant> accepted = new CopyOnWriteArrayList<>();
        MeshNode<WorldGrantGossipService> mesh = harness.messageNode(
                (identity, transport, peers) -> {
                    WorldGrantGossipService gossip =
                            new WorldGrantGossipService(identity.nodeId(), transport, peers);
                    gossip.onGrantAccepted((world, grant) -> accepted.add(grant));
                    return gossip;
                },
                gossip -> gossip::onMessage);
        return new Node(mesh, accepted);
    }

    private void mesh(List<Node> nodes) {
        harness.mesh(nodes.stream().map(Node::mesh).toList());
    }

    private static void awaitAccepted(Node node, int count) {
        Await.quietly(5_000, () -> node.accepted().size() >= count);
    }

    /** Every node in {@code nodes} learns who authors {@code worldIdHex}. */
    private static void track(List<Node> nodes, String worldIdHex, NodeIdentity author) {
        for (Node n : nodes) {
            n.gossip().track(worldIdHex, author.nodeId(), author.publicKeyBytes());
        }
    }

    @Test
    void anAuthorsGrantReachesEveryCoHostingPeerAndIsAppliedIdentically() {
        Node author = node();
        Node coHostA = node();
        Node coHostB = node();
        List<Node> all = List.of(author, coHostA, coHostB);
        mesh(all);

        Bytes worldId = harness.hashes().sha256("l54-world".getBytes());
        String worldIdHex = worldId.toHex();
        track(all, worldIdHex, author.identity());

        // The author promotes a player to operator.
        NodeIdentity operator = NodeIdentity.generate();
        WorldPermissionGrant grant = WorldPermissionGrant.create(author.identity(), worldId,
                operator.nodeId(), operator.publicKeyBytes(), WorldRole.OPERATOR, 1L);

        assertThat(author.gossip().publish(worldIdHex, grant)).isTrue();
        awaitAccepted(coHostA, 1);
        awaitAccepted(coHostB, 1);

        for (Node n : all) {
            WorldPermissions permissions = n.gossip().permissions(worldIdHex).orElseThrow();
            assertThat(permissions.roleOf(operator.nodeId(), operator.publicKeyBytes()))
                    .as("every co-hosting peer resolves the same role")
                    .isEqualTo(WorldRole.OPERATOR);
        }
    }

    @Test
    void aBanReachesThePeersThatWouldOtherwiseHaveAdmittedTheBannedPeer() {
        Node author = node();
        Node coHost = node();
        List<Node> all = List.of(author, coHost);
        mesh(all);

        Bytes worldId = harness.hashes().sha256("l54-ban".getBytes());
        String worldIdHex = worldId.toHex();
        track(all, worldIdHex, author.identity());

        NodeIdentity griefer = NodeIdentity.generate();
        // Before the ban the co-host would let them in — that is the state the ban has to change.
        assertThat(coHost.gossip().permissions(worldIdHex).orElseThrow()
                .canJoin(griefer.nodeId())).isTrue();

        WorldPermissionGrant ban = WorldPermissionGrant.create(author.identity(), worldId,
                griefer.nodeId(), griefer.publicKeyBytes(), WorldRole.BANNED, 1L);
        assertThat(author.gossip().publish(worldIdHex, ban)).isTrue();
        awaitAccepted(coHost, 1);

        assertThat(coHost.gossip().permissions(worldIdHex).orElseThrow()
                .canJoin(griefer.nodeId()))
                .as("the ban is enforced by the peer that never issued it")
                .isFalse();
    }

    @Test
    void aGrantForgedByANonAuthorIsRefusedByEveryPeerThatReceivesIt() throws Exception {
        Node author = node();
        Node attacker = node();
        Node coHost = node();
        List<Node> all = List.of(author, attacker, coHost);
        mesh(all);

        Bytes worldId = harness.hashes().sha256("l54-forge".getBytes());
        String worldIdHex = worldId.toHex();
        track(all, worldIdHex, author.identity());

        // A perfectly well-formed, correctly-signed grant — signed by the wrong key. Relaying it is
        // the attacker's whole plan; the applier is what defeats it, on every peer independently.
        NodeIdentity accomplice = NodeIdentity.generate();
        WorldPermissionGrant forged = WorldPermissionGrant.create(attacker.identity(), worldId,
                accomplice.nodeId(), accomplice.publicKeyBytes(), WorldRole.OPERATOR, 1L);

        assertThat(attacker.gossip().publish(worldIdHex, forged))
                .as("a grant this node cannot justify is not even relayed").isFalse();

        // …and if the attacker bypasses publish() and puts it on the wire by hand, it still dies.
        CanonicalWriter w = new CanonicalWriter();
        forged.encode(w);
        attacker.mesh().send(coHost.mesh(), new WorldGrantGossip(worldId, w.toBytes()));
        Thread.sleep(300);

        assertThat(coHost.accepted()).isEmpty();
        assertThat(coHost.gossip().permissions(worldIdHex).orElseThrow()
                .roleOf(accomplice.nodeId(), accomplice.publicKeyBytes()))
                .isEqualTo(WorldRole.MEMBER);
    }

    @Test
    void aStaleGrantVersionIsNeitherAppliedNorRelayedOnward() throws Exception {
        Node author = node();
        Node coHost = node();
        List<Node> all = List.of(author, coHost);
        mesh(all);

        Bytes worldId = harness.hashes().sha256("l54-stale".getBytes());
        String worldIdHex = worldId.toHex();
        track(all, worldIdHex, author.identity());

        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant promote = WorldPermissionGrant.create(author.identity(), worldId,
                subject.nodeId(), subject.publicKeyBytes(), WorldRole.OPERATOR, 2L);
        assertThat(author.gossip().publish(worldIdHex, promote)).isTrue();
        awaitAccepted(coHost, 1);

        // A replay of an older version must not demote anyone — this is also what terminates the
        // gossip flood, since a peer only relays what it newly accepted.
        WorldPermissionGrant stale = WorldPermissionGrant.create(author.identity(), worldId,
                subject.nodeId(), subject.publicKeyBytes(), WorldRole.BANNED, 1L);
        assertThat(author.gossip().publish(worldIdHex, stale)).isFalse();
        Thread.sleep(200);

        for (Node n : all) {
            assertThat(n.gossip().permissions(worldIdHex).orElseThrow()
                    .roleOf(subject.nodeId(), subject.publicKeyBytes()))
                    .isEqualTo(WorldRole.OPERATOR);
            assertThat(n.accepted()).hasSize(1);
        }
    }

    @Test
    void aWorldThisNodeHasNoAuthorKeyForIsNotHalfApplied() throws Exception {
        Node author = node();
        Node stranger = node();   // never told who authors this world
        mesh(List.of(author, stranger));

        Bytes worldId = harness.hashes().sha256("l54-untracked".getBytes());
        String worldIdHex = worldId.toHex();
        track(List.of(author), worldIdHex, author.identity());

        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant grant = WorldPermissionGrant.create(author.identity(), worldId,
                subject.nodeId(), subject.publicKeyBytes(), WorldRole.OPERATOR, 1L);
        assertThat(author.gossip().publish(worldIdHex, grant)).isTrue();
        Thread.sleep(300);

        // Without the author key there is no way to tell an authorised grant from a fabricated one,
        // so the honest outcome is to hold no opinion at all rather than to trust the sender.
        assertThat(stranger.accepted()).isEmpty();
        assertThat(stranger.gossip().permissions(worldIdHex)).isEmpty();
    }

    @Test
    void anAcceptedGrantIsHandedToTheListenerSoItCanBePersisted() {
        Node author = node();
        Node coHost = node();
        List<Node> all = List.of(author, coHost);
        mesh(all);

        Bytes worldId = harness.hashes().sha256("l54-persist".getBytes());
        String worldIdHex = worldId.toHex();
        track(all, worldIdHex, author.identity());

        NodeIdentity subject = NodeIdentity.generate();
        WorldPermissionGrant grant = WorldPermissionGrant.create(author.identity(), worldId,
                subject.nodeId(), subject.publicKeyBytes(), WorldRole.MEMBER, 1L);
        author.gossip().publish(worldIdHex, grant);
        awaitAccepted(coHost, 1);

        // The co-host must be able to write what it learned to its own nodera-permissions.dat —
        // otherwise the grant is lost on restart and the mesh silently forgets the decision.
        assertThat(coHost.accepted()).singleElement().isEqualTo(grant);
        assertThat(author.accepted()).singleElement().isEqualTo(grant);
    }
}
