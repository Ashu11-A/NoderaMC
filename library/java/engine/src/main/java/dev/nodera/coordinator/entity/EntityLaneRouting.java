package dev.nodera.coordinator.entity;

/** Explicit server decision for a vanilla-authoritative ghost crossing a region border. */
public final class EntityLaneRouting {

    public enum GhostBorderRoute {
        REHOME_GHOST,
        MATERIALIZE_VANILLA
    }

    private EntityLaneRouting() {
    }

    public static GhostBorderRoute ghostBorder(boolean sourceDelegated, boolean targetDelegated) {
        if (!sourceDelegated) {
            throw new IllegalArgumentException("ghost source must be delegated");
        }
        return targetDelegated
                ? GhostBorderRoute.REHOME_GHOST : GhostBorderRoute.MATERIALIZE_VANILLA;
    }
}
