package dev.nodera.coordinator.entity;

import dev.nodera.core.state.PersistedEntityState;

/** Throttles vanilla-authoritative ghost motion while forwarding semantic changes immediately. */
public final class GhostUpdatePolicy {

    public static final int UPDATE_INTERVAL_TICKS = 5;

    private GhostUpdatePolicy() {
    }

    public static boolean shouldEmit(
            PersistedEntityState previous, PersistedEntityState current) {
        if (previous == null || current == null || !previous.id().equals(current.id())) {
            throw new IllegalArgumentException("ghost update states must have one entity id");
        }
        if (previous.equals(current)) {
            return false;
        }
        return !previous.payload().equals(current.payload())
                || Integer.toUnsignedLong(current.ageTicks() - previous.ageTicks())
                >= UPDATE_INTERVAL_TICKS;
    }
}
