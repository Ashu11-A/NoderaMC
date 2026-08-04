package dev.nodera.mod.debug;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionId;
import dev.nodera.diagnostics.classify.ZoneClassifier;
import dev.nodera.diagnostics.model.RegionOwnership;
import dev.nodera.diagnostics.state.OwnershipState;
import dev.nodera.endpoint.lane.ObserverOwnership;
import dev.nodera.endpoint.world.PlayerNodeRegistry;
import dev.nodera.mod.common.NoderaPeerService;
import net.minecraft.server.level.ServerPlayer;

/**
 * The zone word for <b>this</b> player, rather than for the machine drawing it.
 *
 * <h2>The bug this exists to end</h2>
 *
 * <p>Every boss bar in a session is produced on the hosting JVM: {@code DiagnosticsService} takes
 * <b>one</b> telemetry snapshot per second and pushes it to every online player, and the renderer
 * classified each player's own coordinates against that one snapshot's region set. But a snapshot's
 * region set is the host node's own seats — it is not a function of the player being drawn.
 *
 * <p>So the answer was right for exactly one person. A joiner standing on ground the plan had given
 * to the <i>joiner's</i> node was classified against the <i>host's</i> seats, which do not contain
 * it and are not empty, and {@code ZoneClassifier} therefore returned {@code FOREIGN} — permanently,
 * because nothing about standing still ever changes the host's seats. Teleport the two players
 * together and both bars read {@code OWNED}, not because two nodes disagreed about a seat but
 * because one node's answer was being printed on two screens.
 *
 * <p>The plan is the only thing that knows every node's seats, and the host already computes and
 * keeps it ({@link ObserverOwnership}). This asks it the per-player question.
 *
 * @Thread-context server thread (it reads player positions and the session registry).
 */
public final class PlayerZones {

    private PlayerZones() {
    }

    /**
     * Which node this player's seats belong to.
     *
     * <p>Falls back to this process's own node when the player has not announced one. That is not a
     * guess: the hosting player's client never announces — {@code ModNetworking} short-circuits the
     * session payload while hosting — so on a player-hosted world the host player's view is filed
     * under the server peer, and this returns the same node the planner used for them.
     *
     * @param player the player.
     * @return their node, or {@code null} when this process has no peer identity at all.
     * @Thread-context server thread.
     */
    public static NodeId nodeOf(ServerPlayer player) {
        PlayerNodeRegistry.PlayerNode announced = PlayerNodeRegistry.nodeOf(player.getUUID());
        if (announced != null) {
            return announced.nodeId();
        }
        NoderaPeerService.HostContext host = NoderaPeerService.get().hostContext();
        return host == null ? null : host.identity().nodeId();
    }

    /**
     * The ownership state of the region {@code player} is standing in, from that player's point of
     * view.
     *
     * @param player   the player whose bar is being drawn.
     * @param fallback this node's own ownership, used only when no plan has been published — a
     *                 dedicated server before its first plan, or a build with the validated lane
     *                 switched off. Preserves the previous behaviour exactly where there is nothing
     *                 better to say.
     * @return the state to render.
     * @Thread-context server thread.
     */
    public static OwnershipState stateOf(ServerPlayer player, RegionOwnership fallback) {
        int blockX = player.blockPosition().getX();
        int blockZ = player.blockPosition().getZ();
        if (!ObserverOwnership.hasPlan()) {
            return ZoneClassifier.classify(Dimensions.of(player), blockX, blockZ, fallback);
        }
        RegionId region = ZoneClassifier.regionAt(Dimensions.of(player), blockX, blockZ);
        return ObserverOwnership.stateFor(region, nodeOf(player));
    }
}
