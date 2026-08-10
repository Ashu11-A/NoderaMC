package dev.nodera.endpoint.paper;

import dev.nodera.coordinator.interference.InterferenceBuffer;
import dev.nodera.coordinator.interference.InterferenceStats;
import dev.nodera.coordinator.interference.MutationGuard;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.endpoint.paper.compat.BukkitForeignWrites;
import dev.nodera.endpoint.paper.compat.ForeignWriteBridge;
import dev.nodera.endpoint.paper.compat.WorldEditBulkWrites;
import dev.nodera.endpoint.paper.world.CrossRegionCommit;
import dev.nodera.endpoint.paper.world.CrossRegionRefusedException;
import dev.nodera.endpoint.paper.world.FoliaOwnershipProbe;
import dev.nodera.endpoint.paper.world.NoderaFoliaRegionMap;
import dev.nodera.peer.control.CompanionClient;
import dev.nodera.core.region.RegionAlignment;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

/**
 * `nodera-endpoint` — the Paper/Folia endpoint plugin's entry point (server task 1, L-61).
 *
 * <p>This first slice does the two things every later one depends on being true: it says which
 * platform it is on, and on a regionised platform it <b>refuses to enable</b> unless ALIGN-1 holds.
 * Nothing is validated, hosted or captured yet — those are tasks 2 and 3 — but a plugin that
 * enables on a misconfigured Folia would be building on two threads writing one authority unit, and
 * that is not a defect anyone finds by reading logs: it surfaces as an unreproducible state-root
 * divergence days later.
 *
 * <p><b>The grid exponent is read from the platform, not from our own config.</b> Asking the
 * operator to tell us what they configured elsewhere invites the two values to disagree, and the
 * one that matters is the platform's.
 *
 * @Thread-context {@code onEnable}/{@code onDisable} run on the server's main (or global) thread.
 */
public final class NoderaEndpointPlugin extends JavaPlugin {

    /**
     * Pending foreign writes before a region is folded into the certified chain.
     *
     * <p>PC-3 consequence 1 made operational: a {@code //set} over a million blocks becomes a stream
     * of bounded deltas rather than one delta the size of the operation. 4096 is one region's worth
     * of a 16×16×16 volume — small enough that a bulk edit never parks an unbounded buffer on the
     * server thread, large enough that an ordinary player's building never flushes at all.
     */
    private static final int FOREIGN_WRITE_FLUSH_THRESHOLD = 4096;

    private EndpointPlatform platform = EndpointPlatform.UNKNOWN;
    private EndpointConfig config;
    private EndpointPeerLink peerLink;
    private NoderaFoliaRegionMap regionMap;
    private CrossRegionCommit crossRegionCommit;
    private ForeignWriteBridge foreignWrites;
    private WorldEditBulkWrites worldEditWrites;

