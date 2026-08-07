package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.crypto.symmetric.ContentCipher;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.crypto.symmetric.PasswordKeyDerivation;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.distribution.DistFixtures.Peer;
import dev.nodera.shadow.SnapshotDeltaApplier;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.simulation.rules.FlatWorldRules;
import dev.nodera.testkit.engine.EngineFixtures;
import dev.nodera.testkit.peer.PeerTestHarness;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The encrypted content lane, end to end: derive a key from a world password, wrap a piece, wrap a
 * region, wrap a whole archive, and move the result between peers.
 *
 * <p>Six sibling classes over one subject — every one of them turns on {@code ContentCipher} and
 * {@code PasswordKeyDerivation}, and every one of them re-typed the same import block to get there.
 * The imports move here; the fixtures stay per-nest, because a KDF benchmark, a canonical
 * round-trip and a two-peer swarm need genuinely different setups and a shared one would have been
 * a default quietly settling that.
 *
 * <p>Each nest keeps the class Javadoc naming what it was written from, and JUnit reports every
 * {@code @Nested @Test} individually, so the count this file contributes is unchanged.
 */
final class EncryptedContentTest {

    /** L-39 KDF selection: production prefers Argon2id; both sides derive identically. */
    @Nested
    final class PasswordKeyDerivationsTest {
        @Test
        void productionSelectsArgon2WhenBouncyCastleIsPresent() {
            // The test classpath pins bcprov, so the production default MUST be Argon2id here —
            // a silent PBKDF2 downgrade on a full classpath would weaken every new world.
            assertThat(PasswordKeyDerivations.argon2Available()).isTrue();
            assertThat(PasswordKeyDerivations.production())
                    .isInstanceOf(Argon2KeyDerivation.class);
        }

        @Test
        void selectedKdfDerivesDeterministically() {
            PasswordKeyDerivation kdf = PasswordKeyDerivations.production();
            Bytes salt = Bytes.fromHex("00112233445566778899aabbccddeeff");
            var first = kdf.derive("hunter2".toCharArray(), salt, 3);
            var second = kdf.derive("hunter2".toCharArray(), salt, 3);
            assertThat(first.rawBytes()).isEqualTo(second.rawBytes());
            assertThat(kdf.derive("hunter3".toCharArray(), salt, 3).rawBytes())
                    .isNotEqualTo(first.rawBytes());
        }
    }

    /**
     * Argon2id KDF (Task 23; L-39). Memory-hard, BouncyCastle-backed. Same properties as the PBKDF2
     * test: same inputs ⇒ same key; different salt ⇒ different key; cost bounds enforced.
     *
     * <p>Thread-context: single test thread.
     */
    @Nested
    final class Argon2KeyDerivationTest {
        private static final Bytes SALT = Bytes.fromHex("0011223344556677889900aabbccddee");

        private static Argon2KeyDerivation fast() {
            // Minimum-cost instance keeps the test fast while still exercising the real Argon2id path.
            return new Argon2KeyDerivation(Argon2KeyDerivation.MIN_MEMORY_KIB, Argon2KeyDerivation.MIN_PARALLELISM);
        }

        @Test
        void kdfIdIsArgon2id() {
            assertThat(fast().kdfId()).isEqualTo("argon2id");
        }

        @Test
        void sameInputsProduceTheSameKey() {
            Argon2KeyDerivation kdf = fast();
            char[] pw = "hunter2".toCharArray();
            ContentKey a = kdf.derive(pw, SALT, Argon2KeyDerivation.MIN_ITERATIONS);
            ContentKey b = kdf.derive(pw, SALT, Argon2KeyDerivation.MIN_ITERATIONS);
            assertThat(a.rawBytes()).isEqualTo(b.rawBytes());
            assertThat(a.rawBytes().length()).isEqualTo(ContentKey.KEY_BYTES);
        }

