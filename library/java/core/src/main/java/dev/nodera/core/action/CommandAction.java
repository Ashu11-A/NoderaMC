package dev.nodera.core.action;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.TypeTags;
import dev.nodera.core.state.NBlockPos;

/**
 * One command from the <b>deterministic command subset</b> (Task 16 / L-14).
 *
 * <p>Commands were outside the validated path entirely, which is a bigger hole than it sounds: a
 * `/setblock` executed server-side mutated a delegated region behind the committee's back, so the
 * operator's world and every validator's replica silently diverged. Bringing commands in means
 * they stop being a privileged side channel and become ordinary signed actions — validated for
 * authority and legality, committed by quorum, and identical on every replica.
 *
 * <p><b>Why a subset, and why these.</b> A command belongs in the validated lane only if its effect
 * is a pure function of committed state and its own arguments. That admits the block and time
 * commands below. It excludes anything whose result depends on the server's private view or on
 * wall-clock — those must stay out rather than be admitted and quietly desync. The set is
 * deliberately append-only: a new {@link Kind} is a new ordinal, never a renumbering.
 *
 * <p>Wire form:
 * {@code [u16 COMMAND_ACTION][u16 ENCODING_VERSION][u16 kind][NBlockPos from][NBlockPos to][u32 arg]}.
 * {@code from}/{@code to} bound the affected volume ({@code from == to} for a single block) and
 * {@code arg} is the kind's single integer parameter — a block state id, or a time-of-day.
 *
 * @param kind the command; not null.
 * @param from the inclusive lower corner of the affected volume; not null.
 * @param to   the inclusive upper corner; not null.
 * @param arg  the kind's integer argument (block state id, tick-of-day, …).
 * @Thread-context immutable, any thread.
 */
public record CommandAction(Kind kind, NBlockPos from, NBlockPos to, int arg)
        implements GameAction {

    /**
     * The admitted commands. Ordinals are the wire form — <b>append only</b>.
     *
     * <p>Each one is a pure function of (committed state, arguments): no server-private view, no
     * wall-clock, no randomness outside the per-action {@code DeterministicRandom}.
     */
    public enum Kind {
        /** {@code /setblock <pos> <state>} — one block becomes {@code arg}. */
        SETBLOCK,
        /** {@code /fill <from> <to> <state>} — every block in the box becomes {@code arg}. */
        FILL,
        /** {@code /time set <value>} — the region's committed world time becomes {@code arg}. */
        TIME_SET;

        /** @return the kind for {@code ordinal}. @throws IllegalArgumentException if unknown. */
        public static Kind fromOrdinal(int ordinal) {
            Kind[] values = values();
            if (ordinal < 0 || ordinal >= values.length) {
                throw new IllegalArgumentException("unknown command kind ordinal " + ordinal);
            }
            return values[ordinal];
        }
    }

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any argument is null or {@code arg} is negative.
     */
    public CommandAction {
        if (kind == null || from == null || to == null) {
            throw new IllegalArgumentException("kind, from and to must not be null");
        }
        if (arg < 0) {
            throw new IllegalArgumentException("command argument must not be negative: " + arg);
        }
    }

    /** A single-block command at {@code pos}. */
    public static CommandAction at(Kind kind, NBlockPos pos, int arg) {
        return new CommandAction(kind, pos, pos, arg);
    }

    /** @return the number of blocks in the affected volume (1 for a single-block command). */
    public long volume() {
        long dx = Math.abs((long) to.x() - from.x()) + 1;
        long dy = Math.abs((long) to.y() - from.y()) + 1;
        long dz = Math.abs((long) to.z() - from.z()) + 1;
        return dx * dy * dz;
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeFrame(TypeTags.COMMAND_ACTION, ENCODING_VERSION);
        w.writeU16(kind.ordinal());
        from.encode(w);
        to.encode(w);
        w.writeU32(Integer.toUnsignedLong(arg));
    }

    /**
     * Full-frame decode (tag + version + body).
     *
     * @param r the reader positioned at the type tag.
     * @return the decoded action.
     * @throws IllegalStateException if the next tag is not {@code COMMAND_ACTION}.
     */
    public static CommandAction decode(CanonicalReader r) {
        r.expectFrame(TypeTags.COMMAND_ACTION, "COMMAND_ACTION", ENCODING_VERSION);
        return decodeBody(r);
    }

    /** Body decode; the caller has already consumed the tag and version. */
    static CommandAction decodeBody(CanonicalReader r) {
        Kind kind = Kind.fromOrdinal(r.readU16());
        NBlockPos from = NBlockPos.decode(r);
        NBlockPos to = NBlockPos.decode(r);
        return new CommandAction(kind, from, to, r.readU32AsInt());
    }
}
