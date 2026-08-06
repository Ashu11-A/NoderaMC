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
        w.writeFrame(TypeTags.INTERACT_BLOCK_ACTION, ENCODING_VERSION);
        encodeBody(w);
    }

    private void encodeBody(CanonicalWriter w) {
        pos.encode(w);
    }

    static InteractBlockAction decodeBody(CanonicalReader r) {
        NBlockPos pos = NBlockPos.decode(r);
        return new InteractBlockAction(pos);
    }
}
