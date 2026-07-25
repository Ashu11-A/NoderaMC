package dev.nodera.mod.client.multiplayer;

import dev.nodera.core.Bytes;
import dev.nodera.distribution.JoinPasswordGate;
import dev.nodera.mod.common.NoderaJoinChallengePayload;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The joiner's side of the live-join password gate (L-52): the passwords this player has typed,
 * and the answer they produce when a host challenges.
 *
 * <p>Per world, per session, in memory only — a world password is the key to its content, and
 * writing a typed one to disk would turn "the player knows it" into "this machine holds it". A
 * restart simply asks again. The one exception is deliberate and explicit: a player (or a scripted
 * client, which has no keyboard) may set {@code join.password} in the client config, and that value
 * answers any challenge no typed password covers.
 *
 * <p>The last challenge that could not be answered is remembered so the disconnect that follows can
 * say something useful ("this world wants a password") instead of leaving the player to guess.
 *
 * @Thread-context thread-safe; written from the render thread (the prompt), read from the
 *     proof-derivation thread.
 */
public final class ClientJoinPasswords {

    private static final Map<String, String> PASSWORDS = new ConcurrentHashMap<>();

    /** The world id of the most recent challenge this client had no password for ({@code null} if none). */
    private static volatile String unanswered;

    private ClientJoinPasswords() {
    }

    /**
     * Remember the password to use for a world.
     *
     * @param worldIdHex the world's id (hex); ignored when blank.
     * @param password   the password; blank clears any remembered one.
     */
    public static void remember(String worldIdHex, String password) {
        if (worldIdHex == null || worldIdHex.isBlank()) {
            return;
        }
        String key = key(worldIdHex);
        if (password == null || password.isEmpty()) {
            PASSWORDS.remove(key);
        } else {
            PASSWORDS.put(key, password);
        }
    }

    /** @return whether a password is remembered for this world. */
    public static boolean has(String worldIdHex) {
        return worldIdHex != null && PASSWORDS.containsKey(key(worldIdHex));
    }

    /** Forget every remembered password (leaving the game, or an explicit clear). */
    public static void clear() {
        PASSWORDS.clear();
        unanswered = null;
    }

    /**
     * @return the world id of the last challenge this client could not answer, or {@code null}. Read
     *         by the disconnect surface to explain the refusal.
     */
    public static String unansweredWorldId() {
        return unanswered;
    }

    /**
     * Answer a host's challenge with the password this player supplied for that world.
     *
     * <p>Empty when there is none — the client says so immediately rather than letting the host's
     * challenge time out, and the world id is recorded so the refusal can be explained.
     *
     * @param challenge the host's challenge.
     * @return the MAC to send back, or empty when this client has no password for that world.
     * @Thread-context any thread; costs one memory-hard KDF derivation when a password is known.
     */
    public static Optional<Bytes> answer(NoderaJoinChallengePayload challenge) {
        String password = PASSWORDS.get(key(challenge.worldIdHex()));
        if (password == null || password.isEmpty()) {
            // The configured fallback (`join.password`): empty for a player who only ever types
            // passwords, set by anyone who wants this client to answer without a prompt — which is
            // also the only way a scripted client can pass the gate, having no keyboard.
            password = dev.nodera.mod.common.NoderaConfig.JOIN_PASSWORD.get();
        }
        if (password == null || password.isEmpty()) {
            unanswered = challenge.worldIdHex();
            return Optional.empty();
        }
        char[] chars = password.toCharArray();
        try {
            Bytes gateKey = JoinPasswordGate.gateKey(chars, challenge.material());
            return Optional.of(JoinPasswordGate.proof(gateKey, challenge.nonce(),
                    Bytes.fromHex(challenge.worldIdHex())));
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    private static String key(String worldIdHex) {
        return worldIdHex.toLowerCase(Locale.ROOT);
    }
}
