package dev.nodera.distribution;

import dev.nodera.core.Bytes;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Password-attempt throttling for encrypted-world joins (Task 23 / L-39): per (world, peer),
 * failed password attempts open an exponentially growing lockout window, so a peer cannot
 * grind an Argon2id-protected password online even though the KDF itself is already
 * cost-bounded offline. A successful join clears the peer's counter; attempts during a lockout
 * are refused without touching the KDF at all (no work amplification).
 *
 * <p>Deterministic and clock-injected: callers pass {@code nowMillis}, so the policy is
 * replay-testable and never reads a wall clock itself.
 *
 * <p>Thread-context: thread-safe (per-key state in a concurrent map, entries synchronized).
 */
public final class JoinAttemptThrottle {

    /** Free attempts before the first lockout. */
    public static final int FREE_ATTEMPTS = 3;

    /** First lockout window; doubles per subsequent failure. */
    public static final long BASE_LOCKOUT_MILLIS = 5_000L;

    /** Lockout growth cap (~1 h) — bounded, never permanent (register rule 1). */
    public static final long MAX_LOCKOUT_MILLIS = 60L * 60 * 1000;

    private record Key(Bytes worldId, Bytes peer) {
    }

    private static final class Entry {
        int failures;
        long lockedUntil;
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    /** @return true when {@code peer} may attempt a password for {@code worldId} right now. */
    public boolean mayAttempt(Bytes worldId, Bytes peer, long nowMillis) {
        Entry entry = entries.get(key(worldId, peer));
        if (entry == null) {
            return true;
        }
        synchronized (entry) {
            return nowMillis >= entry.lockedUntil;
        }
    }

    /**
     * Record a failed password attempt.
     *
     * @return the epoch-millis until which the peer is now locked out ({@code <= nowMillis}
     *         while free attempts remain).
     */
    public long recordFailure(Bytes worldId, Bytes peer, long nowMillis) {
        Entry entry = entries.computeIfAbsent(key(worldId, peer), ignored -> new Entry());
        synchronized (entry) {
            entry.failures++;
            if (entry.failures <= FREE_ATTEMPTS) {
                entry.lockedUntil = nowMillis;
                return entry.lockedUntil;
            }
            int exponent = Math.min(entry.failures - FREE_ATTEMPTS - 1, 20);
            long window = Math.min(BASE_LOCKOUT_MILLIS << exponent, MAX_LOCKOUT_MILLIS);
            entry.lockedUntil = nowMillis + window;
            return entry.lockedUntil;
        }
    }

    /** A successful join clears the peer's history for that world. */
    public void recordSuccess(Bytes worldId, Bytes peer) {
        entries.remove(key(worldId, peer));
    }

    private static Key key(Bytes worldId, Bytes peer) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(peer, "peer");
        return new Key(worldId, peer);
    }
}
