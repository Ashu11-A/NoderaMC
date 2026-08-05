package dev.nodera.mod.server.shadow;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionClaim;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.ViewOwnershipPlanner;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.endpoint.world.PlayerNodeRegistry;
import dev.nodera.mod.common.NoderaHost;
import dev.nodera.mod.common.RegionSeedSpool;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What happens to a player's regions when that player leaves.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Ownership is geometric and re-planning is deterministic, so when a player disconnects every
 * survivor recomputes the same plan and agrees on who owns what. That was treated as the whole
 * answer, and it is only half of one: a plan says who is <b>responsible</b> for a region, not who
 * <b>has</b> it. The departing node was the one simulating those chunks, and dropping it from the
 * plan releases its chunk tickets — so for a region nobody else can see, the last state of that
 * ground goes away with the player who was standing on it. Someone builds for ten minutes in a
 * corner of the world nobody else has visited, logs out, and the ten minutes are gone.
 *
 * <p>So a departure is a handoff, and it happens in the order that makes it one:
 *
 * <ol>
 *   <li>Ask the plan — <b>while the departing player is still in it</b> — which regions they were
 *       primary of, and whether anybody else covers each one.</li>
 *   <li>A region with a successor needs nothing moved: the successor's own view already covers that
 *       ground, so it holds those chunks, and the re-plan is the entire handover.</li>
 *   <li>A region with no successor is extracted from the live level and pushed to this machine's
 *       always-on worker, which seeds it to the network. Only then do the chunks stop being held.
 *       That is what makes "nobody is nearby" mean "the region went to the network" rather than
 *       "the region was dropped".</li>
 * </ol>
 *
 * <p>Best-effort throughout, and deliberately so: a failure here costs one region one seed cycle,
 * while an exception escaping into {@code PlayerLoggedOutEvent} would take the integrated server
 * down with the player mid-logout — NeoForge's event bus rethrows.
 *
 * @Thread-context server thread. The extraction reads live chunks; the push to the worker is
 *                 handed to {@link RegionSeedSpool}, which is asynchronous by construction.
 */
public final class RegionDepartureHandoff {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaHost");

    /**
     * One spool for the process. {@link RegionSeedSpool#companion()} builds an executor, and a
     * departure is not the moment to build one per player.
     */
    private static final RegionSeedSpool SPOOL = RegionSeedSpool.companion();

    private RegionDepartureHandoff() {
    }

    /**
     * Hand over, or seed, every region the departing player was primary of.
     *
     * <p>Call this <b>before</b> {@link PlayerNodeRegistry#forget}: after the player is forgotten
     * the plan no longer contains them and there is nothing left to hand over.
     *
     * @param server  the hosting server.
     * @param leaving the player who is disconnecting; still present in the player list.
     * @return the outcome, for logging and for the tests that assert on it.
     * @Thread-context server thread.
     */
    public static Outcome handOff(MinecraftServer server, ServerPlayer leaving) {
        try {
            return handOffOrThrow(server, leaving);
        } catch (RuntimeException | LinkageError degraded) {
            // Availability, never correctness. The region stays exactly as fetchable as it already
            // was; what is lost is the edits since the last seed, which is the situation this
            // method improves on rather than one it creates.
            LOG.warn("Nodera: region handoff for a departing player failed: {}",
                    degraded.toString());
            return new Outcome(0, 0, 0);
        }
    }

    private static Outcome handOffOrThrow(MinecraftServer server, ServerPlayer leaving) {
        PlayerNodeRegistry.PlayerNode node = PlayerNodeRegistry.nodeOf(leaving.getUUID());
        if (node == null) {
            // A player who never announced a peer node never owned a region, so there is nothing to
            // hand over. Not a fault: it is the ordinary state of a client that left during login.
            return new Outcome(0, 0, 0);
        }
        Map<RegionId, RegionClaim> plan = NoderaHost.currentOwnershipPlan(server);
        Map<RegionId, NodeId> successors = ViewOwnershipPlanner.successorsFor(plan, node.nodeId());
        if (successors.isEmpty()) {
            return new Outcome(0, 0, 0);
        }
        ServerLevel level = leaving.serverLevel();
        int handedOver = 0;
        int seeded = 0;
        int failed = 0;
        Map<RegionId, NodeId> covered = new LinkedHashMap<>();
        for (Map.Entry<RegionId, NodeId> entry : successors.entrySet()) {
            if (entry.getValue() != null) {
                covered.put(entry.getKey(), entry.getValue());
                handedOver++;
                continue;
            }
            if (seedToNetwork(level, entry.getKey())) {
                seeded++;
            } else {
                failed++;
            }
        }
        if (handedOver > 0) {
            LOG.info("Nodera: {} left — {} region(s) pass to a peer that already covers them "
                            + "({}), {} had nobody nearby and were seeded to the network",
                    leaving.getGameProfile().getName(), handedOver, covered.keySet(), seeded);
        } else if (seeded > 0 || failed > 0) {
            LOG.info("Nodera: {} left — nobody covers their {} region(s), so {} were seeded to the "
                            + "network and {} could not be", leaving.getGameProfile().getName(),
                    successors.size(), seeded, failed);
        }
        return new Outcome(handedOver, seeded, failed);
    }

    /**
     * Extract one region from the live level and push it to the worker.
     *
     * <p>The version is the level's game time, which is monotonic within a session and therefore
     * orders correctly against the region's other snapshots from this host. The chunk stamps
     * underneath it do the cross-node ordering, so this only has to be a number that goes up.
     */
    private static boolean seedToNetwork(ServerLevel level, RegionId region) {
        try {
            long tick = level.getGameTime();
            LiveSnapshotExtractor.Extraction extraction = LiveSnapshotExtractor.extract(
                    level, region, new SnapshotVersion(Math.max(tick, 1)), tick);
            if (extraction.missingChunks() == NoderaChunkCount.PER_REGION) {
                // Every column came back empty: the region is not resident, so there is nothing
                // here to seed and whatever is on the network is already the better copy.
                return false;
            }
            return SPOOL.offer(extraction.snapshot());
        } catch (RuntimeException degraded) {
            LOG.debug("Nodera: could not seed departing region {}: {}", region, degraded.toString());
            return false;
        }
    }

    /** How a departure was resolved, region by region. */
    public record Outcome(int handedOver, int seeded, int failed) {

        /** @return whether anything at all had to be moved. */
        public boolean movedSomething() {
            return handedOver > 0 || seeded > 0;
        }
    }

    /** The region's chunk count, named so the "nothing was resident" check reads as what it is. */
    private static final class NoderaChunkCount {
        private static final int PER_REGION =
                dev.nodera.core.NoderaConstants.REGION_SIZE_CHUNKS
                        * dev.nodera.core.NoderaConstants.REGION_SIZE_CHUNKS;

        private NoderaChunkCount() {
        }
    }
}
