package dev.nodera.protocol.wire;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A boundary enum's explicit wire codes (Task 14 phase 6, retiring {@code Plan.7} R5).
 *
 * <p>Every enum on the wire used to encode {@code ordinal()}. That makes the <em>source order of a
 * Java declaration</em> part of a signed, hashed network contract: inserting a constant in the
 * middle silently renumbers everything after it, and reordering for readability is a network break
 * that nothing detects. One of these enums is not even ours — Minecraft's {@code Pose} — so an
 * upstream refactor could change Nodera's state roots without a line of Nodera changing.
 *
 * <p>A table fixes it in the only way that works: the code is written down, once, and
 * {@link Builder#build()} refuses a table that does not cover every constant. A constant added
 * without a code fails at class initialisation — before anything reaches the wire — instead of
 * being discovered as a mismatch between two peers.
 *
 * <h2>Unknown codes</h2>
 *
 * <p>What to do with a code this build has never seen is a per-plane decision ({@code Plan.7} D7).
 * On the infrastructure plane an unknown code is <b>preserved</b> — see
 * {@link #decodeOrUnknown(int, Enum)} — because a peer that collapses it re-encodes something
 * different from what it received, which is lossy in exactly the way relaying must not be. On the
 * consensus plane an unknown code is <b>rejected</b> ({@link #require(int)}): a validator that
 * quietly ignores a field it does not understand computes a state root nobody else computes.
 *
 * @param <E> the enum type.
 * @Thread-context immutable after construction; any thread.
 */
public final class WireEnum<E extends Enum<E>> {

    private final Class<E> type;
    private final Map<Integer, E> byCode;
    private final Map<E, Integer> codes;

    private WireEnum(Class<E> type, Map<Integer, E> byCode, Map<E, Integer> codes) {
        this.type = type;
        this.byCode = Map.copyOf(byCode);
        this.codes = Map.copyOf(codes);
    }

    /** Start a table for {@code type}. */
    public static <E extends Enum<E>> Builder<E> builder(Class<E> type) {
        return new Builder<>(type);
    }

    /** The wire code of a constant. */
    public int code(E value) {
        Integer code = codes.get(value);
        if (code == null) {
            throw new IllegalStateException(value + " has no wire code in the " + type.getSimpleName()
                    + " table");
        }
        return code;
    }

    /**
     * Resolve a code, or empty when this build does not know it.
     *
     * @param code the code from the wire.
     * @return the constant, or empty.
     * @Thread-context any thread.
     */
    public Optional<E> find(int code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /**
     * Resolve a code, rejecting one this build does not know — the <b>consensus</b> policy.
     *
     * @param code the code from the wire.
     * @return the constant.
     * @throws IllegalStateException if the code is unassigned.
     * @Thread-context any thread.
     */
    public E require(int code) {
        E found = byCode.get(code);
        if (found == null) {
            throw new IllegalStateException("unknown " + type.getSimpleName() + " wire code " + code
                    + "; a consensus value this build cannot interpret must not be guessed at");
        }
        return found;
    }

    /**
     * Resolve a code, falling back to {@code unknown} — the <b>infrastructure</b> policy.
     *
     * <p>The caller is responsible for keeping the raw code alongside the fallback if it intends to
     * re-emit the value; see {@link CodedValue}.
     *
     * @param code    the code from the wire.
     * @param unknown the constant to stand in for an unrecognised code.
     * @return the constant, or {@code unknown}.
     * @Thread-context any thread.
     */
    public E decodeOrUnknown(int code, E unknown) {
        return byCode.getOrDefault(code, unknown);
    }

    /**
     * Resolve a code into a value that remembers the number it came from, so re-encoding reproduces
     * it even when this build could not interpret it.
     *
     * @param code    the code from the wire.
     * @param unknown the constant to stand in for an unrecognised code.
     * @return the resolved value plus its original code.
     * @Thread-context any thread.
     */
    public CodedValue<E> resolve(int code, E unknown) {
        E found = byCode.get(code);
        return new CodedValue<>(found == null ? unknown : found, code, found != null);
    }

    /** Wrap a known constant as a {@link CodedValue}. */
    public CodedValue<E> resolved(E value) {
        return new CodedValue<>(value, code(value), true);
    }

    /** Every assigned code, for snapshot tests. */
    public Map<Integer, E> assignments() {
        return byCode;
    }

    /**
     * An enum value together with the wire code it arrived as.
     *
     * <p>When {@link #recognised} is false the {@link #value} is only a stand-in, and {@link #code}
     * is the truth — re-encode from {@link #code}, never from the stand-in. That is the difference
     * between tolerating an unknown value and destroying it.
     *
     * @param value      the constant, or the caller's stand-in for an unrecognised code.
     * @param code       the code as it appeared on the wire.
     * @param recognised whether {@code value} actually means {@code code}.
     * @param <E>        the enum type.
     */
    public record CodedValue<E extends Enum<E>>(E value, int code, boolean recognised) {}

    /** Builds a {@link WireEnum}, refusing a table that leaves a constant uncoded. */
    public static final class Builder<E extends Enum<E>> {

        private final Class<E> type;
        private final Map<Integer, E> byCode = new LinkedHashMap<>();
        private final Map<E, Integer> codes;

        private Builder(Class<E> type) {
            this.type = type;
            this.codes = new EnumMap<>(type);
        }

        /** Assign {@code code} to {@code value}. */
        public Builder<E> code(int code, E value) {
            if (code <= 0 || code > 0xFFFF) {
                throw new IllegalArgumentException("wire codes are positive u16; got " + code
                        + " for " + value);
            }
            if (byCode.put(code, value) != null) {
                throw new IllegalArgumentException("code " + code + " is assigned twice in the "
                        + type.getSimpleName() + " table");
            }
            if (codes.put(value, code) != null) {
                throw new IllegalArgumentException(value + " is assigned two codes");
            }
            return this;
        }

        /**
         * Finish the table.
         *
         * @return the immutable mapping.
         * @throws IllegalStateException if any constant is uncoded — the check that turns "somebody
         *         forgot" into a build failure rather than a wire mismatch.
         */
        public WireEnum<E> build() {
            EnumSet<E> missing = EnumSet.allOf(type);
            missing.removeAll(codes.keySet());
            if (!missing.isEmpty()) {
                throw new IllegalStateException(type.getSimpleName() + " has constants with no wire "
                        + "code: " + missing + ". Assigning a code is permanent; append a new number "
                        + "rather than reusing one.");
            }
            return new WireEnum<>(type, byCode, codes);
        }
    }
}