        @Test
        void differentSaltProducesDifferentKey() {
            Argon2KeyDerivation kdf = fast();
            char[] pw = "hunter2".toCharArray();
            ContentKey a = kdf.derive(pw, SALT, Argon2KeyDerivation.MIN_ITERATIONS);
            ContentKey b = kdf.derive(pw, Bytes.fromHex("ffeeddccbbaa00998877665544332211"),
                    Argon2KeyDerivation.MIN_ITERATIONS);
            assertThat(a.rawBytes()).isNotEqualTo(b.rawBytes());
        }

        @Test
        void argon2AndPbkdf2KeysDifferForTheSamePassword() {
            // Different KDFs must not agree (else one could substitute for the other silently).
            char[] pw = "hunter2".toCharArray();
            ContentKey argon = fast().derive(pw, SALT, Argon2KeyDerivation.MIN_ITERATIONS);
            ContentKey pbkdf = new dev.nodera.core.crypto.symmetric.Pbkdf2KeyDerivation()
                    .derive(pw, SALT, dev.nodera.core.crypto.symmetric.Pbkdf2KeyDerivation.MIN_ITERATIONS);
            assertThat(argon.rawBytes()).isNotEqualTo(pbkdf.rawBytes());
        }

        @Test
        void unicodePasswordMatchesItsUtf8Encoding() {
            String password = "päss🔐";
            ContentKey actual = fast().derive(
                    password.toCharArray(), SALT, Argon2KeyDerivation.MIN_ITERATIONS);

            Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(Argon2KeyDerivation.MIN_MEMORY_KIB)
                    .withIterations(Argon2KeyDerivation.MIN_ITERATIONS)
                    .withParallelism(Argon2KeyDerivation.MIN_PARALLELISM)
                    .withSalt(SALT.toArray())
                    .build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);
            byte[] expected = new byte[ContentKey.KEY_BYTES];
            generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), expected);

            assertThat(actual.rawBytes().toArray()).isEqualTo(expected);
        }

        @Test
        void canonicallyEquivalentUnicodePasswordsRemainDistinct() {
            Argon2KeyDerivation kdf = fast();
            ContentKey composed = kdf.derive(
                    "café".toCharArray(), SALT, Argon2KeyDerivation.MIN_ITERATIONS);
            ContentKey decomposed = kdf.derive(
                    "café".toCharArray(), SALT, Argon2KeyDerivation.MIN_ITERATIONS);

            assertThat(composed.rawBytes()).isNotEqualTo(decomposed.rawBytes());
        }

        @Test
        void malformedUtf16PasswordsAreRejected() {
            assertThatThrownBy(() -> fast().derive(
                    new char[]{'a', '\ud800'}, SALT, Argon2KeyDerivation.MIN_ITERATIONS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed UTF-16");
            assertThatThrownBy(() -> fast().derive(
                    new char[]{'a', '\udc00'}, SALT, Argon2KeyDerivation.MIN_ITERATIONS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed UTF-16");
        }

        @Test
        void rejectsCostsOutsideMinimumAndMaximumBounds() {
            Argon2KeyDerivation maximum = new Argon2KeyDerivation(
                    Argon2KeyDerivation.MAX_MEMORY_KIB, Argon2KeyDerivation.MAX_PARALLELISM);
            assertThat(maximum.memoryKib()).isEqualTo(Argon2KeyDerivation.MAX_MEMORY_KIB);
            assertThat(maximum.parallelism()).isEqualTo(Argon2KeyDerivation.MAX_PARALLELISM);

            assertThatThrownBy(() -> new Argon2KeyDerivation(
                    Argon2KeyDerivation.MIN_MEMORY_KIB - 1, Argon2KeyDerivation.MIN_PARALLELISM))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Argon2KeyDerivation(
                    Argon2KeyDerivation.MAX_MEMORY_KIB + 1, Argon2KeyDerivation.MIN_PARALLELISM))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Argon2KeyDerivation(
                    Argon2KeyDerivation.MIN_MEMORY_KIB, Argon2KeyDerivation.MIN_PARALLELISM - 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Argon2KeyDerivation(
                    Argon2KeyDerivation.MIN_MEMORY_KIB, Argon2KeyDerivation.MAX_PARALLELISM + 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fast().derive(
                    "pw".toCharArray(), SALT, Argon2KeyDerivation.MIN_ITERATIONS - 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fast().derive(
                    "pw".toCharArray(), SALT, Argon2KeyDerivation.MAX_ITERATIONS + 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsEmptyAndOversizedPasswords() {
            assertThatThrownBy(() -> fast().derive(
                    new char[0], SALT, Argon2KeyDerivation.MIN_ITERATIONS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fast().derive(
                    new char[NoderaConstants.PASSWORD_KDF_MAX_PASSWORD_CHARS + 1], SALT,
                    Argon2KeyDerivation.MIN_ITERATIONS))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsSaltOutsideBounds() {
            assertThatThrownBy(() -> fast().derive(
                    "pw".toCharArray(), Bytes.fromHex("00".repeat(15)),
                    Argon2KeyDerivation.MIN_ITERATIONS))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fast().derive(
                    "pw".toCharArray(), Bytes.fromHex("00".repeat(65)),
                    Argon2KeyDerivation.MIN_ITERATIONS))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void createsFromValidatedWorldKeyMetadata() {
            WorldKeyMaterial material = new WorldKeyMaterial(
                    PasswordKeyDerivation.ARGON2ID,
                    SALT,
                    Argon2KeyDerivation.MIN_MEMORY_KIB,
                    Argon2KeyDerivation.MIN_ITERATIONS,
                    Argon2KeyDerivation.MAX_PARALLELISM);

            Argon2KeyDerivation kdf = Argon2KeyDerivation.from(material);

            assertThat(kdf.memoryKib()).isEqualTo(Argon2KeyDerivation.MIN_MEMORY_KIB);
            assertThat(kdf.parallelism()).isEqualTo(Argon2KeyDerivation.MAX_PARALLELISM);
            assertThatThrownBy(() -> Argon2KeyDerivation.from(new WorldKeyMaterial(
                    PasswordKeyDerivation.PBKDF2,
                    SALT,
                    Argon2KeyDerivation.MIN_MEMORY_KIB,
                    Argon2KeyDerivation.MIN_ITERATIONS,
                    Argon2KeyDerivation.MIN_PARALLELISM)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported kdf");
            assertThatThrownBy(() -> new WorldKeyMaterial(
                    PasswordKeyDerivation.ARGON2ID,
                    SALT,
                    (long) Argon2KeyDerivation.MAX_MEMORY_KIB + 1,
                    Argon2KeyDerivation.MIN_ITERATIONS,
                    Argon2KeyDerivation.MIN_PARALLELISM))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("memoryKib");
            assertThatThrownBy(() -> Argon2KeyDerivation.from(new WorldKeyMaterial(
                    PasswordKeyDerivation.ARGON2ID,
                    SALT,
                    Argon2KeyDerivation.MIN_MEMORY_KIB,
                    Argon2KeyDerivation.MAX_ITERATIONS + 1,
                    Argon2KeyDerivation.MIN_PARALLELISM)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Canonical and structural checks for Task 23's ciphertext wrapper. */
    @Nested
    final class EncryptedPieceTest {
        private static final Bytes NONCE = Bytes.fromHex("00112233445566778899aabb");
        private static final Bytes CIPHERTEXT = Bytes.fromHex("00".repeat(ContentCipher.TAG_BITS / Byte.SIZE));

        @Test
        void roundTripsCanonicallyWithAppendOnlyTypeTag() {
            EncryptedPiece original = new EncryptedPiece(NONCE, CIPHERTEXT);
            CanonicalWriter writer = new CanonicalWriter();
            original.encode(writer);

            CanonicalReader peek = new CanonicalReader(writer.toByteArray());
            assertThat(peek.readU16()).isEqualTo(TypeTags.ENCRYPTED_PIECE);
            assertThat(EncryptedPiece.decode(new CanonicalReader(writer.toByteArray())))
                    .isEqualTo(original);
        }

        @Test
        void ciphertextHashDoesNotRequireOrCoverThePublicNonce() {
            EncryptedPiece first = new EncryptedPiece(NONCE, CIPHERTEXT);
            EncryptedPiece second = new EncryptedPiece(
                    Bytes.fromHex("ffeeddccbbaa998877665544"), CIPHERTEXT);

            assertThat(first.ciphertextHash()).isEqualTo(second.ciphertextHash());
        }

        @Test
        void rejectsInvalidNonceAndTruncatedTag() {
            assertThatThrownBy(() -> new EncryptedPiece(Bytes.fromHex("00"), CIPHERTEXT))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EncryptedPiece(NONCE, Bytes.fromHex("00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Structural and root-binding checks for encrypted region layouts. */
    @Nested
    final class EncryptedRegionTest {
        private static final Bytes SALT = Bytes.fromHex("00112233445566778899aabbccddeeff");

        private static RegionSnapshotSplitter.Layout layout() {
            return RegionSnapshotSplitter.split(
                    EngineFixtures.variedSnapshot(
                            EngineFixtures.region(7, -2), new SnapshotVersion(9L), 90L),
                    512);
        }

        private static ContentKey key(byte value) {
            byte[] raw = new byte[ContentKey.KEY_BYTES];
            java.util.Arrays.fill(raw, value);
            return ContentKey.of(raw);
        }

        @Test
        void encryptionIsDeterministicAndDecryptsToCommittedPlaintext() {
            RegionSnapshotSplitter.Layout layout = layout();
            WorldKeyMaterial material = WorldKeyMaterial.defaultArgon2id(SALT);

            EncryptedRegion first = EncryptedRegion.encrypt(layout, key((byte) 1), material);
            EncryptedRegion second = EncryptedRegion.encrypt(layout, key((byte) 1), material);

            assertThat(first).isEqualTo(second);
            assertThat(first.manifest().regionRoot()).isEqualTo(layout.manifest().regionRoot());
            assertThat(first.manifest().blob().hash()).isNotEqualTo(layout.manifest().blob().hash());
            assertThat(first.decrypt(key((byte) 1))).contains(layout.blob());
            assertThat(first.decrypt(key((byte) 2))).isEmpty();
        }

        @Test
        void constructorRejectsTransportedNonceSubstitution() {
            EncryptedRegion valid = EncryptedRegion.encrypt(
                    layout(), key((byte) 1), WorldKeyMaterial.defaultArgon2id(SALT));
            List<EncryptedPiece> changed = new ArrayList<>(valid.pieces());
            EncryptedPiece first = changed.get(0);
            byte[] nonce = first.nonce().toArray();
            nonce[0] ^= 1;
            changed.set(0, new EncryptedPiece(Bytes.unsafeWrap(nonce), first.ciphertext()));

            assertThatThrownBy(() -> new EncryptedRegion(valid.manifest(), changed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-canonical nonce");
        }

        @Test
        void downloadedBlobIsSplitUsingManifestAndTamperFailsBeforeDecrypt() {
            EncryptedRegion valid = EncryptedRegion.encrypt(
                    layout(), key((byte) 1), WorldKeyMaterial.defaultArgon2id(SALT));
            EncryptedRegion rebuilt = EncryptedRegion.fromCiphertext(
                    valid.manifest(), valid.ciphertextBlob());
            assertThat(rebuilt).isEqualTo(valid);

            byte[] tampered = valid.ciphertextBlob().toArray();
            tampered[tampered.length - ContentCipher.TAG_BITS / Byte.SIZE] ^= 1;
            assertThat(EncryptedRegion.decrypt(
                    valid.manifest(), Bytes.unsafeWrap(tampered), key((byte) 1))).isEmpty();
        }
    }

    /**
     * Task 23 / L-39 — the worker SEED/join halves headlessly: an archive snapshot seeded under a
     * world password moves ONLY ciphertext (piece hashes, ContentId, and blob all cover ciphertext),
     * the KDF parameters travel publicly in the manifest, the right password recovers the exact
     * plaintext, a wrong password fails the AES-GCM tag and yields empty — never a garbage archive —
     * and tampered ciphertext is rejected.
     */
    @Nested
    final class EncryptedArchiveTest {
        private static final HashService HASHES = new HashService();

        private static byte[] archiveBlob() {
            byte[] blob = new byte[300_000]; // > 1 archive piece
            new SecureRandom(new byte[]{42}).nextBytes(blob);
            return blob;
        }

        private static Bytes salt() {
            byte[] salt = new byte[dev.nodera.core.NoderaConstants.PASSWORD_KDF_SALT_BYTES];
            Arrays.fill(salt, (byte) 7);
            return Bytes.unsafeWrap(salt);
        }

        @Test
        void rightPasswordRoundTripsAndSeedersOnlyEverSeeCiphertext() {
            byte[] plain = archiveBlob();
            EncryptedRegion sealed = WorldArchive.encryptArchive(
                    3, plain, "correct horse".toCharArray(), salt());

            PieceManifest manifest = sealed.manifest();
            assertThat(manifest.encrypted()).isTrue();
            assertThat(manifest.keyMaterial()).isNotNull();
            assertThat(manifest.regionRoot().hash())
                    .as("the plaintext identity stays pinned in regionRoot")
                    .isEqualTo(HASHES.sha256(plain));
            byte[] ciphertext = sealed.ciphertextBlob().toArray();
            assertThat(Arrays.equals(ciphertext, plain))
                    .as("the stored/served blob is ciphertext, not the save")
                    .isFalse();
            for (int i = 0; i < manifest.pieceCount(); i++) {
                assertThat(manifest.piece(i).pieceHash())
                        .as("piece hashes cover ciphertext (keyless seeders verify without the key)")
                        .isEqualTo(HASHES.sha256(sealed.pieces().get(i).ciphertext()));
            }

            Optional<byte[]> decrypted = WorldArchive.decryptArchive(
                    manifest, ciphertext, "correct horse".toCharArray());
            assertThat(decrypted).isPresent();
            assertThat(decrypted.get()).isEqualTo(plain);
        }

        @Test
        void wrongPasswordYieldsEmptyNeverGarbage() {
            byte[] plain = archiveBlob();
            EncryptedRegion sealed = WorldArchive.encryptArchive(
                    1, plain, "right".toCharArray(), salt());
            assertThat(WorldArchive.decryptArchive(
                    sealed.manifest(), sealed.ciphertextBlob().toArray(), "wrong".toCharArray()))
                    .isEmpty();
        }

        @Test
        void tamperedCiphertextIsRejected() {
            byte[] plain = archiveBlob();
            EncryptedRegion sealed = WorldArchive.encryptArchive(
                    1, plain, "pw-secret".toCharArray(), salt());
            byte[] tampered = sealed.ciphertextBlob().toArray();
            tampered[tampered.length / 2] ^= 0x01;
            assertThat(WorldArchive.decryptArchive(
                    sealed.manifest(), tampered, "pw-secret".toCharArray()))
                    .as("a flipped ciphertext bit fails the GCM tag")
                    .isEmpty();
        }

        @Test
        void decryptRefusesAPlaintextManifest() {
            byte[] plain = archiveBlob();
            PieceManifest plainManifest = WorldArchive.manifestFor(1, plain);
            assertThatThrownBy(() -> WorldArchive.decryptArchive(
                    plainManifest, plain, "any".toCharArray()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not an encrypted archive");
        }
    }

    /**
     * Task 23 end-to-end proof: seeders receive only hash-verifiable ciphertext; joining peer derives
     * key from password after download, decrypts, and recovers engine-committed state.
     */
    @Nested
    final class EncryptedDistributionIT {
        private static final RegionId REGION = EngineFixtures.region(0, 0);
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
            RegionSnapshot base = EngineFixtures.fullUniformSnapshot(REGION, FlatWorldRules.AIR);
            RegionExecutionResult execution = DistFixtures.executeOneBatch(base, 299);
            RegionSnapshot post = SnapshotDeltaApplier.apply(base, execution.delta(), 1L);
            StateRoot engineRoot = execution.resultingRoot();
            assertThat(StateRoot.of(EngineFixtures.hashes().hash(post))).isEqualTo(engineRoot);

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
            assertThat(StateRoot.of(EngineFixtures.hashes().sha256(downloadedCiphertext)))
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
            assertThat(StateRoot.of(EngineFixtures.hashes().sha256(plaintext))).isEqualTo(engineRoot);
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
}
