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
import java.util.Optional;

/**
 * The owner putting a world back — the exact counterpart of {@link WorldTombstone}.
 *
 * <h2>Why deletion needed an undo</h2>
 *
 * <p>A tombstone is remembered, by design, so that a peer which was offline during the deletion
 * cannot resurrect the world from a stale announce. But the same memory answers the owner's own
 * re-share with a refusal: the world id is derived from the save, so re-sharing the very same world
 * produces the very same id, and every node that kept the tombstone — including the owner's own
 * worker — refuses to host it. Deleting a world therefore made that save permanently unshareable,
 * with the only outward sign being a log line, while the pause menu still said "shared".
 *
 * <p>Deletion is the owner's decision. So is undeletion. This record carries exactly the evidence a
 * tombstone carries — the world's {@link WorldOwnership} claim, signed again by both the world key
 * and the owner's node key — so any receiver reaches the same verdict from first principles, with no
 * trust in whoever relayed it.
 *
 * <h2>Which one wins</h2>
 *
 * <p>Both records carry {@code issuedAtEpoch}, and the <b>newer one is the owner's current
 * intention</b>. That single rule makes the pair replay-safe in both directions: an old tombstone
 * arriving after a revival cannot re-delete the world, and an old revival arriving after a deletion
 * cannot resurrect it. A receiver keeps whichever it holds and compares timestamps; ties go to the
 * deletion, because the destructive answer is the safe one to be wrong about.
 *
 * <p>Wire form: {@code [u16 WORLD_REVIVAL][u16 version][Bytes worldId][Bytes ownershipRecord]
 * [u64 issuedAtEpoch][String reason][Bytes worldSignature][Bytes ownerSignature]}.
 *
 * @param worldId          the world to restore.
 * @param ownershipRecord  canonical {@link WorldOwnership} bytes — the evidence, carried inline.
 * @param issuedAtEpoch    when the owner issued it; what ranks it against a tombstone.
 * @param reason           the owner's own words, for a person reading a log. Never load-bearing.
 * @param worldSignature   the world key's signature over {@link #signedPortion()}.
 * @param ownerSignature   the owner node key's signature over the same bytes.
 * @Thread-context immutable record, safe for any thread.
 */
