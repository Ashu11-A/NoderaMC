package dev.nodera.peer.control;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Something that <b>happened</b> on this node, as opposed to something that is true of it.
 *
 * <h2>Why this exists next to {@code NODERA-STATE}</h2>
 *
 * <p>The state snapshot answers "what is the situation": how many peers, which worlds, how much
 * traffic. It is the right shape for a dashboard and the wrong shape for a prompt. A world being
 * opened to LAN is a <i>moment</i>, and a client that learns about moments by diffing successive
 * snapshots has to keep its own copy of the previous one, decide what counts as a change, and be
 * running at the time — three chances to miss the thing it exists to notice.
 *
 * <p>So the worker says it outright. An event is emitted once, at the instant the worker observes
 * it, carries everything needed to act on it, and is buffered so a client that connects a moment
 * later still hears it.
 *
 * <h2>Names are a namespace, and attributes are strings</h2>
 *
 * <p>{@code lan.opened}, {@code lan.shared}, {@code world.hosted} — a dotted family so a consumer can
 * subscribe to a prefix without this class growing an enum that every new event has to be added to
 * in three modules. Attributes are flat strings for the same reason the control protocol is: this
 * rides a line-oriented channel with no JSON library on the worker's classpath, and a nested shape
 * would need a parser on both ends to carry data that is, in practice, always scalar.
 *
 * @param name          the dotted event name; never blank.
 * @param epochMillis   when the worker observed it.
 * @param attributes    flat key/value detail, in insertion order.
 * @Thread-context immutable record, safe for any thread.
 */
public record WorkerEvent(String name, long epochMillis, Map<String, String> attributes) {

    /** A world on this machine was opened to LAN. Attributes: {@code session}, {@code world}, {@code port}. */
    public static final String LAN_OPENED = "lan.opened";

    /** That world stopped being announced — the player closed it. */
    public static final String LAN_CLOSED = "lan.closed";

    /** The player agreed to put it on the network. */
    public static final String LAN_SHARED = "lan.shared";

    /** The player said no. The world stays listed and can still be shared later. */
    public static final String LAN_DECLINED = "lan.declined";

    /** A world that was on the network was taken off it, back to merely offered. */
    public static final String LAN_STOPPED = "lan.stopped";

    /**
     * A world was deleted at its owner's verified request, here or anywhere on the network.
     * Attributes: {@code world}, {@code reason}.
     *
     * <p>Emitted after the deletion has been applied, so a client that reacts to it is describing
     * something already true rather than something in progress. It is not a confirmation of the
     * local player's own action — a peer supporting somebody else's world sees it too, which is the
     * point: that is how the app can tell the player why a world vanished from their list.
     */
    public static final String WORLD_DELETED = "world.deleted";

    public WorkerEvent {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("an event must have a name");
        }
        attributes = Map.copyOf(Objects.requireNonNullElse(attributes, Map.of()));
    }

    /** Start building an event with the current clock. */
    public static Builder named(String name) {
        return new Builder(name, System.currentTimeMillis());
    }

    /** @return the attribute, or {@code ""} — an absent attribute is not an error to a consumer. */
    public String attribute(String key) {
        return attributes.getOrDefault(key, "");
    }

    /**
     * Render as one JSON line, the shape every control-plane reply uses.
     *
     * <p>{@code seq} is supplied by the bus rather than held on the event: the same event handed to
     * two subscribers has one sequence number, and a field that only means something once it has
     * been published does not belong on the value.
     *
     * @param seq this event's position in the node's event sequence.
     * @return the JSON line, without a trailing newline.
     */
    public String toJson(long seq) {
        StringBuilder json = new StringBuilder(96);
        json.append("{\"seq\":").append(seq)
                .append(",\"event\":\"").append(escape(name)).append('"')
                .append(",\"at\":").append(epochMillis)
                .append(",\"attributes\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        return json.append("}}").toString();
    }

    /** Fluent builder — events are assembled at a call site that should not be building maps. */
    public static final class Builder {
        private final String name;
        private final long epochMillis;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(String name, long epochMillis) {
            this.name = name;
            this.epochMillis = epochMillis;
        }

        /** Add an attribute. A null value becomes {@code ""} rather than a hole in the JSON. */
        public Builder with(String key, String value) {
            attributes.put(key.toLowerCase(Locale.ROOT), value == null ? "" : value);
            return this;
        }

        /** Add a numeric attribute (still a string on the wire — see the class docs). */
        public Builder with(String key, long value) {
            return with(key, Long.toString(value));
        }

        public WorkerEvent build() {
            return new WorkerEvent(name, epochMillis, attributes);
        }
    }

    /** Minimal JSON string escaping, matching the rest of the control plane's hand-written JSON. */
    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
