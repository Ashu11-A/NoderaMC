package dev.nodera.core.crypto;

import dev.nodera.core.Bytes;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Symmetric reader for {@link CanonicalWriter}. Decodes the exact same big-endian fixed-width
 * format. Used by golden-file round-trip tests and (later) by protocol decode paths.
 *
 * <p>Thread-context: NOT thread-safe; one instance per decode call.
 */
public final class CanonicalReader {

    private final DataInputStream in;

    public CanonicalReader(byte[] data) {
        this.in = new DataInputStream(new ByteArrayInputStream(data));
    }

    public CanonicalReader(Bytes data) {
        this(data.toArray());
    }

    public int readU8() {
        try {
            int b = in.read();
            if (b < 0) {
                throw new IllegalStateException("unexpected end of canonical input (u8)");
            }
            return b;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int readU16() {
        try {
            return in.readUnsignedShort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long readU32() {
        try {
            return in.readInt() & 0xFFFFFFFFL;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long readU64() {
        try {
            return in.readLong();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean readBoolean() {
        return readU8() != 0;
    }

    /**
     * Read the {@code u16} ENCODING_VERSION frame field and validate it. Every canonical decoder
     * reads {@code tag} then version; this centralises the version check so the frozen wire
     * contract's "version-aware" guarantee actually fires on every type (a future
     * network-breaking bump is rejected loudly instead of being silently discarded).
     *
     * @param expected the {@link Encodable#ENCODING_VERSION} the caller was compiled against.
     * @return the version read (always {@code == expected} on success).
     * @throws IllegalStateException if the encoded version differs from {@code expected}.
     */
    public int readVersion(int expected) {
        int version = readU16();
        if (version != expected) {
            throw new IllegalStateException(
                    "unsupported canonical encoding version " + version + " (expected " + expected + ")");
        }
        return version;
    }

    /** Read a u8 presence marker. */
    public boolean readOptional() {
        return readBoolean();
    }

    public byte[] readBytes() {
        try {
            long lenL = readU32();
            int len = Math.toIntExact(lenL);
            // Bound the length against the bytes actually present BEFORE allocating. A u32 length
            // prefix is attacker-controlled on the wire; without this check a 4-byte prefix can
            // force a ~2 GiB allocation (new byte[0x7FFFFFFF]) before readFully detects EOF — a
            // memory-amplification DoS reachable pre-auth via any variable-length field.
            int remaining = available();
            if (len < 0 || len > remaining) {
                throw new IllegalStateException(
                        "canonical length prefix " + len + " exceeds remaining " + remaining + " bytes");
            }
            byte[] data = new byte[len];
            in.readFully(data);
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Bytes readBytesValue() {
        return Bytes.unsafeWrap(readBytes());
    }

    public String readString() {
        byte[] u = readBytes();
        return new String(u, StandardCharsets.UTF_8);
    }

    /** Read a list: u32 count + each element decoded by {@code elementReader}. */
    public <T> List<T> readList(Function<CanonicalReader, T> elementReader) {
        long countL = readU32();
        int count = Math.toIntExact(countL);
        // Every encoded element is at least one byte, so a count larger than the remaining frame
        // cannot be legitimate. Bound it before allocating the backing array (attacker-controlled
        // u32 count could otherwise request new ArrayList[0x7FFFFFFF]).
        int remaining = available();
        if (count < 0 || count > remaining) {
            throw new IllegalStateException(
                    "canonical list count " + count + " exceeds remaining " + remaining + " bytes");
        }
        List<T> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(elementReader.apply(this));
        }
        return result;
    }

    /** Bytes remaining without blocking (best-effort). */
    public int available() {
        try {
            return in.available();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
