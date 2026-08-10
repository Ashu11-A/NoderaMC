package dev.nodera.endpoint.paper.compat;

import dev.nodera.coordinator.interference.MutationSource;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.rules.VanillaPalette;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * The Bukkit half of the foreign-write feed: real events on a real server, funnelled into
 * {@link ForeignWriteBridge}.
 *
 * <h2>Two priorities, because PC-2 says two priorities</h2>
 *
 * <p>Every event this class cares about is handled twice:
 *
 * <ul>
 *   <li>{@link EventPriority#HIGH}, {@code ignoreCancelled = true} — <b>the gate</b>. Every
 *       protection, claims and permission plugin runs at {@code LOWEST}…{@code NORMAL} and has
 *       already decided; if it said no, this never runs. Nodera refuses only what the certified
 *       state forbids, and always fires {@link NoderaRegionDeniedEvent} when it does.</li>
 *   <li>{@link EventPriority#MONITOR}, {@code ignoreCancelled = true} — <b>the observer</b>. Never
 *       cancels, never mutates. This is what certifies, and it runs beside CoreProtect and every
 *       other logger at the same priority, seeing exactly the change they see.</li>
 * </ul>
 *
 * <p>The gate deliberately runs at {@code HIGH} and not {@code HIGHEST}: {@code HIGHEST} is where
 * plugins that want the last word live, and taking it from them would be Nodera deciding it
 * outranks the operator's own configuration.
 *
 * <h2>Self-catching is not optional</h2>
 *
 * <p>An exception out of a listener is a {@code Could not pass event} spam loop on Paper, and on
 * Folia an uncaught exception on a region thread <b>halts the scheduler and stops the server</b>
 * (docs/minecraft/folia/06-schedulers.md). So every handler wraps its body: a capture fault degrades
 * certification, never the server. This is the Bukkit reading of the same rule NeoForge's rethrowing
 * event bus taught the mod (issue #39).
 *
 * <h2>What no Bukkit event can see</h2>
 *
 * <p>A plugin calling {@code Block#setType} or {@code World#setBlockData} directly fires <b>no
 * event at all</b>. Such a write is invisible here and there is no supported seam that would make
 * it visible — see {@link ForeignWriteBridge}'s own note, and {@link WorldEditBulkWrites} for the
 * one bulk writer big enough to deserve its own adapter.
 *
 * @Thread-context every handler runs on the thread that owns the block — main on Paper, the
 *                 region's thread on Folia. Nothing here is shared across regions except the
 *                 dimension-key cache, which is written with values that are always equal.
 */
public final class BukkitForeignWrites implements Listener, ForeignWriteBridge.Denials {

    /** Blocks per Nodera region axis: {@code REGION_SIZE_CHUNKS} chunks of 16. */
    private static final int CHUNK_SHIFT = 4;

    private final ForeignWriteBridge bridge;
    private final Plugin plugin;
    private final Map<String, DimensionKey> dimensions = new HashMap<>();

    public BukkitForeignWrites(ForeignWriteBridge bridge, Plugin plugin) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge must not be null");
        }
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
        this.bridge = bridge;
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------------------
    // The gate — may cancel, and always says why
    // -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void gatePlace(BlockPlaceEvent event) {
        guarded("BlockPlaceEvent gate", () -> {
            if (!bridge.allows(regionOf(event.getBlock()), posOf(event.getBlock()),
                    stateOf(event.getBlockReplacedState().getBlockData()),
                    stateOf(event.getBlockPlaced().getBlockData()))) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void gateBreak(BlockBreakEvent event) {
        guarded("BlockBreakEvent gate", () -> {
            if (!bridge.allows(regionOf(event.getBlock()), posOf(event.getBlock()),
                    stateOf(event.getBlock().getBlockData()), air())) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void gateEntityChange(EntityChangeBlockEvent event) {
        guarded("EntityChangeBlockEvent gate", () -> {
            if (!bridge.allows(regionOf(event.getBlock()), posOf(event.getBlock()),
                    stateOf(event.getBlock().getBlockData()), stateOf(event.getBlockData()))) {
                event.setCancelled(true);
            }
        });
    }

    // -------------------------------------------------------------------------------------
    // The observer — never cancels, never mutates, and is what certifies
    // -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordPlace(BlockPlaceEvent event) {
        guarded("BlockPlaceEvent record", () -> bridge.record(
                regionOf(event.getBlock()), posOf(event.getBlock()),
                stateOf(event.getBlockReplacedState().getBlockData()),
                stateOf(event.getBlockPlaced().getBlockData()),
                MutationSource.UNKNOWN));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordBreak(BlockBreakEvent event) {
        guarded("BlockBreakEvent record", () -> bridge.record(
                regionOf(event.getBlock()), posOf(event.getBlock()),
                stateOf(event.getBlock().getBlockData()), air(),
                MutationSource.UNKNOWN));
    }

    /**
     * A falling block landing, an enderman moving one, a wither eating one — vanilla mechanics that
     * a plugin never touched but that still write into a delegated region. They are recorded with
     * {@link MutationSource#ENTITY}, which is what the interference rate policy reads to tell
     * "an entity did this" apart from "a plugin did this".
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordEntityChange(EntityChangeBlockEvent event) {
        guarded("EntityChangeBlockEvent record", () -> bridge.record(
                regionOf(event.getBlock()), posOf(event.getBlock()),
                stateOf(event.getBlock().getBlockData()), stateOf(event.getBlockData()),
                MutationSource.ENTITY));
    }

    // -------------------------------------------------------------------------------------
    // Denials
    // -------------------------------------------------------------------------------------

    @Override
    public void deny(ForeignWriteBridge.Denial denial) {
        org.bukkit.World world = plugin.getServer().getWorld(
                new org.bukkit.NamespacedKey(denial.region().dimension().namespace(),
                        denial.region().dimension().path()));
        if (world == null) {
            // The only way here is a region in a world that has been unloaded between the write and
            // the denial. Say so; a denial that cannot be located is still a denial.
            plugin.getLogger().warning("Nodera refused a write in " + denial.region()
                    + " (" + denial.reason() + ") but that world is no longer loaded");
            return;
        }
        plugin.getServer().getPluginManager().callEvent(new NoderaRegionDeniedEvent(
                denial.region().toString(),
                new org.bukkit.Location(world, denial.pos().x(), denial.pos().y(),
                        denial.pos().z()),
                denial.reason(),
                asRegistryKey(denial.certified())));
    }

    // -------------------------------------------------------------------------------------
    // Bukkit → Nodera
    // -------------------------------------------------------------------------------------

    /** The Nodera region a block falls in. */
    private RegionId regionOf(Block block) {
        return RegionId.fromChunk(dimensionOf(block), block.getX() >> CHUNK_SHIFT,
                block.getZ() >> CHUNK_SHIFT);
    }

    private static NBlockPos posOf(Block block) {
        return new NBlockPos(block.getX(), block.getY(), block.getZ());
    }

    /**
     * The world's own namespaced key is the dimension key.
     *
     * <p>Not {@code World.Environment}: an operator with two overworlds would give both the same
     * dimension and collapse two regions into one. The key is what Minecraft itself identifies a
     * level by, and it is what the mod reads from {@code ServerLevel#dimension()}.
     */
    private DimensionKey dimensionOf(Block block) {
        org.bukkit.NamespacedKey key = block.getWorld().getKey();
        return dimensions.computeIfAbsent(key.toString(),
                ignored -> DimensionKey.of(key.getNamespace(), key.getKey()));
    }

    /**
     * A Bukkit block state as the palette's {@code (key, properties)} pair.
     *
     * <p>Read from {@link BlockData#getAsString()} rather than from the typed {@code BlockData}
     * subinterfaces, because the palette's binding is keyed by vanilla's own property spellings and
     * that string is exactly those: {@code minecraft:redstone_torch[lit=true]}. Going through the
     * typed API would mean a {@code switch} over every {@code BlockData} subtype that drifts every
     * Minecraft version, for the same answer.
     */
    static VanillaPalette.VanillaBlock stateOf(BlockData data) {
        String encoded = data.getAsString();
        int bracket = encoded.indexOf('[');
        if (bracket < 0) {
            return VanillaPalette.VanillaBlock.of(encoded);
        }
        String key = encoded.substring(0, bracket);
        String body = encoded.substring(bracket + 1, encoded.lastIndexOf(']'));
        Map<String, String> properties = new HashMap<>();
        for (String pair : body.split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                properties.put(pair.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                        pair.substring(equals + 1).trim().toLowerCase(Locale.ROOT));
            }
        }
        return new VanillaPalette.VanillaBlock(key, properties);
    }

    /** What a broken block becomes. */
    private static VanillaPalette.VanillaBlock air() {
        return VanillaPalette.VanillaBlock.of("minecraft:air");
    }

    /** A palette state as something a plugin can read out of the denied event. */
    static String asRegistryKey(VanillaPalette.VanillaBlock block) {
        if (block.properties().isEmpty()) {
            return block.key();
        }
        StringBuilder rendered = new StringBuilder(block.key()).append('[');
        boolean first = true;
        for (Map.Entry<String, String> property : new java.util.TreeMap<>(block.properties())
                .entrySet()) {
            if (!first) {
                rendered.append(',');
            }
            rendered.append(property.getKey()).append('=').append(property.getValue());
            first = false;
        }
        return rendered.append(']').toString();
    }

    /** Run a handler body so that no fault in it can reach the event bus. */
    private void guarded(String what, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException | LinkageError fault) {
            plugin.getLogger().log(Level.WARNING,
                    "[NoderaEndpoint] foreign-write " + what + " failed; certification is degraded"
                            + " for this write and the server is unaffected", fault);
        }
    }
}
