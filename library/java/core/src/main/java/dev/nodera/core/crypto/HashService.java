package dev.nodera.core.crypto;

import dev.nodera.core.Bytes;
import dev.nodera.core.NoderaConstants;

import java.security.MessageDigest;

/**
 * SHA-256 hashing service — the consensus hash function (Plan §3.7 / Task 2). Every state root
 * and every hashed structure on the network flows through here.
 *
 * <p>{@link MessageDigest} is <b>NOT</b> thread-safe, so each thread obtains a thread-confined
 * instance via a {@link ThreadLocal}. The digest is reset before each use and {@code digest(byte[])}
 * both consumes the input and resets the instance to its initial state, so per-thread instances
 * are safely reusable across many calls. No external synchronization is required.
 *
 * <p>The algorithm name comes from {@link NoderaConstants#HASH_ALGORITHM} ("SHA-256"); every
 * hash in the system is exactly 32 bytes ({@link NoderaConstants#STATE_ROOT_BYTES}).
 *
 * <p>Thread-context: any thread; the {@link MessageDigest} is thread-confined via {@link ThreadLocal}.
 */
public final class HashService {

    private final ThreadLocal<MessageDigest> digest;

    /** Create a new service backed by a thread-confined SHA-256 {@link MessageDigest} per thread. */
    public HashService() {
        this.digest = ThreadLocal.withInitial(() -> {
            try {
                return MessageDigest.getInstance(NoderaConstants.HASH_ALGORITHM);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "hash algorithm unavailable: " + NoderaConstants.HASH_ALGORITHM, e);
            }
        });
    }

    /**
     * Hash the given bytes and return the result wrapped as an immutable {@link Bytes}. The
     * returned array is freshly allocated by the digest and adopted without an additional copy.
     *
     * <p><b>NOT a consensus hash entry point.</b> Raw-byte hashing exists for content/crypto
     * plumbing — challenge nonces, content ids, piece digests, archive digests. A hash that
     * participates in consensus MUST be produced by {@link #hash(Encodable)} so the input is a
     * canonical encoding; the only legitimate exception is hashing bytes that are <em>already</em>
     * the canonical encoding of an {@link Encodable} (e.g. a persisted canonical blob), which is
     * byte-identical to calling {@code hash} on the decoded value. Never feed ad-hoc,
     * non-canonical bytes into a value that flows into a state root or certificate.
     */
    public Bytes sha256(byte[] data) {
        return Bytes.unsafeWrap(sha256Bytes(data));
    }

    /**
     * {@link Bytes}-accepting variant of {@link #sha256(byte[])}. Same contract: not a consensus
     * hash entry point — see {@link #sha256(byte[])}.
     */
    public Bytes sha256(Bytes data) {
        return sha256(data.toArray());
    }

    /**
     * Hash the given bytes and return a fresh, caller-owned byte array (length 32). Same
     * contract: not a consensus hash entry point — see {@link #sha256(byte[])}.
     */
    public byte[] sha256Bytes(byte[] data) {
        MessageDigest md = digest.get();
        md.reset();
        return md.digest(data);
    }

    /**
     * The consensus hash function: canonical-encode {@code value} via a fresh
     * {@link CanonicalWriter}, then SHA-256 the resulting bytes. This is the function used to
     * compute state roots and the hash of every {@link Encodable} on the network — there is no
     * other path to a consensus hash.
     */
    public Bytes hash(Encodable value) {
        CanonicalWriter writer = new CanonicalWriter();
        value.encode(writer);
        return sha256(writer.toByteArray());
    }
}
