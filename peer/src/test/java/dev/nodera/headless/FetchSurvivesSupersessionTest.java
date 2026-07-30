package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.protocol.content.WorldManifestAnswer;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.PeerAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A download in flight outranks the retention policy.
 *
 * <p>The failure this pins was watched happening. A joiner asked for a 27 MB world archive; the host
 * — whose game was open and streaming a new archive version every couple of minutes — seeded the
 * next version while that download was still running; the joiner's worker learned of it through the
 * ordinary manifest exchange and ran {@code supersedeOlderVersions}, which unpinned the blob and
 * unpublished the manifest root the {@code PieceDownloader} was writing into. The download could
 * then never finish, and the player sat on "Migrating world…" until the fetch deadline expired.
 *
 * <p>{@code supersedeOlderVersions} already refuses to run from {@code seedArchive} for exactly this
 * reason, and says so in a comment. The same hazard arrives through the learning path, which that
 * comment did not cover — a policy written for "this node re-archived" met a case of "somebody else
 * did", and evicted the one version that was still being used.
 *
 * <p>Note what is <em>not</em> asserted: that the fetch targets the newest version. It deliberately
 * does not. The version being downloaded is a complete, valid world — the joiner opens it and
 * catches up live, which is the entire point of the continuity lane. Chasing a moving head on a
 * world that re-archives faster than it transfers would never converge.
 */
final class FetchSurvivesSupersessionTest {

    private final HashService hashes = new HashService();
    private WorldArchiveService service;
    private LoopbackTransport transport;
    private LoopbackTransport seederTransport;
    private InMemoryContentStore store;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        if (transport != null) {
            transport.stop();
        }
        if (seederTransport != null) {
            seederTransport.stop();
        }
    }

    private static byte[] blob(long seed, int size) {
        byte[] raw = new byte[size];
        new java.util.Random(seed).nextBytes(raw);
        return raw;
    }

    private static WorldManifestAnswer answerCarrying(Bytes worldId, PieceManifest manifest) {
        CanonicalWriter w = new CanonicalWriter();
        manifest.encode(w);
        return new WorldManifestAnswer(worldId, List.of(w.toBytes()));
    }

    @Test
    void aNewerVersionLearnedMidFetchDoesNotEvictTheOneBeingDownloaded() throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        NodeIdentity seeder = NodeIdentity.generate();
        // Both on one network: the manifest query has to actually reach a registered endpoint, or
        // the fetch gives up with "no routable seeder" before it ever starts a download.
        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        transport = network.register(identity.nodeId());
        seederTransport = network.register(seeder.nodeId());
        transport.start();
        seederTransport.start();
        store = new InMemoryContentStore(hashes);
        service = new WorldArchiveService(identity, transport, store, List.of());

        Bytes worldId = hashes.sha256("mid-fetch-world".getBytes());
        String worldIdHex = worldId.toHex();

        // The seeder answers the manifest query and then goes quiet — standing in for a peer whose
        // pieces are still arriving.
        PeerAddress seederAddress = PeerAddress.of(seeder.nodeId(), "loopback");

        PieceManifest v1 = WorldArchive.manifestFor(1L, blob(11L, 300_000));
        // An inbound message is how a route is learned; this also puts v1 in the manifest table.
        service.onMessage(seederAddress, answerCarrying(worldId, v1));
        assertThat(service.heldVersions(worldIdHex)).hasSize(1);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread fetch = Thread.ofPlatform().daemon().name("test-fetch").start(() -> {
            try {
                service.fetchArchiveFrom(worldIdHex, java.util.Set.of(seeder.nodeId()),
                        Duration.ofSeconds(30));
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        // Answer the manifest query until the download registers. Re-delivering is deliberate: the
        // pending future is created inside the fetch, so an answer handed over before it starts has
        // nothing to complete. Polling also removes the race this test would otherwise have with
        // the very window it exists to pin.
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!service.isFetching(v1.manifestRoot()) && System.nanoTime() < deadline) {
            if (failure.get() != null) {
                throw new AssertionError("the fetch failed before it started", failure.get());
            }
            service.onMessage(seederAddress, answerCarrying(worldId, v1));
            Thread.sleep(5);
        }
        assertThat(service.isFetching(v1.manifestRoot()))
                .as("the fetch should be in flight on v1's root")
                .isTrue();

        // The host's game is still open, so it archives again. This node learns of v2 and applies
        // "only the newest version is maintained" — the exact moment the bug destroyed the download.
        PieceManifest v2 = WorldArchive.manifestFor(2L, blob(12L, 300_000));
        service.onMessage(seederAddress, answerCarrying(worldId, v2));

        assertThat(service.heldVersions(worldIdHex))
                .as("v1 must survive: it is being downloaded right now")
                .anyMatch(m -> m.manifestRoot().equals(v1.manifestRoot()));
        assertThat(service.content().heldPieces(v1.manifestRoot()))
                .as("and its content root must still be published, or every verified piece "
                        + "written after this point goes nowhere")
                .isNotNull();

        fetch.interrupt();
        fetch.join(Duration.ofSeconds(5));
    }

    @Test
    void aVersionNobodyIsFetchingIsStillSuperseded() {
        NodeIdentity identity = NodeIdentity.generate();
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        store = new InMemoryContentStore(hashes);
        service = new WorldArchiveService(identity, transport, store, List.of());

        Bytes worldId = hashes.sha256("no-fetch-world".getBytes());
        String worldIdHex = worldId.toHex();

        // Encrypted: supersede-eviction is scoped to ciphertext, because that is the only content
        // a password rotation can revoke. A plaintext copy is kept and bounded by the retention
        // window instead — destroying it revoked nothing and left swarms with no servable version.
        PieceManifest old = service.seedEncryptedArchive(worldIdHex, blob(21L, 80_000),
                "pw".toCharArray());
        PieceManifest newer = WorldArchive.manifestFor(2L, blob(22L, 80_000));
        service.onMessage(PeerAddress.of(NodeIdentity.generate().nodeId(), "loopback"),
                answerCarrying(worldId, newer));

        // The guard is narrow on purpose: with no download in flight, L-55 still applies in full and
        // the superseded ciphertext still stops being served here.
        assertThat(service.heldVersions(worldIdHex)).hasSize(1);
        assertThat(store.has(old.blob())).isFalse();
    }
}
