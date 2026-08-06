package dev.nodera.endpoint.state;

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
     * @param players players in-world, or <b>negative when no node in that world has reported</b>.
     *                Not clamped to zero — see {@link #connectedWorlds}.
     * @param mcRoute the host's open Minecraft game endpoint ({@code host:port}), or {@code ""}
     *                while the hosting player's game is closed (listed but not joinable).
     */
    public record HostedWorldInfo(String worldId, String name, long players, String mcRoute,
                                  boolean seeding, boolean owned, long seeders) {

        /**
         * Whether this node <b>hosts</b> the world rather than only keeping its bytes alive.
         *
         * <p>The distinction decides whether this install may speak for the world's liveness at
         * all. For a world it hosts, "no game endpoint here" and "nobody can enter" are the same
         * statement. For a world it merely supports, they are unrelated — the world is somebody
         * else's and is very probably open right now.
         */
        public boolean hostedHere() {
            return !seeding;
        }

        /** @return whether anybody in the world has reported its player count. */
        public boolean playersKnown() {
            return players >= 0;
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
            // NOT clamped to zero. The worker sends -1 for "no node in this world has reported its
            // player count", and clamping turned that into a confident "nobody is playing" on every
            // screen downstream — which is how a world with people in it advertised 0 players from
            // every peer except the one hosting the game. A world row whose count is absent from the
            // JSON entirely is likewise unknown, not empty.
            long players = longField(obj, "players", -1);
            String mcRoute = stringField(obj, "mc_route");
            // Defaults to false — "this node hosts it" — because that is what every row meant
            // before the worker carried the flag, and because it is the conservative reading: a
            // node claiming to host a world it cannot serve is visible, while a node silently
            // overriding the network's view of somebody else's world is not.
            boolean seeding = booleanField(obj, "seeding", false);
            // The worker has computed and sent this since world ownership existed, and this parser
            // dropped it — so the mod had no client-readable answer to "do I own this world" at
            // all, and gated its owner-only controls on `hasSingleplayerServer()` instead. A player
            // who recovered somebody else's world was therefore handed that world's share settings,
            // its password field and its delete button.
            //
            // Defaults to false: not knowing must never read as owning.
            boolean owned = booleanField(obj, "owned", false);
            long seeders = longField(obj, "seeders", 0);
            if (worldId != null || name != null) {
                out.add(new HostedWorldInfo(
                        worldId == null ? "" : worldId,
                        name == null ? "" : name,
                        players,
                        mcRoute == null ? "" : mcRoute,
                        seeding, owned, seeders));
            }
            i = objEnd + 1;
        }
        return out;
    }

    /**
     * Extract the {@code trackers} routes from a worker STATE JSON line.
     *
     * <p>The worker's live tracker list. The mod used to build its own from `NoderaConfig`, and
     * when the two disagreed nothing said so: the mod announced a world to one tracker while every
     * other peer's worker queried a different one, so the world was live and invisible, and joiners
     * fell through to re-hosting a stale copy of it — which is how one world becomes two.
     *
     * <p>Same contract as {@link #rendezvousRoutes}: reachable rows only, worker order preserved,
     * empty means "no opinion" and the caller falls back to configuration.
     *
     * @param json the raw STATE reply, or {@code null}.
     * @return {@code scheme://host:port} routes; empty if absent, malformed, or none reachable.
     */
    public static List<String> trackerRoutes(String json) {
        return routesOf(json, "\"trackers\"");
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
        return routesOf(json, "\"rendezvous\"");
    }

    private static List<String> routesOf(String json, String key) {
        List<String> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        int at = json.indexOf(key);
        if (at < 0) {
            return out;
        }
        int arrayStart = json.indexOf('[', at);
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
        return longField(obj, keyName, 0);
    }

    /** Read a JSON boolean field {@code "key":true} from a flat object. */
    private static boolean booleanField(String obj, String keyName, boolean absent) {
        int at = obj.indexOf("\"" + keyName + "\"");
        if (at < 0) {
            return absent;
        }
        int colon = obj.indexOf(':', at + keyName.length() + 2);
        if (colon < 0) {
            return absent;
        }
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) {
            i++;
        }
        if (obj.startsWith("true", i)) {
            return true;
        }
        if (obj.startsWith("false", i)) {
            return false;
        }
        return absent;
    }

    /**
     * As above, with the value to return when the field is absent or unreadable.
     *
     * <p>Explicit because zero is a legitimate reading for some fields and a wrong answer for
     * others: a missing player count means "nobody told us", and reporting that as "nobody is
     * playing" is the whole defect this overload exists to make impossible to write by accident.
     */
    private static long longField(String obj, String keyName, long absent) {
        int at = obj.indexOf("\"" + keyName + "\"");
        if (at < 0) {
            return absent;
        }
        int colon = obj.indexOf(':', at + keyName.length() + 2);
        if (colon < 0) {
            return absent;
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
            return absent;
        }
        try {
            return Long.parseLong(obj.substring(start, i));
        } catch (NumberFormatException e) {
            return absent;
        }
    }
}
