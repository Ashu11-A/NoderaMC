package dev.nodera.endpoint.share;

import dev.nodera.core.Bytes;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issue #36 (flaw F1): the server's per-login announce challenges. On each player login the host
 * issues a fresh 32-byte {@link SecureRandom} nonce keyed by the player's Minecraft UUID and hands
 * it to the client in the session payload. The client's node announce must carry a signature over
 * that nonce (see {@link NodeAnnounceProof}); the server {@link #consume(UUID, long) consumes} it —
 * single-use — before trusting the announce. A nonce expires after {@link #TTL_MILLIS} so a leaked
 * challenge cannot be hoarded, and re-issue on a second login replaces any outstanding one.
 *
 * @Thread-context thread-safe (concurrent map); issue on the main thread at login, consume on the
 *     announce handler thread.
 */
public final class AnnounceChallenges {

    /** Nonce length in bytes. */
    public static final int NONCE_LENGTH = 32;

    /** How long an issued challenge stays valid. */
    public static final long TTL_MILLIS = 120_000L;

    private static final SecureRandom RNG = new SecureRandom();

    private record Pending(Bytes nonce, long expiresAtMillis) {
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    /**
     * Issue (or replace) a challenge for a player.
     *
     * @param mcUuid   the player's Minecraft UUID.
     * @param nowMillis the current time.
     * @return the fresh nonce to send to that client.
     */
    public Bytes issue(UUID mcUuid, long nowMillis) {
        byte[] raw = new byte[NONCE_LENGTH];
        RNG.nextBytes(raw);
        Bytes nonce = Bytes.unsafeWrap(raw);
        pending.put(mcUuid, new Pending(nonce, nowMillis + TTL_MILLIS));
        return nonce;
    }

    /**
     * Consume the outstanding challenge for a player (single-use). Returns empty when none is
     * pending or it has expired; either way the entry is removed.
     *
     * @param mcUuid    the player's Minecraft UUID.
     * @param nowMillis the current time.
     * @return the valid nonce, or empty.
     */
    public Optional<Bytes> consume(UUID mcUuid, long nowMillis) {
        Pending p = pending.remove(mcUuid);
        if (p == null || nowMillis > p.expiresAtMillis()) {
            return Optional.empty();
        }
        return Optional.of(p.nonce());
    }

    /** Drop a player's outstanding challenge (logout / disconnect). */
    public void forget(UUID mcUuid) {
        pending.remove(mcUuid);
    }

    /** @return the number of outstanding challenges (diagnostics/tests). */
    public int outstanding() {
        return pending.size();
    }
}
