package dev.nodera.mod.server;

import dev.nodera.endpoint.lane.OpSyncDecision;
import dev.nodera.core.identity.WorldRole;
import dev.nodera.mod.common.NoderaHost;
import dev.nodera.endpoint.world.PlayerNodeRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issue #36 (flaws F3 &amp; F4): the bridge from the P2P {@link WorldRole} permission model to
 * vanilla Minecraft operator status on the <b>live</b> connection.
 *
 * <p>Before this, {@code grantHostOperator} blanket-opped <i>every</i> online player at share time
 * (F3), and the role model gated only mesh admission — nothing op/deop/kicked on the vanilla
 * connection (F4). This bridge closes both:
 * <ul>
 *   <li>It ops a player only when their <b>key-checked</b> role is OPERATOR/OWNER, and BANNED ⇒
 *       disconnect (the vanilla-connection gap the mesh join gate never covered).</li>
 *   <li>It only ever deops players <b>it</b> opped — server-configured ops set out-of-band are never
 *       touched.</li>
 * </ul>
 *
 * <p>The role lookup is anchored to the announced key (authenticated by the Phase 2 challenge), not a
 * spoofable NodeId, so an attacker cannot op themselves by announcing a granted player's NodeId. The
 * decision logic lives in the pure {@link OpSyncDecision} so the full matrix is unit-testable without
 * Minecraft.
 *
 * @Thread-context call on the server thread (op/deop/disconnect mutate the player list).
 */
public final class OperatorBridge {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaOpBridge");

    private static final OperatorBridge INSTANCE = new OperatorBridge();

    /** The players this bridge opped — the only ones it will ever deop. */
    private final Set<UUID> ourOps = ConcurrentHashMap.newKeySet();

    private OperatorBridge() {
    }

    /** @return the process-wide bridge. */
    public static OperatorBridge get() {
        return INSTANCE;
    }

    /**
     * Reconcile a player's vanilla op status with their key-checked role in the hosted world's
     * permission set. Called on every verified announce and on demand after a grant.
     *
     * @param server the hosting server.
     * @param player the connected player.
     */
    public void syncPlayer(MinecraftServer server, ServerPlayer player) {
        var perms = NoderaHost.hostedPermissions();
        if (perms == null) {
            return;
        }
        // The AUTHORITY identity, not the session one. A session key is minted fresh on every join
        // and is therefore a key no world has ever issued anything to; asking the permission set
        // about it answers MEMBER for everybody, which is how a world's own author got de-opped
        // from their own world. Where no delegation was presented the two are the same value, so
        // this is the previous behaviour plus the case it was getting wrong.
        PlayerNodeRegistry.PlayerNode node = PlayerNodeRegistry.nodeOf(player.getUUID());
        WorldRole role = node == null ? null
                : perms.roleOf(node.authorityNodeId(), node.authorityPublicKey());
        apply(server, player, OpSyncDecision.decide(role, ourOps.contains(player.getUUID()),
                isThisMachinesOwner(server, player)));
    }

    /**
     * Op the integrated-server owner as the world author. The integrated host never announces a peer
     * node (the client hosting path short-circuits), so the author is opped by owner-identity, gated
     * by the caller's {@code localWorkerIsAuthor} check — not by a registry role.
     */
    public void opAuthor(MinecraftServer server, ServerPlayer owner) {
        apply(server, owner, OpSyncDecision.Action.OP);
    }

    /** Drop any op the bridge granted this player (logout). */
    public void onLogout(MinecraftServer server, ServerPlayer player) {
        if (!ourOps.contains(player.getUUID())) {
            return;
        }
        if (isThisMachinesOwner(server, player)) {
            // Their op is not ours to take back, but the bookkeeping is ours to forget: leaving the
            // uuid in `ourOps` would make a later sync think this bridge granted something it did
            // not, and the whole point of that set is that we only ever undo our own decisions.
            ourOps.remove(player.getUUID());
            return;
        }
        apply(server, player, OpSyncDecision.Action.DEOP);
    }

    /** @return whether this bridge currently ops the given player (test/diagnostic seam). */
    public boolean opped(UUID player) {
        return ourOps.contains(player);
    }

    /**
     * Whether this player owns the integrated server they are playing on — the floor
     * {@link OpSyncDecision#decide(WorldRole, boolean, boolean)} refuses to go under, and the reason
     * it does is written up there.
     */
    private static boolean isThisMachinesOwner(MinecraftServer server, ServerPlayer player) {
        return !server.isDedicatedServer() && server.isSingleplayerOwner(player.getGameProfile());
    }

    private void apply(MinecraftServer server, ServerPlayer player, OpSyncDecision.Action decision) {
        try {
            PlayerList list = server.getPlayerList();
            switch (decision) {
                case OP -> {
                    if (!list.isOp(player.getGameProfile())) {
                        list.op(player.getGameProfile());
                    }
                    ourOps.add(player.getUUID());
                    LOG.info("Nodera: opped {} (role authority)", player.getGameProfile().getName());
                }
                case DEOP -> {
                    if (list.isOp(player.getGameProfile())) {
                        list.deop(player.getGameProfile());
                    }
                    ourOps.remove(player.getUUID());
                    LOG.info("Nodera: de-opped {} (role revoked)", player.getGameProfile().getName());
                }
                case DISCONNECT -> {
                    ourOps.remove(player.getUUID());
                    player.connection.disconnect(Component.literal("Banned from this Nodera world"));
                    LOG.info("Nodera: disconnected banned player {}",
                            player.getGameProfile().getName());
                }
                case NONE -> {
                    // nothing to do
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Nodera: op bridge action {} failed: {}", decision, e.toString());
        }
    }

    /** Forget the bridge's op bookkeeping (server stop / new world). */
    public void reset() {
        ourOps.clear();
    }
}
