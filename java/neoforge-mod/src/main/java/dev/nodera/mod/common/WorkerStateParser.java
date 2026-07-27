package dev.nodera.mod.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free reader for the worker's {@code NODERA-STATE} JSON line — specifically the
 * {@code connected_worlds} array (the worlds the always-on peer worker is currently keeping on the
 * network). The worker emits this hand-written JSON in
 * {@code dev.nodera.headless.WorkerControlHandler.stateJson}; this parser pulls exactly the fields the
 * multiplayer "Worlds" tab needs, without dragging a JSON library onto the mod classpath.
 *
 * <p>It is a tiny scanner (not a general JSON parser): it locates the {@code "connected_worlds"} array
 * and reads each flat object's {@code world_id} / {@code name} / {@code players} fields, honouring
 * JSON string escapes so a world name containing a quote or backslash is read correctly. Anything it
 * cannot make sense of yields an empty list rather than throwing — a malformed worker reply must never
 * crash the client screen.
 *
 * <p>Thread-context: stateless static functions; any thread.
 */
public final class WorkerStateParser {

    /**
     * One hosted world as the worker reports it.
     *
     * @param mcRoute the host's open Minecraft game endpoint ({@code host:port}), or {@code ""}
     *                while the hosting player's game is closed (listed but not joinable).
     */
    public record HostedWorldInfo(String worldId, String name, long players, String mcRoute) {

        /** Pre-join-flow shape (no game endpoint reported). */
        public HostedWorldInfo(String worldId, String name, long players) {
            this(worldId, name, players, "");
        }
    }

    private WorkerStateParser() {
    }

    /**
     * Extract the {@code connected_worlds} entries from a worker STATE JSON line.
     *
     * @param json the raw STATE reply, or {@code null}.
     * @return the hosted worlds in wire order; empty if absent/malformed.
     */
    public static List<HostedWorldInfo> connectedWorlds(String json) {
        List<HostedWorldInfo> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        int key = json.indexOf("\"connected_worlds\"");
        if (key < 0) {
            return out;
        }
        int arrayStart = json.indexOf('[', key);
        if (arrayStart < 0) {
            return out;
        }
        int arrayEnd = matchingBracket(json, arrayStart, '[', ']');
        if (arrayEnd < 0) {
            return out;
        }

        int i = arrayStart + 1;
        while (i < arrayEnd) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0 || objStart >= arrayEnd) {
                break;
            }
            int objEnd = matchingBracket(json, objStart, '{', '}');
            if (objEnd < 0 || objEnd > arrayEnd) {
                break;
            }
            String obj = json.substring(objStart, objEnd + 1);
            String worldId = stringField(obj, "world_id");
            String name = stringField(obj, "name");
            long players = longField(obj, "players");
            String mcRoute = stringField(obj, "mc_route");
            if (worldId != null || name != null) {
                out.add(new HostedWorldInfo(
                        worldId == null ? "" : worldId,
                        name == null ? "" : name,
                        Math.max(0, players),
                        mcRoute == null ? "" : mcRoute));
            }
            i = objEnd + 1;
        }
        return out;
    }

    /**
     * Extract the {@code rendezvous} routes from a worker STATE JSON line.
     *
     * <p>This is the worker's <em>live</em> selection: the relays its {@code RendezvousDirectory}
     * discovered from trackers, probed, scored and chose, re-swept every minute and replaced the
     * instant one of them says it is draining. The mod composes its own rendezvous transport from a
     * static config list read once at world open, so without this the worker migrates to a
     * replacement relay and the in-game transport keeps talking to the one that is leaving until the
     * next world open (L-84).
     *
     * <p>Only reachable endpoints are returned, and order is preserved: the worker already sorted
     * them best-first, and an unreachable row is one the worker is reporting on rather than
     * recommending. An empty result means "no opinion" and callers fall back to configuration —
     * never to nothing, because a worker that is absent, old, or still starting up must not take a
     * player's ability to share a world with it.
     *
     * @param json the raw STATE reply, or {@code null}.
     * @return {@code scheme://host:port} routes, best first; empty if absent, malformed, or none
     *         reachable.
     */
    public static List<String> rendezvousRoutes(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        int key = json.indexOf("\"rendezvous\"");
        if (key < 0) {
            return out;
        }
        int arrayStart = json.indexOf('[', key);
        if (arrayStart < 0) {
            return out;
        }
        int arrayEnd = matchingBracket(json, arrayStart, '[', ']');
        if (arrayEnd < 0) {
            return out;
        }

        int i = arrayStart + 1;
        while (i < arrayEnd) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0 || objStart >= arrayEnd) {
                break;
            }
            int objEnd = matchingBracket(json, objStart, '{', '}');
            if (objEnd < 0 || objEnd > arrayEnd) {
                break;
            }
            String obj = json.substring(objStart, objEnd + 1);
            String host = stringField(obj, "host");
            long port = longField(obj, "port");
            String scheme = stringField(obj, "scheme");
            // `"reachable":true` — matched on the raw object because the value is a JSON literal,
            // not a string, and this scanner has no boolean reader. A row that is not explicitly
            // reachable is skipped: handing the transport a relay the worker just failed to reach
            // would be worse than falling back to the configured list.
            boolean reachable = obj.contains("\"reachable\":true");
            if (reachable && host != null && !host.isBlank() && port > 0 && port <= 65535) {
                String prefix = (scheme == null || scheme.isBlank()) ? "tcp" : scheme;
                out.add(prefix + "://" + host + ":" + port);
            }
            i = objEnd + 1;
        }
        return out;
    }

    /** @return the index of the bracket that closes {@code open} at {@code from}, respecting strings. */
    private static int matchingBracket(String s, int from, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Read a JSON string field {@code "key":"value"} from a flat object, decoding escapes. */
    private static String stringField(String obj, String keyName) {
        int at = obj.indexOf("\"" + keyName + "\"");
        if (at < 0) {
            return null;
        }
        int colon = obj.indexOf(':', at + keyName.length() + 2);
        if (colon < 0) {
            return null;
        }
        int q = obj.indexOf('"', colon + 1);
        if (q < 0) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        boolean escaped = false;
        for (int i = q + 1; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'u' -> {
                        if (i + 4 < obj.length()) {
                            b.append((char) Integer.parseInt(obj.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> b.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return b.toString();
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Read a JSON integer field {@code "key":123} from a flat object; {@code 0} if absent/bad. */
    private static long longField(String obj, String keyName) {
        int at = obj.indexOf("\"" + keyName + "\"");
        if (at < 0) {
            return 0;
        }
        int colon = obj.indexOf(':', at + keyName.length() + 2);
        if (colon < 0) {
            return 0;
        }
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < obj.length() && (Character.isDigit(obj.charAt(i)) || obj.charAt(i) == '-')) {
            i++;
        }
        if (i == start) {
            return 0;
        }
        try {
            return Long.parseLong(obj.substring(start, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
