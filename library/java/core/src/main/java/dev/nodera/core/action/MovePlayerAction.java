package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.FixedVec3;

/**
 * A player's signed, committee-validated step (Task 16, L-12): the actor asks to move its root
 * presence to {@code to}. The engine — not the client — decides legality: the step is bounded by
 * the rule set's speed envelope, the destination must be passable, and a step out of the owned
 * region becomes a dupe-proof cross-region transfer. A modified client cannot teleport, speed, or
 * clip through walls any further than the rules allow.
 *
 * <p>Wire form: {@code [u16 MOVE_PLAYER_ACTION][u16 ENCODING_VERSION][FixedVec3 to]}.
 *
 * @Thread-context immutable, any thread.
 */
public record MovePlayerAction(FixedVec3 to) implements GameAction {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code to} is null.
     */
    public MovePlayerAction {
        if (to == null) {
            throw new IllegalArgumentException("to must not be null");
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.MOVE_PLAYER_ACTION).writeU16(ENCODING_VERSION);
        to.encode(w);
    }

    static MovePlayerAction decodeBody(CanonicalReader r) {
        return new MovePlayerAction(FixedVec3.decode(r));
    }
}
