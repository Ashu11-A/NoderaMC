package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;

/**
 * Task 33: the per-world identity record that turns a plain Minecraft save into a Nodera-shared
 * world with a stable, unique identity and a provable <b>original author</b>. It is written into the
 * save folder (as {@code nodera-world.dat}) when a world is first "Opened to Nodera" and is the
 * source of truth for: the world's unique id, who created it (the author's {@link NodeId} + public
 * key), whether it is currently shared/listed/encrypted, and the author's signature over all of that.
 *
 * <p>The author signature makes the record self-authenticating: any peer can verify that this world
 * id and share state were asserted by the holder of {@code authorPublicKey}, and only that holder
 * (the worker identity, Task 32) can produce a newer signed record — which is what lets the system
 * enforce "only the original host changes the password / share settings".
 *
 * <p>{@code worldId} is derived — {@code SHA-256(genesisRoot ‖ authorPublicKey ‖ createdAtEpoch)} —
 * so it is stable across renames, unique per (world, author), and independent of the save's display
 * name. See {@link #deriveWorldId}.
 *
 * <p>Wire form: {@code [u16 WORLD_IDENTITY][u16 version][Bytes worldId][NodeId author]
 * [Bytes authorPublicKey][u64 createdAtEpoch][bool shared][bool listedOnTracker][bool encrypted]
 * [Bytes manifestRef][Bytes signature]}. The signature covers {@link #signedPortion()} — everything
 * up to but excluding the signature.
 *
 * @Thread-context immutable, any thread.
 */
public record WorldIdentity(
        Bytes worldId,
        NodeId authorNodeId,
        Bytes authorPublicKey,
        long createdAtEpoch,
        boolean shared,
        boolean listedOnTracker,
        boolean encrypted,
        Bytes manifestRef,
        Bytes signature) implements Encodable {

    public WorldIdentity {
        if (worldId == null || authorNodeId == null || authorPublicKey == null
                || manifestRef == null || signature == null) {
            throw new IllegalArgumentException("WorldIdentity fields must not be null");
        }
    }

    /**
     * Derive the stable world id from the genesis root, the author's public key, and the creation
     * time. Deterministic and collision-resistant per (world, author).
     *
     * @param genesisRoot the 32-byte world genesis/state root (or any stable per-world seed bytes).
     * @param authorPublicKey the author's X.509 public key bytes.
     * @param createdAtEpoch  epoch millis the world was first shared.
     * @return the derived 32-byte world id.
     */
    public static Bytes deriveWorldId(Bytes genesisRoot, Bytes authorPublicKey, long createdAtEpoch) {
        CanonicalWriter w = new CanonicalWriter();
        w.writeBytes(genesisRoot);
        w.writeBytes(authorPublicKey);
        w.writeU64(createdAtEpoch);
        return new HashService().sha256(w.toBytes());
    }

    /**
     * Build and sign a fresh world identity with the given author.
     *
     * @param author       the world author (the worker identity, Task 32).
     * @param genesisRoot  a stable per-world seed (genesis/state root bytes).
     * @param createdAtEpoch creation time (epoch millis).
     * @param shared       whether the world is shared now.
     * @param listedOnTracker whether it is listed publicly on the tracker.
     * @param encrypted    whether its content is password-encrypted.
     * @param manifestRef  a reference to the current content manifest (may be {@link Bytes#empty()}).
     * @return a signed {@link WorldIdentity}.
     */
    public static WorldIdentity create(NodeIdentity author, Bytes genesisRoot, long createdAtEpoch,
                                       boolean shared, boolean listedOnTracker, boolean encrypted,
                                       Bytes manifestRef) {
        Bytes worldId = deriveWorldId(genesisRoot, author.publicKeyBytes(), createdAtEpoch);
        return sign(author, worldId, createdAtEpoch, shared, listedOnTracker, encrypted, manifestRef);
    }

    /** Re-sign a modified copy (share state / manifest change) keeping the same id + author. */
    public WorldIdentity resign(NodeIdentity author, boolean nowShared, boolean nowListed,
                                boolean nowEncrypted, Bytes newManifestRef) {
        if (!author.nodeId().equals(authorNodeId)) {
            throw new IllegalArgumentException("only the original author may re-sign a WorldIdentity");
        }
        return sign(author, worldId, createdAtEpoch, nowShared, nowListed, nowEncrypted, newManifestRef);
    }

    private static WorldIdentity sign(NodeIdentity author, Bytes worldId, long createdAtEpoch,
                                      boolean shared, boolean listedOnTracker, boolean encrypted,
                                      Bytes manifestRef) {
        WorldIdentity unsigned = new WorldIdentity(worldId, author.nodeId(), author.publicKeyBytes(),
                createdAtEpoch, shared, listedOnTracker, encrypted, manifestRef, Bytes.empty());
        Bytes sig = author.sign(unsigned.signedPortion());
        return new WorldIdentity(worldId, author.nodeId(), author.publicKeyBytes(), createdAtEpoch,
                shared, listedOnTracker, encrypted, manifestRef, sig);
    }

    /** The canonical bytes the author signature covers: everything except the signature itself. */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        encodeSigned(w);
        return w.toBytes();
    }

    /** @return whether the author signature verifies against {@link #authorPublicKey}. */
    public boolean verifySignature() {
        return new SignatureService().verify(authorPublicKey, signedPortion(), signature);
    }

    /** @return whether {@code identity} is this world's original author. */
    public boolean isAuthor(NodeId identity) {
        return authorNodeId.equals(identity);
    }

    private void encodeSigned(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_IDENTITY).writeU16(ENCODING_VERSION);
        w.writeBytes(worldId);
        authorNodeId.encode(w);
        w.writeBytes(authorPublicKey);
        w.writeU64(createdAtEpoch);
        w.writeBoolean(shared);
        w.writeBoolean(listedOnTracker);
        w.writeBoolean(encrypted);
        w.writeBytes(manifestRef);
    }

    @Override
    public void encode(CanonicalWriter w) {
        encodeSigned(w);
        w.writeBytes(signature);
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code WORLD_IDENTITY}.
     */
    public static WorldIdentity decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_IDENTITY) {
            throw new IllegalStateException("expected WORLD_IDENTITY tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes worldId = r.readBytesValue();
        NodeId author = NodeId.decode(r);
        Bytes authorPublicKey = r.readBytesValue();
        long createdAtEpoch = r.readU64();
        boolean shared = r.readBoolean();
        boolean listedOnTracker = r.readBoolean();
        boolean encrypted = r.readBoolean();
        Bytes manifestRef = r.readBytesValue();
        Bytes signature = r.readBytesValue();
        return new WorldIdentity(worldId, author, authorPublicKey, createdAtEpoch, shared,
                listedOnTracker, encrypted, manifestRef, signature);
    }
}
