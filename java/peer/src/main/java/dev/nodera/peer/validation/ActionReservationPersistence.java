package dev.nodera.peer.validation;

import dev.nodera.core.action.ActionEnvelope;

import java.util.List;

/** Durable replay boundary for authenticated player/server action sequences. */
public interface ActionReservationPersistence {

    /** Atomically retain all actions before the worker reserves their sequence numbers. */
    void reserve(List<ActionEnvelope> actions);

    /** Mark a retained batch committed; idempotent. */
    void commit(List<ActionEnvelope> actions);

    /** Mark a retained batch terminally rejected; sequence numbers remain consumed. */
    void abort(List<ActionEnvelope> actions);

    /** Every retained action, including terminal records, used to restore sequence watermarks. */
    List<ActionEnvelope> retained();

    static ActionReservationPersistence none() {
        return NoOpHolder.INSTANCE;
    }

    final class NoOpHolder {
        private static final ActionReservationPersistence INSTANCE =
                new ActionReservationPersistence() {
                    @Override public void reserve(List<ActionEnvelope> actions) {}
                    @Override public void commit(List<ActionEnvelope> actions) {}
                    @Override public void abort(List<ActionEnvelope> actions) {}
                    @Override public List<ActionEnvelope> retained() { return List.of(); }
                };

        private NoOpHolder() {
        }
    }
}
