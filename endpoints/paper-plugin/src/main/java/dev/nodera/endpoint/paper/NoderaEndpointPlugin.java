package dev.nodera.endpoint.paper;

import dev.nodera.endpoint.paper.entity.BukkitProjections;
import dev.nodera.endpoint.paper.entity.ProjectionListener;
import dev.nodera.endpoint.paper.entity.ProjectionPinner;
import dev.nodera.peer.control.CompanionClient;
import dev.nodera.core.region.RegionAlignment;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
    private ProjectionPinner projectionPinner;

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
        } else {
            getLogger().info("ALIGN-1 preflight not applicable: "
                    + platform.label() + " has one tick thread");
        }

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
        startProjectionPinning();
    }

    /**
     * Install the validated-item projection pin (server task 5 deliverable 6, [L-69]).
     *
     * <p><b>What is complete and what is not, said here rather than discovered later.</b> The pin
     * itself is complete: a projection registered with it does not despawn, does not drift past
     * {@link ProjectionPinner#DRIFT_TOLERANCE_BLOCKS}, cannot be credited by any vanilla actor, and
     * can be credited exactly once through the lane. Its INPUT is not: nothing on the endpoint path
     * delegates a region yet — that is server tasks 2 and 3 — so {@link #regionIsDelegated} answers
     * {@code false} for every location and no item on this server is validated, therefore none is
     * pinned. A server running this build behaves exactly as one without the plugin, which is the
     * only honest behaviour while there is no validated lane to be honest about.
     *
     * <p>It is wired now, rather than with tasks 2 and 3, because the pin is the half that has no
     * dependency on them and because a mechanism landed beside its own test is a mechanism somebody
     * can review. What it must not do is pin anything speculatively: an endpoint that stopped items
     * despawning on a world with no validated lane would be a data-shaped bug in every operator's
     * world, dressed as a feature.
     */
    private void startProjectionPinning() {
        if (!config.entityCapture()) {
            getLogger().info("lane.entity-capture is off — items are vanilla's entirely, and"
                    + " nothing is pinned");
            return;
        }
        projectionPinner = new ProjectionPinner(
                BukkitProjections.regionTicker(this),
                this::creditThroughValidatedLane,
                getLogger()::warning);
        getServer().getPluginManager().registerEvents(
                new ProjectionListener(projectionPinner, this::regionIsDelegated,
                        getLogger()::warning), this);
        getLogger().info("validated-item projection pinning installed (L-69): lifetime reset,"
                + " velocity zeroed, drift band " + ProjectionPinner.DRIFT_TOLERANCE_BLOCKS
                + " blocks, and the validated lane is the only thing that may credit an item");
        getLogger().info("no region on this endpoint is delegated yet (server tasks 2 and 3), so"
                + " no item is validated and nothing is pinned — the world ticks exactly as"
                + " vanilla until a region is delegated");
    }

    /**
     * Whether the Nodera region covering {@code where} is delegated to this endpoint's validated
     * lane.
     *
     * <p>Always {@code false} today. The endpoint's delegation path is server tasks 2 and 3 (the
     * in-process peer and the custody/assignment view); until one of them hands this plugin a live
     * assignment there is no region whose items are validated, and a predicate that guessed would
     * pin items the network has never certified.
     *
     * @param where the location of a candidate projection.
     */
    private boolean regionIsDelegated(Location where) {
        if (where == null) {
            return false;
        }
        return false;
    }

    /**
     * Hand a pickup intent to the validated lane, which is the only thing that may credit an item.
     *
     * <p>Always refuses today, for the same reason {@link #regionIsDelegated} always says no: there
     * is no validated lane on this endpoint yet. A refusal leaves the projection where it is rather
     * than crediting locally, which is the correct failure — crediting locally is precisely the
     * double-credit L-69's exit clause forbids.
     *
     * @param item  the projection's entity id.
     * @param taker the player who reached it.
     * @return whether the lane took the intent.
     */
    private boolean creditThroughValidatedLane(java.util.UUID item, java.util.UUID taker) {
        getLogger().fine("a pickup of " + item + " by " + taker
                + " had no validated lane to credit through (server tasks 2 and 3)");
        return false;
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
        if (projectionPinner != null) {
            // The counts go out before the pin is dropped: "0 pinned" after close() is what every
            // run would print, and a summary that is the same whatever happened is not a summary.
            getLogger().info("projection pin: " + projectionPinner.summary());
            projectionPinner.close();
            projectionPinner = null;
        }
        if (peerLink != null) {
            peerLink.close();
            peerLink = null;
        }
        getLogger().info("Nodera endpoint stopped (" + platform.label() + ")");
    }

    /** @return whether this endpoint currently has a worker answering. */
    public boolean linked() {
        return peerLink != null && peerLink.linked();
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
