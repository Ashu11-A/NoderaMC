package dev.nodera.headless;

import dev.nodera.core.Bytes;

import java.util.Base64;
import java.util.Collection;
import java.util.Map;

/**
 * The worker's JSON writer: one object, built a field at a time.
 *
 * <p>There was none. Ten control replies — {@code stateJson}, {@code worldsJson}, {@code lanJson},
 * {@code validationJson}, {@code telemetryJson}, the piece report, the directory, the config
 * outcome, the config read-back and the endpoint-health rows — each open-coded the same string
 * concatenation, and each carried its own idea of when a value needed escaping. Four of them
 * wrapped every string field in a local {@code escape(…)} and one did not, so a world named
 * {@code my "best" world} produced a reply the companion app could not parse at all.
 *
 * <p>Deliberately <b>not</b> a JSON library. The worker's classpath carries none, and the payloads
 * here are flat objects of scalars and arrays; what was missing was not a parser but a single place
 * that knows a string field is quoted and escaped and a numeric one is not.
 *
 * <p><b>Field order is not contract.</b> Every consumer — {@code nodera-core}'s serde model, the
 * mod's {@code WorkerStateParser} — reads by field name. Nothing may be added here that makes order
 * observable.
 *
 * @Thread-context one instance per reply, never shared; the static helpers are pure.
 */
final class Json {

    private final StringBuilder out = new StringBuilder(256);

    /** Whether a comma is owed before the next field. */
    private boolean started;

    private Json() {
        out.append('{');
    }

    /** @return a new, empty JSON object. */
    static Json object() {
        return new Json();
    }

    /** A quoted, escaped string field. A {@code null} value is written as {@code ""}. */
    Json str(String key, String value) {
        key(key);
        out.append('"').append(escape(value == null ? "" : value)).append('"');
        return this;
    }

    /** A numeric field. */
    Json num(String key, long value) {
        key(key);
        out.append(value);
        return this;
    }

    /** A boolean field. */
    Json bool(String key, boolean value) {
        key(key);
        out.append(value);
        return this;
    }

    /**
     * A field whose value is already JSON — an object, an array, or a literal.
     *
     * <p>The escape hatch for a block another component renders (the telemetry block, the LAN
     * block). Anything that is merely a string belongs in {@link #str}.
     */
    Json raw(String key, String json) {
        key(key);
        out.append(json);
        return this;
    }

    /** Base64 of the bytes, or {@code ""} when there are none — never a placeholder. */
    Json base64(String key, Bytes bytes) {
        return str(key, bytes == null || bytes.isEmpty()
                ? "" : Base64.getEncoder().encodeToString(bytes.toArray()));
    }

    /** An array of strings, each quoted and escaped. */
    Json strings(String key, Iterable<String> values) {
        key(key);
        out.append('[').append(strings(values)).append(']');
        return this;
    }

    /** An array of values that are already JSON — rows built by another {@code Json}. */
    Json array(String key, Collection<String> rendered) {
        key(key);
        out.append('[').append(String.join(",", rendered)).append(']');
        return this;
    }

    /** An object of string → string, each side quoted and escaped. */
    Json map(String key, Map<String, String> entries) {
        key(key);
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(escape(e.getKey())).append("\":\"")
                    .append(escape(e.getValue())).append('"');
        }
        out.append('}');
        return this;
    }

    /**
     * Splice in already-rendered {@code "key":value} pairs, or nothing when the fragment is empty.
     *
     * <p>For a collaborator that emits fields rather than a value — {@link TestMode#stateFragment}
     * and the validation block. Empty means <em>absent</em>, and absent must not leave a dangling
     * comma behind: that is the whole reason this is a method rather than a string concatenation.
     */
    Json fields(String fragment) {
        if (fragment == null || fragment.isEmpty()) {
            return this;
        }
        if (started) {
            out.append(',');
        }
        started = true;
        out.append(fragment);
        return this;
    }

    /** @return the finished object. The builder must not be used afterwards. */
    String end() {
        return out.append('}').toString();
    }

    private void key(String name) {
        if (started) {
            out.append(',');
        }
        started = true;
        out.append('"').append(name).append("\":");
    }

    /** Comma-joined quoted strings, for a caller assembling an array itself. */
    static String strings(Iterable<String> values) {
        StringBuilder b = new StringBuilder();
        for (String value : values) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append('"').append(escape(value == null ? "" : value)).append('"');
        }
        return b.toString();
    }

    /**
     * Minimal JSON string escaping.
     *
     * <p>Control characters go out as {@code \\uXXXX} rather than raw: a world name pasted from a
     * terminal can carry one, and a raw control character in a JSON string is a parse error at
     * every reader rather than an odd-looking name at one.
     */
    static String escape(String s) {
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
