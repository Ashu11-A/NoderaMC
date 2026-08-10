package dev.nodera.simulation.entity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.state.NBlockPos;

/**
 * The canonical payload of an engine-owned {@link dev.nodera.core.state.EntityKind#MOB} — vitals
 * (L-13) plus the {@linkplain AiMemory AI memory} the Task 11 decision lane carries between
 * decisions (L-7).
 *
 * <p><b>Why the memory is in the root and not beside it.</b> An AI that only reads the current
 * root can decide nothing that outlives one decision: it cannot walk to a destination, hold a
 * target, or follow a path, because next tick it has forgotten why it moved. The moment such a
 * decision is kept anywhere OTHER than the committed state, two replicas holding the same root
 * can legally disagree about what a mob does next, and the disagreement is invisible until their
 * roots part. So the memory is a field of the hashed entity payload: replicas either agree about
 * a mob's intention or they have already failed the ordinary root comparison.
 *
 * <p>That makes this a <b>root-shape change</b>. Every MOB in every region hashes differently
 * from a build that predates it, which is why {@link dev.nodera.simulation.rules.FlatWorldRules#RULES_VERSION}
 * moves with it and why a mixed-version committee refuses rather than diverges. See
 * {@code docs/engine/Task.11.md} §Migration for what an already-shared world has to do.
 *
 * <p><b>Wire form</b> (unframed — this is the opaque {@code payload} of an already-framed
 * {@link dev.nodera.core.state.PersistedEntityState}, exactly like
 * {@link ItemEntityRules#payload(int, int)}):
 * <pre>
 *   [u16 health][u16 maxHealth][u8 goal][u32 goalUntilTick][u32 destX][u32 destY][u32 destZ]
 * </pre>
 * {@code goal} is an explicit Nodera code, never an enum ordinal: an ordinal would make the
 * declaration order of a Java enum part of the consensus contract, so inserting a goal in the
 * middle of the list would silently restate every committed mob (the reason
 * {@code PoseCodes} exists on the mod side).
 *
 * @param health    current health in halves; always in {@code [1, maxHealth]} (death removes the
 *                  entity rather than storing zero).
 * @param maxHealth the species' maximum health in halves.
 * @param ai        what this mob is currently trying to do.
 * @Thread-context immutable, any thread.
 */
public record MobState(int health, int maxHealth, AiMemory ai) {

    /** Goal code: the mob has no live intention; the next decision draws a fresh one. */
    public static final int GOAL_NONE = 0;
    /** Goal code: walk to {@link AiMemory#destination()} until it arrives or the goal expires. */
    public static final int GOAL_WANDER = 1;

    /** Encoded size in bytes — fixed, because every field is fixed-width. */
    static final int ENCODED_SIZE = 2 + 2 + 1 + 4 + 4 + 4 + 4;

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if the vitals are outside {@code [1, maxHealth]}, either
     *         exceeds the u16 the wire form allows, or {@code ai} is null.
     */
    public MobState {
        if (maxHealth <= 0 || maxHealth > 0xFFFF) {
            throw new IllegalArgumentException("maxHealth must be in [1, 65535]: " + maxHealth);
        }
        if (health <= 0 || health > maxHealth) {
            throw new IllegalArgumentException(
                    "health must be in [1, maxHealth]: " + health + "/" + maxHealth);
        }
        if (ai == null) {
            throw new IllegalArgumentException("ai must not be null");
        }
    }

    /**
     * What a mob is trying to do, carried in the committed root between decisions.
     *
     * <p>Deliberately a fixed-width triple rather than, say, a stored path: a path in the root
     * would grow the hashed state by its own length and would have to be re-validated against
     * every block change under it. Holding only the <i>destination</i> keeps the root small and
     * makes the route a pure function of the current world — {@link IntPathfinder} re-derives the
     * next step each decision, so a mob whose corridor is walled up mid-journey simply fails to
     * find a path and gives the goal up.
     *
     * @param goal        one of {@link MobState#GOAL_NONE} / {@link MobState#GOAL_WANDER}.
     * @param untilTick   the region tick the goal expires on, truncated to 32 bits and compared
     *                    unsigned — the same convention
     *                    {@link dev.nodera.core.state.PersistedEntityState#despawnTick()} uses.
     * @param destination where the mob is heading; {@code (0,0,0)} when there is no goal.
     * @Thread-context immutable, any thread.
     */
    public record AiMemory(int goal, int untilTick, NBlockPos destination) {

