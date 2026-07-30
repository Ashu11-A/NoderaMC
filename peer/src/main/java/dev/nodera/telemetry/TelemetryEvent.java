package dev.nodera.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One telemetry event: a registry name, a timestamp, and attributes whose values can only be a
 * {@code long}, a {@code boolean}, or a bounded {@code String} from a closed set.
 *
 * <p>The attribute value type is deliberately not {@code Object}. Every setter on the builder is
 * typed, so a call site cannot pass a world name, a player name, or a formatted message without
 * writing {@link Builder#enumeration} — and that method's contract, enforced by
 * {@link TelemetryRegistry}, is that the value must be a declared member of that attribute's enum.
 * The receiver refuses undeclared values anyway ({@code rust/nodera-telemetry}); this makes the
 * mistake visible on the emitting side, where it can be fixed.
 *
 * @param name     a name declared in {@link TelemetryRegistry}.
 * @param atMillis wall-clock time of the observation.
 * @param attrs    declared attributes; insertion-ordered so the JSON is stable for tests.
 * @Thread-context immutable record, any thread.
 */
public record TelemetryEvent(String name, long atMillis, Map<String, Object> attrs) {

    /** Compact constructor makes the attribute map immutable and rejects a missing name. */
    public TelemetryEvent {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a telemetry event needs a registry name");
        }
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    /** Start building the event {@code name} observed at {@code atMillis}. */
    public static Builder named(String name, long atMillis) {
        return new Builder(name, atMillis);
    }

    /** Render this event as the JSON object the batch envelope carries. */
    public String toJson() {
        StringBuilder json = new StringBuilder(64);
        json.append("{\"name\":\"").append(name).append("\",\"t\":").append(atMillis)
                .append(",\"attrs\":{");
        boolean first = true;
        for (Map.Entry<String, Object> attr : attrs.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(attr.getKey()).append("\":");
            Object value = attr.getValue();
            if (value instanceof String text) {
                json.append('"').append(text).append('"');
            } else {
                json.append(value);
            }
        }
        return json.append("}}").toString();
    }

    /** Typed builder — the only way to construct an event. */
    public static final class Builder {

        private final String name;
        private final long atMillis;
        private final Map<String, Object> attrs = new LinkedHashMap<>();

        private Builder(String name, long atMillis) {
            this.name = name;
            this.atMillis = atMillis;
        }

        /** A whole number. Bucket it with {@link Buckets} before it gets here. */
        public Builder number(String key, long value) {
            attrs.put(key, value);
            return this;
        }

        public Builder flag(String key, boolean value) {
            attrs.put(key, value);
            return this;
        }

        /**
         * A member of the attribute's declared enum, a fixed-length hex fingerprint, or a version
         * string — the only shapes a {@code String} attribute may take.
         *
         * @throws IllegalArgumentException if the value is not admissible for this attribute. The
         *         check is local and cheap; it exists so a bad call site fails in the build rather
         *         than silently having its value dropped by the receiver in production.
         */
        public Builder enumeration(String key, String value) {
            if (!TelemetryRegistry.admits(name, key, value)) {
                throw new IllegalArgumentException(
                        "value " + value + " is not declared for " + name + "." + key);
            }
            attrs.put(key, value);
            return this;
        }

        public TelemetryEvent build() {
            return new TelemetryEvent(name, atMillis, attrs);
        }
    }
}
