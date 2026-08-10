package dev.nodera.testkit.peer;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.StateRoot;
import dev.nodera.peer.PeerRuntime;
import dev.nodera.peer.validation.WorkerValidationService;
import dev.nodera.storage.event.InMemoryCertificateStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.PeerAddress;

import java.util.List;
import java.util.Optional;

/**
 * A committee member: an identity, a transport, a {@link WorkerValidationService} over the real
 * engine, and — usually — the {@link PeerRuntime} that carries its messages.
 *
 * <p>Nine integration tests each declared their own {@code Worker} record and their own fourteen-line
 * factory to build one. The three things they genuinely disagreed about are named parameters on
 * {@link PeerTestHarness.ValidationNodeBuilder} rather than defaults: the engine (one suite
 * substitutes a lying one), the vote timeout (one suite needs it short so a failed quorum resolves
 * inside the test, another needs it long enough to survive a slow runner) and the world seed.
 *
 * <p>Thread-context: the service delivers on the transport's executor; use {@link Await} rather
 * than asserting straight after a send.
 */
public final class ValidationNode {

    private final NodeIdentity identity;
    private final LoopbackTransport transport;
    private final PeerRuntime runtime;
    private final WorkerValidationService service;
    private final InMemoryCertificateStore certificates;

    ValidationNode(NodeIdentity identity, LoopbackTransport transport, PeerRuntime runtime,
                   WorkerValidationService service, InMemoryCertificateStore certificates) {
        this.identity = identity;
        this.transport = transport;
        this.runtime = runtime;
        this.service = service;
        this.certificates = certificates;
    }

    /** @return this member's identity. */
    public NodeIdentity identity() {
        return identity;
    }

    /** @return this member's id. */
    public NodeId nodeId() {
        return identity.nodeId();
    }

    /** @return the transport the service votes over. */
    public LoopbackTransport transport() {
        return transport;
    }

    /** @return the peer runtime, or {@code null} for a node built without one. */
    public PeerRuntime runtime() {
        return runtime;
    }

    /** @return the validation service under test. */
    public WorkerValidationService service() {
        return service;
    }

    /** @return this member's certificate store — what a co-signed commit is durable in. */
    public InMemoryCertificateStore certificates() {
        return certificates;
    }

    /** @return this member's loopback address. */
    public PeerAddress address() {
        return PeerAddress.of(identity.nodeId(), MeshNode.ROUTE);
    }

    /**
     * Tell this node about {@code other}: its address and the key its votes must carry.
     *
     * <p>One direction only. A test that introduces A to B and not B to A is modelling a peer that
     * has not been discovered yet, and several of them do exactly that on purpose.
     *
     * @param other the peer to register.
     */
    public void introduce(ValidationNode other) {
        service.registerPeer(other.nodeId(), other.address(), other.identity().publicKeyBytes());
    }

    /**
     * Introduce a node that is not backed by a {@link ValidationNode} — an adversary, or a member
     * that is deliberately offline.
     *
     * @param other the identity to register.
     */
    public void introduce(NodeIdentity other) {
        service.registerPeer(other.nodeId(), PeerAddress.of(other.nodeId(), MeshNode.ROUTE),
                other.publicKeyBytes());
    }

    /**
     * Register a player identity whose signed actions this member will admit.
     *
     * @param actor the acting player's identity.
     */
    public void registerActor(NodeIdentity actor) {
        service.registerActor(actor.nodeId(), actor.publicKeyBytes());
    }

    /** @return the head root this member holds for {@code region}. */
    public Optional<StateRoot> headRoot(RegionId region) {
        return service.headRoot(region);
    }

    /**
     * Stop this node's runtime — how a test kills a committee member mid-lease.
     *
     * <p>Idempotent, and the harness will not stop it a second time at teardown.
     */
    public void stop() {
        if (runtime != null) {
            runtime.stop();
        }
    }

    /**
     * Introduce every node in {@code nodes} to every other, in both directions.
     *
     * @param nodes the committee.
     */
    public static void mesh(List<ValidationNode> nodes) {
        for (ValidationNode node : nodes) {
            for (ValidationNode other : nodes) {
                if (node != other) {
                    node.introduce(other);
                }
            }
        }
    }

    /**
     * Mesh {@code nodes} and register {@code actor} on every one of them.
     *
     * @param nodes the committee.
     * @param actor the acting player every member must admit.
     */
    public static void mesh(List<ValidationNode> nodes, NodeIdentity actor) {
        mesh(nodes);
        for (ValidationNode node : nodes) {
            node.registerActor(actor);
        }
    }
}
