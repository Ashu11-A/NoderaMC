package dev.nodera.endpoint.paper.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The {@code EntityTickEvent.Pre} approximation: a validated item's vanilla projection is
 * <b>pinned</b> rather than suppressed (server task 5 deliverable 6, [L-69]).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The NeoForge mod cancels a validated item's vanilla tick outright
 * ({@code EntityCaptureBridge.onTickPre}), so the canonical item is the only thing that moves and
 * the only thing that can be picked up. Bukkit has no cancellable entity tick and a plugin may not
 * add a mixin, so on an endpoint the vanilla item keeps ticking. What the endpoint can do is take
 * away every consequence of that tick, every tick:
 *
 * <ul>
 *   <li><b>lifetime</b> — {@code ticksLived} is reset, so the 6000-tick despawn is never reached;</li>
 *   <li><b>motion</b> — velocity is zeroed, and a projection that has none the less wandered past
 *       {@link #DRIFT_TOLERANCE_BLOCKS} of its anchor is put back;</li>
 *   <li><b>credit</b> — no vanilla actor may consume it (pickup delay held at
 *       {@link #NEVER_PICK_UP}, mob pickup denied), so the ONLY thing that can credit a validated
 *       item is {@link #claim}, and {@link #claim} answers once.</li>
 * </ul>
 *
 * <h2>The drift bound is a property of the pin, not a hope</h2>
 *
 * <p>Zeroing velocity every tick does not stop motion: the platform integrates the entity BEFORE the
 * region task runs, so each tick can still move the item by whatever velocity it had accumulated —
 * for a falling item, up to about 0.4 blocks. Re-applying position every tick instead would fight
 * the physics engine and show to a player as jitter. So the pin uses a <b>hysteresis band</b>: below
 * {@link #DRIFT_TOLERANCE_BLOCKS} nothing is written back at all, and at or above it the projection
 * is re-anchored in one move. The observable bound is therefore
 * {@link #MAX_OBSERVED_DRIFT_BLOCKS} = the band plus one tick of unrestrained motion, and that is
 * the number the live stage asserts. "The item is still there" is not an assertion: a projection
 * that despawned and was respawned by the lane satisfies it.
 *
 * <h2>Exactly-once belongs to the lane</h2>
 *
 * <p>{@link #claim} is the single credit path and it is idempotent by construction: the first caller
 * for an item id takes it, every later caller is refused, and the projection is unpinned and removed
 * only after the lane has accepted. Two region threads racing the same item, a player and a hopper
 * arriving on the same tick, or a duplicate {@code EntityPickupItemEvent} therefore cannot double
 * credit — not because the events are ordered, but because the claim is.
 *
 * <p>Deliberately free of Bukkit types: everything the pin needs of an item is
 * {@link Projection}, and everything it needs of the platform's scheduler is {@link RegionTicker}.
 * That is what lets {@link ProjectionPinnerTest} run a simulated hour inside {@code ./gradlew check}
 * with no server anywhere. {@code BukkitProjections} is the (thin) adapter.
 *
 * @Thread-context every method runs on the thread that owns the projection's region — on Folia the
 *                 region thread the {@link RegionTicker} dispatched to, on Paper the main thread.
 *                 The registry is a plain {@link LinkedHashMap} for exactly that reason; a
 *                 concurrent map here would hide a threading bug rather than fix one.
 */
public final class ProjectionPinner implements AutoCloseable {

    /**
     * Vanilla despawns an item at this age. The pin resets {@code ticksLived} well inside it; the
     * constant is here so the test can assert the margin rather than assume it.
     */
    public static final int VANILLA_DESPAWN_TICKS = 6000;

    /**
     * The pickup delay that means "never" to vanilla.
     *
     * <p>{@code Short.MAX_VALUE}, not {@code Integer.MAX_VALUE}: the field is a short on the wire
     * and Bukkit clamps, so asking for a larger number silently gets this one — and a constant that
     * does not survive the round trip is a constant nobody can reason about.
     */
    public static final int NEVER_PICK_UP = Short.MAX_VALUE;

    /**
     * How far a projection may wander before it is put back, in blocks. Below this the pin writes
     * nothing but velocity, which is what keeps it from showing as jitter on a client.
     */
    public static final double DRIFT_TOLERANCE_BLOCKS = 0.5;

    /**
     * The bound a live stage may assert: the band, plus one tick of motion at roughly an item's
     * terminal fall speed (~0.4 blocks/tick), rounded up. A pinned projection observed further than
     * this from its anchor is a defect in the pin, not physics.
     */
    public static final double MAX_OBSERVED_DRIFT_BLOCKS = 1.0;

    /**
     * How close a taker must be for the pin to route a pickup intent, in blocks. Vanilla inflates an
     * item's box by one block to decide the same thing.
     */
    public static final double PICKUP_RADIUS_BLOCKS = 1.0;

    /** Everything the pin needs of a projected item. */
    public interface Projection {

        /** The item's stable entity id — the key everything else here is stated in terms of. */
        UUID id();

        /** Whether the item is still in the world (a removed projection is unpinned, not written). */
        boolean valid();

        /** Current position as {@code {x, y, z}}. */
        double[] position();

        /** Reset the age so the vanilla despawn timer is never reached. */
        void resetLifetime();

        /** Zero the velocity. */
        void zeroVelocity();

        /** Hold the pickup delay at {@link #NEVER_PICK_UP} and deny mob pickup. */
        void denyVanillaPickup();

        /** Put the projection back on its anchor. */
        void moveTo(double x, double y, double z);

        /** The nearest player within {@code radius} blocks, if any. */
        Optional<UUID> nearestTakerWithin(double radius);

        /** Remove the projection from the world (the lane has taken the item). */
        void remove();
    }

    /**
     * Runs a body every tick on the thread that owns a projection's region.
     *
     * <p><b>The region's own scheduler, never the global one.</b> On Folia the global scheduler runs
     * on no region's thread, so a pin dispatched through it would touch an entity off-thread — which
     * on Folia is not a race that sometimes bites but an {@code ensureTickThread} failure that halts
     * the scheduler and stops the server.
     */
    public interface RegionTicker {
        /** @return a handle that stops the repeating task. */
        AutoCloseable everyTick(Projection projection, Runnable body);
    }

    /**
     * The validated lane's credit path.
     *
     * @return whether the lane accepted the intent. Only then is the projection removed — a lane
     *         that refuses (mid-commit, not primary, region revoked) leaves the item where it is and
     *         the taker may try again.
     */
    @FunctionalInterface
    public interface PickupRouter {
        boolean route(UUID item, UUID taker);
    }

    private record Pinned(Projection projection, double[] anchor, AutoCloseable task) { }

    private final RegionTicker ticker;
    private final PickupRouter router;
    private final Consumer<String> log;
    private final Map<UUID, Pinned> pinned = new LinkedHashMap<>();
    private final java.util.Set<UUID> claimed = new java.util.LinkedHashSet<>();

    private long reanchors;
    private long refusedDuplicateClaims;
    private long refusedByLane;
    private long faults;
    private double maxDrift;

    public ProjectionPinner(RegionTicker ticker, PickupRouter router, Consumer<String> log) {
        if (ticker == null || router == null || log == null) {
            throw new IllegalArgumentException("ticker, router and log must not be null");
        }
        this.ticker = ticker;
        this.router = router;
        this.log = log;
    }

    /**
     * Start pinning a validated item. Idempotent — a second call for the same id re-anchors nothing
     * and schedules nothing.
     *
     * @param projection the item the lane has taken responsibility for.
     */
    public void pin(Projection projection) {
        if (projection == null) {
            throw new IllegalArgumentException("projection must not be null");
        }
        UUID id = projection.id();
        if (pinned.containsKey(id) || !projection.valid()) {
            return;
        }
        try {
            double[] anchor = projection.position().clone();
            apply(projection);
            AutoCloseable task = ticker.everyTick(projection, () -> onTick(id));
            pinned.put(id, new Pinned(projection, anchor, task));
        } catch (RuntimeException | LinkageError degraded) {
            // Same ladder as the tick: an item that cannot be pinned is one item left to vanilla,
            // never a region and never a server. Registering it and letting the tick discover the
            // fault would leave a scheduled task attached to an entity we could not write once.
            fault("could not pin " + id, degraded);
        }
    }

    /**
     * Stop pinning, leaving the projection to vanilla.
     *
     * <p>Called when the region is revoked, when the world unloads, and when the lane has credited.
     * The pickup delay is NOT restored: an item that stops being validated stops being ours, and
     * writing a value back into an entity we no longer own is the kind of parting shot that shows up
     * as an unexplained item behaviour hours later.
     */
    public void unpin(UUID id) {
        Pinned gone = pinned.remove(id);
        if (gone == null) {
            return;
        }
        try {
            gone.task().close();
        } catch (Exception failed) {
            // A scheduler that will not cancel a task is not a reason to leave the registry
            // inconsistent — the task itself no-ops once the id is gone.
            fault("could not cancel the pin task for " + id, failed);
        }
    }

    /**
     * The one credit path for a validated item, and the reason "exactly once" is a property of this
     * lane rather than of the projection.
     *
     * @param item  the projection's id.
     * @param taker the player asking for it.
     * @return whether THIS call credited. A second call for the same item always answers
     *         {@code false}, whichever thread it arrives on.
     */
    public boolean claim(UUID item, UUID taker) {
        if (item == null || taker == null) {
            throw new IllegalArgumentException("item and taker must not be null");
        }
        Pinned held = pinned.get(item);
        if (held == null) {
            return false;
        }
        if (!claimed.add(item)) {
            refusedDuplicateClaims++;
            return false;
        }
        boolean accepted;
        try {
            accepted = router.route(item, taker);
        } catch (RuntimeException | LinkageError laneFailed) {
            // The failure ladder is fixed: drop this item's claim, keep the region, keep the server.
            claimed.remove(item);
            fault("the validated lane threw routing a pickup of " + item, laneFailed);
            return false;
        }
        if (!accepted) {
            // Not a credit and not an error: the lane may take it on a later tick, so the claim is
            // released rather than burned. Burning it would make one refusal permanent.
            claimed.remove(item);
            refusedByLane++;
            return false;
        }
        unpin(item);
        held.projection().remove();
        return true;
    }

    /** Whether {@code item} is currently pinned by this endpoint. */
    public boolean isPinned(UUID item) {
        return pinned.containsKey(item);
    }

    /**
     * The furthest any projection has been observed from its anchor, in blocks.
     *
     * <p>This is the number {@link #MAX_OBSERVED_DRIFT_BLOCKS} bounds, sampled on the region tick
     * before the re-anchor is applied — so it measures what a player could have seen, not what was
     * left behind afterwards.
     */
    public double maxDriftObserved() {
        return maxDrift;
    }

    /** How many projections are pinned right now. */
    public int pinnedCount() {
        return pinned.size();
    }

    /** How many times a projection was put back on its anchor. */
    public long reanchors() {
        return reanchors;
    }

    /** Claims refused because the item was already claimed — the exactly-once counter. */
    public long refusedDuplicateClaims() {
        return refusedDuplicateClaims;
    }

    /** Claims the lane itself declined (mid-commit, not primary, region revoked). */
    public long refusedByLane() {
        return refusedByLane;
    }

    /** Pin failures contained rather than propagated. */
    public long faults() {
        return faults;
    }

    /** Stop pinning everything (plugin disable, world unload). */
    @Override
    public void close() {
        for (UUID id : new ArrayList<>(pinned.keySet())) {
            unpin(id);
        }
        claimed.clear();
    }

    /**
     * One region tick for one projection.
     *
     * <p>Self-catching is mandatory here and the ladder is fixed: <b>drop this projection's pin →
     * log once → keep the region → keep the server</b>. On Folia an uncaught exception on a tick
     * thread halts the scheduler and stops the entire server, so a pin that throws must never reach
     * the platform.
     */
    private void onTick(UUID id) {
        Pinned held = pinned.get(id);
        if (held == null) {
            return;
        }
        try {
            Projection projection = held.projection();
            if (!projection.valid()) {
                unpin(id);
                return;
            }
            apply(projection);
            double drift = distance(projection.position(), held.anchor());
            maxDrift = Math.max(maxDrift, drift);
            if (drift >= DRIFT_TOLERANCE_BLOCKS) {
                projection.moveTo(held.anchor()[0], held.anchor()[1], held.anchor()[2]);
                reanchors++;
            }
            projection.nearestTakerWithin(PICKUP_RADIUS_BLOCKS)
                    .ifPresent(taker -> claim(id, taker));
        } catch (RuntimeException | LinkageError degraded) {
            unpin(id);
            fault("dropped the pin on " + id, degraded);
        }
    }

    private static void apply(Projection projection) {
        projection.resetLifetime();
        projection.zeroVelocity();
        projection.denyVanillaPickup();
    }

    private void fault(String what, Throwable why) {
        faults++;
        log.accept(what + ": " + why);
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * One line an operator can read, and the reason every counter above has a production caller.
     *
     * @return the pin's state and its refusal counts, for the disable log.
     */
    public String summary() {
        return pinnedCount() + " pinned · " + reanchors() + " re-anchored · max drift "
                + String.format(java.util.Locale.ROOT, "%.3f", maxDriftObserved()) + " blocks · "
                + refusedDuplicateClaims() + " duplicate claim(s) refused · "
                + refusedByLane() + " declined by the lane · " + faults() + " contained fault(s)";
    }
}
