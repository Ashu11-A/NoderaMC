package dev.nodera.core.identity;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Objects;

/**
 * A node's identity: {@link NodeId} + an Ed25519 key pair (Plan §3.7, Task 2 identity/).
 *
 * <p><b>The private key never leaves this object.</b> Signing is performed here, in-process, via
 * {@link #sign(Bytes)}. Other code references a node by {@link NodeId} + the public key bytes.
 * Identity generation uses {@link SecureRandom} (allowed — identity generation is OUTSIDE the
 * deterministic engine path; the engine never reads clocks or RNGs).
 *
 * <p>Encoding/serialization of the key pair to disk is Task 5's concern (server
 * {@code <world>/nodera/server-identity.bin}, client game-dir). This type is the in-memory handle.
 *
 * <p>Thread-context: {@link #sign(Bytes)} synchronizes on the private key (JDK {@link Signature}
 * is not thread-safe). Other accessors are safe for any thread.
 */
public final class NodeIdentity {

    private final NodeId nodeId;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final Bytes publicKeyBytes;

    private final Object signLock = new Object();

    private NodeIdentity(NodeId nodeId, PrivateKey privateKey, PublicKey publicKey, Bytes publicKeyBytes) {
        this.nodeId = nodeId;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.publicKeyBytes = publicKeyBytes;
    }

    /** Generate a fresh identity (random NodeId + fresh Ed25519 key pair). */
    public static NodeIdentity generate() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(NoderaConstants.KEYPAIR_ALGORITHM);
            // Ed25519 is a fixed-size algorithm; do NOT pass a keysize (the JDK rejects
            // initialize(int,...) here). generateKeyPair() uses a cryptographically secure random.
            KeyPair kp = kpg.generateKeyPair();
            NodeId id = NodeId.random();
            return new NodeIdentity(id, kp.getPrivate(), kp.getPublic(), Bytes.unsafeWrap(kp.getPublic().getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate Ed25519 key pair", e);
        }
    }

    /** Reconstruct an identity from persisted material (Task 5 uses this). */
    public static NodeIdentity fromKeys(NodeId nodeId, PrivateKey privateKey, PublicKey publicKey) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(publicKey, "publicKey");
        return new NodeIdentity(nodeId, privateKey, publicKey, Bytes.unsafeWrap(publicKey.getEncoded()));
    }

    public NodeId nodeId() {
        return nodeId;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    /** X.509-encoded public key bytes — the wire form used for verification everywhere. */
    public Bytes publicKeyBytes() {
        return publicKeyBytes;
    }

    /**
     * Sign the given canonical bytes with this identity's private key. Used for action envelopes,
     * proposals, and votes.
     *
     * @param data the exact bytes produced by {@link dev.nodera.core.crypto.CanonicalWriter} /
     *             the type's {@code signedPortion()} (NEVER Java serialization)
     */
    public Bytes sign(Bytes data) {
        synchronized (signLock) {
            try {
                Signature sig = Signature.getInstance(NoderaConstants.SIGNATURE_ALGORITHM);
                sig.initSign(privateKey);
                sig.update(data.toArray());
                return Bytes.unsafeWrap(sig.sign());
            } catch (Exception e) {
                throw new IllegalStateException("Ed25519 signing failed", e);
            }
        }
    }

    /** Convenience: sign raw bytes. */
    public Bytes sign(byte[] data) {
        return sign(Bytes.unsafeWrap(data));
    }

    @Override
    public String toString() {
        return "NodeIdentity[" + nodeId + ", pub=" + publicKeyBytes.toShortHex(4) + "]";
    }
}
