package dev.nodera.fallback;

/**
 * Which lane an action is executed in (Task 8, Phase 4).
 */
public enum Route {
    /** Executed + validated by the region's committee (Task 7). */
    COMMITTEE,
    /** Executed server-side by the {@link FallbackExecutor} (server is the safety net). */
    SERVER_FALLBACK
}
