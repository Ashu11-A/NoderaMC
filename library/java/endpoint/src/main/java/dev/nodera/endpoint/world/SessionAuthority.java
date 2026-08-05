package dev.nodera.endpoint.world;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.SessionDelegation;

import java.util.Base64;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decide which identity an announcing session speaks for.
 *
 * <h2>The two identities, and why the wrong one was being used</h2>
 *
 * <p>A joining client generates a fresh keypair per session for its peer transport and proves
 * possession of it over the server's announce challenge. That is the right key for dialling and for
 * authenticating the transport, and it is the <b>wrong</b> key for deciding what the player is
 * allowed to do — because a world's author is the key inside its signed {@code WorldIdentity}, and a
 * granted role is the key bound into the grant, and both of those live in the player's always-on
 * worker and persist across launches.
 *
 * <p>So evaluating permissions against the session key answered {@code MEMBER} for every player who
 * had ever been given anything, including the world's own creator. The visible symptom was a creator
 * losing {@code /op} — and, because vanilla {@code /gamemode} and friends want permission level 2,
 * losing those too, which reads like several separate bugs and is one.
 *
 * <p>This class closes that gap without moving a private key into the game: the session presents a
 * {@link SessionDelegation} its worker signed, and if that delegation verifies <i>against the key
 * just proven</i> and <i>for the world actually being played</i> and <i>has not expired</i>, then the
 * announced node carries the worker's identity as its authority. Anything else — no delegation, a
 * malformed one, one for another world, one for another session key, an expired one — is not an
 * error to report to the player: the session simply speaks for itself, exactly as it did before
 * delegations existed.
 *
 * @Thread-context stateless, any thread.
 */
public final class SessionAuthority {

    private SessionAuthority() {
    }

    /**
     * Resolve an announce into the node record the session registry should hold.
     *
     * @param sessionNode    the announced session NodeId.
     * @param sessionKey     the session public key the announce proof already verified.
     * @param route          the announced dialable route.
     * @param delegationB64  base64 of the presented {@link SessionDelegation}, or blank/{@code null}.
     * @param worldId        the world this session belongs to; blank/{@code null} when the host has
     *                       no shared world identity, in which case no delegation can be scoped to
     *                       it and none is accepted.
     * @param nowEpochMillis the current wall-clock time, for the expiry check.
     * @param notes          where to report a delegation that was offered and rejected; may be
     *                       {@code null}. A rejected delegation is worth a line because it is the
     *                       difference between "this player has no worker" and "this player's worker
     *                       signed something that does not apply here".
     * @return the node record, with the worker's identity as its authority when a delegation held.
     * @Thread-context any thread.
     */
    public static PlayerNodeRegistry.PlayerNode resolve(NodeId sessionNode, Bytes sessionKey,
                                                        String route, String delegationB64,
                                                        Bytes worldId, long nowEpochMillis,
                                                        Consumer<String> notes) {
        Objects.requireNonNull(sessionNode, "sessionNode");
        Objects.requireNonNull(sessionKey, "sessionKey");
        PlayerNodeRegistry.PlayerNode selfOnly =
                new PlayerNodeRegistry.PlayerNode(sessionNode, sessionKey, route);
        if (delegationB64 == null || delegationB64.isBlank()) {
            return selfOnly;
        }
        if (worldId == null || worldId.isEmpty()) {
            note(notes, "a session offered a worker delegation, but this world has no shared "
                    + "identity to scope one to — the session speaks only for itself");
            return selfOnly;
        }
        SessionDelegation delegation;
        try {
            delegation = SessionDelegation.decode(
                    new CanonicalReader(Bytes.unsafeWrap(Base64.getDecoder().decode(delegationB64))));
        } catch (RuntimeException malformed) {
            note(notes, "a session offered a worker delegation that could not be read ("
                    + malformed.getMessage() + ") — the session speaks only for itself");
            return selfOnly;
        }
        if (!delegation.isValidFor(sessionKey, worldId, nowEpochMillis)) {
            note(notes, "a session offered a worker delegation that does not apply here (wrong "
                    + "world, wrong session key, expired, or badly signed) — the session speaks "
                    + "only for itself");
            return selfOnly;
        }
        return new PlayerNodeRegistry.PlayerNode(sessionNode, sessionKey, route,
                delegation.workerNodeId(), delegation.workerPublicKey());
    }

    private static void note(Consumer<String> notes, String message) {
        if (notes != null) {
            notes.accept(message);
        }
    }
}
