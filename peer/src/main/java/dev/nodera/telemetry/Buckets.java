package dev.nodera.telemetry;

/**
 * The one place a measurement becomes a bucket ({@code docs/plans/Plan.6.md} D6).
 *
 * <p>A byte-exact world size, a millisecond-exact session length, or a precise peer count is a
 * fingerprint: enough of them together identify an installation even with no identifier attached.
 * A bucket index is a statistic. Every emitted number passes through here.
 *
 * <p>Centralised rather than done at each call site because per-site bucketing drifts, and two
 * events that disagree about what "large" means produce aggregates nobody can compare. The
 * boundaries below are the ones the ingest registry's ranges are written against.
 *
 * @Thread-context stateless static helpers, any thread.
 */
public final class Buckets {

    private Buckets() {}

    /**
     * Bytes → whole megabytes, rounded down.
     *
     * <p>Rounded down rather than to nearest so a bucket is always a lower bound: "at least this
     * much" is a claim that survives aggregation, "about this much" is not.
     */
    public static long megabytes(long bytes) {
        return Math.max(0, bytes) / (1024L * 1024L);
    }

    /**
     * Milliseconds → seconds, then to a coarse ladder: exact below a minute, 10-second steps below
     * an hour, minute steps above.
     *
     * <p>The ladder exists because the *interesting* part of a duration is its order of magnitude —
     * a join taking 2 s versus 40 s is a product fact, 41 s versus 42 s is noise with a resolution
     * that helps identify a session.
     */
    public static long seconds(long millis) {
        long seconds = Math.max(0, millis) / 1000L;
        if (seconds < 60) {
            return seconds;
        }
        if (seconds < 3600) {
            return (seconds / 10) * 10;
        }
        return (seconds / 60) * 60;
    }

    /** Milliseconds → whole minutes, rounded down. */
    public static long minutes(long millis) {
        return Math.max(0, millis) / 60_000L;
    }

    /** Milliseconds → whole hours, rounded down. */
    public static long hours(long millis) {
        return Math.max(0, millis) / 3_600_000L;
    }

    /**
     * Ticks per second → a whole number in {@code [0, 20]}.
     *
     * <p>Clamped at 20 because vanilla's ceiling is 20 and a meter reporting 21 is a measurement
     * artefact, not a fast server.
     */
    public static long tps(double ticksPerSecond) {
        if (Double.isNaN(ticksPerSecond) || ticksPerSecond <= 0) {
            return 0;
        }
        return Math.min(20, Math.round(ticksPerSecond));
    }

    /** A ratio → a whole percentage in {@code [0, 100]}. */
    public static long percent(long part, long whole) {
        if (whole <= 0) {
            return 0;
        }
        long value = Math.round((100.0 * Math.max(0, part)) / whole);
        return Math.max(0, Math.min(100, value));
    }

    /**
     * A count → a power-of-two-ish ladder: exact to 8, then 16/32/64/128/256…
     *
     * <p>Used for CPU cores and RAM, where the exact number is a hardware fingerprint and the
     * magnitude is the fact worth having.
     */
    public static long magnitude(long count) {
        long value = Math.max(1, count);
        if (value <= 8) {
            return value;
        }
        long bucket = 8;
        while (bucket < value && bucket < (1L << 20)) {
            bucket *= 2;
        }
        return bucket;
    }

    /** Clamp an arbitrary count into a declared range, so a runaway meter cannot be rejected. */
    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The OS family token declared by the registry, from the running JVM. */
    public static String osFamily() {
        String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (name.contains("linux")) {
            return "linux";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "macos";
        }
        if (name.contains("win")) {
            return "windows";
        }
        return "other";
    }

    /** The architecture token declared by the registry, from the running JVM. */
    public static String arch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            return "x86_64";
        }
        return "other";
    }
}
