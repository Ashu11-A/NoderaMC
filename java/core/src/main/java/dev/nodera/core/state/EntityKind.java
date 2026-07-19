package dev.nodera.core.state;

/**
 * The validated-lane classification of a tracked entity (Task 12a). Determines whether a region's
 * entities keep it delegable (ITEM-only ⇒ delegable now; GHOST mobs ⇒ delegable only under
 * {@code mobCapture}, Task 12b). Reserved kinds (PROJECTILE, TNT) land with Task 15.
 *
 * @Thread-context immutable enum, any thread.
 */
public enum EntityKind {
    /** A deterministic item entity — fully validated physics (Task 12a). */
    ITEM,
    /** A vanilla-authoritative mob streamed as certified ghost state (Task 12b, opt-in). */
    GHOST
}
