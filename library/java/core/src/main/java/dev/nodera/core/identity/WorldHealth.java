package dev.nodera.core.identity;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

/**
 * How healthy a torrent-hosted <b>world</b> is (Task 20). Reported by the tracker and rendered by
 * the multiplayer UI (Task 26) as the world's colour.
 *
 * <p>This is deliberately <b>not</b> the session {@code Health} enum from the diagnostics HUD
 * (Task 18). That one describes <i>this peer's</i> link quality and maps DEGRADED to yellow; this
 * one describes <i>a world's</i> survivability across the swarm and maps DEGRADED to red, because
 * a world with too few seeders is a data-loss risk rather than a comfort problem.
 *
 * <ul>
 *   <li>{@code HEALTHY} (green) — enough seeders to sustain the replication factor.</li>
 *   <li>{@code DEGRADED} (red) — under-replicated; still playable, at risk.</li>
 *   <li>{@code DEAD} (gray) — zero seeders past the coordinated retention window (Task 22). A
 *       world with zero seeders is <i>not</i> DEAD immediately: it is DEGRADED with a countdown,
 *       so a host that reboots does not lose their world to a colour change.</li>
 * </ul>
 *
 * <p>It lives in {@code core} so both {@code protocol} (the wire) and the {@code diagnostics} view
 * model (the UI) can reference it without either depending on the other.
 *
 * <p>Ordinal order is a FROZEN wire contract — never reorder or insert; append only.
 *
 * <p>Thread-context: immutable, any thread.
 */
public enum WorldHealth implements Encodable {
    /** Enough seeders to sustain the replication factor. */
    HEALTHY,
    /** Under-replicated, or zero seeders inside the retention window. Rendered red. */
    DEGRADED,
    /** Zero seeders past the retention window; slated for drop. Rendered gray. */
    DEAD;

    /**
     * Encode as {@code tag(u16) + version(u16) + ordinal(u8)}.
     *
     * @param w the canonical sink.
     * @Thread-context any thread; does not retain the writer.
     */
    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.WORLD_HEALTH).writeU16(ENCODING_VERSION);
        w.writeU8(ordinal());
    }

    /**
     * Inverse of {@link #encode(CanonicalWriter)}.
     *
     * @param r the canonical source, positioned at the {@code WORLD_HEALTH} tag.
     * @return the decoded health.
     * @throws IllegalStateException if the tag, version, or ordinal is invalid.
     * @Thread-context not thread-safe; one reader per decode call.
     */
    public static WorldHealth decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.WORLD_HEALTH) {
            throw new IllegalStateException("expected WORLD_HEALTH tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        int ord = r.readU8();
        WorldHealth[] values = values();
        if (ord < 0 || ord >= values.length) {
            throw new IllegalStateException("invalid WorldHealth ordinal " + ord);
        }
        return values[ord];
    }
}
