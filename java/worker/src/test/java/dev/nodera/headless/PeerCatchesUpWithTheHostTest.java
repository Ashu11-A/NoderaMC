package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.socket.SocketPeerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A peer holding a world has to keep holding <em>that world</em>, not the version of it that
 * happened to exist the first time it looked.
 *
 * <p>The failure this is written from, side by side on one screen: two machines in the same world,
 * the host reporting {@code 100.0% · 208 of 208 pieces · v6 · 51.8 MiB} and the peer reporting
 * {@code 100.0% · 7 of 7 pieces · v2 · 1.7 MiB}. Both said "Full copy held", and both were telling
 * the truth about a manifest — the peer had completed v2 back when the world was minutes old, and
 * every path that could have moved it forward asked "do you hold a complete copy?" and stopped
 * there. It seeded a 1.7 MiB fossil of a 51.8 MiB world for the rest of the session, so the world's
 * durability was still exactly one machine deep.
 *
 * <p>Over sockets rather than a loopback transport, because "the peer catches up" is a claim about
 * addressing, framing and the manifest exchange as much as about the decision.
 */
final class PeerCatchesUpWithTheHostTest {

    private final HashService hashes = new HashService();
    private WorldArchiveService hostService;
    private WorldArchiveService peerService;
    private SocketPeerTransport hostTransport;
    private SocketPeerTransport peerTransport;

    @AfterEach
    void tearDown() {
        if (hostService != null) {
            hostService.close();
        }
        if (peerService != null) {
            peerService.close();
        }
        if (hostTransport != null) {
            hostTransport.stop();
        }
        if (peerTransport != null) {
            peerTransport.stop();
        }
    }

