package dev.nodera.endpoint.client;

import dev.nodera.endpoint.config.NoderaSettings;
import dev.nodera.endpoint.share.JoinChallenge;
import dev.nodera.core.Bytes;
import dev.nodera.distribution.JoinPasswordGate;
import dev.nodera.endpoint.share.JoinChallenge;

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

    /**
     * The world id of the most recent challenge, answered or not, cleared once the player is in the
     * world.
     *
     * <p>Separate from {@link #unanswered} because a <em>wrong</em> password produces a proof, so
     * nothing was "unanswered" — and yet the connection is refused exactly the same way. Anything
     * that needs to know "did this connection end at the gate?" has to ask this one; asking
     * {@code unanswered} answers "did it end at the gate without even trying?", which is a strictly
     * narrower question and reads as false in the more common case of a typo.
     */
    private static volatile String challenged;

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

    /** Forget every remembered password (leaving the game, or an explicit clear). */
    public static void clear() {
        PASSWORDS.clear();
        unanswered = null;
        challenged = null;
    }

    /**
     * @return the world id of the last challenge this client could not answer, or {@code null}. Read
     *         by the disconnect surface to explain the refusal.
     */
    public static String unansweredWorldId() {
        return unanswered;
    }

    /**
     * @return the world id of the gate challenge this connection attempt is still sitting on, or
     *         {@code null} once the player is in the world. A non-null value means a disconnect
     *         belongs to the gate, not to a host that went away.
     */
    public static String pendingGateWorldId() {
        return challenged;
    }

    /**
     * The player is in the world: whatever the gate asked, it was satisfied.
     *
     * <p>Called from the client login event. Without it the markers latch for the rest of the
     * session, and a genuine host loss an hour later would be read as a password refusal.
     */
    public static void passedGate() {
        challenged = null;
        unanswered = null;
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
    public static Optional<Bytes> answer(JoinChallenge challenge) {
        return answer(challenge.worldIdHex(), challenge.material(), challenge.nonce());
    }

    /**
     * The same answer, without the packet.
     *
     * <p>Split out so the gate's decision — which is a password lookup and a KDF, no Minecraft in
     * it anywhere — can be tested headlessly. The packet record implements a Minecraft interface, so
     * a test that mentions it cannot even be discovered outside the game.
     *
     * @param worldIdHex the world the host challenged for.
     * @param material   the challenge's KDF parameters.
     * @param nonce      this connection's single-use nonce.
     * @return the MAC to send back, or empty when this client has no password for that world.
     */
    public static Optional<Bytes> answer(String worldIdHex,
                                         dev.nodera.distribution.WorldKeyMaterial material,
                                         Bytes nonce) {
        // Recorded before the outcome is known: the point of this marker is that the connection
        // reached the gate at all.
        challenged = worldIdHex;
        String password = PASSWORDS.get(key(worldIdHex));
        if (password == null || password.isEmpty()) {
            // The configured fallback (`join.password`): empty for a player who only ever types
            // passwords, set by anyone who wants this client to answer without a prompt — which is
            // also the only way a scripted client can pass the gate, having no keyboard.
            password = configuredFallback();
        }
        if (password == null || password.isEmpty()) {
            unanswered = worldIdHex;
            return Optional.empty();
        }
        char[] chars = password.toCharArray();
        try {
            Bytes gateKey = JoinPasswordGate.gateKey(chars, material);
            return Optional.of(JoinPasswordGate.proof(gateKey, nonce,
                    Bytes.fromHex(worldIdHex)));
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    /**
     * The {@code join.password} client config value, or empty when it cannot be read.
     *
     * <p>Guarded because this is reachable before — and outside — a loaded NeoForge config: the
     * headless proof of the gate's decision has no mod config at all, and a client that failed to
     * load one should ask the player for a password rather than fail the join with a config error.
     */
    private static String configuredFallback() {
        try {
            return NoderaSettings.current().joinPassword();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    private static String key(String worldIdHex) {
        return worldIdHex.toLowerCase(Locale.ROOT);
    }
}
