package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The 24-hour, network-coordinated retention-before-drop countdown (Task 22; retires L-38).
 *
 * <h2>The rule, and the surprise it prevents</h2>
 *
 * <p>A world whose seeders drop to zero is not dropped immediately — that would make a host reboot
 * look like data loss. Instead a 24-hour countdown starts, gossipped network-wide and surfaced by
 * the tracker (Task 20) so the multiplayer UI shows it (Task 26). If a seeder reappears, the
 * countdown cancels. Only at expiry is the world dropped from the directory (gray in the UI). Its
 * certified history can still be re-imported via an invitation (Task 20) if a holder returns
 * out-of-band.
 *
 * <h2>Coordinated, not per-peer</h2>
 *
 * <p>The peer that observes zero seeders proposes a deadline as an explicit wall-clock timestamp;
 * every peer that hears it adopts the <b>earliest</b> announced deadline for that world. One agreed
 * number, no per-peer clock math, so the whole network counts down in lockstep.
 *
 * <h2>Wall-clock is correct here</h2>
 *
 * <p>A zero-seeder world's committed tick cannot advance (nobody holds the data to commit), and the
 * countdown lives outside consensus state — never in a root or certificate. This is the same
 * engine/operational boundary as the Task 25 metrics: operational time is fine outside the hashed
 * path; the deterministic engine never sees it.
 *
 * <p>Thread-context: thread-safe; all methods synchronise on the policy.
 */
public final class RetentionPolicy {

    /** Default countdown: 24 hours (spec rule "end"). */
    public static final long DEFAULT_COUNTDOWN_MILLIS = 24L * 60 * 60 * 1000;

    /** A world's retention state. */
    public enum State {
        /** Seeders present; the world is alive. */
        MONITORED,
        /** Zero seeders; the countdown is running. */
        COUNTDOWN,
        /** The countdown expired; the world is dropped from the directory. */
        DROPPED
    }

    /** The retention state of one world. */
    public record WorldRetention(Bytes world, State state, long deadlineEpochMillis) {
        /**
         * @return {@code true} if a countdown is running (the UI shows the clock).
         */
        public boolean countingDown() {
            return state == State.COUNTDOWN && deadlineEpochMillis > 0;
        }
    }

    private final long countdownMillis;
    private final Map<Bytes, Long> deadlines = new HashMap<>();

    /** Policy with the default 24 h countdown. */
    public RetentionPolicy() {
        this(DEFAULT_COUNTDOWN_MILLIS);
    }

    /**
     * @param countdownMillis how long a zero-seeder world counts down before drop; must be positive.
     * @throws IllegalArgumentException if {@code countdownMillis} is not positive.
     * @Thread-context any thread (construction only).
     */
    public RetentionPolicy(long countdownMillis) {
        if (countdownMillis <= 0) {
            throw new IllegalArgumentException("countdownMillis must be positive: " + countdownMillis);
        }
        this.countdownMillis = countdownMillis;
    }

    /**
     * Observe a world's seeder count at a point in time.
     *
     * @param world       the genesis hash.
     * @param seederCount peers currently holding any of the world's content.
     * @param nowMillis   the observation time.
     * @return the world's retention state after the observation.
     * @throws IllegalArgumentException if {@code world} is null or {@code seederCount} is negative.
     * @Thread-context any thread.
     */
    public synchronized WorldRetention observe(Bytes world, int seederCount, long nowMillis) {
        Objects.requireNonNull(world, "world");
        if (seederCount < 0) {
            throw new IllegalArgumentException("seederCount must be non-negative: " + seederCount);
        }
        Long deadline = deadlines.get(world);
        if (seederCount > 0) {
            // A seeder reappeared: cancel any running countdown.
            deadlines.remove(world);
            return new WorldRetention(world, State.MONITORED, 0L);
        }
        if (deadline == null) {
            // First observation of zero seeders: start the countdown.
            long d = nowMillis + countdownMillis;
            deadlines.put(world, d);
            return new WorldRetention(world, State.COUNTDOWN, d);
        }
        if (nowMillis >= deadline) {
            return new WorldRetention(world, State.DROPPED, deadline);
        }
        return new WorldRetention(world, State.COUNTDOWN, deadline);
    }

    /**
     * Adopt another peer's proposed deadline, taking the earliest — the coordination step that
     * makes the whole network agree on one number.
     *
     * @param world     the genesis hash.
     * @param proposed  the peer's announced deadline (epoch millis).
     * @param nowMillis the observation time.
     * @return the world's retention state after adopting.
     * @throws IllegalArgumentException if {@code world} is null or {@code proposed} is not positive.
     * @Thread-context any thread.
     */
    public synchronized WorldRetention proposeDeadline(Bytes world, long proposed, long nowMillis) {
        Objects.requireNonNull(world, "world");
        if (proposed <= 0) {
            throw new IllegalArgumentException("proposed deadline must be positive: " + proposed);
        }
        Long existing = deadlines.get(world);
        // Adopt the earliest: a peer that observed zero seeders earlier started an earlier clock.
        if (existing == null || proposed < existing) {
            deadlines.put(world, proposed);
            existing = proposed;
        }
        if (nowMillis >= existing) {
            return new WorldRetention(world, State.DROPPED, existing);
        }
        return new WorldRetention(world, State.COUNTDOWN, existing);
    }

    /**
     * @param world the genesis hash.
     * @return the current retention state (without advancing it), or MONITORED if unseen.
     * @Thread-context any thread.
     */
    public synchronized WorldRetention state(Bytes world) {
        Long deadline = deadlines.get(world);
        if (deadline == null) {
            return new WorldRetention(world, State.MONITORED, 0L);
        }
        return new WorldRetention(world, State.COUNTDOWN, deadline);
    }

    /**
     * Drop a world from the directory manually (e.g. an admin command) — clears its countdown.
     *
     * @param world the genesis hash.
     * @Thread-context any thread.
     */
    public synchronized void forget(Bytes world) {
        deadlines.remove(world);
    }

    /** @return the configured countdown duration. */
    public long countdownMillis() {
        return countdownMillis;
    }
}
