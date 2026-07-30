package dev.nodera.mod.debug;

import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.classify.ZoneClassifier;
import dev.nodera.diagnostics.model.TelemetrySnapshot;
import dev.nodera.diagnostics.state.OwnershipState;
import dev.nodera.mod.debug.render.ActionBarNotifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Region-change edge detector → zone alerts (Task 18 surface: zone alerts).
 *
 * <p>Driven from {@code PlayerTickEvent.Post}: tracks each player's last {@link RegionId} and, on
 * the edge where it changes, fires the right coloured alert — RED
 * {@code ⚠ Entering unmanaged zone} for {@link OwnershipState#FOREIGN}/{@code UNASSIGNED}, GREEN
 * {@code ✔ Entered your zone} for {@code OWNED}/{@code VALIDATING}. Debounced to edges only; never
 * per-tick. The first sighting seeds the tracker without alerting (no join-spam).
 *
 * <p>Thread-context: the server main thread ({@code PlayerTickEvent.Post}).
 */
final class ZoneWatcher {

    private final DiagnosticsService service;
    private final ConcurrentMap<UUID, RegionId> lastRegion = new ConcurrentHashMap<>();

    ZoneWatcher(DiagnosticsService service) {
        this.service = service;
    }

    void onPlayerTick(ServerPlayer player) {
        if (player.level().isClientSide()) {
            return;
        }
        if (!service.pref(player.getUUID()).alerts()) {
            return;
        }
        TelemetrySnapshot snap = service.latest();
        if (snap == null) {
            return;
        }
        var dim = Dimensions.of(player);
        int bx = player.blockPosition().getX();
        int bz = player.blockPosition().getZ();
        RegionId region = ZoneClassifier.regionAt(dim, bx, bz);
        RegionId prev = lastRegion.put(player.getUUID(), region);
        if (prev == null || prev.equals(region)) {
            return; // first sighting seeds silently; same region is not an edge
        }
        OwnershipState state = ZoneClassifier.classify(dim, bx, bz, snap.regions());
        String coord = region.regionX() + "," + region.regionZ();
        if (state == OwnershipState.OWNED || state == OwnershipState.VALIDATING) {
            ActionBarNotifier.alertOwned(player, coord);
        } else {
            ActionBarNotifier.alertForeign(player, coord, null);
        }
    }

    /** Forget a player on logout. */
    void remove(UUID id) {
        lastRegion.remove(id);
    }
}
