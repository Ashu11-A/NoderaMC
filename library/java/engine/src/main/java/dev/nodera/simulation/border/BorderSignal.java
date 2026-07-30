package dev.nodera.simulation.border;

import dev.nodera.core.state.NBlockPos;

import java.util.Comparator;

/**
 * A redstone-lane effect that targeted a block OUTSIDE the region's owned bounds (Task 13
 * border/). The engine never mutates halo state — any signal, scheduled effect, or piston
 * motion that would cross the border is collected as a {@code BorderSignal} instead, and the
 * contraption-migration lane (ContraptionMigrator, Task 13 spec) decides what happens next:
 * demote the group to vanilla ({@code CONTRAPTION_CROSSES_VANILLA}) or migrate the whole
 * contraption group to a single primary.
 *
 * <p>Border signals are a deterministic consequence of the batch inputs — every replica
 * collects the identical list in the identical canonical order — but they carry no consensus
 * weight themselves (like {@code ExecutionStats}, they ride the execution result, not the
 * root). The MOTION that was refused is already visible in the root: the piston stays
 * retracted, the wire component simply ends at the border.
 *
 * @param kind   what tried to cross.
 * @param origin the owned-region position the effect originated from (the recompute origin or
 *               the piston base).
 * @param target the out-of-owned-bounds position the effect wanted to touch.
 * @param tick   the local tick at which the crossing was refused.
 * @Thread-context immutable, any thread.
 */
public record BorderSignal(Kind kind, NBlockPos origin, NBlockPos target, long tick) {

    /** What tried to cross the border. */
    public enum Kind {
        /** A wire-network component walk reached the border (dust may connect across it). */
        WIRE,
        /** A piston motion (push line or destination) would have crossed the border. */
        PISTON,
        /** A fluid spread target lies across the border (Task 14 fluid lane). */
        FLUID
    }

    /** Canonical order: tick, kind, target, origin — replica-identical by construction. */
    public static final Comparator<BorderSignal> CANONICAL_ORDER = Comparator
            .comparingLong(BorderSignal::tick)
            .thenComparing(s -> s.kind().ordinal())
            .thenComparing(BorderSignal::target)
            .thenComparing(BorderSignal::origin);

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if any reference is null.
     */
    public BorderSignal {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (origin == null) {
            throw new IllegalArgumentException("origin must not be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
    }
}
