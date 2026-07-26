package dev.nodera.telemetry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Java mirror of the ingest service's collection policy
 * ({@code rust/nodera-telemetry/src/schema.rs}).
 *
 * <p>This exists for the same reason the wire-tag mirror exists: two implementations of one
 * contract drift unless something mechanical fails when they do. {@code TelemetryRegistryMirrorTest}
 * runs {@code nodera-telemetry --print-schema} and compares it with this file, so an event added on
 * one side and forgotten on the other fails the build rather than being silently dropped in
 * production.
 *
 * <p>It is deliberately <b>not</b> generated. A generated mirror would make adding a collected field
 * a mechanical act; keeping it hand-written means every new field is edited into a file whose header
 * says what the rules are, in a commit that also touches {@code docs/plans/Plan.6.md} §4.
 *
 * @Thread-context immutable static tables, any thread.
 */
public final class TelemetryRegistry {

    private TelemetryRegistry() {}

    /** Batch envelope version — must equal {@code event::BATCH_VERSION} on the Rust side. */
    public static final int BATCH_VERSION = 1;

    /** The only consent token the receiver admits. */
    public static final String CONSENT_GRANTED = "granted";

    /** Source token for a player's node (the worker). */
    public static final String SOURCE_PEER = "peer";

    // ---- event names ---------------------------------------------------------------------------
    public static final String SERVICE_START = "service.start";
    public static final String SERVICE_STOP = "service.stop";
    public static final String SESSION_START = "session.start";
    public static final String SESSION_END = "session.end";
    public static final String REGION_OWNERSHIP = "region.ownership";
    public static final String ENGINE_TICK = "engine.tick";
    public static final String ENGINE_DIVERGENCE = "engine.divergence";
    public static final String ENGINE_INTERFERENCE = "engine.interference";
    public static final String WORLD_SHARE = "world.share";
    public static final String WORLD_JOIN = "world.join";
    public static final String WORLD_REHOST = "world.rehost";
    public static final String NET_HANDSHAKE = "net.handshake";
    public static final String NET_TRAFFIC = "net.traffic";
    public static final String STORAGE_ARCHIVE = "storage.archive";
    public static final String FEATURE_USE = "feature.use";
    public static final String CONSENT_CHANGE = "consent.change";
    public static final String ERROR_REPORT = "error.report";

    // ---- closed value domains ------------------------------------------------------------------
    public static final Set<String> OS = Set.of("linux", "macos", "windows", "other");
    public static final Set<String> ARCH = Set.of("x86_64", "aarch64", "other");
    public static final Set<String> PATH_KIND = Set.of("direct", "punched", "relayed", "unknown");
    public static final Set<String> JOIN_FAILURE = Set.of("none", "unreachable", "password",
            "permission", "timeout", "handshake", "world_gone", "other");
    public static final Set<String> CERTIFICATION = Set.of("certified", "pending", "solo");
    public static final Set<String> DIVERGENCE_PHASE =
            Set.of("shadow", "coordinator", "committee", "fallback");
    public static final Set<String> FEATURE = Set.of("share_gui", "multiplayer_gui", "piece_map",
            "selftest", "command_nodera", "command_noderac", "settings_change", "grant", "rekey",
            "hud_toggle", "companion_dashboard");
    public static final Set<String> ERROR_KIND = Set.of("engine", "transport", "storage", "control",
            "mod_lifecycle", "worker", "app", "other");
    public static final Set<String> SHARE_ORIGIN = Set.of("new_world", "existing_world", "rehost");

    /** Marker domain: any bounded {@code digits.dots.dashes} version string is admissible. */
    private static final Set<String> VERSION = Set.of("<version>");
    /** Marker domain: exactly 16 lowercase hex characters. */
    private static final Set<String> HEX16 = Set.of("<hex16>");

    /** Every event this peer may emit, and the string-valued attributes each one declares. */
    private static final Map<String, Map<String, Set<String>>> EVENTS = events();

    private static Map<String, Map<String, Set<String>>> events() {
        Map<String, Map<String, Set<String>>> events = new LinkedHashMap<>();
        events.put(SERVICE_START, Map.of("version", VERSION, "os", OS, "arch", ARCH));
        events.put(SERVICE_STOP, Map.of());
        events.put(SESSION_START, Map.of("mod_version", VERSION, "mc_version", VERSION,
                "loader_version", VERSION));
        events.put(SESSION_END, Map.of());
        events.put(REGION_OWNERSHIP, Map.of("certification", CERTIFICATION));
        events.put(ENGINE_TICK, Map.of());
        events.put(ENGINE_DIVERGENCE, Map.of("phase", DIVERGENCE_PHASE, "fingerprint", HEX16));
        events.put(ENGINE_INTERFERENCE, Map.of());
        events.put(WORLD_SHARE, Map.of("origin", SHARE_ORIGIN));
        events.put(WORLD_JOIN, Map.of("path", PATH_KIND, "failure", JOIN_FAILURE));
        events.put(WORLD_REHOST, Map.of());
        events.put(NET_HANDSHAKE, Map.of("path", PATH_KIND, "failure", JOIN_FAILURE));
        events.put(NET_TRAFFIC, Map.of());
        events.put(STORAGE_ARCHIVE, Map.of());
        events.put(FEATURE_USE, Map.of("feature", FEATURE));
        events.put(CONSENT_CHANGE, Map.of());
        events.put(ERROR_REPORT, Map.of("kind", ERROR_KIND, "fingerprint", HEX16));
        return Map.copyOf(events);
    }

    /** Every event name this peer may emit, in declaration order. */
    public static List<String> eventNames() {
        return List.copyOf(EVENTS.keySet());
    }

    /** Whether {@code name} is a declared event. */
    public static boolean declares(String name) {
        return EVENTS.containsKey(name);
    }

    /**
     * Whether {@code value} is admissible for {@code event.attribute}.
     *
     * <p>An attribute this table does not know about is <b>not</b> admitted. A call site inventing
     * a string attribute is exactly the mistake this method exists to catch, and being permissive
     * here would move the failure to production, where the receiver drops the value silently.
     */
    public static boolean admits(String event, String attribute, String value) {
        Map<String, Set<String>> declared = EVENTS.get(event);
        if (declared == null || value == null) {
            return false;
        }
        Set<String> domain = declared.get(attribute);
        if (domain == null) {
            return false;
        }
        if (domain == VERSION) {
            return isVersion(value);
        }
        if (domain == HEX16) {
            return isHex(value, 16);
        }
        return domain.contains(value);
    }

    /** A bounded {@code digits.dots.dashes} version string. */
    public static boolean isVersion(String value) {
        if (value.isEmpty() || value.length() > 24) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** Exactly {@code length} lowercase hex characters. */
    public static boolean isHex(String value, int length) {
        if (value.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A 64-bit fingerprint rendered as 16 lowercase hex characters.
     *
     * <p>Used for divergence roots and error signatures: enough to tell two occurrences apart,
     * useless for reconstructing what produced them.
     */
    public static String fingerprint(byte[] material) {
        long hash = 0xcbf29ce484222325L; // FNV-1a 64: stable across JVMs, unlike String.hashCode
        for (byte b : material) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return String.format("%016x", hash);
    }
}
