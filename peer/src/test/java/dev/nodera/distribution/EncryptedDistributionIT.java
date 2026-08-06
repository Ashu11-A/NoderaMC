package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;
import dev.nodera.distribution.DistFixtures.Peer;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.testkit.peer.PeerTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 23 end-to-end proof: seeders receive only hash-verifiable ciphertext; joining peer derives
 * key from password after download, decrypts, and recovers engine-committed state.
 */
final class EncryptedDistributionIT {

    private static final RegionId REGION = DistFixtures.region(0, 0);
    private static final int PIECE_TARGET = 512;

    private final PeerTestHarness harness = PeerTestHarness.create();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    /**
     * A keyless network participant: no password and no {@link ContentKey} reaches a seeder, which
     * is the property this suite exists to prove — so the peer type is the ordinary swarm one.
     */
    private Peer peer(long idBits) {
        return DistFixtures.peer(harness, idBits);
    }

    @Test
    void keylessSeedersServeCiphertextAndPasswordJoinRecoversEngineState() throws Exception {
        RegionSnapshot base = DistFixtures.fullUniformSnapshot(REGION, FlatWorldRules.AIR);
        RegionExecutionResult execution = DistFixtures.executeOneBatch(base, 299);
        RegionSnapshot post = SnapshotDeltaApplier.apply(base, execution.delta(), 1L);
        StateRoot engineRoot = execution.resultingRoot();
        assertThat(StateRoot.of(DistFixtures.hashes().hash(post))).isEqualTo(engineRoot);

        RegionSnapshotSplitter.Layout layout = RegionSnapshotSplitter.split(post, PIECE_TARGET);
        Bytes salt = Bytes.fromHex("00112233445566778899aabbccddeeff");
        WorldKeyMaterial keyMaterial = new WorldKeyMaterial(
                "argon2id",
                salt,
                Argon2KeyDerivation.MIN_MEMORY_KIB,
                Argon2KeyDerivation.MIN_ITERATIONS,
                Argon2KeyDerivation.MIN_PARALLELISM);
        char[] hostPassword = "correct horse battery staple".toCharArray();
        ContentKey hostKey;
        try {
            hostKey = Argon2KeyDerivation.from(keyMaterial)
                    .derive(hostPassword, salt, keyMaterial.iterations());
        } finally {
            java.util.Arrays.fill(hostPassword, '\0');
        }

        EncryptedRegion encrypted = EncryptedRegion.encrypt(layout, hostKey, keyMaterial);
        PieceManifest manifest = encrypted.manifest();
        assertThat(manifest.encrypted()).isTrue();
        assertThat(manifest.regionRoot()).isEqualTo(engineRoot);
        assertThat(manifest.keyMaterial()).isEqualTo(keyMaterial);
        assertThat(encrypted.ciphertextBlob()).isNotEqualTo(layout.blob());
        assertThat(manifest.blob().hash()).isNotEqualTo(engineRoot.hash());
        assertThat(manifest.pieceCount()).isGreaterThanOrEqualTo(8);

        List<Peer> seeders = List.of(peer(201), peer(202), peer(203));
        Peer joiner = peer(204);
        for (int i = 0; i < manifest.pieceCount(); i++) {
            Peer seeder = seeders.get(i % seeders.size());
            Bytes ciphertext = encrypted.ciphertextPiece(i);
            assertThat(manifest.verifyPiece(i, ciphertext)).isTrue();
            assertThat(seeder.content().seedPiece(manifest, i, ciphertext)).isTrue();
        }
        for (Peer seeder : seeders) {
            int held = seeder.content().heldPieces(manifest.manifestRoot()).cardinality();
            assertThat((double) held / manifest.pieceCount()).isLessThan(0.4);
        }

        ChunkLockMap locks = new ChunkLockMap();
        locks.track(manifest, layout.pieceOfChunk());
        PieceDownloader downloader = joiner.content().download(manifest, locks);
        seeders.forEach(seeder -> downloader.addHolder(seeder.content().availability()));
        CompletableFuture<Bytes> completed = downloader.start();
        Bytes downloadedCiphertext = completed.get(20, TimeUnit.SECONDS);

        assertThat(downloadedCiphertext).isEqualTo(encrypted.ciphertextBlob());
        assertThat(StateRoot.of(DistFixtures.hashes().sha256(downloadedCiphertext)))
                .isNotEqualTo(engineRoot);
        assertThat(locks.isRegionComplete(REGION)).isTrue();

        char[] joiningPassword = "correct horse battery staple".toCharArray();
        ContentKey joiningKey;
        try {
            joiningKey = Argon2KeyDerivation.from(manifest.keyMaterial())
                    .derive(joiningPassword, manifest.keyMaterial().salt(),
                            manifest.keyMaterial().iterations());
        } finally {
            java.util.Arrays.fill(joiningPassword, '\0');
        }
        Bytes plaintext = EncryptedRegion.decrypt(manifest, downloadedCiphertext, joiningKey)
                .orElseThrow();
        assertThat(plaintext).isEqualTo(layout.blob());
        assertThat(StateRoot.of(DistFixtures.hashes().sha256(plaintext))).isEqualTo(engineRoot);
        assertThat(RegionSnapshot.decode(new CanonicalReader(plaintext))).isEqualTo(post);

        char[] wrongPassword = "wrong password".toCharArray();
        ContentKey wrongKey;
        try {
            wrongKey = Argon2KeyDerivation.from(manifest.keyMaterial())
                    .derive(wrongPassword, manifest.keyMaterial().salt(),
                            manifest.keyMaterial().iterations());
        } finally {
            java.util.Arrays.fill(wrongPassword, '\0');
        }
        assertThat(EncryptedRegion.decrypt(manifest, downloadedCiphertext, wrongKey)).isEmpty();

        byte[] tamperedBytes = downloadedCiphertext.toArray();
        tamperedBytes[tamperedBytes.length / 2] ^= 1;
        assertThat(EncryptedRegion.decrypt(manifest, Bytes.unsafeWrap(tamperedBytes), joiningKey))
                .isEmpty();
    }
}
