package dev.nodera.diagnostics.source;

import dev.nodera.diagnostics.model.RegionOwnership;

/**
 * Supplies this peer's region ownership for the HUD (Task 18).
 *
 * <p>Owned by Task 6 ({@code LeaseManager}). Until then the {@linkplain #stub() stub} returns
 * {@link RegionOwnership#empty()} — the zone geometry is real, the ownership is the
 * {@code UNASSIGNED} placeholder (LIMITATIONS L-31). The surfaces and view-model ship now; only the
 * data fills in when Task 6 lands — no structural change to the HUD.
 *
 * <p>Thread-context: {@link #regions()} is read on the collector sample thread.
 */
public interface RegionOwnershipProvider extends DiagnosticsSource {

    /** @return this peer's region ownership (empty while placeholder). */
    RegionOwnership regions();

    @Override
    default void contribute(SnapshotBuilder b) {
        b.regions(regions());
    }

    /** @return a no-op stub provider (Task 18 placeholder — owned by Task 6). */
    static RegionOwnershipProvider stub() {
        return () -> RegionOwnership.empty();
    }
}
