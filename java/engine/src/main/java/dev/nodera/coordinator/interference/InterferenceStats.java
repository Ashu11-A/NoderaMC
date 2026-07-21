package dev.nodera.coordinator.interference;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.region.RegionId;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rolling foreign-write rates per region per {@link MutationSource} (Task 11). Time is the
 * injected server tick — {@link #advanceTick()} moves the window; there is no wall clock. The
 * headline number, {@link #ratePerWindow(RegionId)}, counts events in the last
 * {@link NoderaConstants#INTERFERENCE_RATE_WINDOW_TICKS} ticks and is what
 * {@code DelegabilityPolicy} compares against {@link NoderaConstants#INTERFERENCE_REVOKE_RATE}.
 *
 * @Thread-context server main thread only.
 */
public final class InterferenceStats {

    private final int windowTicks;
    private long nowTick;
    /** Per region: (tick, count) buckets inside the current window, oldest first. */
    private final Map<RegionId, Deque<long[]>> windows = new LinkedHashMap<>();
    /** Per region per source: lifetime totals (for the diagnostics command). */
    private final Map<RegionId, EnumMap<MutationSource, Long>> totals = new HashMap<>();

    public InterferenceStats() {
        this(NoderaConstants.INTERFERENCE_RATE_WINDOW_TICKS);
    }

    /** @param windowTicks the rolling-window length in ticks (must be positive). */
    public InterferenceStats(int windowTicks) {
        if (windowTicks <= 0) {
            throw new IllegalArgumentException("windowTicks must be positive");
        }
        this.windowTicks = windowTicks;
    }

    /** Record one foreign-write observation for {@code region} at the current tick. */
    public void record(RegionId region, MutationSource source) {
        Deque<long[]> window = windows.computeIfAbsent(region, r -> new ArrayDeque<>());
        long[] newest = window.peekLast();
        if (newest != null && newest[0] == nowTick) {
            newest[1]++;
        } else {
            window.addLast(new long[] {nowTick, 1});
        }
        totals.computeIfAbsent(region, r -> new EnumMap<>(MutationSource.class))
                .merge(source, 1L, Long::sum);
    }

    /** Advance the window by one server tick, expiring buckets older than the window. */
    public void advanceTick() {
        nowTick++;
        long oldestKept = nowTick - windowTicks + 1;
        for (Deque<long[]> window : windows.values()) {
            while (!window.isEmpty() && window.peekFirst()[0] < oldestKept) {
                window.removeFirst();
            }
        }
    }

    /** Foreign writes observed in {@code region} within the last window. */
    public long ratePerWindow(RegionId region) {
        Deque<long[]> window = windows.get(region);
        if (window == null) {
            return 0;
        }
        long sum = 0;
        for (long[] bucket : window) {
            sum += bucket[1];
        }
        return sum;
    }

    /** Lifetime foreign-write count for {@code region} from {@code source}. */
    public long totalFor(RegionId region, MutationSource source) {
        EnumMap<MutationSource, Long> perSource = totals.get(region);
        return perSource == null ? 0 : perSource.getOrDefault(source, 0L);
    }

    /** The configured window length in ticks. */
    public int windowTicks() {
        return windowTicks;
    }
}
