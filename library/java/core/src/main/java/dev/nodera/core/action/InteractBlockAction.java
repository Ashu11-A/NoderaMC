package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.NBlockPos;

/**
 * A player interacting with a block (Task 13 action/ — lever flips, button presses, and later
 * palette interactions). The action carries only the target position: what the interaction DOES
 * is the rule set's decision (deterministic per block id), never the client's claim.
 *
 * <p>Wire form: {@code [u16 INTERACT_BLOCK_ACTION][u16 ENCODING_VERSION][NBlockPos]} — the tag
 * was reserved for Task 13 at the Phase-0 freeze.
 *
 * @Thread-context immutable, any thread.
 */
public record InteractBlockAction(NBlockPos pos) implements GameAction {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code pos} is null.
     */
    public InteractBlockAction {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.INTERACT_BLOCK_ACTION).writeU16(ENCODING_VERSION);
        encodeBody(w);
    }

    private void encodeBody(CanonicalWriter w) {
        pos.encode(w);
    }

    /**
     * Full-frame decode (reads tag + version + body).
     *
     * @throws IllegalStateException if the next tag is not {@code INTERACT_BLOCK_ACTION}.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static InteractBlockAction decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.INTERACT_BLOCK_ACTION) {
            throw new IllegalStateException("expected INTERACT_BLOCK_ACTION tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        return decodeBody(r);
    }

    static InteractBlockAction decodeBody(CanonicalReader r) {
        NBlockPos pos = NBlockPos.decode(r);
        return new InteractBlockAction(pos);
    }
}