public record WorldRevival(
        Bytes worldId,
        Bytes ownershipRecord,
        long issuedAtEpoch,
        String reason,
        Bytes worldSignature,
        Bytes ownerSignature) implements Encodable {

    /** The longest reason that will be carried; anything more is truncated rather than refused. */
    public static final int MAX_REASON = WorldTombstone.MAX_REASON;

    private static final int ENCODING_VERSION = 1;

    public WorldRevival {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownershipRecord, "ownershipRecord");
        Objects.requireNonNull(worldSignature, "worldSignature");
        Objects.requireNonNull(ownerSignature, "ownerSignature");
        reason = reason == null ? "" : reason;
        if (reason.length() > MAX_REASON) {
            reason = reason.substring(0, MAX_REASON);
        }
        if (worldId.isEmpty()) {
            throw new IllegalArgumentException("a revival must name a world");
        }
    }

    /**
     * Issue a revival for a world this node administers.
     *
     * @param owner         the creating peer's identity — supplies the node signature.
     * @param worldKey      the world's key pair; only its administrator holds one.
     * @param ownership     the world's ownership claim, carried as the receiver's evidence.
     * @param reason        the owner's own words, or empty.
     * @param issuedAtEpoch epoch millis; must be later than the deletion it undoes.
     * @return the signed revival.
     * @throws IllegalArgumentException if the key, the claim and the owner do not describe one world.
     */
    public static WorldRevival create(NodeIdentity owner, PersistedWorldKey worldKey,
                                      WorldOwnership ownership, String reason,
                                      long issuedAtEpoch) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(ownership, "ownership");
        if (!ownership.worldId().equals(worldKey.worldId())) {
            throw new IllegalArgumentException("the world key is not for the world being restored");
        }
        if (!ownership.worldPublicKey().equals(worldKey.x509Public())) {
            throw new IllegalArgumentException("the ownership claim names a different world key");
        }
        if (!ownership.isOwner(owner.nodeId())) {
            throw new IllegalArgumentException("only the world's owner may restore it");
        }
        CanonicalWriter claim = new CanonicalWriter();
        ownership.encode(claim);
        WorldRevival unsigned = new WorldRevival(ownership.worldId(), claim.toBytes(),
                issuedAtEpoch, reason, Bytes.empty(), Bytes.empty());
        Bytes signed = unsigned.signedPortion();
        return new WorldRevival(ownership.worldId(), claim.toBytes(), issuedAtEpoch,
                unsigned.reason(), worldKey.sign(signed), owner.sign(signed));
    }

    /** The canonical bytes both signatures cover: everything except the signatures. */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        encodeSigned(w);
        return w.toBytes();
    }

    /**
     * The ownership claim this revival carries, if it is readable and genuine.
     *
     * @return the verified claim, or empty — which is a refusal, not a warning.
     */
    public Optional<WorldOwnership> ownership() {
        if (ownershipRecord.isEmpty()) {
            return Optional.empty();
        }
        try {
            WorldOwnership ownership = WorldOwnership.decode(new CanonicalReader(ownershipRecord));
            return ownership.verify() ? Optional.of(ownership) : Optional.empty();
        } catch (RuntimeException undecodable) {
            return Optional.empty();
        }
    }

    /**
     * Verify the whole chain: is this really the owner asking, for this world?
     *
     * @return whether this node should act on it.
     * @Thread-context any thread.
     */
    public boolean verify() {
        Optional<WorldOwnership> claim = ownership();
        if (claim.isEmpty()) {
            return false;
        }
        WorldOwnership ownership = claim.get();
        if (!ownership.worldId().equals(worldId)) {
            return false;
        }
        SignatureService signatures = new SignatureService();
        Bytes signed = signedPortion();
        return signatures.verify(ownership.worldPublicKey(), signed, worldSignature)
                && signatures.verify(ownership.ownerPublicKey(), signed, ownerSignature);
    }

    /**
     * Does this revival supersede a deletion?
     *
     * <p>Ties go to the tombstone. Two records at the same millisecond is not a real sequence, and
     * between "forget it" and "keep it" the answer that cannot lose somebody's world is the one that
     * keeps the deletion — the owner can always issue another revival a millisecond later.
     *
     * @param tombstone the deletion held for this world, or {@code null} if none.
     * @return whether this record is the owner's later word.
     */
    public boolean supersedes(WorldTombstone tombstone) {
        return tombstone == null || issuedAtEpoch > tombstone.issuedAtEpoch();
    }

    /**
     * @param node a node id.
     * @return whether this revival was issued by that node — only meaningful once {@link #verify()}
     *         has passed, since an unverified record can claim anything.
     */
    public boolean issuedBy(NodeId node) {
        return ownership().map(o -> o.isOwner(node)).orElse(false);
    }

    /** @return the world id, hex-encoded — the key every registry and cache uses. */
    public String worldIdHex() {
        return worldId.toHex();
    }

    private void encodeSigned(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_REVIVAL).writeU16(ENCODING_VERSION);
        w.writeBytes(worldId);
        w.writeBytes(ownershipRecord);
        w.writeU64(issuedAtEpoch);
        w.writeString(reason);
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
     * @return the decoded revival, which the caller must still {@link #verify()}.
     * @throws IllegalStateException if the next tag is not {@code WORLD_REVIVAL}.
     */
    public static WorldRevival decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_REVIVAL) {
            throw new IllegalStateException("expected WORLD_REVIVAL tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes worldId = r.readBytesValue();
        Bytes ownership = r.readBytesValue();
        long issuedAt = r.readU64();
        String reason = r.readString();
        Bytes worldSignature = r.readBytesValue();
        Bytes ownerSignature = r.readBytesValue();
        return new WorldRevival(worldId, ownership, issuedAt, reason, worldSignature,
                ownerSignature);
    }
}
