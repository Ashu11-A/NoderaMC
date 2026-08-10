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
import java.util.concurrent.ConcurrentHashMap;
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
public final class BukkitForeignWrites implements Listener {

    /** Blocks per Nodera region axis: {@code REGION_SIZE_CHUNKS} chunks of 16. */
    private static final int CHUNK_SHIFT = 4;

    /**
     * World key → Nodera dimension, shared with {@link WorldEditBulkWrites}.
     *
     * <p>Static because both adapters ask the same question about the same worlds, and concurrent
     * because on Folia two region threads ask it at once. Every write stores an equal value for an
     * equal key, so a race can only ever recompute one.
     */
    private static final Map<String, DimensionKey> DIMENSIONS = new ConcurrentHashMap<>();

    private final ForeignWriteBridge bridge;
    private final Plugin plugin;

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

    /**
     * How a refusal reaches the rest of the server: as {@link NoderaRegionDeniedEvent}, always.
     *
     * <p>Static, and therefore <b>not</b> a method on the listener, for a reason worth stating: the
     * bridge needs a {@code Denials} to construct and the listener needs the bridge to construct, so
     * making the listener the publisher would be a cycle. It does not need to be one — publishing a
     * denial needs the plugin and nothing else.
     */
    public static ForeignWriteBridge.Denials denials(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
        return denial -> {
            NoderaRegionDeniedEvent event = denialEvent(plugin, denial);
            if (event != null) {
                plugin.getServer().getPluginManager().callEvent(event);
            }
        };
    }

    /**
     * One denial as the event every other plugin on the server receives.
     *
     * <p>Split out of {@link #denials} so the payload deliverable 3 promises — region, location,
     * reason, and the state the world KEEPS — is assertable without a running server.
     *
     * @return the event, or {@code null} when the world it names is no longer loaded. That is the
     *         one case where a denial cannot be located, and it is logged rather than dropped: a
     *         denial nobody can find is still a denial, and silence is what this whole class exists
     *         to avoid.
     */
    static NoderaRegionDeniedEvent denialEvent(Plugin plugin, ForeignWriteBridge.Denial denial) {
        org.bukkit.World world = plugin.getServer().getWorld(
                new org.bukkit.NamespacedKey(denial.region().dimension().namespace(),
                        denial.region().dimension().path()));
        if (world == null) {
            plugin.getLogger().warning("Nodera refused a write in " + denial.region()
                    + " (" + denial.reason() + ") but that world is no longer loaded");
            return null;
        }
        return new NoderaRegionDeniedEvent(
                denial.region().toString(),
                new org.bukkit.Location(world, denial.pos().x(), denial.pos().y(),
                        denial.pos().z()),
                denial.reason(),
                asRegistryKey(denial.certified()));
    }

    // -------------------------------------------------------------------------------------
    // Bukkit → Nodera
    // -------------------------------------------------------------------------------------

    /** The Nodera region a block falls in. */
    private static RegionId regionOf(Block block) {
        return regionOf(block.getWorld(), block.getX(), block.getZ());
    }

    /** The Nodera region a block coordinate in {@code world} falls in. */
    static RegionId regionOf(org.bukkit.World world, int blockX, int blockZ) {
        return RegionId.fromChunk(dimensionOf(world), blockX >> CHUNK_SHIFT, blockZ >> CHUNK_SHIFT);
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
    static DimensionKey dimensionOf(org.bukkit.World world) {
        org.bukkit.NamespacedKey key = world.getKey();
        return DIMENSIONS.computeIfAbsent(key.toString(),
                ignored -> DimensionKey.of(key.getNamespace(), key.getKey()));
    }

    /** A Bukkit block state as the palette's {@code (key, properties)} pair. */
    static VanillaPalette.VanillaBlock stateOf(BlockData data) {
        return stateOf(data.getAsString());
    }

    /**
     * A vanilla block-state string as the palette's {@code (key, properties)} pair.
     *
     * <p>Read from the encoded form — {@code minecraft:redstone_torch[lit=true]} — rather than from
     * the typed {@code BlockData} subinterfaces, because the palette's binding is keyed by vanilla's
     * own property spellings and that string is exactly those. Going through the typed API would
     * mean a {@code switch} over every {@code BlockData} subtype that drifts every Minecraft
     * version, for the same answer.
     *
     * <p>It is also why {@link WorldEditBulkWrites} can share this method: WorldEdit's
     * {@code BlockState#getAsString()} produces the identical spelling, because both are printing
     * the same registry.
     */
    static VanillaPalette.VanillaBlock stateOf(String encoded) {
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

    /**
     * A palette state as something a plugin can read out of the denied event.
     *
     * <p>The namespace is restored here. The palette's own reverse table stores bare paths
     * ({@code stone}), which is right for the consensus tables and wrong for an event another
     * plugin's author reads: {@code Material.matchMaterial} and every registry lookup on the
     * platform expect {@code minecraft:stone}.
     */
    static String asRegistryKey(VanillaPalette.VanillaBlock block) {
        String key = block.key().indexOf(':') >= 0
                ? block.key() : VanillaPalette.NAMESPACE + ":" + block.key();
        if (block.properties().isEmpty()) {
            return key;
        }
        StringBuilder rendered = new StringBuilder(key).append('[');
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
