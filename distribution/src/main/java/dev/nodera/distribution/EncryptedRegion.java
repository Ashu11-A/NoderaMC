package dev.nodera.distribution;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.symmetric.ContentCipher;
import dev.nodera.core.crypto.symmetric.ContentKey;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.storage.Compression;
import dev.nodera.storage.ContentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Encrypts a plaintext {@link RegionSnapshotSplitter.Layout} into an encrypted world's piece plane
 * (Task 23; L-39), and decrypts fetched ciphertext back to the canonical region blob.
 *
 * <h2>What encryption changes — and what it does not</h2>
 *
 * <p>The plaintext region root, version, tick, and geometry remain public. Each plaintext piece is
 * AES-GCM-encrypted under a nonce deterministically derived by {@link ContentCipher#nonceFor}; the
 * encrypted manifest pins ciphertext hashes, ciphertext lengths, and a ciphertext {@link ContentId}.
 * Task 19's data plane therefore moves only opaque ciphertext. A seeder neither receives this object
 * nor needs a {@link ContentKey}; it stores the bytes returned by {@link #ciphertextPiece(int)}.
 *
 * <p>Thread-context: immutable record; factories and accessors are safe from any thread.
 *
 * @param manifest encrypted manifest whose pieces pin ciphertext.
 * @param pieces encrypted pieces indexed in parallel with {@code manifest.pieces()}.
 */
public record EncryptedRegion(PieceManifest manifest, List<EncryptedPiece> pieces) {

    private static final HashService HASHES = new HashService();

    /**
     * Compact constructor verifies nonce derivation, every ciphertext piece, and the ciphertext blob.
     *
     * @throws IllegalArgumentException if metadata or bytes disagree.
     */
    public EncryptedRegion {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(pieces, "pieces");
        if (!manifest.encrypted()) {
            throw new IllegalArgumentException("EncryptedRegion manifest must be encrypted");
        }
        if (pieces.size() != manifest.pieceCount()) {
            throw new IllegalArgumentException(
                    "pieces count " + pieces.size() + " != manifest pieceCount "
                            + manifest.pieceCount());
        }

        List<EncryptedPiece> copy = List.copyOf(pieces);
        for (int i = 0; i < copy.size(); i++) {
            EncryptedPiece encryptedPiece = copy.get(i);
            Bytes expectedNonce = ContentCipher.nonceFor(
                    manifest.regionRoot(), manifest.version(), i);
            if (!expectedNonce.equals(encryptedPiece.nonce())) {
                throw new IllegalArgumentException("piece " + i + " carries a non-canonical nonce");
            }
            if (!manifest.verifyPiece(i, encryptedPiece.ciphertext())) {
                throw new IllegalArgumentException(
                        "piece " + i + " ciphertext does not match the encrypted manifest");
            }
        }

        Bytes ciphertextBlob = joinCiphertexts(copy, manifest.totalLength());
        if (!HASHES.sha256(ciphertextBlob).equals(manifest.blob().hash())) {
            throw new IllegalArgumentException("ciphertext blob does not match the manifest ContentId");
        }
        pieces = copy;
    }

    /** @return encrypted piece metadata at {@code index}. */
    public EncryptedPiece piece(int index) {
        return pieces.get(index);
    }

    /** @return ciphertext bytes a keyless seeder stores and serves for {@code index}. */
    public Bytes ciphertextPiece(int index) {
        return pieces.get(index).ciphertext();
    }

    /** @return contiguous ciphertext blob addressed by {@link PieceManifest#blob()}. */
    public Bytes ciphertextBlob() {
        return joinCiphertexts(pieces, manifest.totalLength());
    }

    /**
     * Encrypt a plaintext layout under a password-derived content key.
     *
     * @param layout plaintext region layout.
     * @param key content key derived by the host.
     * @param keyMaterial public KDF metadata carried by the encrypted manifest.
     * @return ciphertext manifest and pieces.
     * @Thread-context any thread.
     */
    public static EncryptedRegion encrypt(
            RegionSnapshotSplitter.Layout layout,
            ContentKey key,
            WorldKeyMaterial keyMaterial) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(keyMaterial, "keyMaterial");

        PieceManifest plain = layout.manifest();
        if (plain.encrypted()) {
            throw new IllegalArgumentException("layout must contain a plaintext manifest");
        }
        StateRoot regionRoot = plain.regionRoot();
        SnapshotVersion version = plain.version();
        byte[] plaintextBlob = layout.blob().toArray();

        List<Piece> ciphertextPieces = new ArrayList<>(plain.pieceCount());
        List<EncryptedPiece> encryptedPieces = new ArrayList<>(plain.pieceCount());
        long offset = 0;
        for (int i = 0; i < plain.pieceCount(); i++) {
            Piece plainPiece = plain.piece(i);
            int plainOffset = Math.toIntExact(plainPiece.offset());
            int plainLength = Math.toIntExact(plainPiece.length());
            Bytes plaintext = new Bytes(plaintextBlob, plainOffset, plainLength);
            Bytes nonce = ContentCipher.nonceFor(regionRoot, version, i);
            Bytes ciphertext = ContentCipher.encrypt(key, nonce, plaintext);
            EncryptedPiece encryptedPiece = new EncryptedPiece(nonce, ciphertext);
            encryptedPieces.add(encryptedPiece);
            ciphertextPieces.add(new Piece(
                    i, offset, ciphertext.length(), encryptedPiece.ciphertextHash()));
            offset = Math.addExact(offset, ciphertext.length());
        }
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ciphertext blob exceeds in-memory transfer limit");
        }

        Bytes ciphertextBlob = joinCiphertexts(encryptedPieces, offset);
        Bytes blobHash = HASHES.sha256(ciphertextBlob);
        ContentId blobId = new ContentId(blobHash, ciphertextBlob.length(), Compression.NONE);
        PieceManifest encryptedManifest = PieceManifest.encrypted(
                plain.region(), version, plain.tick(), regionRoot,
                blobId, ciphertextBlob.length(), keyMaterial, ciphertextPieces);
        return new EncryptedRegion(encryptedManifest, encryptedPieces);
    }

    /**
     * Recreate the decrypting view from ciphertext delivered by keyless seeders.
     *
     * @param manifest encrypted manifest used by Task 19's verifier.
     * @param ciphertextBlob completely verified ciphertext blob.
     * @return encrypted region view with nonces re-derived locally, never trusted from transport.
     * @throws IllegalArgumentException if blob or manifest fails validation.
     */
    public static EncryptedRegion fromCiphertext(
            PieceManifest manifest,
            Bytes ciphertextBlob) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(ciphertextBlob, "ciphertextBlob");
        if (!manifest.encrypted()) {
            throw new IllegalArgumentException("manifest must be encrypted");
        }
        if (ciphertextBlob.length() != manifest.totalLength()) {
            throw new IllegalArgumentException("ciphertext blob length does not match manifest");
        }

        byte[] blob = ciphertextBlob.toArray();
        List<EncryptedPiece> pieces = new ArrayList<>(manifest.pieceCount());
        for (int i = 0; i < manifest.pieceCount(); i++) {
            Piece piece = manifest.piece(i);
            Bytes ciphertext = new Bytes(
                    blob, Math.toIntExact(piece.offset()), Math.toIntExact(piece.length()));
            Bytes nonce = ContentCipher.nonceFor(manifest.regionRoot(), manifest.version(), i);
            pieces.add(new EncryptedPiece(nonce, ciphertext));
        }
        return new EncryptedRegion(manifest, pieces);
    }

    /**
     * Decrypt one verified ciphertext piece. Nonce comes from manifest context, not transported data.
     *
     * @return plaintext piece, or empty for wrong key/authentication failure.
     */
    public Optional<Bytes> decryptPiece(int index, ContentKey key) {
        Objects.requireNonNull(key, "key");
        EncryptedPiece encryptedPiece = pieces.get(index);
        Bytes nonce = ContentCipher.nonceFor(manifest.regionRoot(), manifest.version(), index);
        return ContentCipher.decrypt(key, nonce, encryptedPiece.ciphertext());
    }

    /**
     * Decrypt all pieces and prove the plaintext blob matches the committee's {@link StateRoot}.
     *
     * @return canonical plaintext region blob, or empty for wrong key/tamper/root mismatch.
     */
    public Optional<Bytes> decrypt(ContentKey key) {
        Objects.requireNonNull(key, "key");
        long plaintextLength = manifest.totalLength()
                - (long) manifest.pieceCount() * (ContentCipher.TAG_BITS / Byte.SIZE);
        if (plaintextLength <= 0 || plaintextLength > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        byte[] plaintextBlob = new byte[(int) plaintextLength];
        int offset = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Optional<Bytes> plaintext = decryptPiece(i, key);
            if (plaintext.isEmpty()) {
                return Optional.empty();
            }
            Bytes piece = plaintext.orElseThrow();
            piece.copyInto(plaintextBlob, offset);
            offset += piece.length();
        }
        if (offset != plaintextBlob.length) {
            return Optional.empty();
        }
        Bytes plaintext = Bytes.unsafeWrap(plaintextBlob);
        if (!HASHES.sha256(plaintext).equals(manifest.regionRoot().hash())) {
            return Optional.empty();
        }
        return Optional.of(plaintext);
    }

    /**
     * Validate, decrypt, and root-check a downloaded ciphertext blob in one call.
     *
     * @return plaintext blob, or empty for malformed ciphertext, wrong key, or root mismatch.
     */
    public static Optional<Bytes> decrypt(
            PieceManifest manifest,
            Bytes ciphertextBlob,
            ContentKey key) {
        try {
            return fromCiphertext(manifest, ciphertextBlob).decrypt(key);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static Bytes joinCiphertexts(List<EncryptedPiece> pieces, long totalLength) {
        if (totalLength < 0 || totalLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("ciphertext blob exceeds in-memory transfer limit");
        }
        byte[] blob = new byte[(int) totalLength];
        int offset = 0;
        for (EncryptedPiece piece : pieces) {
            Bytes ciphertext = piece.ciphertext();
            if (ciphertext.length() > blob.length - offset) {
                throw new IllegalArgumentException("ciphertext pieces exceed manifest length");
            }
            ciphertext.copyInto(blob, offset);
            offset += ciphertext.length();
        }
        if (offset != blob.length) {
            throw new IllegalArgumentException("ciphertext pieces do not fill manifest length");
        }
        return Bytes.unsafeWrap(blob);
    }
}
