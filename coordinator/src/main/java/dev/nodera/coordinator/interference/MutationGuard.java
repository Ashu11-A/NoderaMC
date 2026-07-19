package dev.nodera.coordinator.interference;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Predicate;

/**
 * The single write choke point for delegated chunks (Task 11 Hole A). Every
 * {@code setBlockState}-equivalent reaching a delegated region is classified here:
 *
 * <ul>
 *   <li><b>not delegated</b> → {@link Verdict#PASS} — the vanilla lane is untouched;</li>
 *   <li><b>applier context</b> → {@link Verdict#PASS} — {@code WorldMutationApplier} is the one
 *       legal writer; it wraps its apply pass in {@link #applierScope(Runnable)};</li>
 *   <li><b>mode STRICT</b> → {@link Verdict#BLOCK} — cancel the write, count it (debug/CI runs);</li>
 *   <li><b>mode CONVERT</b> (default) → {@link Verdict#CONVERT} — let the write land and record it
 *       into the {@link InterferenceBuffer}; the {@link InterferenceCommitter} folds it into the
 *       version chain as a certified external delta at tick end.</li>
 * </ul>
 *
 * <p>CONVERT is the default because cancelling an arbitrary vanilla write mid-mechanic (a piston
 * that already committed its move) corrupts vanilla invariants Nodera does not control; STRICT
 * exists for debugging and CI determinism runs.
 *
 * <p>The applier scope uses a {@code ThreadLocal} depth counter (the Task 11 fallback — the repo
 * pins Java 21 bytecode, where {@code ScopedValue} is still preview). Source markers are a stack so
 * nested vanilla phases (entity tick scheduling a neighbor update) attribute to the innermost phase.
 *
 * @Thread-context server main thread only; the thread-locals exist so a misuse from another thread
 *                 fails closed (no applier scope ⇒ foreign write) rather than corrupting state.
 */
public final class MutationGuard {

    /** Guard operating mode ({@code interference.mode}). */
    public enum Mode {
        /** Block every foreign write (debug / CI determinism runs). */
        STRICT,
        /** Let foreign writes land and convert them into certified external deltas (default). */
        CONVERT
    }

    /** The classification of one write. */
    public enum Verdict {
        /** Legal write — vanilla lane or the applier itself. */
        PASS,
        /** Foreign write cancelled (STRICT). */
        BLOCK,
        /** Foreign write allowed through and recorded for tick-end conversion (CONVERT). */
        CONVERT
    }

    private static final ThreadLocal<int[]> APPLIER_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<Deque<MutationSource>> SOURCE_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private final Predicate<RegionId> delegated;
    private final Mode mode;
    private final InterferenceBuffer buffer;
    private final InterferenceStats stats;

    private long applierWrites;
    private long blockedWrites;
    private long convertedWrites;

    /**
     * @param delegated answers "is this region currently delegated" (the coordinator's live
     *                  assignment view).
     * @param mode      STRICT or CONVERT.
     * @param buffer    receives CONVERT-mode recordings.
     * @param stats     receives every foreign-write observation (both modes) for rate tracking.
     */
    public MutationGuard(Predicate<RegionId> delegated, Mode mode,
                         InterferenceBuffer buffer, InterferenceStats stats) {
        if (delegated == null) {
            throw new IllegalArgumentException("delegated must not be null");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null");
        }
        this.delegated = delegated;
        this.mode = mode;
        this.buffer = buffer;
        this.stats = stats;
    }

    /** Run {@code body} inside the applier context: its writes classify as PASS. Re-entrant. */
    public void applierScope(Runnable body) {
        int[] depth = APPLIER_DEPTH.get();
        depth[0]++;
        try {
            body.run();
        } finally {
            depth[0]--;
        }
    }

    /** Run {@code body} with {@code source} as the innermost vanilla-phase marker. */
    public void withSource(MutationSource source, Runnable body) {
        Deque<MutationSource> stack = SOURCE_STACK.get();
        stack.push(source);
        try {
            body.run();
        } finally {
            stack.pop();
        }
    }

    /**
     * Classify one write. In CONVERT mode a {@link Verdict#CONVERT} means the caller lets the
     * write proceed; the guard has already recorded it for the tick-end committer.
     *
     * @param prevStateId the state currently in the world at {@code pos} (before this write).
     * @param newStateId  the state the write wants to leave behind.
     */
    public Verdict verdict(RegionId region, NBlockPos pos, int prevStateId, int newStateId) {
        if (!delegated.test(region)) {
            return Verdict.PASS;
        }
        if (APPLIER_DEPTH.get()[0] > 0) {
            applierWrites++;
            return Verdict.PASS;
        }
        MutationSource source = currentSource();
        stats.record(region, source);
        if (mode == Mode.STRICT) {
            blockedWrites++;
            return Verdict.BLOCK;
        }
        buffer.record(region, new RecordedMutation(pos, prevStateId, newStateId, source));
        convertedWrites++;
        return Verdict.CONVERT;
    }

    private static MutationSource currentSource() {
        MutationSource top = SOURCE_STACK.get().peek();
        return top == null ? MutationSource.UNKNOWN : top;
    }

    /** Writes that classified PASS because they came from inside the applier scope. */
    public long applierWrites() {
        return applierWrites;
    }

    /** Foreign writes cancelled in STRICT mode. */
    public long blockedWrites() {
        return blockedWrites;
    }

    /** Foreign writes recorded for conversion in CONVERT mode. */
    public long convertedWrites() {
        return convertedWrites;
    }

    /** The configured mode. */
    public Mode mode() {
        return mode;
    }
}
