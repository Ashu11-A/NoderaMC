package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionBatch;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.action.PlaceBlockAction;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;
import dev.nodera.simulation.RegionExecutionContext;
import dev.nodera.simulation.RegionExecutionRequest;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.engine.FlatWorldRegionEngine;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.testkit.LoopbackTransport;
import dev.nodera.transport.PeerAddress;
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
    private static final long WORLD_SEED = 0x4E4F4445_5241L;
    private static final int PIECE_TARGET = 512;

    /** Keyless network participant: no password or ContentKey field exists on this type. */
    private record KeylessPeer(
            NodeId id,
            LoopbackTransport transport,
            ContentTransferService content) {}

    @Test
    void keylessSeedersServeCiphertextAndPasswordJoinRecoversEngineState() throws Exception {
        RegionSnapshot base = DistFixtures.fullUniformSnapshot(REGION, FlatWorldRules.AIR);
        RegionExecutionResult execution = executeOneBatch(base);
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

        LoopbackTransport.LoopbackNetwork network = LoopbackTransport.LoopbackNetwork.newNetwork();
        List<KeylessPeer> seeders = List.of(
                peer(network, 201), peer(network, 202), peer(network, 203));
        KeylessPeer joiner = peer(network, 204);
        try {
            for (int i = 0; i < manifest.pieceCount(); i++) {
                KeylessPeer seeder = seeders.get(i % seeders.size());
                Bytes ciphertext = encrypted.ciphertextPiece(i);
                assertThat(manifest.verifyPiece(i, ciphertext)).isTrue();
                assertThat(seeder.content().seedPiece(manifest, i, ciphertext)).isTrue();
            }
            for (KeylessPeer seeder : seeders) {
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
        } finally {
            joiner.transport().stop();
            seeders.forEach(seeder -> seeder.transport().stop());
        }
    }

    private static KeylessPeer peer(LoopbackTransport.LoopbackNetwork network, long idBits) {
        NodeId id = DistFixtures.node(idBits);
        LoopbackTransport transport = network.register(id);
        ContentTransferService content = new ContentTransferService(
                id,
                transport,
                new DistFixtures.MapContentStore(),
                node -> PeerAddress.of(node, "loopback"),
                64,
                64L * 1024 * 1024);
        transport.setHandler(content);
        transport.start();
        return new KeylessPeer(id, transport, content);
    }

    private static RegionExecutionResult executeOneBatch(RegionSnapshot base) {
        FlatWorldRegionEngine engine = new FlatWorldRegionEngine(
                FlatWorldRules.RULES_VERSION,
                FlatWorldRules.registryFingerprint(),
                DistFixtures.hashes());
        ActionEnvelope place = new ActionEnvelope(
                DistFixtures.node(299),
                1L,
                1L,
                1L,
                REGION,
                new PlaceBlockAction(new NBlockPos(3, 0, 3), FlatWorldRules.STONE, 1),
                Bytes.empty());
        ActionBatch batch = new ActionBatch(
                REGION, RegionEpoch.INITIAL, base.version(), 1L, 1L, List.of(place));
        RegionExecutionContext context = new RegionExecutionContext(
                REGION,
                RegionEpoch.INITIAL,
                base.version(),
                1L,
                1L,
                WORLD_SEED,
                FlatWorldRules.RULES_VERSION,
                FlatWorldRules.registryFingerprint());
        return engine.execute(new RegionExecutionRequest(context, base, batch));
    }
}
