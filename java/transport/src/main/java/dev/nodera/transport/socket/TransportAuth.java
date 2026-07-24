package dev.nodera.transport.socket;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.transport.TransportException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Pure codec + crypto helpers for the authenticated socket handshake (issue #41 / L-53).
 *
 * <p>The legacy hello ({@code [16-byte NodeId][u16 routeLen][route]}) attributes a connection to
 * a claimed {@link NodeId} with no proof — a NodeId is a random UUID unrelated to the Ed25519
 * key, so any TCP client can claim any identity. In authenticated mode each side sends a fresh
 * 32-byte challenge as its first frame and replies to the remote's challenge with an
 * <i>authenticated hello</i> whose Ed25519 signature covers
 * {@code challenge ‖ nodeId ‖ route ‖ publicKey} — proving possession of the private key behind
 * the claimed identity for THIS connection (fresh challenge ⇒ no replay).
 *
 * <h2>Frames</h2>
 * <ul>
 *   <li>challenge: {@code [0xA7][32 random bytes]} (33 bytes exactly)</li>
 *   <li>hello: {@code [0xA8][16-byte NodeId][u16 routeLen][route][u16 pkLen][pk][u16 sigLen][sig]}</li>
 * </ul>
 *
 * <p>All methods are static and side-effect free (the {@link SecureRandom} draw excepted); every
 * malformed or unverifiable input throws {@link TransportException} — callers fail closed.
 */
final class TransportAuth {

    static final byte CHALLENGE_MAGIC = (byte) 0xA7;
    static final byte HELLO_MAGIC = (byte) 0xA8;
    static final int CHALLENGE_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final SignatureService SIGNATURES = new SignatureService();

    private TransportAuth() {
    }

    /** A verified remote hello: the connection's proven (nodeId, publicKey) attribution. */
    record VerifiedHello(NodeId nodeId, String route, Bytes publicKey) {
    }

    /** @return a fresh challenge frame: {@code [0xA7][32 random bytes]}. */
    static byte[] newChallengeFrame() {
        byte[] frame = new byte[1 + CHALLENGE_BYTES];
        frame[0] = CHALLENGE_MAGIC;
        byte[] nonce = new byte[CHALLENGE_BYTES];
        RANDOM.nextBytes(nonce);
        System.arraycopy(nonce, 0, frame, 1, CHALLENGE_BYTES);
        return frame;
    }

    /**
     * Parse a received challenge frame.
     *
     * @return the 32-byte challenge.
     * @throws TransportException if the frame is not a well-formed challenge (e.g. a legacy
     *                            unauthenticated hello — authenticated transports refuse legacy
     *                            peers by design).
     */
    static byte[] parseChallenge(byte[] frame) {
        if (frame.length != 1 + CHALLENGE_BYTES || frame[0] != CHALLENGE_MAGIC) {
            throw new TransportException(
                    "peer did not open with an auth challenge (legacy/unknown handshake refused)");
        }
        byte[] challenge = new byte[CHALLENGE_BYTES];
        System.arraycopy(frame, 1, challenge, 0, CHALLENGE_BYTES);
        return challenge;
    }

    /**
     * Build the authenticated hello answering {@code remoteChallenge}: identity's nodeId + this
     * transport's advertised route + public key + an Ed25519 signature over
     * {@code remoteChallenge ‖ nodeId ‖ route ‖ publicKey}.
     */
    static byte[] encodeHello(NodeIdentity identity, String route, byte[] remoteChallenge) {
        byte[] routeBytes = route.getBytes(StandardCharsets.UTF_8);
        byte[] pk = identity.publicKeyBytes().toArray();
        byte[] signed = signedPortion(remoteChallenge, identity.nodeId(), routeBytes, pk);
        byte[] sig = identity.sign(signed).toArray();

        byte[] frame = new byte[1 + 16 + 2 + routeBytes.length + 2 + pk.length + 2 + sig.length];
        int o = 0;
        frame[o++] = HELLO_MAGIC;
        UUID id = identity.nodeId().value();
        putLong(frame, o, id.getMostSignificantBits());
        putLong(frame, o + 8, id.getLeastSignificantBits());
        o += 16;
        o = putBlock(frame, o, routeBytes);
        o = putBlock(frame, o, pk);
        putBlock(frame, o, sig);
        return frame;
    }

    /**
     * Verify a received authenticated hello against the challenge WE sent on this connection.
     *
     * @return the proven attribution.
     * @throws TransportException on any malformed field or a signature that does not verify
     *                            against the carried public key.
     */
    static VerifiedHello verifyHello(byte[] frame, byte[] localChallenge) {
        if (frame.length < 1 + 16 + 2 || frame[0] != HELLO_MAGIC) {
            throw new TransportException("malformed auth hello");
        }
        int o = 1;
        long msb = getLong(frame, o);
        long lsb = getLong(frame, o + 8);
        o += 16;
        byte[] routeBytes = readBlock(frame, o);
        o += 2 + routeBytes.length;
        byte[] pk = readBlock(frame, o);
        o += 2 + pk.length;
        byte[] sig = readBlock(frame, o);
        o += 2 + sig.length;
        if (o != frame.length) {
            throw new TransportException("malformed auth hello: trailing bytes");
        }
        NodeId nodeId = new NodeId(new UUID(msb, lsb));
        byte[] signed = signedPortion(localChallenge, nodeId, routeBytes, pk);
        if (!SIGNATURES.verify(pk, signed, sig)) {
            throw new TransportException("auth hello signature does not verify (key possession not proven)");
        }
        return new VerifiedHello(nodeId, new String(routeBytes, StandardCharsets.UTF_8),
                new Bytes(pk));
    }

    /** The signed portion: {@code challenge ‖ nodeId(16) ‖ route ‖ publicKey}. */
    private static byte[] signedPortion(byte[] challenge, NodeId nodeId, byte[] routeBytes, byte[] pk) {
        byte[] data = new byte[challenge.length + 16 + routeBytes.length + pk.length];
        int o = 0;
        System.arraycopy(challenge, 0, data, o, challenge.length);
        o += challenge.length;
        UUID id = nodeId.value();
        putLong(data, o, id.getMostSignificantBits());
        putLong(data, o + 8, id.getLeastSignificantBits());
        o += 16;
        System.arraycopy(routeBytes, 0, data, o, routeBytes.length);
        o += routeBytes.length;
        System.arraycopy(pk, 0, data, o, pk.length);
        return data;
    }

    private static int putBlock(byte[] dest, int offset, byte[] block) {
        if (block.length > 0xFFFF) {
            throw new TransportException("auth hello field too large: " + block.length);
        }
        dest[offset] = (byte) ((block.length >>> 8) & 0xFF);
        dest[offset + 1] = (byte) (block.length & 0xFF);
        System.arraycopy(block, 0, dest, offset + 2, block.length);
        return offset + 2 + block.length;
    }

    private static byte[] readBlock(byte[] src, int offset) {
        if (offset + 2 > src.length) {
            throw new TransportException("malformed auth hello: truncated length");
        }
        int len = ((src[offset] & 0xFF) << 8) | (src[offset + 1] & 0xFF);
        if (offset + 2 + len > src.length) {
            throw new TransportException("malformed auth hello: truncated field");
        }
        byte[] block = new byte[len];
        System.arraycopy(src, offset + 2, block, 0, len);
        return block;
    }

    private static void putLong(byte[] a, int off, long v) {
        for (int i = 0; i < 8; i++) {
            a[off + i] = (byte) ((v >>> (56 - 8 * i)) & 0xFF);
        }
    }

    private static long getLong(byte[] a, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (a[off + i] & 0xFF);
        }
        return v;
    }
}
