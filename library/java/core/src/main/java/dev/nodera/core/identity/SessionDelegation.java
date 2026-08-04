package dev.nodera.core.identity;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.Encodable;
import dev.nodera.core.crypto.SignatureService;
import dev.nodera.core.crypto.TypeTags;

import java.util.Objects;
import java.util.UUID;

/**
 * A worker letting one game session speak in its name for a while.
 *
 * <h2>The problem this exists to solve</h2>
 *
 * <p>Every privilege in a Nodera world is anchored to a <b>persistent key</b>: a world's author is
 * the key inside its signed {@code WorldIdentity}, and a granted role is the key bound into the
 * grant. That key lives in the always-on worker, at {@code ~/.nodera/worker-identity.bin}, and it
 * never leaves that process.
 *
 * <p>The game client, meanwhile, generated a <b>fresh throwaway keypair every session</b> for its
 * peer transport, and announced that key to the session. So the key a player proved possession of
 * was, by construction, a key no world had ever heard of. The permission evaluator did exactly what
 * it was told and answered {@code MEMBER} — which meant a world's own creator was de-opped from
 * their own world the moment they joined it as a client, and every grant anyone had ever issued
 * stopped conferring its role the next time that player launched the game.
 *
 * <p>The fix is not to move the private key into the game. It is this: the client keeps its
 * per-session transport key, and asks its worker — over the loopback control socket, the same local
 * trust boundary every other control verb uses — to <b>sign a statement</b> that the session key
 * speaks for the worker key, for one world, until a stated instant. The session announces both. A
 * verifier that accepts the delegation resolves the announced identity to the <i>worker</i> key and
 * evaluates permissions against that, which is the key the world actually knows.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>A delegation is not a role and confers nothing on its own: it says "this session key is that
 * worker key", and every existing authority check still runs against the worker key afterwards. A
 * delegation for the wrong world, or one that has expired, is simply not a delegation — the session
 * falls back to being an unrecognised peer, exactly as before.
 *
 * <p>Wire form: {@code [u16 SESSION_DELEGATION][u16 ENCODING_VERSION][string workerNodeId]
 * [bytes workerPublicKey][bytes sessionPublicKey][bytes worldId][u64 notAfterEpochMillis]
 * [bytes signature]}.
 *
 * @param workerNodeId        the delegating worker's {@link NodeId}.
 * @param workerPublicKey     the delegating worker's Ed25519 public key — what permissions resolve
 *                            against, and the key this delegation's signature verifies under.
 * @param sessionPublicKey    the game session's per-session transport key being spoken for.
 * @param worldId             the world this delegation is valid in; a delegation minted for one
 *                            world is inert in every other.
 * @param notAfterEpochMillis when it stops being valid, in wall-clock milliseconds.
 * @param signature           the worker's signature over {@link #canonicalBytes}.
 * @Thread-context immutable record, safe for any thread.
 */
