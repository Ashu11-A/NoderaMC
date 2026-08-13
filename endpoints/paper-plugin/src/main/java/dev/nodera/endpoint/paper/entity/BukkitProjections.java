package dev.nodera.endpoint.paper.entity;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.UUID;

/**
 * The Bukkit half of the projection pin (server task 5 deliverable 6, [L-69]): everything in this
 * file is an adapter, and every decision it adapts lives in {@link ProjectionPinner}.
 *
 * <p>The split is not tidiness. The pin's whole claim is a bound on drift and a credit that happens
 * once, and both have to be provable in {@code ./gradlew check} on a machine with no Minecraft on
 * it. So the arithmetic is Minecraft-free and this file is the thin, boring layer that reads and
 * writes an {@code Item}.
 *
 * @Thread-context every method here must run on the thread that owns the item's region. The
 *                 {@link ProjectionPinner.RegionTicker} built by {@link #regionTicker} is what
 *                 guarantees that on Folia.
 */
public final class BukkitProjections {

    private BukkitProjections() {
    }

    /**
     * Wrap a live {@code Item} as something the pin can hold.
     *
     * @param item the projected item entity.
     */
    public static ProjectionPinner.Projection of(Item item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        return new ItemProjection(item);
    }

    /**
     * The repeating pin task, dispatched on the thread that owns each projection's region.
     *
     * <p><b>{@code RegionScheduler}, on both platforms.</b> Paper implements the Folia scheduler API
     * and runs it on its single main thread, so there is one call site rather than a platform
     * branch — and, more to the point, no reference to {@code BukkitScheduler} anywhere, which is
     * the rule that keeps a plugin from quietly doing region work on a thread Folia will kill it
     * for. The global scheduler would compile and would be wrong: it runs on no region's thread, so
     * every entity access from it is an {@code ensureTickThread} failure, and on Folia that halts
     * the scheduler and stops the server rather than throwing into a handler.
     *
     * @param plugin the owning plugin, which is how the platform cancels the task on disable.
     */
    public static ProjectionPinner.RegionTicker regionTicker(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
        return (projection, body) -> {
            // The cast is safe by construction — the only Projection this class's ticker is ever
            // handed is the one this class made — and it is unavoidable: a Location needs a World,
            // and a World is exactly the kind of Bukkit type ProjectionPinner.Projection exists to
            // keep out of the pin. A wrong one would be caught by ProjectionPinner.pin's own catch.
            Location where = ((ItemProjection) projection).item().getLocation();
            io.papermc.paper.threadedregions.scheduler.ScheduledTask task =
                    plugin.getServer().getRegionScheduler()
                            .runAtFixedRate(plugin, where, ignored -> body.run(), 1L, 1L);
            return task::cancel;
        };
    }

    /** One projected item, read and written through the Bukkit API and nothing else. */
    private record ItemProjection(Item item) implements ProjectionPinner.Projection {

        @Override
        public UUID id() {
            return item.getUniqueId();
        }

        @Override
        public boolean valid() {
            return item.isValid();
        }

        @Override
        public double[] position() {
            Location at = item.getLocation();
            return new double[] {at.getX(), at.getY(), at.getZ()};
        }

        @Override
        public void resetLifetime() {
            // setTicksLived refuses 0 — the field is "how long has this lived", and nothing has
            // lived for no ticks. 1 is the youngest an item can be told it is.
            item.setTicksLived(1);
        }

        @Override
        public void zeroVelocity() {
            item.setVelocity(new Vector(0, 0, 0));
        }

        @Override
        public void denyVanillaPickup() {
            item.setPickupDelay(ProjectionPinner.NEVER_PICK_UP);
            item.setCanMobPickup(false);
        }

        @Override
        public void moveTo(double x, double y, double z) {
            Location anchor = item.getLocation();
            anchor.setX(x);
            anchor.setY(y);
            anchor.setZ(z);
            // A synchronous teleport is legal here and only here: the anchor is at most
            // ProjectionPinner.DRIFT_TOLERANCE_BLOCKS away, so it is inside the same Folia region as
            // the item, and this call already runs on that region's thread. A move that could leave
            // the region would need teleportAsync — the pin has no such move by construction.
            item.teleport(anchor);
        }

        @Override
        public Optional<UUID> nearestTakerWithin(double radius) {
            double nearest = Double.MAX_VALUE;
            UUID taker = null;
            for (Entity candidate : item.getNearbyEntities(radius, radius, radius)) {
                if (!(candidate instanceof Player player) || !player.isValid()) {
                    continue;
                }
                double distance = player.getLocation().distanceSquared(item.getLocation());
                if (distance < nearest) {
                    nearest = distance;
                    taker = player.getUniqueId();
                }
            }
            return Optional.ofNullable(taker);
        }

        @Override
        public void remove() {
            item.remove();
        }
    }
}
