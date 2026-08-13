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
     * A scenario that needs a physical device <b>and</b> real Minecraft clients.
     *
     * <p>The two are not alternatives: a phone can only be shown to hold a world's content if there
     * is a world, and a world only exists because a player opened one.
     */
    public static Requirements liveDevice(int freeGb) {
        return new Requirements(true, false, true, freeGb);
    }

    /**
     * Why this machine cannot host the scenario.
     *
     * <p>The KIND matters as much as the reason. A display, a phone and free memory are facts about
     * the machine and produce a {@link SkipKind#CIRCUMSTANTIAL} skip. The endpoint plugin jar does
     * not: the runner builds it before the queue starts, so a missing one means the harness did not
     * produce its own artefact — {@link SkipKind#STRUCTURAL}, and never tolerated. The two used to
     * be one string, which is how a suite that could not run anywhere read the same as one this box
     * merely could not host.
     *
     * @return the skip to report, or empty when everything is available.
     */
    public Optional<Skip> unmet(TestPaths paths) {
        List<String> circumstantial = new ArrayList<>();
        List<String> structural = new ArrayList<>();
        if (needsDisplay && System.getenv("DISPLAY") == null
                && System.getenv("WAYLAND_DISPLAY") == null) {
            circumstantial.add("no DISPLAY/WAYLAND_DISPLAY (real Minecraft clients need a GUI "
                    + "session; CI runs these under Xvfb)");
        }
        if (needsPaperJar && !Files.isRegularFile(paths.paperPluginJar())) {
            structural.add("no endpoint plugin at " + paths.paperPluginJar()
                    + " — the runner builds this itself (./gradlew :paper-plugin:jar), so its "
                    + "absence is a harness fault and not a property of this machine");
        }
        if (needsDevice && System.getenv("ANDROID_SERIAL") == null) {
            circumstantial.add("no ANDROID_SERIAL — this scenario needs a device on wireless "
                    + "debugging");
        }
        if (minimumFreeGb > 0) {
            long freeMib = freeMemoryMib();
            if (freeMib > 0 && roundedToGib(freeMib) < minimumFreeGb) {
                circumstantial.add("only " + freeMib + " MiB of RAM free, this scenario needs "
                        + minimumFreeGb + " GiB");
            }
        }
        if (!structural.isEmpty()) {
            List<String> all = new ArrayList<>(structural);
            all.addAll(circumstantial);
            return Optional.of(new Skip(SkipKind.STRUCTURAL, String.join("; ", all)));
        }
        if (!circumstantial.isEmpty()) {
            return Optional.of(new Skip(SkipKind.CIRCUMSTANTIAL, String.join("; ", circumstantial)));
        }
        return Optional.empty();
    }

    /**
     * Free memory in GiB, rounded to nearest rather than truncated.
     *
     * <p>This used to divide by 1024 twice, which floors: 4.99 GiB free reported as 4, so a
     * scenario asking for 5 skipped on a machine that had all but 10 MiB of what it wanted. On a
     * box that hovers between 4 and 5 GiB during a busy session, that turned a floor of 5 into a
     * floor of very nearly 6 — and every one of those skips is now red, so it turned them into
     * failures. Rounding also fixes the message, which said "only 4 GiB free" about 4.99.
     */
    static long roundedToGib(long mib) {
        return (mib + 512) / 1024;
    }

    /** Free memory in MiB from {@code /proc/meminfo}, or 0 where that cannot be read. */
    static long freeMemoryMib() {
        try {
            for (String line : Files.readAllLines(java.nio.file.Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    // MemAvailable is reported in kB.
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) / 1024;
                }
            }
        } catch (Exception notLinux) {
            return 0;
        }
        return 0;
    }
}
