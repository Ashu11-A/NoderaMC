package dev.nodera.core.state;

/**
 * The validated-lane classification of a tracked entity (Task 12a). Determines whether a region's
 * entities keep it delegable (ITEM-only ⇒ delegable now; GHOST mobs ⇒ delegable only under
 * {@code mobCapture}, Task 12b). Engine-owned kinds (TNT, and the reserved PROJECTILE/MINECART)
 * are validated state like ITEM — they do not require {@code mobCapture} because their behaviour
 * originates in the validated root, not a server-mirrored mob. Reserved kinds (PROJECTILE,
 * MINECART) land with their Task 15 increments.
 *
 * @Thread-context immutable enum, any thread.
 */
public enum EntityKind {
    /** A deterministic item entity — fully validated physics (Task 12a). */
    ITEM,
    /** A vanilla-authoritative mob streamed as certified ghost state (Task 12b, opt-in). */
    GHOST,
    /** A primed TNT entity — engine-owned fuse + deterministic blast (Task 15, L-9). */
    TNT
}
