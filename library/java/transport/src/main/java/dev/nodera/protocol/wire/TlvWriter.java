package dev.nodera.protocol.wire;

import dev.nodera.core.Bytes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Writes a canonical TLV body — the infrastructure plane's encoding (Task 14 phase 3,
 * {@code Plan.7} §4.2).
 *
 * <pre>
 * body  = field*                        (ascending fieldId, each id at most once)
 * field = fieldId:u16 | wireType:u8 | len:u32 | value
 * </pre>
 *
 * <p><b>Why the length is there.</b> It is the whole point. A positional body cannot be extended,
 * because a reader that meets a field it does not know has no way to find the end of it and
 * therefore no way to find the start of the next one — which is why, before this, there was no such
 * thing as a compatible change. A per-field length makes an unknown field skippable, and that single
 * property is what lets two peers on different releases keep talking.
 *
 * <p><b>Canonical, despite being tolerant.</b> Field ids must ascend and may not repeat, and every
 * declared field is always written — never omitted because it happens to hold a default. One value
 * therefore has exactly one encoding, which is what keeps the decode-then-encode invariant that the
 * conformance harness asserts.
 *
 * <p>Thread-context: NOT thread-safe; one instance per encoding call.
 */
public final class TlvWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(128);
    private int lastId = -1;

    /**
     * Write one field header and value.
     *
     * @throws IllegalStateException if the id does not ascend — a duplicate or out-of-order id would
     *         give one message two valid spellings.
     */
    private TlvWriter field(int id, WireType type, byte[] value) {
        if (id <= lastId) {
            throw new IllegalStateException("TLV field ids must strictly ascend; wrote " + lastId
                    + " then " + id);
        }
        if (id < 0 || id > 0xFFFF) {
            throw new IllegalArgumentException("field id must be a u16, got " + id);
        }
        lastId = id;
        out.write((id >>> 8) & 0xFF);
        out.write(id & 0xFF);
        out.write(type.code() & 0xFF);
        writeU32Raw(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    private void writeU32Raw(long v) {
        out.write((int) ((v >>> 24) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }

    /** Write an unsigned 8-bit field. */
    public TlvWriter u8(int id, int value) {
        return field(id, WireType.U8, new byte[] {(byte) value});
    }

    /** Write an unsigned 16-bit field, big-endian. */
    public TlvWriter u16(int id, int value) {
        return field(id, WireType.U16, new byte[] {(byte) (value >>> 8), (byte) value});
    }

    /** Write an unsigned 32-bit field, big-endian. */
    public TlvWriter u32(int id, long value) {
        return field(id, WireType.U32, new byte[] {
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }

    /** Write an unsigned 64-bit field, big-endian. */
    public TlvWriter u64(int id, long value) {
        byte[] raw = new byte[8];
        for (int i = 0; i < 8; i++) {
            raw[i] = (byte) (value >>> (56 - 8 * i));
        }
        return field(id, WireType.U64, raw);
    }

    /** Write an {@code int} that is semantically signed but always non-negative on this wire. */
    public TlvWriter i32(int id, int value) {
        return u32(id, Integer.toUnsignedLong(value));
    }

    /** Write a boolean as exactly 0 or 1. */
    public TlvWriter bool(int id, boolean value) {
        return field(id, WireType.BOOL, new byte[] {(byte) (value ? 1 : 0)});
    }

    /** Write raw bytes. */
    public TlvWriter bytes(int id, byte[] value) {
        return field(id, WireType.BYTES, value.clone());
    }

    /** Write raw bytes. */
    public TlvWriter bytes(int id, Bytes value) {
        return field(id, WireType.BYTES, value.toArray());
    }

    /** Write UTF-8 text. */
    public TlvWriter string(int id, String value) {
        if (value == null) {
            throw new IllegalArgumentException("field " + id + ": null string");
        }
        return field(id, WireType.STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    /** Write a UUID as two big-endian u64 halves inside one 16-byte field. */
    public TlvWriter uuid(int id, UUID value) {
        byte[] raw = new byte[16];
        long msb = value.getMostSignificantBits();
        long lsb = value.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            raw[i] = (byte) (msb >>> (56 - 8 * i));
            raw[8 + i] = (byte) (lsb >>> (56 - 8 * i));
        }
        return field(id, WireType.BYTES, raw);
    }

    /**
     * Write a nested TLV body. The nested body follows the same grammar, so a structure inside a
     * message is as extensible as the message itself — which is what makes appending a field to
     * {@code NodeCapabilities} or {@code PeerEntry} a non-event rather than a network break.
     */
    public TlvWriter nested(int id, Consumer<TlvWriter> body) {
        TlvWriter inner = new TlvWriter();
        body.accept(inner);
        return field(id, WireType.NESTED, inner.toByteArray());
    }

    /**
     * Write a list: {@code count:u32} then each element as {@code len:u32 | elementBytes}.
     *
     * <p>Each element carries its own length for the same reason each field does: an element that
     * grows a component must not shift the elements after it out of alignment.
     */
    public <T> TlvWriter list(int id, Collection<T> items, BiConsumer<TlvWriter, T> element) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(64);
        int count = items == null ? 0 : items.size();
        body.write((count >>> 24) & 0xFF);
        body.write((count >>> 16) & 0xFF);
        body.write((count >>> 8) & 0xFF);
        body.write(count & 0xFF);
        if (items != null) {
            for (T item : items) {
                TlvWriter inner = new TlvWriter();
                element.accept(inner, item);
                byte[] raw = inner.toByteArray();
                body.write((raw.length >>> 24) & 0xFF);
                body.write((raw.length >>> 16) & 0xFF);
                body.write((raw.length >>> 8) & 0xFF);
                body.write(raw.length & 0xFF);
                body.write(raw, 0, raw.length);
            }
        }
        return field(id, WireType.LIST, body.toByteArray());
    }

    /**
     * Write a strictly ascending list of unsigned 32-bit values, packed four bytes each.
     *
     * <p>Piece-index lists run to thousands of entries, and the general list encoding spends eleven
     * bytes on each one to buy an extensibility a {@code u32} will never need. Packing keeps them at
     * four. The ascending requirement is not an optimisation: the same list in two orders would be
     * two byte strings meaning one value.
     *
     * @throws IllegalArgumentException if the values are not strictly ascending.
     */
    public TlvWriter u32Array(int id, Collection<Integer> values) {
        byte[] raw = new byte[values.size() * 4];
        int at = 0;
        long previous = -1;
        for (int v : values) {
            long unsigned = Integer.toUnsignedLong(v);
            if (unsigned <= previous) {
                throw new IllegalArgumentException("field " + id + ": u32 arrays must be strictly "
                        + "ascending; got " + previous + " then " + unsigned);
            }
            previous = unsigned;
            raw[at++] = (byte) (unsigned >>> 24);
            raw[at++] = (byte) (unsigned >>> 16);
            raw[at++] = (byte) (unsigned >>> 8);
            raw[at++] = (byte) unsigned;
        }
        return field(id, WireType.BYTES, raw);
    }

    /**
     * Re-emit a field this build did not recognise, preserving the bytes exactly.
     *
     * <p>Used when forwarding: a peer in the middle of a version spread must not silently strip the
     * fields the peers on either side of it depend on.
     */
    public TlvWriter raw(TlvField preserved) {
        return field(preserved.id(), preserved.type(), preserved.value().toArray());
    }

    /** The encoded body. */
    public byte[] toByteArray() {
        return out.toByteArray();
    }

    /** The encoded body as immutable {@link Bytes}. */
    public Bytes toBytes() {
        return Bytes.unsafeWrap(out.toByteArray());
    }

    /** Number of bytes written so far. */
    public int size() {
        return out.size();
    }
}
