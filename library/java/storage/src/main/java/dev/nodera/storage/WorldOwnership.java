package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;

import java.util.Objects;

/**
 * The record that tells the network <b>who administers a world</b>: a world's own public key, bound
 * to the peer that created it, signed by both.
 *
 * <h2>The two signatures are the whole point</h2>
 *
 * <p>A world key on its own would prove only that <i>somebody</i> holds it, and a node claiming a
 * world would prove only that a node said so. This record carries both halves of the binding and is
 * refused unless both verify:
 *
 * <ul>
 *   <li>{@code worldSignature} — made with the world's private key ({@link PersistedWorldKey}).
 *       Whoever published this record holds the key that administers the world, so a peer cannot
 *       announce a world public key it does not own the private half of.</li>
 *   <li>{@code ownerSignature} — made with the creating peer's {@link NodeIdentity}. The world key
 *       belongs to <i>this</i> node, so a third party cannot re-publish somebody else's world key
 *       under its own node id and be believed.</li>
 * </ul>
 *
 * <p>Both cover {@link #signedPortion()}, which includes the world id, both public keys, the owner's
 * node id and the creation time — so neither signature can be lifted onto a different world, a
 * different key, or a different owner.
 *
 * <h2>What it is not</h2>
 *
 * <p>It is not a permission system: {@link WorldPermissionGrant} still carries per-subject roles,
 * anchored to the world author's <i>node</i> key. This record answers the prior question — which
 * key is the world's administrative root at all — and is what a peer verifies a
 * {@link WorldAdminProof} against.
 *
 * <p>It is also not a claim about content. A peer that holds a world's bytes is a supporter; a peer
 * that holds its private key is its administrator. Those are independent, which is exactly why the
 * companion app can list them as two separate things.
 *
 * <p>Wire form: {@code [u16 WORLD_OWNERSHIP][u16 version][Bytes worldId][Bytes worldPublicKey]
 * [NodeId ownerNodeId][Bytes ownerPublicKey][u64 createdAtEpoch][Bytes worldSignature]
 * [Bytes ownerSignature]}.
 *
 * @param worldId        the world being claimed.
 * @param worldPublicKey the world's X.509 public key — the administrative root.
 * @param ownerNodeId    the peer that created the world and holds the world's private key.
 * @param ownerPublicKey that peer's X.509 node public key.
 * @param createdAtEpoch when the claim was minted (epoch millis).
 * @param worldSignature the world key's signature over {@link #signedPortion()}.
 * @param ownerSignature the owner node key's signature over {@link #signedPortion()}.
 * @Thread-context immutable record, safe for any thread.
 */
public record WorldOwnership(
        Bytes worldId,
        Bytes worldPublicKey,
        NodeId ownerNodeId,
        Bytes ownerPublicKey,
        long createdAtEpoch,
        Bytes worldSignature,
        Bytes ownerSignature) implements Encodable {

    public WorldOwnership {
        if (worldId == null || worldPublicKey == null || ownerNodeId == null
                || ownerPublicKey == null || worldSignature == null || ownerSignature == null) {
            throw new IllegalArgumentException("WorldOwnership fields must not be null");
        }
    }

    /**
     * Mint and doubly sign a fresh ownership claim.
     *
     * @param owner          the creating peer's identity (supplies the node signature).
     * @param worldKey       the world's key pair (supplies the world signature and public key).
     * @param createdAtEpoch epoch millis the claim is minted.
     * @return the signed claim.
     * @throws IllegalArgumentException if either argument is null.
     * @Thread-context any thread.
     */
    public static WorldOwnership create(NodeIdentity owner, PersistedWorldKey worldKey,
                                        long createdAtEpoch) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(worldKey, "worldKey");
        WorldOwnership unsigned = new WorldOwnership(worldKey.worldId(), worldKey.x509Public(),
                owner.nodeId(), owner.publicKeyBytes(), createdAtEpoch, Bytes.empty(), Bytes.empty());
        Bytes signed = unsigned.signedPortion();
        return new WorldOwnership(worldKey.worldId(), worldKey.x509Public(), owner.nodeId(),
                owner.publicKeyBytes(), createdAtEpoch, worldKey.sign(signed), owner.sign(signed));
    }

    /** The canonical bytes both signatures cover: everything except the signatures themselves. */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        encodeSigned(w);
        return w.toBytes();
    }

    /**
     * Verify the claim end to end.
     *
     * <p>Both signatures must verify. A record with only one is not "partially valid" — it is a
     * claim somebody could have manufactured, so it is refused with no further distinction.
     *
     * @return whether this record is a genuine, self-authenticating ownership claim.
     * @Thread-context any thread.
     */
    public boolean verify() {
        SignatureService signatures = new SignatureService();
        Bytes signed = signedPortion();
        return signatures.verify(worldPublicKey, signed, worldSignature)
                && signatures.verify(ownerPublicKey, signed, ownerSignature);
    }

    /**
     * @param node the node to test.
     * @return whether {@code node} is the peer that created this world.
     */
    public boolean isOwner(NodeId node) {
        return ownerNodeId.equals(node);
    }

    private void encodeSigned(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_OWNERSHIP).writeU16(ENCODING_VERSION);
        w.writeBytes(worldId);
        w.writeBytes(worldPublicKey);
        ownerNodeId.encode(w);
        w.writeBytes(ownerPublicKey);
        w.writeU64(createdAtEpoch);
    }

    @Override
    public void encode(CanonicalWriter w) {
        encodeSigned(w);
        w.writeBytes(worldSignature);
        w.writeBytes(ownerSignature);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this value's tag.
     * @return the decoded claim (whose signatures the caller must still {@link #verify()}).
     * @throws IllegalStateException if the next tag is not {@code WORLD_OWNERSHIP}.
     */
    public static WorldOwnership decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_OWNERSHIP) {
            throw new IllegalStateException("expected WORLD_OWNERSHIP tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes worldId = r.readBytesValue();
        Bytes worldPublicKey = r.readBytesValue();
        NodeId owner = NodeId.decode(r);
        Bytes ownerPublicKey = r.readBytesValue();
        long createdAtEpoch = r.readU64();
        Bytes worldSignature = r.readBytesValue();
        Bytes ownerSignature = r.readBytesValue();
        return new WorldOwnership(worldId, worldPublicKey, owner, ownerPublicKey, createdAtEpoch,
                worldSignature, ownerSignature);
    }
}
