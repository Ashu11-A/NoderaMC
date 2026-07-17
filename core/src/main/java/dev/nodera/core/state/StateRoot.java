package dev.nodera.core.state;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.TypeTags;

import java.util.Arrays;

/**
 * A 32-byte SHA-256 state root (Plan §3.7 / Task 2). The root is <b>truth</b> — the delta is only
 * transport. Two replicas with the same root hold equivalent state, by construction.
 *
 * <p>Value semantics via the underlying {@link Bytes}; hex {@link #toString()} for log lines.
 *
 * <p>Thread-context: immutable, any thread.
 */
public record StateRoot(Bytes hash) implements Encodable {

    public StateRoot {
        if (hash == null) {
            throw new IllegalArgumentException("hash must not be null");
        }
        if (hash.length() != NoderaConstants.STATE_ROOT_BYTES) {
            throw new IllegalArgumentException(
                    "StateRoot must be " + NoderaConstants.STATE_ROOT_BYTES
                            + " bytes, got " + hash.length());
        }
    }

    /** All-zero root for an empty region (pre-hash placeholder; never a real committed root). */
    public static StateRoot zero() {
        return new StateRoot(new Bytes(new byte[NoderaConstants.STATE_ROOT_BYTES]));
    }

    /** Convenience: wrap already-computed SHA-256 bytes as a root. */
    public static StateRoot of(Bytes sha256Hash) {
        return new StateRoot(sha256Hash);
    }

    public String toHex() {
        return hash.toHex();
    }

    /** Short prefix for log lines. */
    public String toShortHex(int bytes) {
        return hash.toShortHex(bytes);
    }

    @Override
    public String toString() {
        return "root:" + hash.toShortHex(6);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.STATE_ROOT).writeU16(ENCODING_VERSION);
        w.writeBytes(hash);
    }

    public static StateRoot decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.STATE_ROOT) {
            throw new IllegalStateException("expected STATE_ROOT tag, got " + tag);
        }
        r.readU16(); // version
        return new StateRoot(r.readBytesValue());
    }
}
