package dev.nodera.diagnostics.source;

import dev.nodera.diagnostics.model.EntityControl;

/**
 * Supplies the entities delegated to this peer for the HUD (Task 18).
 *
 * <p>Owned by Task 12 (entity &amp; mob lane). Until then the {@linkplain #stub() stub} returns
 * {@link EntityControl#empty()} — no controlled entities (LIMITATIONS L-31).
 *
 * <p>Thread-context: {@link #entities()} is read on the collector sample thread.
 */
public interface EntityControlProvider extends DiagnosticsSource {

    /** @return the entities delegated to this peer (empty while placeholder). */
    EntityControl entities();

    @Override
    default void contribute(SnapshotBuilder b) {
        b.entities(entities());
    }

    /** @return a no-op stub provider (Task 18 placeholder — owned by Task 12). */
    static EntityControlProvider stub() {
        return () -> EntityControl.empty();
    }
}
