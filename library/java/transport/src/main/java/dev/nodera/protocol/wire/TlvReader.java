package dev.nodera.protocol.wire;

import dev.nodera.core.Bytes;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads a canonical TLV body (Task 14 phase 3, {@code Plan.7} §4.2).
 *
 * <p>The whole body is parsed into fields up front, and accessors then read by id. Two consequences
 * are the point of the design:
 *
 * <ul>
 *   <li><b>A field this build does not know is skipped, not fatal.</b> Its length says where it
 *       ends, so the fields after it are still found. It is also kept — see {@link #unknown()} — so
 *       a peer relaying between two newer peers does not quietly strip what it cannot read.</li>
 *   <li><b>A field a newer build added is simply absent</b> for an older reader, which takes the
 *       documented default. That is what makes appending a field a non-event.</li>
 * </ul>
 *
 * <p><b>Tolerant is not lax.</b> Ids must strictly ascend and may not repeat, fixed-width types must
 * carry their exact length, a boolean must be 0 or 1, and a string must be well-formed UTF-8. All
 * four were real defects on the old wire — accepting anything else gives one value several
 * spellings, which is how two implementations end up hashing the same message differently.
 *
 * <p>Thread-context: NOT thread-safe; one instance per decoding call.
 */
public final class TlvReader {

    private final Map<Integer, TlvField> fields = new LinkedHashMap<>();
    private final java.util.Set<Integer> consumed = new java.util.LinkedHashSet<>();
    private final Map<Integer, TlvField> verbatim = new LinkedHashMap<>();
    private final java.util.Set<Integer> absent = new java.util.TreeSet<>();

    /**
     * Parse a TLV body.
     *
     * @param body the body bytes.
     * @throws IllegalStateException if the body is malformed, out of order, or has duplicate ids.
     * @Thread-context any thread.
     */
    public TlvReader(byte[] body) {
        int pos = 0;
        int lastId = -1;
        while (pos < body.length) {
            if (body.length - pos < 7) {
                throw new IllegalStateException("truncated TLV field header: "
                        + (body.length - pos) + " byte(s) left, 7 needed");
            }
            int id = ((body[pos] & 0xFF) << 8) | (body[pos + 1] & 0xFF);
            int typeCode = body[pos + 2] & 0xFF;
            long len = ((long) (body[pos + 3] & 0xFF) << 24)
                    | ((long) (body[pos + 4] & 0xFF) << 16)
                    | ((long) (body[pos + 5] & 0xFF) << 8)
                    | (body[pos + 6] & 0xFF);
            pos += 7;
            if (len > body.length - pos) {
                throw new IllegalStateException("TLV field " + id + " claims " + len
                        + " byte(s) but only " + (body.length - pos) + " remain");
            }
            if (id <= lastId) {
                throw new IllegalStateException("TLV field ids must strictly ascend; saw " + lastId
                        + " then " + id);
            }
            lastId = id;
            byte[] value = new byte[(int) len];
            System.arraycopy(body, pos, value, 0, (int) len);
            pos += (int) len;
            // TlvField's own invariants reject a fixed-width type carrying the wrong length.
            fields.put(id, new TlvField(id, WireType.fromCode(typeCode), Bytes.unsafeWrap(value)));
        }
    }

    /** Parse a TLV body. */
    public TlvReader(Bytes body) {
        this(body.toArray());
    }

    private TlvField typed(int id, WireType expected) {
        // Reads are recorded so the overlay can say exactly how the received field set differed
        // from the one this build writes — in both directions.
        consumed.add(id);
        TlvField f = fields.get(id);
        if (f == null) {
            // An older peer that does not write this field yet. Recording the omission is what
            // stops a re-encode inventing a value on its behalf.
            absent.add(id);
            return null;
        }
        if (f.type() != expected) {
            throw new IllegalStateException("TLV field " + id + " is " + f.type()
                    + " but was read as " + expected + "; a field's type is part of the contract");
        }
        return f;
    }

    /** Read a u16, or {@code fallback} when the field is absent. */
    public int u16(int id, int fallback) {
        TlvField f = typed(id, WireType.U16);
        if (f == null) {
            return fallback;
        }
        byte[] v = f.value().toArray();
        return ((v[0] & 0xFF) << 8) | (v[1] & 0xFF);
    }

    /** Read a u32 into a long, or {@code fallback} when the field is absent. */
    public long u32(int id, long fallback) {
        TlvField f = typed(id, WireType.U32);
        if (f == null) {
            return fallback;
        }
        byte[] v = f.value().toArray();
        return ((long) (v[0] & 0xFF) << 24) | ((long) (v[1] & 0xFF) << 16)
                | ((long) (v[2] & 0xFF) << 8) | (v[3] & 0xFF);
    }

    /** Read a u32 as an {@code int} (the shape most call sites want), or {@code fallback}. */
    public int i32(int id, int fallback) {
        return (int) u32(id, Integer.toUnsignedLong(fallback));
    }

    /** Read a u64, or {@code fallback} when the field is absent. */
    public long u64(int id, long fallback) {
        TlvField f = typed(id, WireType.U64);
        if (f == null) {
            return fallback;
        }
        byte[] v = f.value().toArray();
        long out = 0;
        for (int i = 0; i < 8; i++) {
            out = (out << 8) | (v[i] & 0xFF);
        }
        return out;
    }

    /**
     * Read a boolean, or {@code fallback} when the field is absent.
     *
     * @throws IllegalStateException if the byte is neither 0 nor 1. One value must have one
     *         spelling; accepting "any nonzero byte" gave {@code true} 255 encodings, and the
     *         conformance fuzz found both implementations doing it.
     */
    public boolean bool(int id, boolean fallback) {
        TlvField f = typed(id, WireType.BOOL);
        if (f == null) {
            return fallback;
        }
        int marker = f.value().toArray()[0] & 0xFF;
        if (marker > 1) {
            throw new IllegalStateException("TLV field " + id
                    + ": a boolean must be 0 or 1, got " + marker);
        }
        return marker == 1;
    }

    /** Read raw bytes, or {@code fallback} when the field is absent. */
    public Bytes bytes(int id, Bytes fallback) {
        TlvField f = typed(id, WireType.BYTES);
        return f == null ? fallback : f.value();
    }

    /** Read raw bytes; the field is required. */
    public Bytes bytes(int id) {
        return require(typed(id, WireType.BYTES), id).value();
    }

    /**
     * Read UTF-8 text, or {@code fallback} when the field is absent.
     *
     * @throws IllegalStateException if the bytes are not well-formed UTF-8. Replacement-decoding
     *         them would re-encode as different bytes — which was a live divergence between the two
     *         implementations, because only one of them did it.
     */
    public String string(int id, String fallback) {
        TlvField f = typed(id, WireType.STRING);
        if (f == null) {
            return fallback;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(f.value().toArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("TLV field " + id + " is not well-formed UTF-8", e);
        }
    }

    /** Read a 16-byte UUID field, or {@code fallback} when absent. */
    public UUID uuid(int id, UUID fallback) {
        TlvField f = typed(id, WireType.BYTES);
        if (f == null) {
            return fallback;
        }
        byte[] v = f.value().toArray();
        if (v.length != 16) {
            throw new IllegalStateException("TLV field " + id + ": a UUID is 16 bytes, got "
                    + v.length);
        }
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (v[i] & 0xFF);
            lsb = (lsb << 8) | (v[8 + i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }

    /** Read a nested TLV body, or {@code fallback} when the field is absent. */
    public <T> T nested(int id, Function<TlvReader, T> body, T fallback) {
        TlvField f = typed(id, WireType.NESTED);
        if (f == null) {
            return fallback;
        }
        return readNested(f, body);
    }

    /** Read a nested TLV body; the field is required. */
    public <T> T nested(int id, Function<TlvReader, T> body) {
        return readNested(require(typed(id, WireType.NESTED), id), body);
    }

    /**
     * Run a nested decode, and remember the whole field verbatim if the nested body carried
     * something this build could not read.
     *
     * <p>Preservation has to work at every depth or it does not work at all: {@code NodeCapabilities}
     * and {@code PeerEntry} are exactly the structures a newer release grows, and they are always
     * nested inside something else. Remembering the enclosing field is the honest way to reproduce
     * them — this build cannot re-serialise a component it does not understand, so it re-emits the
     * bytes it was handed.
     */
    private <T> T readNested(TlvField field, Function<TlvReader, T> body) {
        TlvReader inner = new TlvReader(field.value());
        T value = body.apply(inner);
        if (!inner.unconsumed().isEmpty()) {
            verbatim.put(field.id(), field);
        }
        return value;
    }

    /**
     * Read a list. Each element is length-delimited, so an element that grew a component in a newer
     * release does not shift the elements after it.
     *
     * @return the decoded elements, or an empty list when the field is absent.
     */
    public <T> List<T> list(int id, Function<TlvReader, T> element) {
        TlvField f = typed(id, WireType.LIST);
        if (f == null) {
            return List.of();
        }
        byte[] body = f.value().toArray();
        if (body.length < 4) {
            throw new IllegalStateException("TLV list " + id + " is missing its count");
        }
        long count = ((long) (body[0] & 0xFF) << 24) | ((long) (body[1] & 0xFF) << 16)
                | ((long) (body[2] & 0xFF) << 8) | (body[3] & 0xFF);
        if (count > body.length) {
            throw new IllegalStateException("TLV list " + id + " claims " + count
                    + " elements but holds only " + body.length + " byte(s)");
        }
        List<T> out = new ArrayList<>((int) count);
        int pos = 4;
        for (long i = 0; i < count; i++) {
            if (body.length - pos < 4) {
                throw new IllegalStateException("TLV list " + id + " is truncated at element " + i);
            }
            long len = ((long) (body[pos] & 0xFF) << 24) | ((long) (body[pos + 1] & 0xFF) << 16)
                    | ((long) (body[pos + 2] & 0xFF) << 8) | (body[pos + 3] & 0xFF);
            pos += 4;
            if (len > body.length - pos) {
                throw new IllegalStateException("TLV list " + id + " element " + i + " claims "
                        + len + " byte(s) but only " + (body.length - pos) + " remain");
            }
            byte[] raw = new byte[(int) len];
            System.arraycopy(body, pos, raw, 0, (int) len);
            pos += (int) len;
            TlvReader element_ = new TlvReader(raw);
            out.add(element.apply(element_));
            if (!element_.unconsumed().isEmpty()) {
                // One element carrying an unreadable component makes the whole list unrepeatable
                // from the decoded value, so the original field is kept verbatim.
                verbatim.put(id, f);
            }
        }
        if (pos != body.length) {
            throw new IllegalStateException("TLV list " + id + " has " + (body.length - pos)
                    + " trailing byte(s) after its declared elements");
        }
        return List.copyOf(out);
    }

    /**
     * Read a packed ascending {@code u32} array.
     *
     * @return the values, or an empty list when the field is absent.
     * @throws IllegalStateException if the length is not a multiple of four, or the values are not
     *         strictly ascending. The order requirement is a canonicality rule, not a convenience:
     *         the encoder always emits ascending, so accepting anything else would give one value
     *         several encodings.
     * @Thread-context any thread.
     */
    public List<Integer> u32Array(int id) {
        TlvField f = typed(id, WireType.BYTES);
        if (f == null) {
            return List.of();
        }
        byte[] raw = f.value().toArray();
        if (raw.length % 4 != 0) {
            throw new IllegalStateException("TLV field " + id + ": a u32 array must be a multiple "
                    + "of 4 bytes, got " + raw.length);
        }
        List<Integer> out = new ArrayList<>(raw.length / 4);
        long previous = -1;
        for (int at = 0; at < raw.length; at += 4) {
            long v = ((long) (raw[at] & 0xFF) << 24) | ((long) (raw[at + 1] & 0xFF) << 16)
                    | ((long) (raw[at + 2] & 0xFF) << 8) | (raw[at + 3] & 0xFF);
            if (v <= previous) {
                throw new IllegalStateException("TLV field " + id + ": u32 arrays must be strictly "
                        + "ascending; got " + previous + " then " + v);
            }
            previous = v;
            out.add((int) v);
        }
        return List.copyOf(out);
    }

    private static TlvField require(TlvField f, int id) {
        if (f == null) {
            throw new IllegalStateException("required TLV field " + id + " is absent");
        }
        return f;
    }

    /**
     * The fields no accessor asked for — the ones this build does not know.
     *
     * <p>Handed back so they can be re-emitted verbatim. A peer that drops them turns itself into a
     * lossy relay between two peers that understand each other perfectly well, and makes
     * decode-then-encode produce different bytes from the ones it was given.
     *
     * @return the unread fields, ascending.
     * @Thread-context any thread; call after decoding.
     */
    public List<TlvField> unconsumed() {
        Map<Integer, TlvField> out = new java.util.TreeMap<>(verbatim);
        for (Map.Entry<Integer, TlvField> e : fields.entrySet()) {
            if (!consumed.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return List.copyOf(out.values());
    }

    /**
     * Declare that this build read a field <em>lossily</em>, so it must be re-emitted verbatim.
     *
     * <p>For values whose interpretation can drop something: an enum code from a later release, a
     * role this build has no name for. The decoded value is a usable approximation and the bytes are
     * the truth, so the bytes are what gets forwarded. Without this a peer would quietly rewrite a
     * newer peer's capabilities into the subset it happened to recognise.
     *
     * @param id the field that was approximated.
     * @Thread-context any thread; call during decoding.
     */
    public void markVerbatim(int id) {
        TlvField f = fields.get(id);
        if (f != null) {
            verbatim.put(id, f);
        }
    }

    /**
     * How the received field set differed from the one this build writes.
     *
     * @return the overlay; empty when the frame is exactly what this build would have produced.
     * @Thread-context any thread; call after decoding.
     */
    public TlvOverlay overlay() {
        return new TlvOverlay(unconsumed(), absent);
    }

    /** Every field, ascending — used by diagnostics and by the forward-compatibility tests. */
    public List<TlvField> unknown() {
        return List.copyOf(fields.values());
    }

    /** The number of fields present. */
    public int fieldCount() {
        return fields.size();
    }
}
