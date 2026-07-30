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
 * A world's owner asking the network to forget it — and the proof that it really was the owner.
 *
 * <h2>Why this has to carry its own evidence</h2>
 *
 * <p>A deletion is the one instruction on this network that destroys something. It travels to peers
 * that have never met the sender, through relays nobody vouches for, and arrives at nodes that were
 * offline when the world was created. If any of them had to <i>trust the messenger</i>, then anyone
 * able to reach a peer could delete anybody's world.
 *
 * <p>So a tombstone is self-contained. It embeds the world's {@link WorldOwnership} claim — itself
 * doubly signed, binding the world's public key to the peer that created it — and then signs the
 * deletion with <b>both</b> of those keys again. A receiver needs nothing it did not just get:
 *
 * <ol>
 *   <li>the embedded ownership claim verifies (world key ∧ owner key), so the world's administrative
 *       key is established from first principles;</li>
 *   <li>the claim is <i>for this world id</i>, so a valid claim cannot be lifted onto another world;</li>
 *   <li>the deletion verifies under that same world key, so only the administrator could have
 *       written it;</li>
 *   <li>and under the owner's node key, so a stolen world key alone is not enough.</li>
 * </ol>
 *
 * <p>Any of those failing means the tombstone is refused and <b>nothing is deleted</b>. There is no
 * partial acceptance: a deletion is not a thing to be half-sure about.
 *
 * <h2>It is a record, not an event</h2>
 *
 * <p>A tombstone is kept after it is applied. A peer that deleted a world and then received an older
 * announce for it would otherwise resurrect what its owner asked it to forget — so "deleted" has to
 * be a state the node remembers, not a message it processed once. Trackers keep them longer still
 * (120 days), because the peers that most need to hear about a deletion are the ones that were
 * offline when it happened.
 *
 * <p>Wire form: {@code [u16 WORLD_TOMBSTONE][u16 version][Bytes worldId][Bytes ownershipRecord]
 * [u64 issuedAtEpoch][String reason][Bytes worldSignature][Bytes ownerSignature]}.
 *
 * @param worldId          the world to forget.
 * @param ownershipRecord  canonical {@link WorldOwnership} bytes — the evidence, carried inline.
 * @param issuedAtEpoch    when the owner issued it.
 * @param reason           the owner's own words, for a person reading a log. Never load-bearing.
 * @param worldSignature   the world key's signature over {@link #signedPortion()}.
 * @param ownerSignature   the owner node key's signature over the same bytes.
 * @Thread-context immutable record, safe for any thread.
 */
public record WorldTombstone(
        Bytes worldId,
        Bytes ownershipRecord,
        long issuedAtEpoch,
        String reason,
        Bytes worldSignature,
        Bytes ownerSignature) implements Encodable {

    /** The longest reason that will be carried; anything more is truncated rather than refused. */
    public static final int MAX_REASON = 200;

    public WorldTombstone {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(ownershipRecord, "ownershipRecord");
        Objects.requireNonNull(worldSignature, "worldSignature");
        Objects.requireNonNull(ownerSignature, "ownerSignature");
        reason = reason == null ? "" : reason;
        if (reason.length() > MAX_REASON) {
            reason = reason.substring(0, MAX_REASON);
        }
        if (worldId.isEmpty()) {
            throw new IllegalArgumentException("a tombstone must name a world");
        }
    }

    /**
     * Issue a deletion for a world this node administers.
     *
     * @param owner      the creating peer's identity — supplies the node signature.
     * @param worldKey   the world's key pair; only its administrator holds one.
     * @param ownership  the world's ownership claim, carried as the receiver's evidence.
     * @param reason     the owner's own words, or empty.
     * @param issuedAtEpoch epoch millis.
     * @return the signed tombstone.
     * @throws IllegalArgumentException if the key, the claim and the owner do not describe one world.
     */
    public static WorldTombstone create(NodeIdentity owner, PersistedWorldKey worldKey,
                                        WorldOwnership ownership, String reason,
                                        long issuedAtEpoch) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(ownership, "ownership");
        // Refused here rather than producing something no receiver would accept: a tombstone whose
        // parts disagree is not a weaker deletion, it is a bug that would look like a forgery.
        if (!ownership.worldId().equals(worldKey.worldId())) {
            throw new IllegalArgumentException("the world key is not for the world being deleted");
        }
        if (!ownership.worldPublicKey().equals(worldKey.x509Public())) {
            throw new IllegalArgumentException("the ownership claim names a different world key");
        }
        if (!ownership.isOwner(owner.nodeId())) {
            throw new IllegalArgumentException("only the world's owner may delete it");
        }
        CanonicalWriter claim = new CanonicalWriter();
        ownership.encode(claim);
        WorldTombstone unsigned = new WorldTombstone(ownership.worldId(), claim.toBytes(),
                issuedAtEpoch, reason, Bytes.empty(), Bytes.empty());
        Bytes signed = unsigned.signedPortion();
        return new WorldTombstone(ownership.worldId(), claim.toBytes(), issuedAtEpoch,
                unsigned.reason(), worldKey.sign(signed), owner.sign(signed));
    }

    /** The canonical bytes both signatures cover: everything except the signatures. */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        encodeSigned(w);
        return w.toBytes();
    }

    /**
     * The ownership claim this tombstone carries, if it is readable and genuine.
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
     * <p>Everything needed is inside the record, so a peer that has never seen this world before
     * reaches the same answer as one that hosted it for a year. That symmetry is the point — it is
     * what lets a tombstone be relayed by anyone without the relay having to be trusted.
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
        // A genuine claim for a DIFFERENT world would otherwise let its owner delete this one.
        if (!ownership.worldId().equals(worldId)) {
            return false;
        }
        SignatureService signatures = new SignatureService();
        Bytes signed = signedPortion();
        return signatures.verify(ownership.worldPublicKey(), signed, worldSignature)
                && signatures.verify(ownership.ownerPublicKey(), signed, ownerSignature);
    }

    /**
     * @param node a node id.
     * @return whether this tombstone was issued by that node — only meaningful once {@link #verify()}
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
        w.writeU16(TypeTags.WORLD_TOMBSTONE).writeU16(ENCODING_VERSION);
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
     * @return the decoded tombstone, which the caller must still {@link #verify()}.
     * @throws IllegalStateException if the next tag is not {@code WORLD_TOMBSTONE}.
     */
    public static WorldTombstone decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_TOMBSTONE) {
            throw new IllegalStateException("expected WORLD_TOMBSTONE tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes worldId = r.readBytesValue();
        Bytes ownership = r.readBytesValue();
        long issuedAt = r.readU64();
        String reason = r.readString();
        Bytes worldSignature = r.readBytesValue();
        Bytes ownerSignature = r.readBytesValue();
        return new WorldTombstone(worldId, ownership, issuedAt, reason, worldSignature,
                ownerSignature);
    }
}
