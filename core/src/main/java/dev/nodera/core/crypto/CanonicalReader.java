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

    /** Read a u8 presence marker. */
    public boolean readOptional() {
        return readBoolean();
    }

    public byte[] readBytes() {
        try {
            long lenL = readU32();
            int len = Math.toIntExact(lenL);
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
