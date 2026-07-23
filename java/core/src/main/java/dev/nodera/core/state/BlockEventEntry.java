package dev.nodera.core.state;

import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Objects;

/**
 * One pending block event inside a region's hashed state (Task 13 / L-26): the piston
 * two-phase move mechanism. A move that spans a batch boundary hashes identically on every
 * replica precisely because the in-flight phase is explicit state, not implicit scheduler
 * position.
 *
 * @param pos   the event's block position.
 * @param type  the event type (vanilla block-event id semantics for the palette block).
 * @param param the event parameter (e.g. piston direction).
 * @param phase the two-phase progress: 0 = start requested, 1 = finishing.
 * @Thread-context immutable, any thread.
 */
public record BlockEventEntry(NBlockPos pos, int type, int param, int phase)
        implements Encodable {

    public static final int ENCODING_VERSION = 1;

    public BlockEventEntry {
        Objects.requireNonNull(pos, "pos");
        if (phase != 0 && phase != 1) {
            throw new IllegalArgumentException("phase must be 0 or 1: " + phase);
        }
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.BLOCK_EVENT_ENTRY).writeU16(ENCODING_VERSION);
        pos.encode(w);
        w.writeU32(Integer.toUnsignedLong(type));
        w.writeU32(Integer.toUnsignedLong(param));
        w.writeU8(phase);
    }

    /** Full-frame decode (tag + version validated). */
    public static BlockEventEntry decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.BLOCK_EVENT_ENTRY) {
            throw new IllegalStateException("expected BLOCK_EVENT_ENTRY tag, got " + tag);
        }
        r.readVersion(ENCODING_VERSION);
        NBlockPos pos = NBlockPos.decode(r);
        int type = r.readU32AsInt();
        int param = r.readU32AsInt();
        int phase = r.readU8();
        return new BlockEventEntry(pos, type, param, phase);
    }
}
