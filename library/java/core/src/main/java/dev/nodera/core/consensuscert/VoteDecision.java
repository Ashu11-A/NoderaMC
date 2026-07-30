package dev.nodera.core.consensuscert;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * A validator's verdict on a proposed commit (Task 2 consensuscert/). Encoded as a framed
 * {@code u8} ordinal (the ordinal is position-stable within this enum by Java language guarantee,
 * so it is a safe wire value as long as entries are only ever appended — never reordered).
 *
 * <p>Wire form: {@code [u16 VOTE_DECISION][u16 ENCODING_VERSION][u8 ordinal]}.
 *
 * @Thread-context immutable, any thread.
 */
public enum VoteDecision implements Encodable {
    /** The proposed delta/state-root is correct; the validator will sign it. */
    ACCEPT,
    /** The proposed {@link dev.nodera.core.state.StateRoot} does not match local computation. */
    REJECT_STATE_ROOT,
    /** An action in the batch is invalid (out of bounds, bad palette id, etc.). */
    REJECT_INVALID_ACTION,
    /** The proposal targets the wrong {@link dev.nodera.core.region.RegionEpoch}. */
    REJECT_WRONG_EPOCH,
    /** The replica is too far behind and must resync from a snapshot. */
    RESYNC_REQUIRED;

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.VOTE_DECISION).writeU16(ENCODING_VERSION);
        w.writeU8(ordinal());
    }

    /**
     * Full-frame decode.
     *
     * @throws IllegalStateException if the next tag is not {@code VOTE_DECISION} or the ordinal is
     *                               out of range.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static VoteDecision decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.VOTE_DECISION) {
            throw new IllegalStateException("expected VOTE_DECISION tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        int ord = r.readU8();
        VoteDecision[] values = values();
        if (ord < 0 || ord >= values.length) {
            throw new IllegalStateException("VoteDecision ordinal out of range: " + ord);
        }
        return values[ord];
    }
}
