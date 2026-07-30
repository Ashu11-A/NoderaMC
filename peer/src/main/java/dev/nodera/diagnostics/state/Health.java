package dev.nodera.diagnostics.state;

/**
 * Coarse session-health state for HUD surfaces (Task 18 colour <b>policy</b>).
 *
 * <p>Mapped to a colour by {@code dev.nodera.mod.debug.render.Palette}. Derived by
 * {@link dev.nodera.diagnostics.DiagnosticsCollector} from the session view (no gateway known →
 * {@link #CRITICAL}; quorum/liveness degraded → {@link #DEGRADED}; else {@link #HEALTHY}).
 *
 * <p>Thread-context: immutable enum, any thread.
 */
public enum Health {
    HEALTHY,
    DEGRADED,
    CRITICAL
}
