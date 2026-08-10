package dev.nodera.endpoint.paper;

import dev.nodera.endpoint.paper.world.CrossRegionCommit;
import dev.nodera.endpoint.paper.world.FoliaOwnershipProbe;
import dev.nodera.endpoint.paper.world.NoderaFoliaRegionMap;
import dev.nodera.peer.control.CompanionClient;
import dev.nodera.core.region.RegionAlignment;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

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

    private EndpointPlatform platform = EndpointPlatform.UNKNOWN;
    private EndpointConfig config;
    private EndpointPeerLink peerLink;
    private NoderaFoliaRegionMap regionMap;
    private CrossRegionCommit crossRegionCommit;

    @Override
    public void onEnable() {
        platform = EndpointPlatform.detect();
        getLogger().info("Nodera endpoint on " + platform.label() + " ("
                + Bukkit.getVersion() + ")");

        if (platform.isRegionised()) {
            int exponent = gridExponent();
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

        linkPeer();
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

    /** @return which Nodera regions this platform writes on one thread (server task 3, L-64). */
    public NoderaFoliaRegionMap regionMap() {
        return regionMap;
    }

    /** @return the gate every multi-region delta passes before a write (server task 4, L-64). */
    public CrossRegionCommit crossRegionCommit() {
        return crossRegionCommit;
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
