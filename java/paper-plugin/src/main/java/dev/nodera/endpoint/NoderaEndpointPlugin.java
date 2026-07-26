package dev.nodera.endpoint;

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

        // Server task 2 is the node itself. Saying so is the point: an endpoint that quietly did
        // nothing would look identical to one that was working, and the operator would find out
        // when a world failed to appear on a tracker.
        getLogger().info("this build does not host a Nodera node yet (server task 2): the world is"
                + " not announced, validated, or archived. The plugin is a platform preflight.");
    }

    @Override
    public void onDisable() {
        getLogger().info("Nodera endpoint stopped (" + platform.label() + ")");
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
