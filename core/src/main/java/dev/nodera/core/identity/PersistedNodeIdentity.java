package dev.nodera.core.identity;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Objects;
import java.util.UUID;

/**
 * The on-disk form of a {@link NodeIdentity} — the mechanism that lets a returning peer keep its
 * {@link NodeId} across restarts (Task 20; retires LIMITATIONS L-28).
 *
 * <h2>Why this type exists separately</h2>
 *
 * <p>{@link NodeIdentity} promises that the private key never leaves the object. Persistence
 * necessarily breaks that promise <i>once</i>, so it is done here, in one clearly-named place,
 * rather than by adding a general-purpose {@code privateKeyBytes()} accessor that any caller could
 * reach for. {@link #of(NodeIdentity, PrivateKey)} requires the caller to already hold the private
 * key, so this type cannot be used to <i>extract</i> a key from an identity it was not given.
 *
 * <h2>This is secret material</h2>
 *
 * <p>The encoded form contains a PKCS#8 private key in the clear. Whoever writes it to disk is
 * responsible for owner-only file permissions; {@code peer-runtime}'s identity store does that.
 * Never send this over a transport, never log it, never put it in a content-addressed store.
 *
 * <p>Wire form (disk only, never a network message):
 * {@code [u16 NODE_IDENTITY_SECRET][u16 ENCODING_VERSION][u64 idMsb][u64 idLsb]
 * [bytes pkcs8PrivateKey][bytes x509PublicKey]}.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param nodeId     the stable node id to restore.
 * @param pkcs8Private the PKCS#8-encoded Ed25519 private key. <b>Secret.</b>
 * @param x509Public   the X.509-encoded Ed25519 public key.
 */
public record PersistedNodeIdentity(NodeId nodeId, Bytes pkcs8Private, Bytes x509Public)
        implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null or a key blob is empty.
     */
    public PersistedNodeIdentity {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(pkcs8Private, "pkcs8Private");
        Objects.requireNonNull(x509Public, "x509Public");
        if (pkcs8Private.isEmpty() || x509Public.isEmpty()) {
            throw new IllegalArgumentException("key material must not be empty");
        }
    }

    /**
     * Capture an identity for persistence.
     *
     * @param identity   the identity (supplies the id and public key).
     * @param privateKey the matching private key, which the caller must already hold.
     * @return the persistable form.
     * @throws IllegalArgumentException if an argument is null.
     * @Thread-context any thread.
     */
    public static PersistedNodeIdentity of(NodeIdentity identity, PrivateKey privateKey) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(privateKey, "privateKey");
        return new PersistedNodeIdentity(
                identity.nodeId(),
                Bytes.unsafeWrap(privateKey.getEncoded()),
                identity.publicKeyBytes());
    }

    /**
     * Generate a fresh identity together with its persistable form, so a first run can save what it
     * just created without the private key having to be pried back out of {@link NodeIdentity}.
     *
     * @return the pair.
     * @Thread-context any thread.
     */
    public static Generated generate() {
        try {
            java.security.KeyPairGenerator kpg =
                    java.security.KeyPairGenerator.getInstance(NoderaConstants.KEYPAIR_ALGORITHM);
            java.security.KeyPair kp = kpg.generateKeyPair();
            NodeId id = NodeId.random();
            NodeIdentity identity = NodeIdentity.fromKeys(id, kp.getPrivate(), kp.getPublic());
            return new Generated(identity, of(identity, kp.getPrivate()));
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate Ed25519 key pair", e);
        }
    }

    /**
     * A freshly generated identity plus its persistable form.
     *
     * @param identity  the live identity.
     * @param persisted its on-disk form.
     * @Thread-context immutable record, safe for any thread.
     */
    public record Generated(NodeIdentity identity, PersistedNodeIdentity persisted) {}

    /**
     * Rebuild the live identity from persisted material.
     *
     * @return the restored identity, with the same {@link NodeId} it had before.
     * @throws IllegalStateException if the key material cannot be parsed.
     * @Thread-context any thread.
     */
    public NodeIdentity restore() {
        try {
            KeyFactory factory = KeyFactory.getInstance(NoderaConstants.KEYPAIR_ALGORITHM);
            PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Private.toArray()));
            PublicKey pub = factory.generatePublic(new X509EncodedKeySpec(x509Public.toArray()));
            return NodeIdentity.fromKeys(nodeId, priv, pub);
        } catch (Exception e) {
            throw new IllegalStateException("failed to restore persisted node identity", e);
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.NODE_IDENTITY_SECRET).writeU16(ENCODING_VERSION);
        w.writeU64(nodeId.value().getMostSignificantBits());
        w.writeU64(nodeId.value().getLeastSignificantBits());
        w.writeBytes(pkcs8Private);
        w.writeBytes(x509Public);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this value's tag.
     * @return the decoded persisted identity.
     * @throws IllegalStateException if the next tag is not {@code NODE_IDENTITY_SECRET}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static PersistedNodeIdentity decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.NODE_IDENTITY_SECRET) {
            throw new IllegalStateException("expected NODE_IDENTITY_SECRET tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        long msb = r.readU64();
        long lsb = r.readU64();
        Bytes priv = r.readBytesValue();
        Bytes pub = r.readBytesValue();
        return new PersistedNodeIdentity(new NodeId(new UUID(msb, lsb)), priv, pub);
    }

    /** Never renders the private key. */
    @Override
    public String toString() {
        return "PersistedNodeIdentity[" + nodeId + ", pub=" + x509Public.toShortHex(4)
                + ", private=<redacted>]";
    }
}
