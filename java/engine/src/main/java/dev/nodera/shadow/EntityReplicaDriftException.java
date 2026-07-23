package dev.nodera.shadow;

import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;

/** Entity-table counterpart of {@link ReplicaDriftException}. */
public final class EntityReplicaDriftException extends RuntimeException {

    private final transient NetworkEntityId id;
    private final transient PersistedEntityState expected;
    private final transient PersistedEntityState actual;

    public EntityReplicaDriftException(
            NetworkEntityId id, PersistedEntityState expected, PersistedEntityState actual) {
        super("entity replica drift for " + id + ": expected " + expected + " but found " + actual);
        this.id = id;
        this.expected = expected;
        this.actual = actual;
    }

    public NetworkEntityId id() {
        return id;
    }

    public PersistedEntityState expected() {
        return expected;
    }

    public PersistedEntityState actual() {
        return actual;
    }
}
