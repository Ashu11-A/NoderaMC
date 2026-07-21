package dev.nodera.coordinator;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionDelta;

/**
 * THE single world writer (Task 0 §4.5, Task 6, Plan §3.8). Applies a {@link RegionDelta} to the
 * {@link MutableWorldView} with a two-pass compare-and-set: pass 1 validates <b>every</b> mutation's
 * {@code expectedPreviousStateId} against the live world; only if all pass does pass 2 write. A
 * single failing CAS aborts the whole delta with <b>zero mutations applied</b> — a delta never
 * partially commits (Invariant 11 in spirit). On abort the caller runs the resync path (fresh
 * snapshot, version bump, pipeline → SNAPSHOT_SYNC).
 *
 * @Thread-context server main thread only (single writer); not thread-safe.
 */
public final class WorldMutationApplier {

    private final MutableWorldView world;

    public WorldMutationApplier(MutableWorldView world) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        this.world = world;
    }

    /**
     * Apply {@code delta} atomically.
     *
     * @return {@link ApplyResult#committed()} with the applied count, or an aborted result naming the
     *         first failing position — in which case the world is untouched.
     */
    public ApplyResult apply(RegionDelta delta) {
        if (delta == null) {
            throw new IllegalArgumentException("delta must not be null");
        }
        RegionId region = delta.region();

        // Pass 1 — validate all CAS guards. No writes yet.
        for (BlockMutation m : delta.blockMutations()) {
            int current = world.getBlock(region, m.pos());
            if (current != m.expectedPreviousStateId()) {
                return ApplyResult.aborted(m.pos(), m.expectedPreviousStateId(), current);
            }
        }
        // Pass 2 — apply all. Every guard already held, so this pass cannot fail on state.
        for (BlockMutation m : delta.blockMutations()) {
            world.setBlock(region, m.pos(), m.newStateId());
        }
        return ApplyResult.committed(delta.blockMutations().size());
    }

    /**
     * The outcome of an {@link #apply(RegionDelta)} call.
     *
     * @param committed {@code true} if the delta was applied in full.
     * @param applied   number of mutations written (0 on abort).
     * @param failedAt  the position whose CAS failed (null on success).
     * @param expected  the {@code expectedPreviousStateId} at {@code failedAt}.
     * @param actual    the state id actually present at {@code failedAt}.
     */
    public record ApplyResult(boolean committed, int applied, NBlockPos failedAt, int expected, int actual) {

        static ApplyResult committed(int applied) {
            return new ApplyResult(true, applied, null, 0, 0);
        }

        static ApplyResult aborted(NBlockPos pos, int expected, int actual) {
            return new ApplyResult(false, 0, pos, expected, actual);
        }
    }
}
