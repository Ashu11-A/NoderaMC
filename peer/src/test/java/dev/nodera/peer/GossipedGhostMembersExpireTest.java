package dev.nodera.peer;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.membership.MembershipUpdate;
import dev.nodera.protocol.membership.PeerEntry;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.testkit.peer.Await;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A member this node was only <b>told</b> about, and has never heard from, expires.
 *
 * <p>{@code pruneSilentMembers} used to skip every member outside {@code heard} — the set of peers
 * that have actually spoken to this node. Every member learned from a gossiped
 * {@link MembershipUpdate} is outside it, so those entries could never time out: they were removed
 * only by an explicit goodbye or a transport-down on a link that, for a peer this node never dialled,
 * does not exist. A crashed worker therefore stayed in the roster for the lifetime of the process.
 *
 * <p>Live evidence, from the phone: nine peers reported while three processes were running — six of
 * them exited, two of them the same machine and port under two different ids because the process had
 * restarted with a fresh {@link NodeId}. The count is the visible half; the dangerous half is that a
 * ghost stays eligible to be gateway, and re-election only runs when a member is removed, so a dead
 * node can hold the seat with nothing able to unseat it.
 */
final class GossipedGhostMembersExpireTest {

    /** 100 ms beats, 500 ms failure window — so the unheard grace (4 windows) is 2 s. */
    private final PeerRuntimeConfig fast =
            new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500));
    private final List<PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
    }

    private PeerRuntime start(NodeIdentity id, LoopbackNetwork net, String route) {
        PeerRuntime rt = PeerRuntime.bootstrap(id, NodeCapabilities.initial(),
                net.register(id.nodeId()), () -> route, fast, new RecordingListener());
        runtimes.add(rt);
        return rt;
    }

    private static boolean holds(PeerRuntime runtime, NodeId member) {
        for (PeerEntry entry : runtime.sessionView().members()) {
            if (entry.nodeId().equals(member)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a gossiped member that never speaks is dropped; a live one is kept")
    void aGossipedMemberThatNeverSpeaksIsDropped() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity hostId = NodeIdentity.generate();
        NodeIdentity liveId = NodeIdentity.generate();
        NodeId ghost = NodeIdentity.generate().nodeId();

        PeerRuntime host = start(hostId, net, "host");
        PeerRuntime live = start(liveId, net, "live");
        PeerAddress hostAddress = PeerAddress.of(hostId.nodeId(), "host");
        live.joinSession(hostAddress);
        Await.until("the host and the live peer mesh", 5_000, () -> host.sessionView().size() == 2);

        // What the mesh's gossip looks like for a peer that died: another member still lists it.
        // Its route names a node with no transport behind it, which is exactly a process that has
        // exited — every frame the host addresses to it fails, silently, forever.
        net.transportOf(liveId.nodeId()).send(hostAddress, WireCodec.encode(
                new MembershipUpdate(0L, hostId.nodeId(), List.of(new PeerEntry(
                        ghost, "ghost", NodeCapabilities.initial(), false, Bytes.empty(),
                        "test")))));

        Await.until("the host takes the gossiped member into its view", 5_000,
                () -> holds(host, ghost));
        Await.until("and drops it once the unheard grace passes with nothing heard", 5_000,
                () -> !holds(host, ghost));
        assertThat(holds(host, liveId.nodeId()))
                .as("the peer that keeps sending keep-alives is untouched by the sweep")
                .isTrue();
        assertThat(host.sessionView().size())
                .as("the host and the one peer that is really there")
                .isEqualTo(2);
    }
}
