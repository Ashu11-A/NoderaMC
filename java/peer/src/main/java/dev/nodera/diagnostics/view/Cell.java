package dev.nodera.diagnostics.view;

import dev.nodera.diagnostics.state.Semantic;

import java.util.List;

/**
 * One coloured text fragment of a {@link Row} (Task 18), expressed as a <b>translation key plus
 * arguments</b> — never as assembled English (MC-GUI-5).
 *
 * <p>The view models in this package are Minecraft-free so they stay unit-testable on the gate,
 * which historically meant they emitted finished English strings and the GUI drew them verbatim: a
 * screen no lang file could ever translate. A cell now carries the key the renderer resolves
 * ({@code Component.translatable(key, args)}) and the typed arguments that fill its placeholders.
 * Values that are already data rather than prose — an endpoint, a player name, a count, a formatted
 * byte size — go through {@link #RAW}, whose lang entry is a bare {@code %s}.
 *
 * <p>The {@link Semantic} carries the colour <b>policy</b>; the renderer maps it to a concrete
 * colour in exactly one place ({@code dev.nodera.mod.debug.render.Palette}).
 *
 * @param key      the translation key to resolve.
 * @param args     the ordered arguments for the key's placeholders.
 * @param semantic the colour policy.
 * @param bold     whether to render bold.
 * @Thread-context immutable record, any thread.
 */
public record Cell(String key, List<Object> args, Semantic semantic, boolean bold) {

    /**
     * The pass-through key: {@code "%s"}. Used for cells whose whole content is data supplied by
     * the caller (ids, endpoints, names, numbers, formatted sizes) rather than prose.
     */
    public static final String RAW = "nodera.value";

    /** Compact constructor: key required, arguments copied into an immutable list. */
    public Cell {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** @return a neutral, non-bold data cell. */
    public static Cell raw(Object data) {
        return raw(data, Semantic.NEUTRAL);
    }

    /** @return a non-bold data cell with the given semantic. */
    public static Cell raw(Object data, Semantic semantic) {
        return new Cell(RAW, List.of(String.valueOf(data)), semantic, false);
    }

    /** @return a bold data cell with the given semantic. */
    public static Cell boldRaw(Object data, Semantic semantic) {
        return new Cell(RAW, List.of(String.valueOf(data)), semantic, true);
    }

    /** @return a non-bold translated cell. */
    public static Cell tr(String key, Semantic semantic, Object... args) {
        return new Cell(key, argList(args), semantic, false);
    }

    /** @return a bold translated cell. */
    public static Cell boldTr(String key, Semantic semantic, Object... args) {
        return new Cell(key, argList(args), semantic, true);
    }

    /** @return {@code true} when this cell is a pass-through data cell. */
    public boolean isRaw() {
        return RAW.equals(key);
    }

    private static List<Object> argList(Object... args) {
        if (args == null || args.length == 0) {
            return List.of();
        }
        return List.of((Object[]) args.clone());
    }
}
