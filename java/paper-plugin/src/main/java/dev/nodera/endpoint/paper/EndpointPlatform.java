package dev.nodera.endpoint.paper;

/**
 * Which server the plugin is running on, decided by what the platform actually provides rather than
 * by what it calls itself (server task 1).
 *
 * <p>Folia forks Paper, so a version string is not evidence: forks rename themselves, and a plugin
 * that keys off a name is broken by the next rebrand. The load-bearing difference is the regionised
 * scheduler, so the probe is for the class that only exists where regions do. Everything downstream
 * — whether ALIGN-1 has to be preflighted at all, which scheduler dispatches region work — follows
 * from this one answer.
 *
 * @Thread-context stateless; safe from any thread.
 */
public enum EndpointPlatform {

    /** Paper (or a Paper fork) with one main tick thread. */
    PAPER("Paper"),

    /** Folia: regionised threading, so ALIGN-1 is load-bearing. */
    FOLIA("Folia"),

    /** A Bukkit-family server that is neither — Spigot, CraftBukkit, or something unrecognised. */
    UNKNOWN("unknown Bukkit platform");

    private final String label;

    EndpointPlatform(String label) {
        this.label = label;
    }

    /** @return the human-readable name for the enable log line. */
    public String label() {
        return label;
    }

    /** @return whether region work must be dispatched through a regionised scheduler. */
    public boolean isRegionised() {
        return this == FOLIA;
    }

    /** Detect from the running classpath. */
    public static EndpointPlatform detect() {
        return detect(EndpointPlatform::classPresent);
    }

    /**
     * Detect with an injected class probe, so the decision is testable without a server.
     *
     * @param present answers whether a class name is loadable here.
     */
    public static EndpointPlatform detect(java.util.function.Predicate<String> present) {
        if (present.test("io.papermc.paper.threadedregions.RegionizedServer")) {
            return FOLIA;
        }
        if (present.test("io.papermc.paper.configuration.Configuration")
                || present.test("com.destroystokyo.paper.PaperConfig")) {
            return PAPER;
        }
        return UNKNOWN;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, EndpointPlatform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
