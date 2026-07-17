package dev.nodera.diagnostics.model;

import dev.nodera.diagnostics.state.Health;

/**
 * Session health at a sample (Task 18).
 *
 * @param state  the coarse {@link Health}.
 * @param reason short human reason for non-{@link Health#HEALTHY} states ("" if healthy).
 * @Thread-context immutable record, any thread.
 */
public record HealthStat(Health state, String reason) {

    /** A healthy default with no reason. */
    public static HealthStat healthy() {
        return new HealthStat(Health.HEALTHY, "");
    }
}
