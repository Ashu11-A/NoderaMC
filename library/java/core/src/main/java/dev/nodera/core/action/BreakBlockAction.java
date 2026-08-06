package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.NBlockPos;

/**
 * A player breaking a block (Task 2 action/). The resulting state is computed by the simulation
 * (typically air or a fluid); this action only carries the target position.
 *
 * <p>Wire form: {@code [u16 BREAK_BLOCK_ACTION][u16 ENCODING_VERSION][NBlockPos]}.
 *
 * @Thread-context immutable, any thread.
 */
public record BreakBlockAction(NBlockPos pos) implements GameAction {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code pos} is null.
     */
    public BreakBlockAction {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.BREAK_BLOCK_ACTION).writeU16(ENCODING_VERSION);
        encodeBody(w);
    }

    private void encodeBody(CanonicalWriter w) {
        pos.encode(w);
    }

    static BreakBlockAction decodeBody(CanonicalReader r) {
        NBlockPos pos = NBlockPos.decode(r);
        return new BreakBlockAction(pos);
    }
}