public record SessionDelegation(
        NodeId workerNodeId,
        Bytes workerPublicKey,
        Bytes sessionPublicKey,
        Bytes worldId,
        long notAfterEpochMillis,
        Bytes signature
) implements Encodable {

    /** Wire encoding version. */
    public static final int ENCODING_VERSION = 1;

    /**
     * How long a freshly minted delegation lasts. Long enough that a play session never has to
     * re-mint mid-game, short enough that a delegation copied off a disk is not a permanent
     * credential.
     */
    public static final long DEFAULT_TTL_MILLIS = 12L * 60 * 60 * 1000;

    private static final SignatureService SIGNATURES = new SignatureService();

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if a required field is null or empty.
     */
    public SessionDelegation {
        Objects.requireNonNull(workerNodeId, "workerNodeId");
        if (workerPublicKey == null || workerPublicKey.isEmpty()) {
            throw new IllegalArgumentException("a delegation needs the worker's public key");
        }
        if (sessionPublicKey == null || sessionPublicKey.isEmpty()) {
            throw new IllegalArgumentException("a delegation needs the session's public key");
        }
        if (worldId == null || worldId.isEmpty()) {
            throw new IllegalArgumentException("a delegation is scoped to one world");
        }
        Objects.requireNonNull(signature, "signature");
    }

    /**
     * The canonical bytes the signature covers. The session key is inside them, so an attacker who
     * intercepts a delegation cannot re-point it at a key they hold; the world id is inside them, so
     * a delegation minted for a world the worker authored cannot be replayed into someone else's.
     *
     * @param workerNodeId        the delegating worker's node id.
     * @param workerPublicKey     the delegating worker's public key.
     * @param sessionPublicKey    the session key being spoken for.
     * @param worldId             the world scope.
     * @param notAfterEpochMillis the expiry.
     * @return the bytes to sign / verify.
     * @Thread-context any thread.
     */
    public static Bytes canonicalBytes(NodeId workerNodeId, Bytes workerPublicKey,
                                       Bytes sessionPublicKey, Bytes worldId,
                                       long notAfterEpochMillis) {
        CanonicalWriter w = new CanonicalWriter();
        w.writeString(workerNodeId.value().toString());
        w.writeBytes(workerPublicKey);
        w.writeBytes(sessionPublicKey);
        w.writeBytes(worldId);
        w.writeU64(notAfterEpochMillis);
        return w.toBytes();
    }

    /**
     * Mint a delegation, signing with the worker identity.
     *
     * @param worker              the worker identity doing the delegating (holds the private key).
     * @param sessionPublicKey    the game session's per-session transport key.
     * @param worldId             the world this delegation is valid in.
     * @param notAfterEpochMillis when it expires.
     * @return the signed delegation.
     * @Thread-context any thread.
     */
    public static SessionDelegation create(NodeIdentity worker, Bytes sessionPublicKey,
                                           Bytes worldId, long notAfterEpochMillis) {
        Objects.requireNonNull(worker, "worker");
        Bytes signature = worker.sign(canonicalBytes(worker.nodeId(), worker.publicKeyBytes(),
                sessionPublicKey, worldId, notAfterEpochMillis));
        return new SessionDelegation(worker.nodeId(), worker.publicKeyBytes(), sessionPublicKey,
                worldId, notAfterEpochMillis, signature);
    }

    /**
     * Whether this delegation is valid <i>right now</i>, for this session key, in this world.
     *
     * <p>All three questions are asked together on purpose. Checking the signature alone would
     * accept a delegation for another world; checking the world alone would accept an expired one;
     * and checking either without the session key would accept a delegation lifted from someone
     * else's announce.
     *
     * @param expectedSessionKey the key the peer actually proved possession of.
     * @param expectedWorldId    the world the session belongs to.
     * @param nowEpochMillis     the current wall-clock time.
     * @return whether the worker key may be used in place of the session key.
     * @Thread-context any thread.
     */
    public boolean isValidFor(Bytes expectedSessionKey, Bytes expectedWorldId, long nowEpochMillis) {
        if (expectedSessionKey == null || !sessionPublicKey.equals(expectedSessionKey)) {
            return false;
        }
        if (expectedWorldId == null || !worldId.equals(expectedWorldId)) {
            return false;
        }
        if (nowEpochMillis > notAfterEpochMillis) {
            return false;
        }
        return verifySignature();
    }

    /**
     * @return whether the signature verifies under {@link #workerPublicKey()}.
     * @Thread-context any thread.
     */
    public boolean verifySignature() {
        if (signature.isEmpty()) {
            return false;
        }
        return SIGNATURES.verify(workerPublicKey, canonicalBytes(workerNodeId, workerPublicKey,
                sessionPublicKey, worldId, notAfterEpochMillis), signature);
    }

    @Override
    public void encode(CanonicalWriter w) {
        w.writeU16(TypeTags.SESSION_DELEGATION).writeU16(ENCODING_VERSION);
        w.writeString(workerNodeId.value().toString());
        w.writeBytes(workerPublicKey);
        w.writeBytes(sessionPublicKey);
        w.writeBytes(worldId);
        w.writeU64(notAfterEpochMillis);
        w.writeBytes(signature);
    }

    /**
     * Decode a delegation.
     *
     * @param r the reader positioned at the type tag.
     * @return the delegation.
     * @throws IllegalStateException if the tag or version is not this type's.
     * @Thread-context any thread.
     */
    public static SessionDelegation decode(CanonicalReader r) {
        int tag = r.readU16();
        if (tag != TypeTags.SESSION_DELEGATION) {
            throw new IllegalStateException("not a SessionDelegation (tag " + tag + ")");
        }
        int version = r.readU16();
        if (version != ENCODING_VERSION) {
            throw new IllegalStateException("unsupported SessionDelegation version " + version);
        }
        NodeId worker = new NodeId(UUID.fromString(r.readString()));
        Bytes workerKey = r.readBytesValue();
        Bytes sessionKey = r.readBytesValue();
        Bytes world = r.readBytesValue();
        long notAfter = r.readU64();
        Bytes sig = r.readBytesValue();
        return new SessionDelegation(worker, workerKey, sessionKey, world, notAfter, sig);
    }
}
