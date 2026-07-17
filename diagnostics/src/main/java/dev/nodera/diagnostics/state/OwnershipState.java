package dev.nodera.diagnostics.state;

/**
 * A peer's ownership relationship to one region (Task 18 colour <b>policy</b>).
 *
 * <p>Semantic, Minecraft-free: the renderer ({@code dev.nodera.mod.debug.render.Palette}) maps each
 * value to a {@code ChatFormatting} / {@code BossBarColor} in exactly one place. Distinct from
 * {@link dev.nodera.core.region.RegionReplicaRole} (the wire role) — this is the player-facing state
 * that folds in "this region is not delegated to you at all" ({@link #FOREIGN} / {@link #UNASSIGNED}).
 *
 * <p>Thread-context: immutable enum, any thread.
 */
public enum OwnershipState {
    /** You are the primary of this region (you own &amp; manage it). */
    OWNED,
    /** You are a validator of this region. */
    VALIDATING,
    /** You hold a replica of this region. */
    REPLICA,
    /** The region is delegated to a committee you are not on — outside your control. */
    FOREIGN,
    /** The region has no committee / is not delegated yet. */
    UNASSIGNED
}
