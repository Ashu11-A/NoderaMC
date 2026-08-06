package dev.nodera.mod.server.entity;

import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NetworkEntityId;
import dev.nodera.core.state.PersistedEntityState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * The capture bridge's runtime on a node that owns no regions (minecraft L-60).
 *
 * <p>Under field-of-view ownership a dedicated server routinely owns nothing, and it is also the
 * only process that sees the world. The lane used not to open at all in that case, so the bridge
 * kept {@code Runtime.DISABLED} — which answers every method by doing nothing — and a live drive
 * could not tell an observer that had nothing to do from a lane that had never booted. Eight
 * dispatched `e2e-mobs.sh` runs read that as "the lane said nothing"; the line that named it was
 * {@code LANE: … (capture=false, runtime=DISABLED)}.
 *
 * <p><b>Why this is not a session.</b> Opening a full {@code LiveEntityLaneSession} instead was
 * tried and measured: the server stalled for over 720 seconds after a nether crossing, because every
 * region-boundary crossing re-plans and each re-plan then opened and closed a RocksDB store for a
 * session holding nothing. This runtime holds no store, no journals and no replicas, so installing
 * and replacing it costs nothing.
 *
 * <p><b>What it is for now: being nameable.</b> Every method here is a deliberate no-op and
 * {@link #delegated} is always false, so nothing on a seatless node can become validated state.
 * That is behaviourally the same as {@code Runtime.DISABLED} and it is <b>not</b> the same
 * diagnostically: {@code EntityCaptureBridge.captureJoin} prints the runtime's class in its
 * once-per-region {@code LANE: … observed} line, so "no seats fell to this node, and the bridge
 * knows it" and "the lane never activated" are two different log lines instead of one. Five live
 * runs could not tell those apart, which is why the class exists.
 *
 * <p><b>It no longer refuses anything, and that is the decision rather than the gap</b> (issue
 * #236, decided 2026-08-06). This class was written to do one further thing — announce that a
 * region cannot be validated — and the announcement lane was retired on 2026-07-29 in {@code
 * f4ad09e}, ten days before the dead-code sweep that removed its last orphaned method. Refusing a
 * region for an entity this build's engine does not model was a **product defect**, recorded as one
 * in {@code docs/minecraft/Task.10.md}: {@code mobCaptureSpecies} defaults to zombies alone, so the
 * first cow, bat, squid or item frame to walk into a region deleted it from the validated lane, on
 * every node, for the rest of the session — "in a real world every region is revoked within
 * seconds". The replacement is in {@code EntityCaptureBridge.captureJoin}: an unmodelled entity is
 * left to vanilla and the region goes on validating blocks and modelled entities. Nothing is
 * announced because nothing is refused.
 *
 * <p>So {@code ObserverRefusals} is gone, {@code RegionRefusal.Reason.NON_DELEGABLE_ENTITY} (wire
 * code 1) is reserved-with-no-sender in {@code WireEnums}, and no field here is stored unread. See
 * {@code docs/network/REFACTORING.md} under `RegionDelegabilityGate` and minecraft L-60.
 *
 * @Thread-context server main thread, like every other bridge runtime.
 */
public final class ObserverLaneRuntime implements EntityCaptureBridge.Runtime {

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
