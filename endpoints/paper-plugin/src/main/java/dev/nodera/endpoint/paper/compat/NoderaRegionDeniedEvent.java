package dev.nodera.endpoint.paper.compat;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Nodera undid a write, and said so (server task 8 deliverable 3).
 *
 * <p>This event exists because of one rule in the compatibility contract: <b>a revert is never
 * silent</b>. A protection plugin's allow that Nodera overrides, a WorldEdit operation that raced a
 * committed delta and was put back — every one of them fires this, carrying the region, the block,
 * the reason, and the state the world now holds. The difference between a bug report and a log line
 * is exactly this event, and the difference between a bug report and a <i>mystery</i> is that other
 * plugins can subscribe to it.
 *
 * <p><b>Not cancellable, and that is deliberate.</b> By the time this fires the certified state has
 * already won; offering a plugin the chance to veto it would be offering it the chance to fork the
 * world from its committee. Plugins that want to disagree with Nodera's authority should not have
 * the region delegated in the first place.
 *
 * <p>Subscribing costs a plugin nothing and requires no Nodera dependency beyond this class:
 *
 * <pre>{@code
 * @EventHandler
 * public void onDenied(NoderaRegionDeniedEvent event) {
 *     getLogger().info(event.getReason() + " at " + event.getLocation());
 * }
 * }</pre>
 *
 * @Thread-context fired synchronously on the thread that owned the write — the main thread on
 *                 Paper, the region's thread on Folia. A listener that hops threads is its own
 *                 business; this event never does.
 */
public final class NoderaRegionDeniedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String region;
    private final Location location;
    private final ForeignWriteBridge.DenialReason reason;
    private final String certifiedState;

    /**
     * @param region         the Nodera region, in its {@code RegionId#toString} form.
     * @param location       where the write was undone.
     * @param reason         why.
     * @param certifiedState the state the block now holds, as a registry key.
     */
    public NoderaRegionDeniedEvent(String region, Location location,
                                   ForeignWriteBridge.DenialReason reason, String certifiedState) {
        super(false);
        if (region == null || location == null || reason == null || certifiedState == null) {
            throw new IllegalArgumentException(
                    "a denied event must name region, location, reason and state");
        }
        this.region = region;
        this.location = location;
        this.reason = reason;
        this.certifiedState = certifiedState;
    }

    /** The Nodera region whose certified state won. */
    public String getRegion() {
        return region;
    }

    /** Where the write was undone. */
    public Location getLocation() {
        return location;
    }

    /** Why the write was undone. */
    public ForeignWriteBridge.DenialReason getReason() {
        return reason;
    }

    /** The state the block holds now — the certified one. */
    public String getCertifiedState() {
        return certifiedState;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Required by Bukkit's event system; the registration scan calls it reflectively. */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