    private static void bridge(SocketPeerTransport transport, WorldArchiveService service) {
        transport.setHandler(new MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                try {
                    service.onMessage(from, dev.nodera.protocol.wire.WireCodec.decode(frame));
                } catch (RuntimeException ignored) {
                    // Not this lane's frame; the real mux fans out the same way.
                }
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        });
    }

    /** A world of {@code size} bytes, deterministic per seed so equality assertions mean something. */
    private static byte[] world(long seed, int size) {
        byte[] raw = new byte[size];
        new java.util.Random(seed).nextBytes(raw);
        return raw;
    }

    private Bytes startTwoNodes(String worldName) {
        NodeIdentity host = NodeIdentity.generate();
        NodeIdentity peer = NodeIdentity.generate();
        hostTransport = new SocketPeerTransport(host, "127.0.0.1", 0, "127.0.0.1");
        peerTransport = new SocketPeerTransport(peer, "127.0.0.1", 0, "127.0.0.1");
        hostService = new WorldArchiveService(host, hostTransport,
                new InMemoryContentStore(hashes), List.of());
        peerService = new WorldArchiveService(peer, peerTransport,
                new InMemoryContentStore(hashes), List.of());
        bridge(hostTransport, hostService);
        bridge(peerTransport, peerService);
        hostTransport.start();
        peerTransport.start();
        hostIds = Set.of(host.nodeId());
        peerService.learnRoute(host.nodeId(), hostTransport.listenRoute());
        return hashes.sha256(worldName.getBytes());
    }

    private Set<dev.nodera.core.identity.NodeId> hostIds;

    @Test
    @DisplayName("a peer that completed an old version follows the world forward")
    void thePeerFollowsTheWorldForward() {
        Bytes worldId = startTwoNodes("catch-up-world");
        String worldIdHex = worldId.toHex();

        // The world as it was when the peer joined: small, and fully fetched.
        byte[] early = world(1L, 512 * 1024);
        hostService.seedArchive(worldIdHex, early);
        assertThat(peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                .isEqualTo(early);
        long earlyVersion = peerService.newestCompleteLocally(worldIdHex).orElseThrow()
                .version().value();

        // The players kept playing. The host streamed several more versions, each larger; the last
        // one is the world as it actually exists now.
        byte[] current = null;
        for (int i = 0; i < 4; i++) {
            current = world(10L + i, (i + 2) * 512 * 1024);
            hostService.seedArchive(worldIdHex, current);
        }

        assertThat(peerService.refreshArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                .as("the peer had work to do — it was four versions behind")
                .isTrue();

        var caughtUp = peerService.newestCompleteLocally(worldIdHex).orElseThrow();
        assertThat(caughtUp.version().value())
                .as("the peer now holds the version the host is actually seeding")
                .isEqualTo(hostService.newestCompleteLocally(worldIdHex).orElseThrow()
                        .version().value())
                .isGreaterThan(earlyVersion);
        assertThat(caughtUp.manifestRoot())
                .isEqualTo(hostService.newestCompleteLocally(worldIdHex).orElseThrow()
                        .manifestRoot());
        // And it is a real, complete, serveable copy — not a manifest it merely knows about.
        WorldArchiveService.PieceReport report = peerService.pieceReport(worldIdHex);
        assertThat(report.heldCount()).isEqualTo(report.pieceCount());
        assertThat(peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                .isEqualTo(current);
    }

    /**
     * "Nobody here has it" is an answer, and a fetch has to be able to hear it.
     *
     * <p>Every peer of a world it has only heard of answers the manifest query with an empty list,
     * and that answer used to be dropped on the floor: the future waited for a <em>useful</em>
     * reply, so a swarm in which everyone honestly said "nothing" was indistinguishable from one
     * that never replied. The fetch then parked the replication sweep's single thread for its whole
     * budget — five minutes, per world, while every other world waited its turn. Live evidence: a
     * peer logged "Supporting world 'Hello' — fetching its archive" and produced no further line
     * for eleven minutes, then a summary of all zeroes.
     */
    @Test
    @DisplayName("a swarm that holds nothing says so, instead of parking the fetch")
    void anEmptyAnswerEndsTheWaitInsteadOfTimingOut() {
        Bytes worldId = startTwoNodes("nobody-holds-this-world");
        String worldIdHex = worldId.toHex();
        // The host knows the world exists — it is in its registry and announced — and has not
        // archived a byte of it yet. That is every peer's state before the first save is streamed.

        long start = System.nanoTime();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> peerService.fetchArchiveFrom(worldIdHex, hostIds,
                        Duration.ofMinutes(5)))
                .hasMessageContaining("hold no version of it");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took)
                .as("the answer came back, so the sweep thread is free — not held for the deadline")
                .isLessThan(Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("a peer already on the newest version downloads nothing")
    void anUpToDatePeerDoesNoWork() {
        Bytes worldId = startTwoNodes("already-current-world");
        String worldIdHex = worldId.toHex();

        byte[] archive = world(2L, 512 * 1024);
        hostService.seedArchive(worldIdHex, archive);
        peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30));

        assertThat(peerService.refreshArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                .as("nothing newer is being seeded, so there is nothing to fetch")
                .isFalse();
    }

    @Test
    @DisplayName("a newer version nobody can serve never costs the peer the copy it has")
    void theHeldCopySurvivesAnUnreachableNewerVersion() {
        Bytes worldId = startTwoNodes("unreachable-newer-world");
        String worldIdHex = worldId.toHex();

        byte[] archive = world(3L, 512 * 1024);
        hostService.seedArchive(worldIdHex, archive);
        assertThat(peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(30)))
                .isEqualTo(archive);

        // The host archives once more and closes its game: every peer hears of the version, nobody
        // holds it. This is the ordinary end of every session, and the reason the probe's answer is
        // "keep what you have" rather than "chase what you heard about".
        hostTransport.stop();
        hostService.close();
        hostService = null;
        hostTransport = null;

        assertThat(peerService.refreshArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(2)))
                .as("nobody answered, so the peer stays where it is")
                .isFalse();
        assertThat(peerService.newestCompleteLocally(worldIdHex)).isPresent();
        assertThat(peerService.fetchArchiveFrom(worldIdHex, hostIds, Duration.ofSeconds(2)))
                .as("and the world is still openable from this disk")
                .isEqualTo(archive);
    }
}
