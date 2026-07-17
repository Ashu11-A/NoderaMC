package dev.nodera.diagnostics.state;

/**
 * The umbrella of every colour-bearing concept surfaced by the HUD (Task 18).
 *
 * <p>This is the single domain the renderer's {@code Palette} must be total over — acceptance #1:
 * no {@code Semantic} may be unmapped (a unit test in {@code neoforge-mod} asserts totality).
 * {@link dev.nodera.diagnostics.view.Cell} carries the {@code Semantic} of its text so colour is
 * decided by policy, not improvised at each render site.
 *
 * <p>Thread-context: immutable enum, any thread.
 */
public enum Semantic {
    /** Data sent (TX). */
    TX,
    /** Data received (RX). */
    RX,
    /** The session gateway. */
    GATEWAY,
    /** This peer itself. */
    SELF,
    /** You own &amp; manage the region. */
    OWNED,
    /** You validate the region. */
    VALIDATING,
    /** You hold a replica. */
    REPLICA,
    /** Outside your control. */
    FOREIGN,
    /** No committee / not delegated. */
    UNASSIGNED,
    /** Session health: healthy. */
    HEALTHY,
    /** Session health: degraded. */
    DEGRADED,
    /** Session health: critical. */
    CRITICAL,
    /** A panel/section heading. */
    HEADING,
    /** A unit label / secondary detail. */
    SECONDARY,
    /** Neutral default text. */
    NEUTRAL
}
