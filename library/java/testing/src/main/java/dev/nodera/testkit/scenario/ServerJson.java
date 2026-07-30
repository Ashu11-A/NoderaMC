package dev.nodera.testkit.scenario;

import dev.nodera.testkit.harness.HarnessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A tiny JSON reader for the documents a live scenario interrogates.
 *
 * <h2>Why a parser and not a regular expression</h2>
 *
 * <p>The shell suites read these documents two different ways, and the difference is not a matter of
 * taste. {@code scripts/e2e-android-mesh.sh} says it outright — "parsed with python, not grep: the
 * state is JSON and a regex over it would read {@code peers} out of a nested object" — and
 * {@code scripts/lib/e2e-server.sh}'s {@code endpoint_state_field} exists for the same reason:
 * "assert on the STATE field rather than grepping the id anywhere in the reply: an error message
 * that happens to quote the id would otherwise read as a hosted world"
 * ({@code scripts/e2e-endpoint.sh}). Every one of those suites reached for {@code python3} to do it.
 * With the suites in Java there is no python in the loop, so the same job is done here.
 *
 * <p>Deliberately minimal: objects, arrays, strings, numbers, booleans and null, which is the whole
 * of what {@code NODERA-STATE}, the telemetry status document and the collector's spool rows are
 * made of. It is not a general JSON library and must not grow into one.
 *
 * <p>Thread-context: stateless static helpers over an immutable parse result; safe anywhere.
 */
public final class ServerJson {

    private final String source;
    private int cursor;

    private ServerJson(String source) {
        this.source = source;
    }

    /**
     * Parse one document.
     *
     * @param text the JSON text.
     * @return a {@link Map}, {@link List}, {@link String}, {@link Double}, {@link Boolean} or null.
     * @throws HarnessException naming the offset, because a state document a scenario cannot read is
     *                          a failure of the scenario's evidence, not a silent empty answer.
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new HarnessException("cannot parse JSON: the document is absent");
        }
        ServerJson reader = new ServerJson(text);
        reader.skipSpace();
        Object value = reader.readValue();
        reader.skipSpace();
        return value;
    }

    /** Parse, returning empty rather than throwing — for documents that may legitimately be absent. */
    public static Optional<Object> tryParse(String text) {
        try {
            return Optional.ofNullable(parse(text));
        } catch (RuntimeException notJson) {
            return Optional.empty();
        }
    }

    /**
     * Follow a dotted path through a parsed document, exactly as the shell's
     * {@code endpoint_state_field} and {@code android_state_field} did.
     *
     * @param document a parsed document.
     * @param path     {@code "custody.class"}, {@code "validation.divergences"},
     *                 {@code "connected_worlds.0.world_id"} — an integer step indexes an array.
     * @return the value at that path, or empty when any step is missing.
     */
    public static Optional<Object> at(Object document, String path) {
        Object current = document;
        for (String key : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(key);
            } else if (current instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(key);
                } catch (NumberFormatException notAnIndex) {
                    return Optional.empty();
                }
                if (index < 0 || index >= list.size()) {
                    return Optional.empty();
                }
                current = list.get(index);
            } else {
                return Optional.empty();
            }
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(current);
    }

    /** The value at {@code path} rendered as text, or empty when it is missing. */
    public static Optional<String> text(Object document, String path) {
        return at(document, path).map(ServerJson::render);
    }

    /**
     * The value at {@code path} as a whole number, or {@code 0} when it is missing.
     *
     * <p>Zero-for-missing matches the shell's {@code int(v.get("divergences") or 0)}: a node that
     * has not reported a counter yet has not diverged, and treating that as a failure would fail a
     * run for a document that simply has not been written.
     */
    public static long number(Object document, String path) {
        Object value = at(document, path).orElse(null);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return (long) Double.parseDouble(s.trim());
            } catch (NumberFormatException notANumber) {
                return 0L;
            }
        }
        return 0L;
    }

    /** The top-level keys of a parsed object, or empty when it is not an object. */
    public static List<String> keys(Object document) {
        if (document instanceof Map<?, ?> map) {
            List<String> out = new ArrayList<>();
            map.keySet().forEach(key -> out.add(String.valueOf(key)));
            return out;
        }
        return List.of();
    }

    /** How a value prints in an assertion message: text as text, everything else as JSON. */
    public static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Double d) {
            return d == Math.rint(d) && !d.isInfinite()
                    ? String.valueOf((long) (double) d) : String.valueOf((double) d);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            map.forEach((key, entry) -> {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append('"').append(key).append("\":").append(quote(entry));
            });
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            list.forEach(entry -> {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append(quote(entry));
            });
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static String quote(Object value) {
        return value instanceof String s ? '"' + s + '"' : render(value);
    }

    // ---------------------------------------------------------------------------------------

    private Object readValue() {
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> out = new LinkedHashMap<>();
        expect('{');
        skipSpace();
        if (peek() == '}') {
            cursor++;
            return out;
        }
        while (true) {
            skipSpace();
            String key = readString();
            skipSpace();
            expect(':');
            skipSpace();
            out.put(key, readValue());
            skipSpace();
            char c = next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw fail("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> out = new ArrayList<>();
        expect('[');
        skipSpace();
        if (peek() == ']') {
            cursor++;
            return out;
        }
        while (true) {
            skipSpace();
            out.add(readValue());
            skipSpace();
            char c = next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw fail("expected ',' or ']'");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(source.substring(cursor, cursor + 4), 16));
                    cursor += 4;
                }
                default -> throw fail("unknown escape '\\" + escape + "'");
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!source.startsWith(literal, cursor)) {
            throw fail("expected " + literal);
        }
        cursor += literal.length();
        return value;
    }

    private Double readNumber() {
        int start = cursor;
        while (cursor < source.length() && "+-.eE0123456789".indexOf(source.charAt(cursor)) >= 0) {
            cursor++;
        }
        try {
            return Double.valueOf(source.substring(start, cursor));
        } catch (NumberFormatException notANumber) {
            throw fail("not a number: '" + source.substring(start, cursor) + "'");
        }
    }

    private void skipSpace() {
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
    }

    private char peek() {
        if (cursor >= source.length()) {
            throw fail("the document ended early");
        }
        return source.charAt(cursor);
    }

    private char next() {
        char c = peek();
        cursor++;
        return c;
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw fail("expected '" + expected + "' but found '" + c + "'");
        }
    }

    private HarnessException fail(String what) {
        int from = Math.max(0, cursor - 40);
        int to = Math.min(source.length(), cursor + 40);
        return new HarnessException("cannot parse JSON at offset " + cursor + ": " + what
                + " — near: " + source.substring(from, to));
    }
}
