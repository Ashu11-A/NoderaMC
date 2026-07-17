package dev.nodera.core.consensuscert;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.StateRoot;

/**
 * A single validator's signed vote on a proposed commit (Task 2 consensuscert/). The
 * {@link Bytes signature} covers exactly the bytes returned by {@link #signedPortion()} (typeTag +
 * version + voter + resultingRoot + decision); the signature itself is never signed.
 *
 * <p>Wire form: {@code signedPortion()} || {@code [u32 len][signature bytes]}.
 *
 * @Thread-context immutable, any thread.
 */
public record SignedVote(
        NodeId voter,
        StateRoot resultingRoot,
        VoteDecision decision,
        Bytes signature
) implements Encodable {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null.
     */
    public SignedVote {
        if (voter == null) {
            throw new IllegalArgumentException("voter must not be null");
        }
        if (resultingRoot == null) {
            throw new IllegalArgumentException("resultingRoot must not be null");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision must not be null");
        }
        if (signature == null) {
            throw new IllegalArgumentException("signature must not be null");
        }
    }

    /**
     * The canonical bytes that the signature covers: {@code SIGNED_VOTE} typeTag + version + voter
     * + resultingRoot + decision. A strict prefix of {@link #encode(CanonicalWriter)}; excludes the
     * signature by construction.
     *
     * @Thread-context deterministic; safe from any thread.
     */
    public Bytes signedPortion() {
        CanonicalWriter w = new CanonicalWriter();
        writeSignedFields(w);
        return w.toBytes();
    }

    private void writeSignedFields(CanonicalWriter w) {
        w.writeU16(TypeTags.SIGNED_VOTE).writeU16(ENCODING_VERSION);
        voter.encode(w);
        resultingRoot.encode(w);
        decision.encode(w);
    }

    @Override
    public void encode(CanonicalWriter w) {
        writeSignedFields(w);
        w.writeBytes(signature);
    }

    /**
     * Full-frame decode (signed fields + signature).
     *
     * @throws IllegalStateException if the next tag is not {@code SIGNED_VOTE}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static SignedVote decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SIGNED_VOTE) {
            throw new IllegalStateException("expected SIGNED_VOTE tag, got " + tag);
        }
        r.readU16();
        NodeId voter = NodeId.decode(r);
        StateRoot resultingRoot = StateRoot.decode(r);
        VoteDecision decision = VoteDecision.decode(r);
        Bytes signature = r.readBytesValue();
        return new SignedVote(voter, resultingRoot, decision, signature);
    }
}
