package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.distribution.PieceManifest;
import dev.nodera.storage.event.InMemoryContentStore;
import dev.nodera.testkit.LoopbackTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L-61 — the continuous archive stream must not grow the content store for as long as the session
 * lasts.
 *
 * <p>Issue #43's streaming appends a full world archive every {@code archive.streamIntervalTicks}
 * (2400 by default, so roughly every two minutes of play). Every version was kept: an evening's
 * session left dozens of whole-world snapshots on disk, all of them announced.
 *
 * <p>{@link WorldArchiveService#supersedeOlderVersions} could not be the answer — it keeps exactly
 * the newest, and evicting the previous version out from under a joiner mid-fetch would trade a
 * growth bug for an availability one, which is precisely why the streaming path never called it.
 * A retention <i>window</i> bounds the store while leaving the last few snapshots fetchable.
 */
final class ArchiveRetentionWindowTest {

    private final HashService hashes = new HashService();
    private WorldArchiveService service;
    private LoopbackTransport transport;
    private InMemoryContentStore store;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        if (transport != null) {
            transport.stop();
        }
    }

    private WorldArchiveService host() {
        NodeIdentity identity = NodeIdentity.generate();
        transport = LoopbackTransport.LoopbackNetwork.newNetwork().register(identity.nodeId());
        transport.start();
        store = new InMemoryContentStore(hashes);
        service = new WorldArchiveService(identity, transport, store, List.of());
        return service;
    }

    private static byte[] blob(long seed, int size) {
        byte[] raw = new byte[size];
        new java.util.Random(seed).nextBytes(raw);
        return raw;
    }

    @Test
    void aLongStreamingSessionKeepsOnlyTheWindow() {
        WorldArchiveService archive = host();
        archive.setRetainedVersions(3);
        String worldIdHex = hashes.sha256("l61-world".getBytes()).toHex();

        // 20 stream intervals — about 40 minutes of play at the default cadence.
        List<PieceManifest> streamed = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            streamed.add(archive.seedArchive(worldIdHex, blob(i, 30_000)));
        }

        assertThat(archive.heldVersions(worldIdHex))
                .as("the store holds the window, not the session")
                .hasSize(3);
        assertThat(archive.heldVersions(worldIdHex).stream().map(m -> m.version().value()))
                .containsExactly(18L, 19L, 20L);

        for (PieceManifest dropped : streamed.subList(0, 17)) {
            assertThat(store.has(dropped.blob()))
                    .as("v%s's bytes are off the disk", dropped.version().value())
                    .isFalse();
            assertThat(archive.holdingsFor(worldIdHex))
                    .as("and the next tracker announce does not advertise it")
                    .noneMatch(h -> h.manifestRoot().equals(dropped.manifestRoot()));
        }
        // What the window buys over supersede-to-one: a joiner that started fetching two versions
        // ago is still served.
        assertThat(store.has(streamed.get(17).blob())).isTrue();
        assertThat(store.has(streamed.get(19).blob())).isTrue();
    }

    @Test
    void theEncryptedStreamIsBoundedToo() {
        WorldArchiveService archive = host();
        archive.setRetainedVersions(2);
        String worldIdHex = hashes.sha256("l61-world-enc".getBytes()).toHex();

        List<PieceManifest> streamed = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            streamed.add(archive.seedEncryptedArchive(
                    worldIdHex, blob(100 + i, 20_000), "correct horse".toCharArray()));
        }

        assertThat(archive.heldVersions(worldIdHex)).hasSize(2);
        // A password-protected world is exactly where stale versions are most harmful: each one is
        // still readable with whatever password encrypted it.
        for (PieceManifest dropped : streamed.subList(0, 4)) {
            assertThat(store.has(dropped.blob())).isFalse();
        }
    }

    @Test
    void theNewestArchiveIsNeverEvicted() {
        WorldArchiveService archive = host();
        // The newest archive of a hosted world IS the world; a window of 0 (or -1) must clamp.
        archive.setRetainedVersions(0);
        assertThat(archive.retainedVersions()).isEqualTo(1);

        String worldIdHex = hashes.sha256("l61-world-floor".getBytes()).toHex();
        archive.seedArchive(worldIdHex, blob(7L, 10_000));
        PieceManifest newest = archive.seedArchive(worldIdHex, blob(8L, 10_000));

        assertThat(archive.heldVersions(worldIdHex)).hasSize(1);
        assertThat(store.has(newest.blob())).isTrue();
        assertThat(archive.holdingsFor(worldIdHex)).isNotEmpty();
    }

    @Test
    void theDefaultWindowLeavesShortSessionsUntouched() {
        WorldArchiveService archive = host();
        assertThat(archive.retainedVersions())
                .isEqualTo(WorldArchiveService.DEFAULT_RETAINED_VERSIONS);

        String worldIdHex = hashes.sha256("l61-world-short".getBytes()).toHex();
        PieceManifest first = archive.seedArchive(worldIdHex, blob(11L, 5_000));
        archive.seedArchive(worldIdHex, blob(12L, 5_000));

        assertThat(archive.heldVersions(worldIdHex)).hasSize(2);
        assertThat(store.has(first.blob()))
                .as("nothing is evicted before the window is full")
                .isTrue();
    }
}
