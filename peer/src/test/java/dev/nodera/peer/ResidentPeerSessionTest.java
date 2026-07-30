package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.identity.PeerRole;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A world's peers are the peers on the network, not the players logged into it.
 *
 * <p>This pins the membership half of that: an always-on headless worker boots as its own session
 * of one (it has no world yet), is later handed a hosting world's route, and becomes a real
 * <b>member</b> of that world's session — counted in the view, carrying a public key the other
 * members can verify committee work against, and eligible to inherit the gateway when the hosting
 * game exits.
 *
 * <p>Before this, {@code NODERA-JOIN} was a no-op and a worker could never enter a game's session:
 * session size was exactly {@code 1 server node + 1 node per connected client}, so a two-player
 * player-hosted world sat permanently at two members — below the quorum of three that
 * {@code DiagnosticsCollector.deriveHealth} calls DEGRADED — no matter how many peers were running.
 */
final class ResidentPeerSessionTest {

    private final PeerRuntimeConfig fast =
            new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500));
    private final List<PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
    }

    private PeerRuntime start(NodeIdentity id, LoopbackTransport tx, String route,
                              PeerEventListener listener) {
        PeerRuntime rt = PeerRuntime.bootstrap(id, workerCapabilities(), tx, () -> route, fast, listener);
        runtimes.add(rt);
        return rt;
    }

    private static NodeCapabilities workerCapabilities() {
        return NodeCapabilities.initial().withRoles(
                EnumSet.of(PeerRole.FULL_ARCHIVE, PeerRole.BOOTSTRAP, PeerRole.REGION_VALIDATOR));
    }

    private static Optional<PeerEntry> entryOf(PeerRuntime rt, NodeIdentity who) {
        return rt.sessionView().members().stream()
                .filter(e -> e.nodeId().equals(who.nodeId()))
                .findFirst();
    }

    @Test
    void anAlwaysOnWorkerStartsAsItsOwnSessionOfOneUntilItIsGivenAWorld() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity workerId = NodeIdentity.generate();
        PeerRuntime worker = start(workerId, net.register(workerId.nodeId()), "worker",
                new RecordingListener());

        // This is the state the user observed: peers running, none of them in any world's session.
        // The first view is published on the runtime's state thread, so poll rather than race it.
        Await.until("the worker publishes its own session of one", 5_000,
                () -> worker.sessionView().size() == 1 && worker.isGateway());
        assertThat(worker.sessionAddress()).isNull();
    }

    @Test
    void joinSessionMakesTheWorkerARealMemberOfTheHostingWorldsSession() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity workerId = NodeIdentity.generate();
        RecordingListener hostL = new RecordingListener();
        RecordingListener workerL = new RecordingListener();

        PeerRuntime host = start(hostId, net.register(hostId.nodeId()), "host", hostL);
        PeerRuntime worker = start(workerId, net.register(workerId.nodeId()), "worker", workerL);

        // Two independent sessions of one — exactly the pre-fix topology.
        Await.until("both start as sessions of one", 5_000,
                () -> host.sessionView().size() == 1 && worker.sessionView().size() == 1);

        // The world starts hosting and hands the worker its route (the NODERA-MESH handoff).
        worker.joinSession(PeerAddress.of(hostId.nodeId(), "host"));

        Await.until("host and worker converge on a 2-member session", 5_000,
                () -> host.sessionView().size() == 2 && worker.sessionView().size() == 2);

        // The worker yields its self-elected gateway claim to the world it joined.
        Await.until("worker adopts the host as gateway", 5_000,
                () -> hostId.nodeId().equals(worker.gatewayId()));
        assertThat(host.isGateway()).isTrue();
        assertThat(worker.isGateway()).isFalse();
        assertThat(worker.sessionAddress()).isNotNull();
    }

    @Test
    void membershipCarriesEveryMembersPublicKeySoCommitteeSeatsCanBeVerified() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity workerId = NodeIdentity.generate();

        PeerRuntime host = start(hostId, net.register(hostId.nodeId()), "host", new RecordingListener());
        PeerRuntime worker = start(workerId, net.register(workerId.nodeId()), "worker",
                new RecordingListener());
        worker.joinSession(PeerAddress.of(hostId.nodeId(), "host"));

        Await.until("both see 2 members", 5_000,
                () -> host.sessionView().size() == 2 && worker.sessionView().size() == 2);

        // THE prerequisite for a headless committee seat: the host can verify the worker's votes
        // and the worker can verify the host's proposals, having exchanged no identity message.
        Await.until("the host learns the worker's key", 5_000,
                () -> entryOf(host, workerId).filter(PeerEntry::hasPublicKey).isPresent());
        Await.until("the worker learns the host's key", 5_000,
                () -> entryOf(worker, hostId).filter(PeerEntry::hasPublicKey).isPresent());

        assertThat(entryOf(host, workerId).orElseThrow().publicKey())
                .isEqualTo(workerId.publicKeyBytes());
        assertThat(entryOf(worker, hostId).orElseThrow().publicKey())
                .isEqualTo(hostId.publicKeyBytes());
    }

    @Test
    void theWorkerOutlivesTheHostingGameAndInheritsTheSession() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity workerId = NodeIdentity.generate();

        PeerRuntime host = start(hostId, net.register(hostId.nodeId()), "host", new RecordingListener());
        PeerRuntime worker = start(workerId, net.register(workerId.nodeId()), "worker",
                new RecordingListener());
        worker.joinSession(PeerAddress.of(hostId.nodeId(), "host"));
        Await.until("both see 2 members", 5_000,
                () -> host.sessionView().size() == 2 && worker.sessionView().size() == 2);

        // The hosting player closes the game. The worker stays bootstrap-capable precisely so the
        // world's session does not die with it — this is what "any peer can serve the world" means.
        host.stop();

        Await.until("the worker inherits the gateway", 10_000,
                () -> worker.isGateway() && worker.sessionView().size() == 1);
    }

    @Test
    void detachingReturnsTheWorkerToItsOwnSession() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity workerId = NodeIdentity.generate();

        PeerRuntime host = start(hostId, net.register(hostId.nodeId()), "host", new RecordingListener());
        PeerRuntime worker = start(workerId, net.register(workerId.nodeId()), "worker",
                new RecordingListener());
        worker.joinSession(PeerAddress.of(hostId.nodeId(), "host"));
        Await.until("both see 2 members", 5_000, () -> worker.sessionView().size() == 2);

        // The world stops hosting: the mod detaches the companion so it stops heartbeating at a
        // route that is about to disappear.
        worker.joinSession(null);
        assertThat(worker.sessionAddress()).isNull();
    }
}
