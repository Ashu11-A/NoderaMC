package dev.nodera.coordinator;

import dev.nodera.core.state.EntityKind;

import java.util.Collection;

/** Task-12 narrowing of the legacy {@link DelegabilityPolicy.Reason#ENTITY_PRESENT} gate. */
public final class EntityDelegabilityRules {

    private EntityDelegabilityRules() {
    }

    /**
     * @return {@code true} when every tracked entity can remain in the delegated lane: ITEM is
     * always validated; GHOST requires the opt-in {@code mobCapture} stream.
     */
    public static boolean allows(Collection<EntityKind> kinds, boolean mobCapture) {
        if (kinds == null) {
            throw new IllegalArgumentException("kinds must not be null");
        }
        for (EntityKind kind : kinds) {
            if (kind == null || (kind == EntityKind.GHOST && !mobCapture)) {
                return false;
            }
        }
        return true;
    }

    /** Value to feed into {@link DelegabilityPolicy.Inputs#entityPresent()}. */
    public static boolean hasBlockingEntity(Collection<EntityKind> kinds, boolean mobCapture) {
        return !allows(kinds, mobCapture);
    }
}
