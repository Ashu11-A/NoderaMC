package dev.nodera.mod.server.entity;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.simulation.entity.PlayerRules;

import java.util.Optional;

/**
 * Whether the committee already holds the presence a movement step would move (L-12 / L-11).
 *
 * <p>{@link dev.nodera.simulation.entity.MovementRules} validates a {@code MovePlayerAction}
 * against the actor's committed {@link dev.nodera.core.state.EntityKind#PLAYER} entity: with no
 * such entity the step is rejected {@code ENTITY_NOT_FOUND} on every honest replica. Registering
 * that entity is the player-root lane's job (L-11) and must not be improvised here — the engine
 * routes pickups into the validated root inventory the moment a player entity exists, while the
 * mod mirrors only the {@code InventoryCredit} stopgap back into the vanilla inventory, so an
 * improvised empty registration would make picked-up items vanish from real inventories.
 *
 * <p>So the capture path asks first. Kept Minecraft-free, like {@link VanillaCancelGate}, because
 * a gate only observable in a running game is a gate nobody can regression-test.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class PlayerLanePresence {

    private PlayerLanePresence() {
    }

    /**
     * @param snapshot the region's committed snapshot, or empty if this node holds none.
     * @param actor    the moving player's node id.
     * @return true when a signed movement step for {@code actor} can be validated at all.
     */
    public static boolean registered(Optional<RegionSnapshot> snapshot, NodeId actor) {
        if (snapshot == null || actor == null) {
            return false;
        }
        return snapshot
                .map(s -> PlayerRules.findPlayer(s.entities(), actor) != null)
                .orElse(false);
    }
}
