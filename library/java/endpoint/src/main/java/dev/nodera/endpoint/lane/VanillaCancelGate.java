package dev.nodera.endpoint.lane;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionLease;

import java.util.Optional;

/**
 * The single rule that decides whether the validated lane may <b>cancel a vanilla outcome</b>
 * (issues #33 and #44).
 *
 * <p>The contract the live sessions taught us: a local action must be immediate and
 * vanilla-identical; peers are validators <i>after</i> the fact, never a gate in front of the
 * player's own game. Cancelling vanilla is therefore only legal when this node is the region's
 * primary — the commit is synchronous there, so the validated entity materialises in the very tick
 * the vanilla one is cancelled. Everywhere else the submit is fire-and-forget to a remote primary,
 * and "accepted" is not "committed": a stalled remote lane meant a tossed item silently snapped
 * back into the inventory (#44) and a picked-up item vanished entirely (#33).
 *
 * <p>Kept Minecraft-free on purpose so the rule is pinned headlessly rather than only observed in
 * a live two-client session.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class VanillaCancelGate {

    private VanillaCancelGate() {
    }

    /**
     * @param lease this node's lease for the acting region, empty when the region is not delegated
     *              here at all.
     * @param self  this node's id.
     * @return {@code true} only when cancelling the vanilla outcome is safe, i.e. this node is the
     *         region's primary and the commit will be synchronous.
     */
    public static boolean mayCancelVanilla(Optional<RegionLease> lease, NodeId self) {
        if (lease == null || lease.isEmpty() || self == null) {
            return false;
        }
        return self.equals(lease.get().primary());
    }
}