        /** The memoryless state: no intention, nothing to walk towards. */
        public static final AiMemory IDLE = new AiMemory(GOAL_NONE, 0, new NBlockPos(0, 0, 0));

        /** Compact constructor. */
        public AiMemory {
            if (destination == null) {
                throw new IllegalArgumentException("destination must not be null");
            }
            if (goal != GOAL_NONE && goal != GOAL_WANDER) {
                throw new IllegalArgumentException("unknown goal code: " + goal);
            }
        }

        /** A wander goal towards {@code destination}, live until {@code untilTick}. */
        public static AiMemory wanderTo(NBlockPos destination, long untilTick) {
            return new AiMemory(GOAL_WANDER, (int) untilTick, destination);
        }

        /**
         * @param tick the current region tick.
         * @return whether this memory still names something to do at {@code tick}.
         */
        public boolean isLiveAt(long tick) {
            return goal != GOAL_NONE && tick < Integer.toUnsignedLong(untilTick);
        }
    }

    /** A freshly spawned mob: full-shape vitals and no intention yet. */
    public static MobState fresh(int health, int maxHealth) {
        return new MobState(health, maxHealth, AiMemory.IDLE);
    }

    /** This mob with {@code newHealth}, keeping its intention — damage does not erase memory. */
    public MobState withHealth(int newHealth) {
        return new MobState(newHealth, maxHealth, ai);
    }

    /** This mob with a new intention, keeping its vitals. */
    public MobState withAi(AiMemory newAi) {
        return new MobState(health, maxHealth, newAi);
    }

    /** @return the canonical opaque payload bytes for {@link dev.nodera.core.state.PersistedEntityState}. */
    public Bytes encode() {
        CanonicalWriter w = new CanonicalWriter(ENCODED_SIZE);
        w.writeU16(health);
        w.writeU16(maxHealth);
        w.writeU8(ai.goal());
        w.writeU32(Integer.toUnsignedLong(ai.untilTick()));
        w.writeU32(Integer.toUnsignedLong(ai.destination().x()));
        w.writeU32(Integer.toUnsignedLong(ai.destination().y()));
        w.writeU32(Integer.toUnsignedLong(ai.destination().z()));
        return w.toBytes();
    }

    /**
     * Decode a MOB payload.
     *
     * <p>Refuses rather than repairs: hashed state that does not parse is a divergence the engine
     * must surface loudly, not a value to guess at. A payload written by an older
     * {@code RULES_VERSION} lands here as a malformed one, which is correct — the engine that
     * would execute it has already refused the region's rules version.
     *
     * @throws IllegalStateException if the bytes are not exactly one well-formed payload.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static MobState decode(Bytes payload) {
        if (payload.length() != ENCODED_SIZE) {
            throw new IllegalStateException(
                    "malformed mob payload: " + payload.length() + " bytes, expected " + ENCODED_SIZE);
        }
        CanonicalReader r = new CanonicalReader(payload);
        int health = r.readU16();
        int maxHealth = r.readU16();
        int goal = r.readU8();
        int untilTick = (int) r.readU32();
        int x = (int) r.readU32();
        int y = (int) r.readU32();
        int z = (int) r.readU32();
        if (health == 0 || health > maxHealth || (goal != GOAL_NONE && goal != GOAL_WANDER)) {
            throw new IllegalStateException("malformed mob payload");
        }
        return new MobState(health, maxHealth,
                new AiMemory(goal, untilTick, new NBlockPos(x, y, z)));
    }
}
