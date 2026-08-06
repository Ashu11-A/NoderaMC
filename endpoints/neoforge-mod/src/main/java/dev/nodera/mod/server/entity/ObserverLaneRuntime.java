package dev.nodera.mod.server.entity;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import dev.nodera.peer.validation.ObserverRefusals;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * The capture bridge's runtime on a node that owns no regions (minecraft L-60).
 *
 * <p>Under field-of-view ownership a dedicated server routinely owns nothing, and it is also the
 * only process that sees the world. The lane used not to open at all in that case, so the bridge
 * kept {@code Runtime.DISABLED} — which answers every method by doing nothing — and the node that
 * could see a mob nobody can validate had no way to say so. Eight dispatched `e2e-mobs.sh` runs read
 * that as "the lane said nothing"; the line that named it was
 * {@code LANE: … (capture=false, runtime=DISABLED)}.
 *
 * <p><b>Why this is not a session.</b> Opening a full {@code LiveEntityLaneSession} instead was
 * tried and measured: the server stalled for over 720 seconds after a nether crossing, because every
 * region-boundary crossing re-plans and each re-plan then opened and closed a RocksDB store for a
 * session holding nothing. This runtime holds no store, no journals and no replicas, so installing
 * and replacing it costs nothing.
 *
 * <p><b>It validates nothing</b>, and as of 2026-08-06 <b>it no longer refuses anything either.</b>
 * {@link #delegated} is always false, so nothing here can become validated state, and every capture
 * entry point is a deliberate no-op. This class was written to do one further thing — announce that
 * a region cannot be validated, which is all a node with no seat is entitled to do — and that is
 * the part that is currently missing.
 *
 * <p>The method that did it, {@code revokeForEntity}, was deleted from
 * {@link EntityCaptureBridge.Runtime} on 2026-08-06 (Plan 11 round 2, issue #210) as a method
 * nothing referenced, which it was: it had no invoker even before the deletion, so no behaviour
 * changed. But it was the sole caller of {@link ObserverRefusals#refuse} and the only producer of
 * {@code RegionRefusal.Reason.NON_DELEGABLE_ENTITY}, so {@link #refusals} is now a field this class
 * stores and never reads, and reason code 1 has no sender anywhere.
 *
 * <p><b>Do not resolve this by deleting the field.</b> Whether the refusal lane is being retired or
 * is waiting for a caller is an open decision — the wire reason, the transport path, the dedupe and
 * the delegability rule table all still exist and are all tested; only the call from the capture
 * bridge is gone. See {@code docs/network/REFACTORING.md} under `RegionDelegabilityGate` and
 * minecraft L-60, which was retired partly on the announcement this class no longer makes.
 *
 * @Thread-context server main thread, like every other bridge runtime.
 */
public final class ObserverLaneRuntime implements EntityCaptureBridge.Runtime {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaEntityLane");

    private final ObserverRefusals refusals;

    public ObserverLaneRuntime(ObserverRefusals refusals) {
        if (refusals == null) {
            throw new IllegalArgumentException("refusals must not be null");
        }
        this.refusals = refusals;
    }

    /** Always false: an observer holds no replica, so it delegates nothing. */
    @Override
    public boolean delegated(RegionId region) {
        return false;
    }

    @Override
    public boolean validatedItem(RegionId region, NetworkEntityId id) {
        return false;
    }

    @Override
    public boolean submitDrop(ServerPlayer player, ItemEntity vanillaDrop) {
        return false;
    }

    @Override
    public boolean submitPickup(ServerPlayer player, RegionId region, NetworkEntityId id) {
        return false;
    }

    /** An observer delegates nothing, so it has no committed presence to move. */
    @Override
    public boolean submitMove(
            ServerPlayer player, RegionId region, dev.nodera.core.action.MovePlayerAction move) {
        return false;
    }

    @Override
    public void externalEntity(
            RegionId region, PersistedEntityState expected, PersistedEntityState replacement) {
        // Nothing to capture into: this node holds no canonical state for the region.
    }

    @Override
    public void transferGhost(
            RegionId source, RegionId target, PersistedEntityState expected,
            PersistedEntityState replacement) {
        // A transfer is a mutation of two canonical tables an observer does not have.
    }

    @Override
    public void pearlTeleported(ServerPlayer player, RegionId destination) {
        // Evidence for the pearl drive belongs to the owning lane, which this is not.
    }

    @Override
    public void tickEnd(MinecraftServer server) {
        // No committer, no metrics, no lag window: there is nothing here that ticks.
    }

}
