package dev.nodera.core.crypto;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Ed25519 signature <b>verification</b> and key utilities (Plan §3.7 / Task 2).
 *
 * <p>Signing lives on {@code dev.nodera.core.identity.NodeIdentity} — the private key never
 * leaves that object. This service performs verification plus key encoding/generation helpers
 * and deliberately has <b>no dependency on the {@code identity} package</b>, keeping the
 * {@code crypto} layer dependency-light and independently testable. {@link #generateKeyPair()}
 * is kept consistent with {@code NodeIdentity}'s own generation path (same algorithm, same
 * keysize hint, same {@link SecureRandom} source).
 *
 * <p>Public keys are exchanged and persisted in their X.509 subject-public-key-info encoding;
 * {@link #verify(Bytes, Bytes, Bytes)} rebuilds a {@link PublicKey} from those bytes on each call.
 *
 * <p>Thread-context: verification allocates a fresh {@link Signature} per call, so it is safe
 * for any thread with no external synchronization.
 */
public final class SignatureService {

    /** Create a new verification service (stateless beyond cached algorithm names). */
    public SignatureService() {}

    /**
     * Verify an Ed25519 signature against the signer's X.509-encoded public key.
     *
     * @param publicKeyX509 the signer's public key in X.509 DER ({@code NodeIdentity.publicKeyBytes()})
     * @param data          the exact canonical bytes that were signed
     * @param signature     the signature bytes produced by {@code NodeIdentity.sign(data)}
     * @return {@code true} iff the signature is valid for {@code (key, data)}
     */
    public boolean verify(Bytes publicKeyX509, Bytes data, Bytes signature) {
        return verify(publicKeyX509.toArray(), data.toArray(), signature.toArray());
    }

    /** Byte-array convenience for {@link #verify(Bytes, Bytes, Bytes)}. */
    public boolean verify(byte[] publicKeyX509, byte[] data, byte[] signature) {
        // A missing algorithm/provider is a deployment problem worth failing hard on.
        final Signature verifier;
        try {
            verifier = Signature.getInstance(NoderaConstants.SIGNATURE_ALGORITHM);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 Signature algorithm unavailable", e);
        }
        // Any malformed key or signature, or a cryptographically invalid signature, means the
        // pair is simply NOT valid — return false rather than throwing. This covers tampered
        // signatures (SunEC throws SignatureException on invalid encodings), malformed keys, and
        // wrong-key/wrong-data cases uniformly.
        try {
            PublicKey key = KeyFactory.getInstance(NoderaConstants.KEYPAIR_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(publicKeyX509));
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generate a fresh Ed25519 {@link KeyPair}. Helper; {@code NodeIdentity} uses its own
     * equivalent path internally. Uses a fresh {@link SecureRandom} — identity/key generation is
     * explicitly outside the deterministic engine path.
     */
    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(NoderaConstants.KEYPAIR_ALGORITHM);
            // Ed25519 is fixed-size; the JDK rejects initialize(int,...). generateKeyPair()
            // seeds itself from a cryptographically secure random source.
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate Ed25519 key pair", e);
        }
    }

    /** Encode a {@link PublicKey} to its X.509 wire form wrapped as immutable {@link Bytes}. */
    public Bytes encodePublicKey(PublicKey key) {
        return Bytes.unsafeWrap(key.getEncoded());
    }
}
