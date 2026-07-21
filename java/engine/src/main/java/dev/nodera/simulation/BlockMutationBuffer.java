package dev.nodera.simulation;

import dev.nodera.core.state.BlockMutation;
import dev.nodera.core.state.NBlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects {@link BlockMutation}s during one {@code execute} call (Task 3). Per-position
 * last-write-wins semantics: a second {@link #record} against an already-touched position updates
 * the {@code newStateId} but <b>preserves the {@code expectedPreviousStateId} captured at the first
 * touch</b>. The commit applier (Task 6) CAS-compares that first-touch expectation against the
 * pre-batch world, so it must reflect the state at batch start, not an intermediate mutation.
 *
 * <p>On {@link #sortedMutations()} the buffer emits mutations in {@link NBlockPos}'s canonical
 * {@code (y, z, x)} order (the same order the {@link dev.nodera.core.state.RegionDelta} compact
 * constructor enforces), so the resulting delta is byte-stable regardless of action arrival order.
 *
 * <p>{@code flags} is passed through verbatim; MVP callers pass {@code 0} (the reserved per-
 * mutation metadata bitmask is unused today).
 *
 * @Thread-context thread-confined per {@code execute} call; the buffer is not shared and not
 *                 thread-safe.
 */
public final class BlockMutationBuffer {

    /** Internal per-position accumulator; keyed by exact {@link NBlockPos}. */
    private final Map<NBlockPos, Entry> entries = new HashMap<>();

    /**
     * Record (or update) a mutation at {@code pos}. On the first touch for {@code pos} the
     * {@code expectedPreviousStateId} is fixed; subsequent records for the same {@code pos} replace
     * {@code newStateId} and {@code flags} but leave the expectation alone.
     *
     * @param pos                    the target position; must not be null.
     * @param expectedPreviousStateId the pre-batch state id at {@code pos} (captured at first touch).
     * @param newStateId             the state id to apply.
     * @param flags                  reserved per-mutation bitmask (MVP: {@code 0}).
     * @Thread-context thread-confined per call.
     */
    public void record(NBlockPos pos, int expectedPreviousStateId, int newStateId, int flags) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        Entry e = entries.get(pos);
        if (e == null) {
            entries.put(pos, new Entry(expectedPreviousStateId, newStateId, flags));
        } else {
            e.newStateId = newStateId;
            e.flags = flags;
        }
    }

    /**
     * @return {@code true} if no mutation has been recorded.
     * @Thread-context thread-confined per call.
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * @return the recorded mutations, sorted by {@link NBlockPos#compareTo} ({@code (y, z, x)}).
     *         Unmodifiable; a fresh copy each call.
     * @Thread-context thread-confined per call.
     */
    public List<BlockMutation> sortedMutations() {
        List<BlockMutation> out = new ArrayList<>(entries.size());
        for (Map.Entry<NBlockPos, Entry> e : entries.entrySet()) {
            Entry v = e.getValue();
            out.add(new BlockMutation(e.getKey(), v.expectedPreviousStateId, v.newStateId, v.flags));
        }
        out.sort(BlockMutationBuffer::comparePos);
        return List.copyOf(out);
    }

    private static int comparePos(BlockMutation a, BlockMutation b) {
        return a.pos().compareTo(b.pos());
    }

    /** Mutable accumulator for one position. */
    private static final class Entry {
        final int expectedPreviousStateId;
        int newStateId;
        int flags;

        Entry(int expectedPreviousStateId, int newStateId, int flags) {
            this.expectedPreviousStateId = expectedPreviousStateId;
            this.newStateId = newStateId;
            this.flags = flags;
        }
    }
}
