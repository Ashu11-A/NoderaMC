package dev.nodera.core.crypto;

import dev.nodera.core.Bytes;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.function.BiConsumer;

/**
 * The low-level writer for Nodera's canonical binary encoding (Task 2). Big-endian, fixed-width
 * integers, no varints. This is the ONE place that knows how scalars are serialised; every
 * {@link Encodable} composes these primitives, so changing a method here changes every hash and
 * signature on the network.
 *
 * <p>Format reference (documented also in {@link Encodable}):
 * <ul>
 *   <li>u8 / u16 / u32 / u64: big-endian fixed width.</li>
 *   <li>bytes: u32 length + raw bytes.</li>
 *   <li>String: u32 byte-length + UTF-8 bytes.</li>
 *   <li>boolean / optional presence: u8 (0 or 1).</li>
 *   <li>list: u32 count + elements (sets/maps MUST be sorted by documented key BEFORE writing).</li>
 * </ul>
 *
 * <p>Thread-context: NOT thread-safe; one instance per encoding call.
 */
public final class CanonicalWriter {

    private final ByteArrayOutputStream baos;
    private final DataOutputStream out;

    public CanonicalWriter() {
        this(256);
    }

    public CanonicalWriter(int initialSize) {
        this.baos = new ByteArrayOutputStream(initialSize);
        this.out = new DataOutputStream(baos);
    }

    // --- scalars ---

    public CanonicalWriter writeU8(int v) {
        write(0xFF & v);
        return this;
    }

    public CanonicalWriter writeU16(int v) {
        try {
            out.writeShort(0xFFFF & v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    /** Writes the low 32 bits of {@code v}, big-endian. */
    public CanonicalWriter writeU32(long v) {
        try {
            out.writeInt((int) v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public CanonicalWriter writeU64(long v) {
        try {
            out.writeLong(v);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public CanonicalWriter writeBoolean(boolean v) {
        writeU8(v ? 1 : 0);
        return this;
    }

    // --- variable-length ---

    public CanonicalWriter writeBytes(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("null bytes; use writeOptional for absent values");
        }
        writeU32(bytes.length);
        write(bytes);
        return this;
    }

    public CanonicalWriter writeBytes(Bytes bytes) {
        return writeBytes(bytes == null ? null : adopt(bytes));
    }

    /** Write a raw byte array WITHOUT a length prefix (caller knows the length). */
    public CanonicalWriter writeRaw(byte[] bytes) {
        write(bytes);
        return this;
    }

    public CanonicalWriter writeString(String s) {
        if (s == null) {
            throw new IllegalArgumentException("null string; use writeOptional for absent values");
        }
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        writeU32(u.length);
        write(u);
        return this;
    }

    // --- optionals ---

    /** Writes a u8 presence marker. Caller writes the payload only when present. */
    public CanonicalWriter writeOptional(Object value) {
        return writeBoolean(value != null);
    }

    // --- collections ---

    /**
     * Write a list: u32 count + each element via {@code elementWriter}. For SET/MAP semantics,
     * sort the source collection by the documented key before calling.
     */
    public <T> CanonicalWriter writeList(Collection<T> items, BiConsumer<CanonicalWriter, T> elementWriter) {
        writeU32(items == null ? 0 : items.size());
        if (items != null) {
            for (T item : items) {
                elementWriter.accept(this, item);
            }
        }
        return this;
    }

    /** Write an {@link Encodable} value (no extra framing — the value owns its typeTag). */
    public CanonicalWriter writeEncodable(Encodable value) {
        value.encode(this);
        return this;
    }

    // --- lifecycle ---

    private void write(byte[] bytes) {
        try {
            out.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(int b) {
        try {
            out.write(b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] adopt(Bytes bytes) {
        // Encode from Bytes without an intermediate copy where possible: Bytes.toArray copies.
        return bytes.toArray();
    }

    /** Number of bytes written so far. */
    public int size() {
        return baos.size();
    }

    /** Returns a fresh defensive copy of the encoded bytes. */
    public byte[] toByteArray() {
        return baos.toByteArray();
    }

    /** Returns the encoded bytes wrapped as an immutable {@link Bytes} (defensive copy retained). */
    public Bytes toBytes() {
        return Bytes.unsafeWrap(baos.toByteArray());
    }

    /** Reset the writer for reuse (discards current contents). */
    public void reset() {
        baos.reset();
    }
}
