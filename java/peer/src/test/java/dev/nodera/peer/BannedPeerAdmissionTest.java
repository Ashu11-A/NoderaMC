package dev.nodera.peer;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.testkit.LoopbackTransport.LoopbackNetwork;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-49 / L-18 admission gate: a BANNED peer is refused at JOIN — never entered into the
 * membership view, never replied to, never gossiped — and cannot slip in via another node's
 * membership gossip either. The gate is {@link PeerRuntime.JoinAdmission}, installed by a hosted
 * world as {@code WorldPermissions::canJoin}; before it existed, {@code onPeerJoin} admitted
 * every joiner unconditionally (the register's concrete Sybil hole).
 */
final class BannedPeerAdmissionTest {

    private final PeerRuntimeConfig fast =
            new PeerRuntimeConfig(Duration.ofMillis(100), Duration.ofMillis(500));
    private final List<PeerRuntime> runtimes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (PeerRuntime rt : runtimes) {
            rt.stop();
        }
    }

    @Test
    void bannedPeerIsRefusedAtJoinAndViaGossip() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity bootId = NodeIdentity.generate();
        NodeIdentity okId = NodeIdentity.generate();
        NodeIdentity bannedId = NodeIdentity.generate();

        RecordingListener bootL = new RecordingListener();
        PeerRuntime boot = PeerRuntime.bootstrap(bootId, NodeCapabilities.initial(),
                net.register(bootId.nodeId()), () -> "loopback", fast, bootL);
        runtimes.add(boot);
        // The hosted world's permission model gates the mesh: bannedId may not join.
        boot.setJoinAdmission(joiner -> !joiner.equals(bannedId.nodeId()));

        PeerAddress bootAddr = PeerAddress.of(bootId.nodeId(), "loopback");
        RecordingListener okL = new RecordingListener();
        PeerRuntime ok = PeerRuntime.peer(okId, NodeCapabilities.initial(),
                net.register(okId.nodeId()), () -> "loopback", bootAddr, fast, okL);
        runtimes.add(ok);

        Await.until("permitted peer joins normally", 5_000,
                () -> boot.sessionView().size() == 2 && ok.sessionView().size() == 2);

        RecordingListener bannedL = new RecordingListener();
        PeerRuntime banned = PeerRuntime.peer(bannedId, NodeCapabilities.initial(),
                net.register(bannedId.nodeId()), () -> "loopback", bootAddr, fast, bannedL);
        runtimes.add(banned);

        // Give the refused join every chance to have (wrongly) landed, then assert refusal.
        Await.sleep(1_500);
        assertThat(boot.sessionView().size())
                .as("the banned peer must not enter the bootstrap's membership view")
                .isEqualTo(2);
        assertThat(bootL.joined()).doesNotContain(bannedId.nodeId());
        assertThat(okL.joined())
                .as("the banned peer must not be gossiped to permitted members")
                .doesNotContain(bannedId.nodeId());
        assertThat(bannedL.memberCount())
                .as("the banned peer learns nothing about the mesh")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void gossipIngestFiltersBannedEntriesToo() {
        LoopbackNetwork net = LoopbackNetwork.newNetwork();
        NodeIdentity bootId = NodeIdentity.generate();
        NodeIdentity openId = NodeIdentity.generate();
        NodeIdentity bannedId = NodeIdentity.generate();

        // Bootstrap does NOT filter (a permissive node), but the second member does: even when
        // the banned peer reaches the mesh through the permissive node, the filtering member's
        // own view must exclude it.
        PeerRuntime boot = PeerRuntime.bootstrap(bootId, NodeCapabilities.initial(),
                net.register(bootId.nodeId()), () -> "loopback", fast, new RecordingListener());
        runtimes.add(boot);
        PeerAddress bootAddr = PeerAddress.of(bootId.nodeId(), "loopback");

        RecordingListener openL = new RecordingListener();
        PeerRuntime open = PeerRuntime.peer(openId, NodeCapabilities.initial(),
                net.register(openId.nodeId()), () -> "loopback", bootAddr, fast, openL);
        runtimes.add(open);
        open.setJoinAdmission(joiner -> !joiner.equals(bannedId.nodeId()));

        Await.until("two-member mesh", 5_000, () -> open.sessionView().size() == 2);

        PeerRuntime banned = PeerRuntime.peer(bannedId, NodeCapabilities.initial(),
                net.register(bannedId.nodeId()), () -> "loopback", bootAddr, fast,
                new RecordingListener());
        runtimes.add(banned);

        Await.until("permissive bootstrap admits the third peer", 5_000,
                () -> boot.sessionView().size() == 3);
        Await.sleep(1_000);
        assertThat(open.sessionView().size())
                .as("the filtering member's view excludes the banned peer even via gossip")
                .isEqualTo(2);
        assertThat(openL.joined()).doesNotContain(bannedId.nodeId());
    }
}
