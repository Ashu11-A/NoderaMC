package dev.nodera.core.crypto;

import dev.nodera.core.Bytes;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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

    /**
     * Read a u32 whose domain is a non-negative Java {@code int}. A wire value with the high bit
     * set would silently wrap negative under a bare {@code (int) readU32()} cast and flow into
     * loop bounds, array sizes, or quorum arithmetic; this helper rejects it at the decode
     * boundary instead. Fields that legitimately round-trip negative ints through
     * {@code writeU32(Integer.toUnsignedLong(x))} (coordinates, {@code -1} sentinels) must keep
     * the cast and NOT use this helper.
     *
     * @throws IllegalStateException if the u32 exceeds {@link Integer#MAX_VALUE}.
     */
    public int readU32AsInt() {
        long value = readU32();
        if (value > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "canonical u32 value " + value + " exceeds Integer.MAX_VALUE for a non-negative int field");
        }
        return (int) value;
    }

    public long readU64() {
        try {
            return in.readLong();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Read a canonical boolean: exactly {@code 0} or {@code 1}.
     *
     * <p>Any other byte is rejected. Accepting "nonzero means true" would give a single value two
     * hundred and fifty-six spellings on the wire, and the canonical contract is that a value has
     * exactly one — two peers that hashed two spellings of the same message would disagree about a
     * root while agreeing about the state. Rust's reader has always been strict here; this is the
     * side that was lenient.
     *
     * @return the decoded boolean.
     * @throws IllegalStateException if the marker byte is neither 0 nor 1.
     */
    public boolean readBoolean() {
        int marker = readU8();
        if (marker > 1) {
            throw new IllegalStateException(
                    "canonical boolean must be 0 or 1, got " + marker);
        }
        return marker == 1;
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

    /**
     * Read and validate the {@code u16} type tag that opens every {@link Encodable} frame.
     *
     * <p>This is the first half of the frame every {@code decode} used to open by hand:
     * {@code int tag = r.readU16(); if (tag != TypeTags.X) throw …}. Written out at every call
     * site it is five lines of which four are the same four lines, and a decoder that forgets the
     * guard accepts any type's bytes as its own. One method makes the guard unforgettable.
     *
     * <p>{@code typeName} is a parameter rather than a reverse lookup on {@link TypeTags} on
     * purpose: the thrown message is part of the observed behaviour of these decoders, and the
     * names in it are not all the constant's name ({@code ServiceScore}, {@code SignedPeerRecord}
     * and {@code PeerCandidate} spell theirs in camel case). A reverse lookup would silently
     * reword three dozen exceptions.
     *
     * @param expectedTag the {@link TypeTags} constant this decoder answers to.
     * @param typeName    the name to put in the failure message.
     * @return the tag read (always {@code == expectedTag} on success).
     * @throws IllegalStateException if the frame opens with a different type's tag.
     */
    public int expectFrame(int expectedTag, String typeName) {
        int tag = readU16();
        if (tag != expectedTag) {
            throw new IllegalStateException("expected " + typeName + " tag, got " + tag);
        }
        return tag;
    }

    /**
     * Read a whole {@code typeTag + version} frame: {@link #expectFrame(int, String)} followed by
     * {@link #readVersion(int)}.
     *
     * <p>Types whose body version is a <i>range</i> rather than a single accepted value (the
     * tolerant readers in {@code RegionSnapshot}, {@code RegionDelta}, {@code SignedVote},
     * {@code ServerAuthorityCertificate} and {@code ChunkColumnState}) must use the two-argument
     * overload and keep their own version check — collapsing those here would turn a tolerated
     * older body into a decode failure.
     *
     * @param expectedTag     the {@link TypeTags} constant this decoder answers to.
     * @param typeName        the name to put in the tag failure message.
     * @param expectedVersion the single body version this decoder accepts.
     * @return the version read (always {@code == expectedVersion} on success).
     */
    public int expectFrame(int expectedTag, String typeName, int expectedVersion) {
        expectFrame(expectedTag, typeName);
        return readVersion(expectedVersion);
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

    /**
     * Read a length-prefixed UTF-8 string, rejecting malformed input.
     *
     * <p>{@code new String(bytes, UTF_8)} substitutes U+FFFD for anything it cannot decode, which
     * makes malformed bytes decode "successfully" into a value that re-encodes to <i>different</i>
     * bytes — a second wire spelling of the same message, and a hash divergence against Rust, whose
     * reader rejects malformed UTF-8 outright. Strict decoding here costs nothing for valid frames:
     * every string this codec emits is well-formed by construction.
     *
     * @return the decoded string.
     * @throws IllegalStateException if the bytes are not well-formed UTF-8.
     */
    public String readString() {
        byte[] u = readBytes();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(u))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("canonical string is not well-formed UTF-8", e);
        }
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
