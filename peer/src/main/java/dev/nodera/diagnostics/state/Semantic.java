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
    NEUTRAL,

    // --- torrent-world health (Task 26). Deliberately distinct from the session Health trio:
    //     a torrent world that lost data is RED and a dead one GRAY, while a degraded SESSION
    //     stays YELLOW — sharing the values would silently recolour the Task 18 HUD.
    /** Torrent world fully replicated. */
    WORLD_HEALTHY,
    /** Torrent world lost data / under-replicated (rendered red). */
    WORLD_DEGRADED,
    /** Torrent world unusable: zero seeders past retention (rendered gray). */
    WORLD_DEAD
}
