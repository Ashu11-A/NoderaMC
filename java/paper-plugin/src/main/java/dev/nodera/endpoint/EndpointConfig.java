package dev.nodera.endpoint;

import dev.nodera.core.region.CustodyClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * `nodera-endpoint.yml`, parsed and checked (server task 2).
 *
 * <p><b>Why a parser of its own.</b> Bukkit ships a YAML reader, and using it would tie the meaning
 * of this file to a running server — which is exactly where a misread cannot be caught cheaply. The
 * file's real shape is a handful of nested scalars and two string lists, so it is read here with no
 * Minecraft on the classpath and the whole contract is unit-testable. If the file ever needs
 * anchors, aliases or multi-document streams, that is the signal to take a YAML dependency, not to
 * grow this.
 *
 * <p><b>Validation refuses contradictions, not omissions.</b> A missing key takes a documented
 * default; a key that cannot be honoured as written is an error, and the message says what to change.
 * The distinction matters because an operator who leaves a section out is asking for the default,
 * while one who writes {@code custody: FULL} with no world to be custodian of has made a mistake
 * that will otherwise surface as an empty announce nobody can explain.
 *
 * @Thread-context immutable once parsed; safe from any thread.
 */
public record EndpointConfig(
        PeerMode peerMode,
        int controlPort,
        String p2pBind,
        int p2pPort,
        String p2pAdvertise,
        String worldId,
        CustodyClass custody,
        boolean listed,
        List<String> trackers,
        List<String> rendezvous,
        boolean entityCapture,
        List<String> mobCaptureDimensions
) {

    /** Where this endpoint's Nodera node runs. */
    public enum PeerMode {
        /** Inside the server JVM — dies with it (L-71). */
        EMBEDDED,
        /** A separate always-on worker process reached over the control socket. */
        EXTERNAL
    }

    /** Defaults chosen so an operator who writes nothing gets a working, honest node. */
    public static final int DEFAULT_CONTROL_PORT = 25610;
    public static final int DEFAULT_P2P_PORT = 25620;

    public EndpointConfig {
        trackers = List.copyOf(trackers == null ? List.of() : trackers);
        rendezvous = List.copyOf(rendezvous == null ? List.of() : rendezvous);
        mobCaptureDimensions = List.copyOf(
                mobCaptureDimensions == null ? List.of() : mobCaptureDimensions);
    }

    /** One problem with the configuration, in the operator's terms. */
    public record Problem(String key, String message) {
    }

    /**
     * @return every reason this configuration cannot be honoured as written, in file order. Empty
     *         means the plugin may enable.
     */
    public List<Problem> problems() {
        List<Problem> out = new ArrayList<>();
        if (controlPort < 1 || controlPort > 65535) {
            out.add(new Problem("peer.control-port",
                    "must be a port in 1..65535, was " + controlPort));
        }
        if (p2pPort < 1 || p2pPort > 65535) {
            out.add(new Problem("peer.p2p.port", "must be a port in 1..65535, was " + p2pPort));
        }
        if (controlPort == p2pPort) {
            out.add(new Problem("peer.p2p.port",
                    "cannot equal peer.control-port (" + controlPort
                            + ") — the control socket is loopback-only and the p2p socket is not,"
                            + " so one port cannot be both"));
        }
        if (custody == CustodyClass.FULL && listed && worldId.isBlank()) {
            // FULL custody with no world id is NOT a contradiction on its own: an endpoint is
            // routinely configured before its world has an id, which the host flow assigns from the
            // certified genesis. It only becomes one when the world is also to be ANNOUNCED —
            // advertising full custody of a world nobody can name is an announce no tracker can
            // use. (Found by running the plugin against the harness's own staged config, which is
            // exactly that shape.)
            out.add(new Problem("world.id",
                    "world.listed: true with custody: FULL advertises a whole world, but no"
                            + " world.id names which one. Set world.id, or set world.listed: false"
                            + " until the world has one."));
        }
        if (listed && trackers.isEmpty()) {
            out.add(new Problem("discovery.trackers",
                    "world.listed: true asks for the world to be discoverable, but no tracker is"
                            + " configured to announce it to. Add discovery.trackers, or set"
                            + " world.listed: false."));
        }
        return List.copyOf(out);
    }

    /**
     * Parse the file's text. Unknown keys are ignored — a newer plugin's file must not stop an
     * older one from starting, and the register is the place that says what is understood.
     *
     * @param yaml the file contents.
     */
    public static EndpointConfig parse(String yaml) {
        Scalars values = Scalars.of(yaml);
        return new EndpointConfig(
                mode(values.text("peer.mode", "embedded")),
                values.number("peer.control-port", DEFAULT_CONTROL_PORT),
                values.text("peer.p2p.bind", "0.0.0.0"),
                values.number("peer.p2p.port", DEFAULT_P2P_PORT),
                values.text("peer.p2p.advertise", ""),
                values.text("world.id", ""),
                CustodyClass.parse(values.text("world.custody", "view")),
                values.flag("world.listed", false),
                values.list("discovery.trackers"),
                values.list("discovery.rendezvous"),
                values.flag("lane.entity-capture", false),
                values.list("lane.mob-capture-dimensions"));
    }

    private static PeerMode mode(String raw) {
        return "external".equals(raw.trim().toLowerCase(Locale.ROOT))
                ? PeerMode.EXTERNAL : PeerMode.EMBEDDED;
    }

    /**
     * The flat subset of YAML this file actually uses: two-space nesting, {@code key: value}, and
     * inline {@code ["a", "b"]} lists. Comments and blank lines are skipped; quotes are stripped.
     */
    private static final class Scalars {

        private final java.util.Map<String, String> values = new java.util.LinkedHashMap<>();

        static Scalars of(String yaml) {
            Scalars scalars = new Scalars();
            if (yaml == null) {
                return scalars;
            }
            List<String> path = new ArrayList<>();
            for (String rawLine : yaml.split("\n", -1)) {
                String line = rawLine.stripTrailing();
                String withoutComment = stripComment(line);
                if (withoutComment.isBlank()) {
                    continue;
                }
                int indent = withoutComment.length() - withoutComment.stripLeading().length();
                String trimmed = withoutComment.strip();
                int colon = trimmed.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = trimmed.substring(0, colon).strip();
                String value = trimmed.substring(colon + 1).strip();
                int depth = indent / 2;
                while (path.size() > depth) {
                    path.remove(path.size() - 1);
                }
                path.add(key);
                if (!value.isEmpty()) {
                    scalars.values.put(String.join(".", path), unquote(value));
                    path.remove(path.size() - 1);
                }
            }
            return scalars;
        }

        /** Strip a trailing `# comment`, but never inside quotes — a password may contain a hash. */
        private static String stripComment(String line) {
            boolean inQuotes = false;
            char quote = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inQuotes) {
                    if (c == quote) {
                        inQuotes = false;
                    }
                } else if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quote = c;
                } else if (c == '#') {
                    return line.substring(0, i);
                }
            }
            return line;
        }

        private static String unquote(String value) {
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                return value.substring(1, value.length() - 1);
            }
            return value;
        }

        String text(String key, String fallback) {
            String value = values.get(key);
            return value == null ? fallback : value;
        }

        int number(String key, int fallback) {
            String value = values.get(key);
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException notANumber) {
                // Out-of-shape values become an out-of-range sentinel so `problems()` reports them
                // instead of the parser throwing during enable — the operator gets a message, not a
                // stack trace in a server log they did not write.
                return -1;
            }
        }

        boolean flag(String key, boolean fallback) {
            String value = values.get(key);
            return value == null ? fallback : Boolean.parseBoolean(value.trim());
        }

        List<String> list(String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                return List.of();
            }
            String inner = value.trim();
            if (inner.startsWith("[") && inner.endsWith("]")) {
                inner = inner.substring(1, inner.length() - 1);
            }
            List<String> out = new ArrayList<>();
            for (String element : inner.split(",")) {
                String item = unquote(element.strip());
                if (!item.isEmpty()) {
                    out.add(item);
                }
            }
            return List.copyOf(out);
        }
    }
}
