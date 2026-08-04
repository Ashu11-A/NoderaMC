package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two always-on workers that joined the same session must exchange keep-alives with each other, not
 * only with the node they joined through.
 *
 * <p>{@code canSend} answered "never initiate" for every bootstrap-capable runtime. That is right
 * for a session root — peers dial it because it is the reachable end — and wrong for a worker, which
 * is bootstrap-capable and also joins other people's sessions. In a mesh of workers no pair had a
 * dialer, so keep-alives only ever crossed the join edges: every other pair was permanently "never
 * heard from", outside failure detection entirely, and known only through gossip.
 *
 * <p>What that cost live, once unheard members started expiring: a phone joined to three Linux
 * workers cycled its peer list 1 → 4 → 1 every twenty seconds — gossip adding the workers, the sweep
 * dropping them again — while every node was up and moving content over routes the membership plane
 * could not see.
 */
final class WorkersInOneSessionHearEachOtherTest {

    private final PeerRuntimeConfig fast =
            new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500));
    private final List<PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
    }

    private PeerRuntime worker(NodeIdentity id, LoopbackNetwork net, String route,
                               RecordingListener listener) {
        PeerRuntime rt = PeerRuntime.bootstrap(id, NodeCapabilities.initial(),
                net.register(id.nodeId()), () -> route, fast, listener);
        runtimes.add(rt);
        return rt;
    }

    @Test
    @DisplayName("two joined workers keep each other alive, not just the node they joined through")
    void twoJoinedWorkersExchangeKeepAlivesDirectly() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity rootId = NodeIdentity.generate();
        NodeIdentity oneId = NodeIdentity.generate();
        NodeIdentity twoId = NodeIdentity.generate();

        worker(rootId, net, "root", new RecordingListener());
        RecordingListener oneL = new RecordingListener();
        RecordingListener twoL = new RecordingListener();
        PeerRuntime one = worker(oneId, net, "one", oneL);
        PeerRuntime two = worker(twoId, net, "two", twoL);

        PeerAddress rootAddress = PeerAddress.of(rootId.nodeId(), "root");
        one.joinSession(rootAddress);
        two.joinSession(rootAddress);

        Await.until("both workers see the whole session", 5_000,
                () -> one.sessionView().size() == 3 && two.sessionView().size() == 3);
        // The assertion the old rule could not satisfy: traffic on the worker-to-worker edge. It is
        // what puts each of them inside the other's failure detection, so a worker that dies is
        // noticed rather than remembered forever.
        Await.until("the two workers keep-alive each other directly", 5_000,
                () -> oneL.keepAlivesFrom(twoId.nodeId()) > 0
                        && twoL.keepAlivesFrom(oneId.nodeId()) > 0);

        // And they stay: an edge that carries keep-alives is never swept, so the peer count is
        // steady instead of cycling as gossip re-adds what the sweep just dropped.
        Await.sleep(2_000);
        assertThat(one.sessionView().size())
                .as("no flapping: the session is still three nodes after several sweeps")
                .isEqualTo(3);
        assertThat(two.sessionView().size()).isEqualTo(3);
    }
}
