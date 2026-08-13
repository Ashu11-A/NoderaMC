package dev.nodera.endpoint.paper.entity;

import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The two Bukkit events the projection pin is driven by (server task 5 deliverable 6, [L-69]).
 *
 * <p>Both handlers are three lines of adaptation around {@link ProjectionPinner} and both self-catch,
 * because the failure ladder for this whole task is fixed: <b>drop the entity's capture → log once →
 * keep the region → keep the server</b>. On Folia an uncaught exception on a tick thread halts the
 * scheduler and stops the entire server, so "the handler throws" is not a degraded mode.
 *
 * <p><b>Why the pickup handler exists at all, given the pin already denies vanilla pickup.</b> It is
 * the belt to the pin's braces. {@link ProjectionPinner#NEVER_PICK_UP} stops vanilla crediting a
 * validated item, but a pickup delay is a mutable field on an entity any plugin may write — an item
 * manager, an anti-lag sweeper, a `/give`-adjacent utility — and the moment one zeroes it, vanilla
 * would credit a stack the validated lane still believes it owns. Cancelling here means the vanilla
 * credit cannot happen even then, so exactly-once survives a plugin the endpoint has never heard of.
 *
 * @Thread-context both handlers run on the thread that owns the entity's region: on Folia the region
 *                 thread the event fired on, on Paper the main thread. They therefore share the
 *                 {@link ProjectionPinner}'s own thread contract.
 */
public final class ProjectionListener implements Listener {

    private final ProjectionPinner pinner;
    private final Predicate<Location> delegated;
    private final Consumer<String> log;

    /**
     * @param pinner    the pin these events drive.
     * @param delegated answers "is the Nodera region covering this location delegated to this
     *                  endpoint's validated lane". An item outside a delegated region is not
     *                  validated, is not pinned, and behaves exactly as vanilla — which is why a
     *                  server with no delegation is byte-for-byte a server without this plugin.
     * @param log       the plugin logger's info sink.
     */
    public ProjectionListener(ProjectionPinner pinner, Predicate<Location> delegated,
                              Consumer<String> log) {
        if (pinner == null || delegated == null || log == null) {
            throw new IllegalArgumentException("pinner, delegated and log must not be null");
        }
        this.pinner = pinner;
        this.delegated = delegated;
        this.log = log;
    }

    /**
     * A new item in a delegated region becomes a validated item's projection, and is pinned from the
     * tick it appears.
     *
     * <p>{@code MONITOR} because this observes rather than decides: another plugin cancelling the
     * spawn must win, and pinning an item that was then cancelled would leave a task ticking an
     * entity that never existed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        try {
            Item item = event.getEntity();
            if (!delegated.test(item.getLocation())) {
                return;
            }
            pinner.pin(BukkitProjections.of(item));
        } catch (RuntimeException | LinkageError degraded) {
            log.accept("could not pin a spawned item: " + degraded);
        }
    }

    /**
     * A vanilla pickup of a pinned projection is cancelled, and the intent is handed to the lane.
     *
     * <p>{@code HIGHEST} rather than {@code MONITOR}: this one decides. The cancel is the whole
     * point, and a MONITOR handler may not change the outcome.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        try {
            Item item = event.getItem();
            if (!pinner.isPinned(item.getUniqueId())) {
                return;
            }
            event.setCancelled(true);
            if (event.getEntity() instanceof Player player) {
                pinner.claim(item.getUniqueId(), player.getUniqueId());
            }
        } catch (RuntimeException | LinkageError degraded) {
            log.accept("could not route a pickup of a validated item: " + degraded);
        }
    }
}
