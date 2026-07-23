package dev.nodera.peer.validation;

import dev.nodera.core.state.InventoryCredit;

import java.util.List;

/** Durable outbox for certified inventory credits awaiting vanilla-player reconciliation. */
public interface InventoryCreditPersistence {

    /** Retain one idempotent actor/entity credit before attempting its vanilla side effect. */
    void retain(InventoryCredit credit);

    /** All retained credits; delivered entries remain for restart-time attachment reconciliation. */
    List<InventoryCredit> retained();
}
