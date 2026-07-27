package dev.nodera.storage;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;

import java.util.Objects;

/**
 * A peer's answer to "prove you administer this world": a challenge signed by the world's private
 * key.
 *
 * <h2>Why a challenge and not just the ownership record</h2>
 *
 * <p>{@link WorldOwnership} is a public record — it is gossiped, so anyone can hold a copy and
 * anyone can replay it. Presenting one proves nothing about the presenter. This proof binds a
 * verifier-chosen {@code challenge} into the signed bytes, so only a peer that currently holds the
 * world's private key can produce it, and a proof captured from one exchange cannot be reused in
 * another.
 *
 * <p>The claimant's node id is signed too. Without it a proof intercepted in flight could be
 * forwarded by a different node claiming to be the administrator — the signature would still verify
 * because it says nothing about who is presenting it.
 *
 * <p>Verification needs only the world's public key, which is what {@link WorldOwnership} publishes.
 * A verifier therefore checks two independent things: that the ownership record is genuine
 * ({@link WorldOwnership#verify()}), and that this proof answers <i>its own</i> challenge under that
 * record's key ({@link #verify(Bytes, Bytes, NodeId)}).
 *
 * <p>Wire form: {@code [u16 WORLD_ADMIN_PROOF][u16 version][Bytes worldId][Bytes challenge]
 * [NodeId claimant][u64 issuedAtEpoch][Bytes signature]}.
 *
 * @param worldId       the world whose administration is being proved.
 * @param challenge     the verifier's nonce, echoed back inside the signed bytes.
 * @param claimant      the node presenting the proof.
 * @param issuedAtEpoch epoch millis the proof was made (lets a verifier bound its freshness).
 * @param signature     the world private key's signature over {@link #signedPortion()}.
 * @Thread-context immutable record, safe for any thread.
 */
public record WorldAdminProof(
        Bytes worldId,
        Bytes challenge,
        NodeId claimant,
        long issuedAtEpoch,
        Bytes signature) implements Encodable {

    public WorldAdminProof {
        if (worldId == null || challenge == null || claimant == null || signature == null) {
            throw new IllegalArgumentException("WorldAdminProof fields must not be null");
        }
    }

    /**
     * Sign a challenge with the world's private key.
     *
     * @param worldKey      the world key pair — only the administrator has one.
     * @param claimant      the node presenting the proof (bound into the signature).
     * @param challenge     the verifier's nonce; must not be empty, or the proof is replayable.
     * @param issuedAtEpoch epoch millis.
     * @return the signed proof.
     * @throws IllegalArgumentException if the challenge is empty.
     * @Thread-context any thread.
     */
    public static WorldAdminProof create(PersistedWorldKey worldKey, NodeId claimant,
                                         Bytes challenge, long issuedAtEpoch) {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(claimant, "claimant");
        Objects.requireNonNull(challenge, "challenge");
        // An empty challenge would make every proof for a world byte-identical and therefore
        // replayable forever by anyone who saw one. Refused rather than signed.
        if (challenge.isEmpty()) {
            throw new IllegalArgumentException("a proof challenge must not be empty");
        }
        WorldAdminProof unsigned = new WorldAdminProof(worldKey.worldId(), challenge, claimant,
                issuedAtEpoch, Bytes.empty());
        return new WorldAdminProof(worldKey.worldId(), challenge, claimant, issuedAtEpoch,
                worldKey.sign(unsigned.signedPortion()));
    }

    /** The canonical bytes the signature covers: everything except the signature itself. */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        encodeSigned(w);
        return w.toBytes();
    }

    /**
     * Verify this proof against a world public key <b>and the verifier's own expectations</b>.
     *
     * <p>The expected challenge and claimant are parameters rather than read off the proof on
     * purpose: checking a proof against the values it carries would always pass. The verifier must
     * supply the nonce it issued and the node it believes it is talking to.
     *
     * @param worldPublicKey    the world's public key, from a verified {@link WorldOwnership}.
     * @param expectedChallenge the nonce this verifier issued.
     * @param expectedClaimant  the node this verifier is challenging.
     * @return whether the proof is genuine, fresh for this challenge, and from that node.
     * @Thread-context any thread.
     */
    public boolean verify(Bytes worldPublicKey, Bytes expectedChallenge, NodeId expectedClaimant) {
        if (worldPublicKey == null || expectedChallenge == null || expectedClaimant == null) {
            return false;
        }
        if (!challenge.equals(expectedChallenge) || !claimant.equals(expectedClaimant)) {
            return false;
        }
        return new SignatureService().verify(worldPublicKey, signedPortion(), signature);
    }

    private void encodeSigned(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_ADMIN_PROOF).writeU16(ENCODING_VERSION);
        w.writeBytes(worldId);
        w.writeBytes(challenge);
        claimant.encode(w);
        w.writeU64(issuedAtEpoch);
    }

    @Override
    public void encode(CanonicalWriter w) {
        encodeSigned(w);
        w.writeBytes(signature);
    }

    /**
     * Full-frame decode.
     *
     * @param r the reader positioned at this value's tag.
     * @return the decoded proof (which the caller must still verify).
     * @throws IllegalStateException if the next tag is not {@code WORLD_ADMIN_PROOF}.
     */
    public static WorldAdminProof decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_ADMIN_PROOF) {
            throw new IllegalStateException("expected WORLD_ADMIN_PROOF tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        Bytes worldId = r.readBytesValue();
        Bytes challenge = r.readBytesValue();
        NodeId claimant = NodeId.decode(r);
        long issuedAtEpoch = r.readU64();
        Bytes signature = r.readBytesValue();
        return new WorldAdminProof(worldId, challenge, claimant, issuedAtEpoch, signature);
    }
}
