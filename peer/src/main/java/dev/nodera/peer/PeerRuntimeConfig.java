package dev.nodera.peer;

import java.time.Duration;
import java.util.Objects;

/**
 * Timing configuration for a {@link PeerRuntime} (Phase 6 P2P continuity).
 *
 * @param keepAliveInterval how often each peer sends a {@code SessionKeepAlive} to every member.
 * @param failureTimeout    how long without a keep-alive (or a transport-down signal) before a
 *                          member is declared failed; must comfortably exceed
 *                          {@code keepAliveInterval} (a few missed beats) to avoid false positives.
 */
public record PeerRuntimeConfig(Duration keepAliveInterval, Duration failureTimeout) {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a duration is null, non-positive, or the failure timeout
     *                                  does not exceed the keep-alive interval.
     */
    public PeerRuntimeConfig {
        Objects.requireNonNull(keepAliveInterval, "keepAliveInterval");
        Objects.requireNonNull(failureTimeout, "failureTimeout");
        if (keepAliveInterval.isZero() || keepAliveInterval.isNegative()) {
            throw new IllegalArgumentException("keepAliveInterval must be positive");
        }
        if (failureTimeout.compareTo(keepAliveInterval) <= 0) {
            throw new IllegalArgumentException("failureTimeout must exceed keepAliveInterval");
        }
    }

    /** Production default: 1 s keep-alive, 5 s failure timeout (five missed beats). */
    public static PeerRuntimeConfig defaults() {
        return new PeerRuntimeConfig(Duration.ofSeconds(1), Duration.ofSeconds(5));
    }
}
