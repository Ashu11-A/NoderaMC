package dev.nodera.testkit.suite;

import dev.nodera.testkit.harness.TestPaths;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a scenario needs from the machine it runs on.
 *
 * <h2>A skip must be circumstantial, never structural</h2>
 *
 * <p>A suite that skips because this machine has no display is reporting the truth. A suite that
 * skips because nothing in the harness ever builds the jar it looks for is a suite that never runs
 * — and that is what the three server-category shell suites did for weeks, reporting SKIP on every
 * machine including CI. So the two are different things here: requirements describe the machine
 * (display, RAM, a phone), and anything the build produces is built by the runner instead of being
 * waited for.
 *
 * @param needsDisplay  a GUI session, for scenarios that launch real Minecraft clients.
 * @param needsPaperJar the Paper/Folia endpoint plugin jar (the runner builds it).
 * @param needsDevice   an attached Android device.
 * @param minimumFreeGb free RAM the run needs, in GiB.
 */
public record Requirements(boolean needsDisplay, boolean needsPaperJar, boolean needsDevice,
                           int minimumFreeGb) {

    public static Requirements none() {
        return new Requirements(false, false, false, 0);
    }

    /** A scenario that launches real Minecraft clients. */
    public static Requirements liveClients(int freeGb) {
        return new Requirements(true, false, false, freeGb);
    }

    /** A scenario that runs against the Paper/Folia endpoint plugin. */
    public static Requirements paperEndpoint() {
        return new Requirements(false, true, false, 4);
    }

    /** A scenario that needs a physical device. */
    public static Requirements device() {
        return new Requirements(false, false, true, 0);
    }

    /**
     * Why this machine cannot host the scenario.
     *
     * @return the reason to report as a skip, or empty when everything is available.
     */
    public Optional<String> unmet(TestPaths paths) {
        List<String> missing = new ArrayList<>();
        if (needsDisplay && System.getenv("DISPLAY") == null
                && System.getenv("WAYLAND_DISPLAY") == null) {
            missing.add("no DISPLAY/WAYLAND_DISPLAY (real Minecraft clients need a GUI session; "
                    + "CI runs these under Xvfb)");
        }
        if (needsPaperJar && !Files.isRegularFile(paths.paperPluginJar())) {
            missing.add("no endpoint plugin at " + paths.paperPluginJar()
                    + " (./gradlew :paper-plugin:jar)");
        }
        if (needsDevice && System.getenv("ANDROID_SERIAL") == null) {
            missing.add("no ANDROID_SERIAL — this scenario needs a device on wireless debugging");
        }
        if (minimumFreeGb > 0) {
            long freeGb = freeMemoryGb();
            if (freeGb > 0 && freeGb < minimumFreeGb) {
                missing.add("only " + freeGb + " GiB of RAM free, this scenario needs "
                        + minimumFreeGb + " GiB");
            }
        }
        return missing.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", missing));
    }

    /** Free memory in GiB from {@code /proc/meminfo}, or 0 where that cannot be read. */
    private static long freeMemoryGb() {
        try {
            for (String line : Files.readAllLines(java.nio.file.Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) / 1024 / 1024;
                }
            }
        } catch (Exception notLinux) {
            return 0;
        }
        return 0;
    }
}
