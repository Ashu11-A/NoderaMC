package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Objects;

/**
 * A <b>world's own signing key</b> — the private half of the key pair that identifies a world's
 * administrator, held only by the peer that created that world.
 *
 * <h2>Why a world has a key of its own</h2>
 *
 * <p>Until this existed, "who administers this world" was answered by the creating <i>node's</i>
 * identity: {@link WorldIdentity} is signed by the author's {@code NodeIdentity}, so the two were
 * the same key. That conflates two different facts. A node key says <i>which machine this is</i> and
 * is used for every transport handshake on the network; a world key says <i>who runs this world</i>
 * and should be presentable without exposing anything else the node does. Giving each world its own
 * key pair means a peer can prove authority over one world — and only that world — by signing a
 * challenge with a key that exists on exactly one machine.
 *
 * <p>The public half travels the network inside {@link WorldOwnership}. This half never does. It is
 * written to the creating peer's disk with owner-only permissions and read back only to sign a
 * {@link WorldAdminProof} or to re-sign an ownership record.
 *
 * <h2>Modelled on {@code PersistedNodeIdentity}</h2>
 *
 * <p>Same shape and the same reasoning: the key blobs are held by a type whose name says it is
 * secret, generation and persistence happen together ({@link #generate}), and there is no path that
 * extracts a private key from any other object. Whoever writes it is responsible for file
 * permissions — {@code WorldKeyStore} in the worker does that.
 *
 * <p>Wire form (disk only, never a network message):
 * {@code [u16 WORLD_KEY_SECRET][u16 version][Bytes worldId][Bytes pkcs8Private][Bytes x509Public]}.
 *
 * @param worldId      the world this key administers.
 * @param pkcs8Private the PKCS#8-encoded Ed25519 private key. <b>Secret.</b>
 * @param x509Public   the X.509-encoded Ed25519 public key — the world's public key.
 * @Thread-context immutable record, safe for any thread.
 */
public record PersistedWorldKey(Bytes worldId, Bytes pkcs8Private, Bytes x509Public)
        implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if an argument is null or a key blob is empty.
     */
    public PersistedWorldKey {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(pkcs8Private, "pkcs8Private");
        Objects.requireNonNull(x509Public, "x509Public");
        if (pkcs8Private.isEmpty() || x509Public.isEmpty()) {
            throw new IllegalArgumentException("world key material must not be empty");
        }
    }

    /**
     * Generate a fresh key pair for one world.
     *
     * @param worldId the world this key will administer.
     * @return the persistable key pair.
     * @Thread-context any thread.
     */
    public static PersistedWorldKey generate(Bytes worldId) {
        Objects.requireNonNull(worldId, "worldId");
        try {
            KeyPair pair = KeyPairGenerator.getInstance(NoderaConstants.KEYPAIR_ALGORITHM)
                    .generateKeyPair();
            return new PersistedWorldKey(worldId,
                    Bytes.unsafeWrap(pair.getPrivate().getEncoded()),
                    Bytes.unsafeWrap(pair.getPublic().getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate a world key pair", e);
        }
    }

    /**
     * Sign canonical bytes with the world's private key.
     *
     * <p>The key is rebuilt per call rather than cached: signing happens when a world is first
     * shared and when somebody asks for a proof, which is rare enough that holding a live
     * {@link PrivateKey} in memory for the life of the process buys nothing and costs a place for
     * the key to leak from.
     *
     * @param data the exact bytes to sign.
     * @return the signature.
     * @Thread-context any thread.
     */
    public Bytes sign(Bytes data) {
        Objects.requireNonNull(data, "data");
        try {
            PrivateKey key = KeyFactory.getInstance(NoderaConstants.KEYPAIR_ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8Private.toArray()));
            Signature signer = Signature.getInstance(NoderaConstants.SIGNATURE_ALGORITHM);
            signer.initSign(key);
            signer.update(data.toArray());
            return Bytes.unsafeWrap(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign with the world key", e);
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_KEY_SECRET).writeU16(ENCODING_VERSION);
        w.writeBytes(worldId);
        w.writeBytes(pkcs8Private);
        w.writeBytes(x509Public);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this value's tag.
     * @return the decoded key pair.
     * @throws IllegalStateException if the next tag is not {@code WORLD_KEY_SECRET}.
     */
    public static PersistedWorldKey decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_KEY_SECRET) {
            throw new IllegalStateException("expected WORLD_KEY_SECRET tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes worldId = r.readBytesValue();
        Bytes priv = r.readBytesValue();
        Bytes pub = r.readBytesValue();
        return new PersistedWorldKey(worldId, priv, pub);
    }

    /** Never renders the private key. */
    @Override
    public String toString() {
        return "PersistedWorldKey[world=" + worldId.toShortHex(6)
                + ", pub=" + x509Public.toShortHex(4) + ", private=<redacted>]";
    }
}
