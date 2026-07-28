package dev.nodera.protocol.wire;

import dev.nodera.core.Bytes;

/**
 * The outer wire frame (Task 14 phase 3, {@code Plan.7} §4.1).
 *
 * <pre>
 * magic:u32 'NDR2' | epoch:u16 | kind:u16 | flags:u16 | correlationId:u64 | len:u32 | body
 * </pre>
 *
 * <p><b>magic + epoch.</b> The previous frame started straight in on a {@code u16} tag, so a peer
 * from a different generation did not fail — it <em>misparsed</em>, and reported whatever the
 * misparse happened to produce. A magic number and an explicit epoch turn that into one readable
 * error at the first byte. The epoch is expected to stay at 2: TLV absorbs additive change, and a
 * change TLV cannot absorb is a new kind rather than a new epoch ({@code Plan.7} D3).
 *
 * <p><b>len.</b> The body is skippable without being understood, which is what makes "an unknown
 * kind is discarded and answered" implementable at all ({@code Plan.7} D4). Under the old frame an
 * unrecognised tag left the reader with no way to find the end of the message, so the only safe
 * response was to drop the connection — or, in practice, to drop the frame silently and let the
 * peer time out.
 *
 * @param epoch         the wire generation; {@link WireRegistry#WIRE_EPOCH} for this build.
 * @param kind          the message kind ({@link WireRegistry}).
 * @param flags         request / response / event bits ({@link FrameFlags}).
 * @param correlationId 0 for events; echoed by a response onto the request that asked for it.
 * @param body          the encoded body — canonical TLV on the infrastructure plane, and a TLV
 *                      envelope around opaque canonical bytes on the consensus plane.
 * @Thread-context immutable; any thread.
 */
public record NoderaFrame(int epoch, int kind, int flags, long correlationId, Bytes body) {

    /** {@code 'N' 'D' 'R' '2'} — the first four bytes of every frame. */
    public static final int MAGIC = 0x4E445232;

    /** Bytes before the body: magic(4) + epoch(2) + kind(2) + flags(2) + correlation(8) + len(4). */
    public static final int HEADER_BYTES = 22;

    public NoderaFrame {
        if (epoch < 0 || epoch > 0xFFFF) {
            throw new IllegalArgumentException("epoch must be a u16, got " + epoch);
        }
        if (kind < 0 || kind > 0xFFFF) {
            throw new IllegalArgumentException("kind must be a u16, got " + kind);
        }
        if (flags < 0 || flags > 0xFFFF) {
            throw new IllegalArgumentException("flags must be a u16, got " + flags);
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
    }

    /**
     * Encode the frame.
     *
     * @return the complete frame bytes.
     * @Thread-context any thread.
     */
    public byte[] encode() {
        byte[] payload = body.toArray();
        byte[] out = new byte[HEADER_BYTES + payload.length];
        putU32(out, 0, MAGIC);
        putU16(out, 4, epoch);
        putU16(out, 6, kind);
        putU16(out, 8, flags);
        putU64(out, 10, correlationId);
        putU32(out, 18, payload.length);
        System.arraycopy(payload, 0, out, HEADER_BYTES, payload.length);
        return out;
    }

    /**
     * Decode a frame.
     *
     * @param raw the complete frame bytes.
     * @return the parsed frame.
     * @throws IllegalStateException if the magic, epoch, or declared length does not hold. The magic
     *         is checked before anything else so a frame from the previous wire generation — or from
     *         something that is not Nodera at all — produces one clear message rather than a
     *         cascade of nonsense field values.
     * @Thread-context any thread.
     */
    public static NoderaFrame decode(byte[] raw) {
        if (raw == null) {
            throw new IllegalArgumentException("frame must not be null");
        }
        if (raw.length < HEADER_BYTES) {
            throw new IllegalStateException("frame is " + raw.length + " byte(s); a NDR2 header is "
                    + HEADER_BYTES);
        }
        int magic = (int) getU32(raw, 0);
        if (magic != MAGIC) {
            throw new IllegalStateException("not a Nodera frame: expected magic NDR2 (0x"
                    + Integer.toHexString(MAGIC) + "), got 0x" + Integer.toHexString(magic)
                    + ". A peer speaking the pre-NDR2 wire looks exactly like this.");
        }
        int epoch = getU16(raw, 4);
        if (epoch != WireRegistry.WIRE_EPOCH) {
            throw new IllegalStateException("wire epoch " + epoch + " is not this build's epoch "
                    + WireRegistry.WIRE_EPOCH + "; the frame grammar itself differs, so nothing "
                    + "below this point can be trusted");
        }
        int kind = getU16(raw, 6);
        int flags = getU16(raw, 8);
        long correlationId = getU64(raw, 10);
        long declared = getU32(raw, 18);
        long available = raw.length - (long) HEADER_BYTES;
        if (declared != available) {
            throw new IllegalStateException("frame declares a " + declared + "-byte body but "
                    + available + " byte(s) follow the header");
        }
        byte[] body = new byte[(int) declared];
        System.arraycopy(raw, HEADER_BYTES, body, 0, (int) declared);
        return new NoderaFrame(epoch, kind, flags, correlationId, Bytes.unsafeWrap(body));
    }

    /**
     * Read only the kind out of a frame, without validating or copying the body.
     *
     * <p>Used by a router deciding whether it knows a kind before it commits to decoding one.
     *
     * @param raw the frame bytes.
     * @return the kind.
     * @Thread-context any thread.
     */
    public static int peekKind(byte[] raw) {
        if (raw == null || raw.length < HEADER_BYTES) {
            throw new IllegalStateException("frame is too short to carry a kind");
        }
        if ((int) getU32(raw, 0) != MAGIC) {
            throw new IllegalStateException("not a Nodera frame: bad magic");
        }
        return getU16(raw, 6);
    }

    private static void putU16(byte[] out, int at, int v) {
        out[at] = (byte) (v >>> 8);
        out[at + 1] = (byte) v;
    }

    private static void putU32(byte[] out, int at, long v) {
        out[at] = (byte) (v >>> 24);
        out[at + 1] = (byte) (v >>> 16);
        out[at + 2] = (byte) (v >>> 8);
        out[at + 3] = (byte) v;
    }

    private static void putU64(byte[] out, int at, long v) {
        for (int i = 0; i < 8; i++) {
            out[at + i] = (byte) (v >>> (56 - 8 * i));
        }
    }

    private static int getU16(byte[] raw, int at) {
        return ((raw[at] & 0xFF) << 8) | (raw[at + 1] & 0xFF);
    }

    private static long getU32(byte[] raw, int at) {
        return ((long) (raw[at] & 0xFF) << 24) | ((long) (raw[at + 1] & 0xFF) << 16)
                | ((long) (raw[at + 2] & 0xFF) << 8) | (raw[at + 3] & 0xFF);
    }

    private static long getU64(byte[] raw, int at) {
        long out = 0;
        for (int i = 0; i < 8; i++) {
            out = (out << 8) | (raw[at + i] & 0xFF);
        }
        return out;
    }
}
