package dev.nodera.endpoint.paper.compat;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.EventHandler;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import dev.nodera.coordinator.interference.MutationSource;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.simulation.rules.VanillaPalette;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The half of the foreign-write feed that Bukkit cannot see: <b>WorldEdit's bulk path</b> (server
 * task 8 deliverable 4, L-65).
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>{@code //set} does not fire {@code BlockPlaceEvent}. It fires no Bukkit block event of any
 * kind. WorldEdit builds an {@code Extent} pipeline that ends at the world and writes through it,
 * which is why a protection plugin that only listens to Bukkit events cannot stop a {@code //set}
 * either — WorldGuard hooks this same pipeline, and so does CoreProtect.
 *
 * <p>So a foreign-write bridge listening only to {@link BukkitForeignWrites}'s events would report
 * "certified" for a player placing one block by hand and observe <b>nothing at all</b> for the
 * million-block operation L-65's exit clause names. That is not a partial implementation; it is a
 * green test that asserts nothing about the case it claims to cover. This class is the answer:
 * Nodera subscribes to WorldEdit's own event bus and wraps the extent, so every block a bulk
 * operation writes passes through the same {@link ForeignWriteBridge} a hand-placed block does.
 *
 * <h2>Innermost, deliberately</h2>
 *
 * <p>The subscription runs at {@link EventHandler.Priority#VERY_EARLY}, and on WorldEdit's bus that
 * makes our extent the <b>innermost</b> one — nearest the world, wrapped by everybody else's. Two
 * consequences, both wanted:
 *
 * <ul>
 *   <li>we observe what actually lands, after every other plugin's masks and rewrites, so a recorded
 *       mutation is the block the world ends up holding rather than the block WorldEdit set out to
 *       write;</li>
 *   <li>our refusal is the last word, taken only after every other plugin has had theirs — the same
 *       ordering {@link BukkitForeignWrites} gets from running its gate at {@code HIGH}.</li>
 * </ul>
 *
 * <p>Only {@link EditSession.Stage#BEFORE_CHANGE} is wrapped. The other two stages sit above
 * history and reordering, where a "write" may still be discarded or replayed, and recording there
 * would certify operations that never reached the world.
 *
 * <h2>What this still does not reach — stated, not discovered</h2>
 *
 * <ol>
 *   <li><b>FAWE's asynchronous queues.</b> FastAsyncWorldEdit fires {@code EditSessionEvent} for
 *       compatibility, so the ordinary path is covered, but its fast queues can write off the main
 *       thread and some of its modes bypass the extent chain entirely. An off-thread edit reaches
 *       this class on the thread that made it, and the bridge's single-writer discipline is then
 *       ours to state rather than to enforce: the per-session counters below are per-extent and are
 *       therefore correct, the bridge's totals are approximate under a concurrent FAWE edit.</li>
 *   <li><b>Anything that writes through NMS directly.</b> Same hole {@link BukkitForeignWrites}
 *       names, and it is not closable from a plugin.</li>
 * </ol>
 *
 * @Thread-context the subscription is installed on the server thread; a session's writes arrive on
 *                 whichever thread WorldEdit runs the operation on. Every body here self-catches,
 *                 because an exception thrown into a plugin's edit is a corrupted edit and on Folia
 *                 an exception on a region thread stops the server.
 */
public final class WorldEditBulkWrites {

    /**
     * The line the live corpus stage reads.
     *
     * <p>A constant rather than a format buried in a call, because a live scenario greps it: three
     * suites have already been broken by a reworded log message, and the fix is that the message and
     * its assertion name the same thing.
     */
    public static final String SESSION_PREFIX = "WorldEdit edit session in ";

    private final ForeignWriteBridge bridge;
    private final Plugin plugin;
    private final Logger log;

    public WorldEditBulkWrites(ForeignWriteBridge bridge, Plugin plugin) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge must not be null");
        }
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
        this.bridge = bridge;
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /**
     * Subscribe to WorldEdit's event bus.
     *
     * <p>Separate from the constructor so the caller can decide, having checked that WorldEdit is
     * actually installed, and so {@link #uninstall} has something symmetric to undo.
     */
    public void install() {
        WorldEdit.getInstance().getEventBus().register(this);
        log.info("WorldEdit is installed: its bulk write path is bridged into Nodera's foreign-write"
                + " certification (server task 8 PC-3). Sessions are reported as \""
                + SESSION_PREFIX + "<world>: N foreign block write(s) observed\".");
    }

    /** Unsubscribe. A bus subscription that outlives the plugin is a classloader leak. */
    public void uninstall() {
        WorldEdit.getInstance().getEventBus().unregister(this);
    }

    /**
     * Wrap the edit session's extent.
     *
     * @param event WorldEdit's own event, fired once per operation per stage.
     */
    @Subscribe(priority = EventHandler.Priority.VERY_EARLY)
    public void onEditSession(EditSessionEvent event) {
        try {
            if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
                return;
            }
            com.sk89q.worldedit.world.World edited = event.getWorld();
            if (edited == null) {
                return; // a clipboard or a schematic: no world, nothing delegated, nothing to certify
            }
            org.bukkit.World world = plugin.getServer().getWorld(edited.getName());
            if (world == null) {
                log.warning("WorldEdit is editing '" + edited.getName() + "', which this server does"
                        + " not have loaded as a Bukkit world — those writes are NOT bridged into"
                        + " Nodera. This is a compatibility gap, not a refusal: the edit proceeds.");
                return;
            }
            event.setExtent(new NoderaCertifyingExtent(event.getExtent(), world));
        } catch (RuntimeException | LinkageError fault) {
            // A fault here must never break a plugin's edit. Nodera loses sight of this session and
            // says so; WorldEdit carries on with the extent chain it already had.
            log.log(Level.WARNING, "could not bridge a WorldEdit session into Nodera's foreign-write"
                    + " certification; that session is unobserved and the edit is unaffected", fault);
        }
    }

    /**
     * The extent that turns one bulk write into one foreign write.
     *
     * <p>Non-static so it can reach the bridge and the log; per-session so its counters describe the
     * operation an operator just ran rather than the server's whole uptime.
     */
    private final class NoderaCertifyingExtent extends AbstractDelegateExtent implements Operation {

        private final org.bukkit.World world;
        private long observed;
        private long refused;
        private long faults;

        NoderaCertifyingExtent(Extent parent, org.bukkit.World world) {
            super(parent);
            this.world = world;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 at, T block)
                throws WorldEditException {
            RegionId region;
            NBlockPos pos;
            VanillaPalette.VanillaBlock before;
            VanillaPalette.VanillaBlock after;
            try {
                region = BukkitForeignWrites.regionOf(world, at.x(), at.z());
                pos = new NBlockPos(at.x(), at.y(), at.z());
                before = BukkitForeignWrites.stateOf(getExtent().getBlock(at).getAsString());
                after = BukkitForeignWrites.stateOf(block.toImmutableState().getAsString());
            } catch (RuntimeException | LinkageError fault) {
                faults++;
                return super.setBlock(at, block);
            }

            try {
                if (!bridge.allows(region, pos, before, after)) {
                    // PC-3 consequence 2: the committed delta wins. The denial has ALREADY fired
                    // NoderaRegionDeniedEvent — WorldEdit reporting "0 blocks changed" with no
                    // explanation is the outcome deliverable 3 exists to prevent.
                    refused++;
                    return false;
                }
            } catch (RuntimeException | LinkageError fault) {
                faults++;
                return super.setBlock(at, block);
            }

            boolean changed = super.setBlock(at, block);
            if (changed) {
                try {
                    // UNKNOWN is the honest source for a plugin: a plugin is not a vanilla phase.
                    bridge.record(region, pos, before, after, MutationSource.UNKNOWN);
                    observed++;
                } catch (RuntimeException | LinkageError fault) {
                    faults++;
                }
            }
            return changed;
        }

        /**
         * End of the session — {@code AbstractDelegateExtent.commit()} is final and calls this.
         *
         * <p>It returns an {@link Operation} rather than logging here, and that distinction cost a
         * live run to find. {@code BatchingExtent} and {@code ChunkBatchingExtent} sit <b>above</b>
         * the {@code BEFORE_CHANGE} wrapper and hold every block of a bulk edit until the commit
         * operation is <i>executed</i>. {@code commit()} only <i>builds</i> that operation, so a
         * line logged here reports the counters as they stood before a single block had reached us:
         * a real {@code //set} of 1,280 blocks logged "0 foreign block write(s) observed" while the
         * world dutifully turned to stone. The queue runs outermost-first, so an operation returned
         * from here runs after those flushes and counts all of them.
         *
         * <p>This is also the only place that knows one WorldEdit operation has finished, which is
         * why the line the live stage reads is emitted from here rather than on a timer the plugin
         * is not allowed to have (PC-4 forbids {@code BukkitScheduler}).
         */
        @Override
        protected Operation commitBefore() {
            return this;
        }

        /** The session is done and every batched write has landed: report it once. */
        @Override
        public Operation resume(com.sk89q.worldedit.function.operation.RunContext run) {
            log.info(SESSION_PREFIX + world.getName() + ": " + observed
                    + " foreign block write(s) observed, " + refused + " refused, "
                    + faults + " uncaptured");
            return null;
        }

        @Override
        public void cancel() {
            // Nothing to undo: this operation only reports.
        }
    }
}
