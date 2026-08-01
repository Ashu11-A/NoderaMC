package dev.nodera.core.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The tracker and rendezvous services a build points at when nobody has configured any.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Every Java entry point used to fall back to {@code 127.0.0.1:25600} / {@code :25601} — the mod
 * through {@code NoderaSettings.defaults()}, the headless worker through its own literal. Those are
 * the ports {@code scripts/dev.sh} runs services on, which is why it looked like it worked: on a
 * developer's machine the loopback default and the running stack were the same thing. Anywhere else
 * they are not. On a player's desktop loopback is a tracker they never installed; on a phone it is
 * the phone. Either way the node announces into a network of one and reports "no trackers are
 * answering", which reads as a broken application rather than as a missing address.
 *
 * <p>So a shipped build defaults to the project's own list — {@code index.json} on the orphan
 * {@code services} branch, compiled into this jar by the {@code generateOfficialServices} task,
 * resolved with {@code git show} at build time — and loopback is returned only in
 * {@linkplain #developmentMode() development mode}. The same file is compiled into the companion
 * app as its built-in tracker store, so the app and the worker default to the same addresses by
 * construction rather than by a comment promising they agree.
 *
 * <h2>What a default is and is not</h2>
 *
 * <p>These are addresses to <em>try</em>, nothing more. Every service still proves its own identity
 * — a tracker's directory rows are Ed25519-signed and verified before anything is dialled — and no
 * service holds authority over world state. A user who configures their own endpoints replaces
 * these entirely; a user who adds a store adds to them.
 *
 * <p>Thread-context: immutable, loaded once, safe to share.
 */
public final class DefaultServices {

    /** Where the generated list lives on the classpath. */
    private static final String RESOURCE = "/dev/nodera/core/official-services.list";

    /** The tracker a development stack runs on ({@code scripts/dev.sh}). */
    public static final String DEVELOPMENT_TRACKER = "127.0.0.1:25600";

    /** The rendezvous relay a development stack runs on ({@code scripts/dev.sh}). */
    public static final String DEVELOPMENT_RENDEZVOUS = "127.0.0.1:25601";

    /**
     * The environment variable, and equivalently the system property {@code nodera.dev}, that says
     * this process is a development run.
     */
    public static final String DEVELOPMENT_KEY = "NODERA_DEV";

    private static final List<String> OFFICIAL_TRACKERS;
    private static final List<String> OFFICIAL_RENDEZVOUS;

    static {
        List<String> trackers = new ArrayList<>();
        List<String> rendezvous = new ArrayList<>();
        for (String line : read()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int space = trimmed.indexOf(' ');
            if (space <= 0) {
                continue;
            }
            String kind = trimmed.substring(0, space).trim();
            String route = trimmed.substring(space + 1).trim();
            if (route.isEmpty()) {
                continue;
            }
            if ("tracker".equals(kind)) {
                trackers.add(route);
            } else if ("rendezvous".equals(kind)) {
                // A relay route is dialled as a bare `host:port`: `RendezvousEndpoint.parse` speaks
                // no scheme, and the mod's own config validator refuses one. The published list
                // writes `tcp://` because that is the form every other consumer uses, so the scheme
                // is dropped here rather than in the file — one list, read correctly by each side.
                rendezvous.add(withoutScheme(route));
            }
        }
        OFFICIAL_TRACKERS = List.copyOf(trackers);
        OFFICIAL_RENDEZVOUS = List.copyOf(rendezvous);
    }

    private DefaultServices() {
    }

    /**
     * Whether this process is a development run.
     *
     * <p>{@code NODERA_DEV=1} in the environment, or {@code -Dnodera.dev=true} — the property as
     * well as the variable because an Android worker runs inside another process and cannot be
     * given an environment, and because Gradle's {@code runClient} sets properties more easily than
     * it sets variables. Anything else, including absence, is a production run.
     *
     * @return whether local services may be used as defaults.
     */
    public static boolean developmentMode() {
        String property = System.getProperty("nodera.dev");
        if (property != null && !property.isBlank()) {
            return truthy(property);
        }
        String variable = System.getenv(DEVELOPMENT_KEY);
        return variable != null && truthy(variable);
    }

    /**
     * The tracker routes to use when nothing is configured.
     *
     * @return loopback in development mode, the official list otherwise. Never {@code null}; may be
     *     empty if the compiled list carries no tracker, which is a build fault the caller reports
     *     rather than papers over.
     */
    public static List<String> trackerEndpoints() {
        return developmentMode() ? List.of(DEVELOPMENT_TRACKER) : OFFICIAL_TRACKERS;
    }

    /**
     * The rendezvous routes to use when nothing is configured.
     *
     * @return loopback in development mode, the official list otherwise, as bare {@code host:port}.
     */
    public static List<String> rendezvousEndpoints() {
        return developmentMode() ? List.of(DEVELOPMENT_RENDEZVOUS) : OFFICIAL_RENDEZVOUS;
    }

    // There were once `officialTrackerEndpoints()` / `officialRendezvousEndpoints()` here, returning
    // the compiled list whatever mode the process was in. Nothing in production ever called them —
    // a caller either wants the defaults for the mode it is in, which is the two methods above, or
    // it wants to inspect the artefact, which is a test's job and not an API's. The structural
    // report caught them as test-only methods, which is exactly what they were. The gate they
    // existed to serve now reads the generated resource directly (`DefaultServicesTest`), and is
    // stronger for it: it asserts about the file that shipped rather than about a list this class
    // chose to expose.

    /**
     * Join a route list the way every {@code NODERA_*_ENDPOINTS} variable is spelled.
     *
     * @param routes the routes.
     * @return the routes, comma-separated.
     */
    public static String joined(List<String> routes) {
        return String.join(",", routes);
    }

    private static boolean truthy(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return "1".equals(value) || "true".equals(value) || "yes".equals(value);
    }

    private static String withoutScheme(String route) {
        int marker = route.indexOf("://");
        return marker < 0 ? route : route.substring(marker + 3);
    }

    private static List<String> read() {
        try (InputStream in = DefaultServices.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                // A jar built without the generated resource would silently have no services at
                // all, and every node built from it would announce nowhere. Fail where it can be
                // seen — at class initialisation, in the build that produced the jar.
                throw new IllegalStateException(
                        "the official service list is missing from this build (" + RESOURCE
                                + "); the generateOfficialServices task did not run");
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }
}