    @Override
    public void onEnable() {
        platform = EndpointPlatform.detect();
        getLogger().info("Nodera endpoint on " + platform.label() + " ("
                + Bukkit.getVersion() + ")");

        int exponent = platform.isRegionised()
                ? gridExponent() : RegionAlignment.DEFAULT_GRID_EXPONENT;
        if (platform.isRegionised()) {
            String refusal = RegionAlignment.preflight(exponent);
            if (!refusal.isEmpty()) {
                getLogger().severe(refusal);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("ALIGN-1 preflight passed: grid-exponent " + exponent + ", "
                    + RegionAlignment.regionsPerSectionAxis(exponent)
                    * RegionAlignment.regionsPerSectionAxis(exponent)
                    + " Nodera regions per Folia section, none split");
            regionMap = NoderaFoliaRegionMap.regionised(exponent, ownershipProbe());
        } else {
            getLogger().info("ALIGN-1 preflight not applicable: "
                    + platform.label() + " has one tick thread");
            regionMap = NoderaFoliaRegionMap.singleThreaded();
        }
        // Stage 1 of L-64: the endpoint can now SAY which regions share a thread, and a delta that
        // spans two that do not is refused with a named error rather than raced. The joint-transfer
        // commit that makes such a span atomic is built (CrossRegionCommit.joint) and is not wired
        // here, because nothing on this path delegates a region yet — server tasks 2 and 3.
        crossRegionCommit = CrossRegionCommit.refusing(regionMap);
        getLogger().info(regionMap.describe());
        getLogger().info(gateSelfCheck(exponent));

        config = readConfig();
        var problems = config.problems();
        if (!problems.isEmpty()) {
            // A configuration that cannot be honoured as written is refused with every reason at
            // once. Reporting only the first would send an operator round the loop once per
            // mistake, and the file is short enough that all of it can be checked in one pass.
            getLogger().severe("Nodera refuses to enable: nodera-endpoint.yml cannot be honoured"
                    + " as written.");
            for (EndpointConfig.Problem problem : problems) {
                getLogger().severe("  " + problem.key() + ": " + problem.message());
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("config: peer " + config.peerMode() + " · control port "
                + config.controlPort() + " · custody " + config.custody()
                + (config.listed() ? " · listed" : " · unlisted")
                + " · " + config.trackers().size() + " tracker(s)");

        installForeignWriteBridge();
        linkPeer();
    }

    /**
     * PC-3, wired: every foreign write this endpoint can see reaches the interference guard
     * (server task 8 deliverables 2–4, L-65).
     *
     * <blockquote><b>Plugins keep authority over the world; Nodera certifies what they did.</b>
     * </blockquote>
     *
     * <p>Two feeds, because one of them is not enough and believing otherwise is the failure this
     * method's shape exists to prevent:
     *
     * <ul>
     *   <li>{@link BukkitForeignWrites} — the Bukkit events, gating at {@code HIGH} and observing at
     *       {@code MONITOR} beside CoreProtect;</li>
     *   <li>{@link WorldEditBulkWrites} — WorldEdit's own extent pipeline, which is the ONLY way to
     *       see a {@code //set}. It fires no Bukkit event at all.</li>
     * </ul>
     *
     * <h2>What is honestly connected, and what is not</h2>
     *
     * <p>The delegation view is <b>empty</b> and the certification sink returns nothing, and both are
     * deliberate rather than unfinished-by-accident:
     *
     * <ul>
     *   <li>nothing on the plugin path delegates a region yet — that is server tasks 2 and 3 — so
     *       "is this region delegated" has exactly one honest answer today, and a predicate that
     *       guessed anything else would certify writes into a chain that does not exist;</li>
     *   <li>an {@code EXTERNAL_MUTATION} certificate is <b>signed by the node</b>, and under
     *       {@code peer.mode: external} the node and its key are a separate process (L-71). The
     *       endpoint can classify, gate, announce and record today; it cannot sign.</li>
     * </ul>
     *
     * <p>So on this tree the bridge's honest output is {@code observed} — the count of foreign
     * writes that reached Nodera — and that is exactly the number the live corpus stage reads,
     * because it is the number a Bukkit-events-only bridge would get wrong for a {@code //set}.
     * L-65's "certified" clause is not met by this method and the register says so.
     */
    private void installForeignWriteBridge() {
        foreignWrites = new ForeignWriteBridge(
                // CONVERT is the product default and the only mode that keeps a plugin's write.
                // STRICT cancels foreign writes outright and exists for CI determinism runs.
                new MutationGuard(region -> false, MutationGuard.Mode.CONVERT,
                        new InterferenceBuffer(), new InterferenceStats()),
                region -> Optional.empty(),
                (region, pos) -> ForeignWriteBridge.CertifiedHead.UNKNOWN,
                BukkitForeignWrites.denials(this),
                FOREIGN_WRITE_FLUSH_THRESHOLD);
        getServer().getPluginManager()
                .registerEvents(new BukkitForeignWrites(foreignWrites, this), this);
        installWorldEditBridge();
    }

    /**
     * Subscribe to WorldEdit's event bus, if this server has WorldEdit.
     *
     * <p>Guarded by a class check and a {@code LinkageError} catch because {@code paper-plugin.yml}
     * gives every plugin its own classloader: without the {@code dependencies.server.WorldEdit}
     * entry in that descriptor these types are simply not visible, and with it they are visible only
     * when WorldEdit is installed. A server without WorldEdit must still enable this plugin, so the
     * absence is an {@code info} line and never a refusal.
     */
    private void installWorldEditBridge() {
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit");
        } catch (ClassNotFoundException | LinkageError absent) {
            getLogger().info("WorldEdit is not installed, so no bulk-write bridge is needed."
                    + " Bukkit's block events are the whole foreign-write feed on this server.");
            return;
        }
        try {
            worldEditWrites = new WorldEditBulkWrites(foreignWrites, this);
            worldEditWrites.install();
        } catch (RuntimeException | LinkageError broken) {
            worldEditWrites = null;
            getLogger().warning("WorldEdit is installed but its bulk write path could NOT be bridged"
                    + " (" + broken + "). A //set on this server is invisible to Nodera; hand-placed"
                    + " blocks are still observed. This is a compatibility gap, not a refusal.");
        }
    }

    /**
     * Attach this endpoint to its Nodera node.
     *
     * <p><b>External is the mode that works.</b> L-71 records why: a node inside the server JVM dies
     * with it, taking the world off the network exactly when it most needs to still be there. An
     * always-on worker beside the server has neither problem, and it is the same worker the
     * companion app and the mod already supervise — so the endpoint gets crash independence by not
     * owning the process rather than by engineering around owning it.
     *
     * <p>Linking is never a startup gate: a worker that is slow to boot must not stop a Minecraft
     * server from accepting players.
     */
    private void linkPeer() {
        if (config.peerMode() != EndpointConfig.PeerMode.EXTERNAL) {
            getLogger().info("peer.mode is " + config.peerMode()
                    + ", and an in-JVM node is not built (server task 2; see L-71 for why external"
                    + " is the destination). This world is not announced, validated, or archived —"
                    + " set peer.mode: external and point peer.control-port at a running worker.");
            return;
        }
        peerLink = new EndpointPeerLink(
                CompanionClient.loopback(config.controlPort()), getLogger()::info, 30_000L);
        peerLink.start();

        // Hosting runs through the WORKER, not in this process: that is what makes the world
        // outlive the server JVM, which is the entire reason external mode exists (L-71). An
        // endpoint with no world id has nothing to host yet and says so rather than announcing an
        // empty claim.
        if (config.worldId().isBlank()) {
            getLogger().info("no world.id configured — nothing is announced. Set world.id to put"
                    + " this world on the network.");
            return;
        }
        if (!peerLink.linked()) {
            getLogger().info("the worker is not up yet, so world " + config.worldId()
                    + " is not announced. It will be once the link is made.");
            return;
        }
        peerLink.host(config.worldId(), getServer().getWorldContainer().getName(), config.listed())
                .ifPresent(error -> getLogger().warning(
                        "the worker refused to host " + config.worldId() + ": " + error
                                + " — the server keeps running; the world is simply not on the"
                                + " network"));
    }

    @Override
    public void onDisable() {
        if (worldEditWrites != null) {
            // A WorldEdit bus subscription that outlives the plugin holds our whole classloader.
            worldEditWrites.uninstall();
            worldEditWrites = null;
        }
        if (foreignWrites != null) {
            // A buffer that outlives the server is a foreign write nobody ever certified.
            foreignWrites.flushAll();
            getLogger().info(foreignWrites.summary());
            foreignWrites = null;
        }
        if (peerLink != null) {
            peerLink.close();
            peerLink = null;
        }
        if (crossRegionCommit != null) {
            crossRegionCommit.close();
            crossRegionCommit = null;
        }
        getLogger().info("Nodera endpoint stopped (" + platform.label() + ")");
    }

    /** @return whether this endpoint currently has a worker answering. */
    public boolean linked() {
        return peerLink != null && peerLink.linked();
    }

    /** @return the gate every multi-region delta passes before a write (server task 4, L-64). */
    public CrossRegionCommit crossRegionCommit() {
        return crossRegionCommit;
    }

    /**
     * ALIGN-1 asserted <b>live</b> rather than assumed (server task 3, L-64).
     *
     * <p>The preflight above is arithmetic on a number read out of a config file. This asks the
     * gate itself, on the exponent this server is actually running, two questions whose answers the
     * whole cross-region design rests on: are the Nodera regions that nest in one Folia section
     * admitted, and is a span the platform cannot justify refused? An operator gets the answer at
     * enable, in one line, instead of discovering it from a state-root divergence days later.
     *
     * <p><b>It reports; it never refuses to enable.</b> Ownership can only be answered from a
     * region thread and plugin enable is not one, so on a regionised platform the second question
     * is expected to refuse — that is the design, not a fault. Turning a diagnostic into a startup
     * gate would let a future platform's answer stop a correctly configured server.
     */
    private String gateSelfCheck(int gridExponent) {
        try {
            DimensionKey dimension = DimensionKey.overworld();
            // On a regionised platform the pair must be the corners of one section, because that is
            // the ALIGN-1 claim. On one tick thread there are no sections, so any two distinct
            // regions make the point that every pair is admitted.
            int perAxis = regionMap.regionised()
                    ? RegionAlignment.regionsPerSectionAxis(gridExponent) : 2;
            RegionId anchor = new RegionId(dimension, 0, 0);
            RegionId sameSection = new RegionId(dimension, perAxis - 1, perAxis - 1);
            RegionId nextSection = new RegionId(dimension, perAxis, 0);

            crossRegionCommit.requireJointCriticalSection(0L, anchor, sameSection);
            String nested = "ALIGN-1 live: the gate admits " + anchor + " + " + sameSection
                    + " in one critical section";
            try {
                crossRegionCommit.requireJointCriticalSection(0L, anchor, nextSection);
                return nested + ", and admits " + nextSection + " too (one execution thread)";
            } catch (CrossRegionRefusedException refused) {
                return nested + ", and REFUSES " + nextSection
                        + " — a span it cannot justify fails closed (L-64 stage 1)";
            }
        } catch (RuntimeException broken) {
            return "ALIGN-1 live: the cross-region gate could not be exercised at enable ("
                    + broken + "); every multi-region delta will be refused";
        }
    }

    /**
     * The platform's own region-ownership answer, or none.
     *
     * <p>The overworld is the right world to bind: it is the one an endpoint's regions are
     * delegated in today, and a probe bound to a world that does not exist would answer about the
     * wrong regioniser rather than refusing.
     */
    private NoderaFoliaRegionMap.ExecutionOwnership ownershipProbe() {
        // Self-catching on purpose. This runs inside onEnable on a tick thread, the seam it looks
        // for is resolved reflectively against a platform we do not compile against, and an
        // unresolvable probe is already a supported answer — so there is no surprise here worth
        // refusing to enable over, and an uncaught one on a Folia tick thread stops the server.
        try {
            var worlds = getServer().getWorlds();
            if (worlds.isEmpty()) {
                return NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE;
            }
            return FoliaOwnershipProbe.resolve(getServer(), worlds.getFirst());
        } catch (RuntimeException | LinkageError unresolvable) {
            getLogger().warning("could not resolve the platform's region ownership (" + unresolvable
                    + "); a delta spanning two Folia sections will be refused rather than raced");
            return NoderaFoliaRegionMap.ExecutionOwnership.UNANSWERABLE;
        }
    }

    /**
     * @return the endpoint's configuration. A missing file is every default rather than a refusal:
     *         an operator who installed the plugin to see what it does should get a working,
     *         honest node, not an error about a file they were never told to write.
     */
    private EndpointConfig readConfig() {
        java.nio.file.Path file = getDataFolder().toPath().resolve("nodera-endpoint.yml");
        if (!java.nio.file.Files.exists(file)) {
            getLogger().info("no " + file.getFileName() + " — using defaults");
            return EndpointConfig.parse("");
        }
        try {
            return EndpointConfig.parse(java.nio.file.Files.readString(file));
        } catch (java.io.IOException unreadable) {
            getLogger().warning("could not read " + file + " (" + unreadable
                    + "); using defaults");
            return EndpointConfig.parse("");
        }
    }

    /** @return the platform's configured grid exponent, or Folia's default when unreadable. */
    private int gridExponent() {
        // Read from the platform's own global configuration file. A value we cannot read is not a
        // reason to refuse — Folia's default nests cleanly, and refusing to start a correctly
        // configured server because a config parser changed shape would be the worse failure.
        java.nio.file.Path config = getServer().getWorldContainer().toPath()
                .resolve("config").resolve("paper-global.yml");
        try {
            for (String line : java.nio.file.Files.readAllLines(config)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("grid-exponent:")) {
                    return Integer.parseInt(trimmed.substring("grid-exponent:".length()).trim());
                }
            }
        } catch (java.io.IOException | RuntimeException unreadable) {
            getLogger().warning("could not read " + config + " (" + unreadable
                    + "); assuming the platform default grid-exponent "
                    + RegionAlignment.DEFAULT_GRID_EXPONENT);
        }
        return RegionAlignment.DEFAULT_GRID_EXPONENT;
    }
}
