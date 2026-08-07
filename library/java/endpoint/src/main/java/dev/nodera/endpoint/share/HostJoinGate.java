package dev.nodera.endpoint.share;

import dev.nodera.core.Bytes;
import dev.nodera.distribution.JoinAttemptThrottle;
import dev.nodera.distribution.JoinPasswordGate;
import dev.nodera.distribution.WorldKeyMaterial;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The host's side of the live-join password gate (L-52): one armed world, one outstanding challenge
 * per connecting client, one verdict.
 *
 * <p>Before this, a world password protected the archived <b>content</b> plane only — a listed
 * world's game route is public, so anyone who resolved it could connect and play. The gate closes
 * that: while a password-protected world is hosted, every joining connection must answer a fresh
 * challenge with a MAC derived from the password ({@link JoinPasswordGate}) before it leaves the
 * configuration phase. No password, no world.
 *
 * <p>The password itself is <b>not</b> retained: {@link #arm} derives the gate key and drops the
 * characters it was given. The public key material (random salt + KDF parameters) is regenerated
 * per host session and travels to joiners in the challenge, so nothing has to be persisted and a
 * restarted host gates just as tightly.
 *
 * <p>Minecraft-free on purpose — connections are opaque keys — so the rule is asserted on the
 * ordinary headless gate rather than only in a live session.
 *
 * <p><b>Bounded.</b> Outstanding challenges are keyed by remote-initiated connections, so the store
 * has both a TTL and a hard cap; the oldest entry is evicted when the cap is reached. An evicted or
 * expired challenge is a refusal, never a pass.
 *
 * <p><b>Throttled.</b> A challenge is single-use <i>per connection</i>, which bounds replay and
 * nothing else: a client that reconnects gets a fresh nonce, so on its own the gate is an unlimited
 * online guessing oracle against the world password. {@link JoinAttemptThrottle} is what closes
 * that — failures are counted per (world, joiner) and open an exponentially growing lockout, and a
 * throttled joiner is refused <b>before</b> a challenge is issued, so a grinding client cannot even
 * make the host pay for the nonce. The joiner key is supplied by the caller (the remote address in
 * production) rather than the connection object, because keying on the connection would reset the
 * counter on exactly the action being throttled.
 *
 * @Thread-context thread-safe; every method synchronizes on the instance. Armed from the server
 *     thread, issued and verified from network handler threads.
 */
public final class HostJoinGate {

    /** How long an issued challenge stays answerable. */
    public static final long TTL_MILLIS = 60_000L;

    /** Hard cap on outstanding challenges (bounded cache keyed by remote input). */
    public static final int MAX_OUTSTANDING = 256;

    private static final HostJoinGate INSTANCE = new HostJoinGate();

    private static final SecureRandom RNG = new SecureRandom();

    /** The verdict on a joiner's answer — every non-{@link #PASS} value ends the connection. */
    public enum Verdict {
        /** The joiner proved knowledge of the world password. */
        PASS,
        /** The world is not password-protected; there is nothing to prove. */
        NOT_GATED,
        /** No outstanding challenge for this connection (never issued, expired, or evicted). */
        NO_CHALLENGE,
        /** An answer arrived and it was not the right one. */
        WRONG_PASSWORD,
        /** This joiner has failed too often too recently; the attempt is not judged at all. */
        THROTTLED
    }

    /**
     * What a joining client is told: which world it is being gated on, how to derive the key, and
     * the nonce to answer with.
     *
     * @param worldIdHex the hosted world's id (hex) — bound into the MAC.
     * @param material   the public salt + KDF parameters.
     * @param nonce      this connection's single-use nonce.
     */
    public record Challenge(String worldIdHex, WorldKeyMaterial material, Bytes nonce) {
    }

    private record Pending(Bytes nonce, long expiresAtMillis) {
    }

    private Bytes worldId;
    private WorldKeyMaterial material;
    private Bytes gateKey;

    /** Sealed: the world needs a gate and does not have a working one, so nobody gets in. */
    private boolean sealed;

    /** Access-ordered so the cap evicts the least recently touched challenge. */
    private final Map<Object, Pending> outstanding =
            new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Per-(world, joiner) failure history. Replaced on every arm/disarm/seal: a host session is the
     * lifetime of a gate key, and carrying a lockout across one would punish a joiner for a password
     * that is no longer the one being asked for.
     */
    private JoinAttemptThrottle throttle = new JoinAttemptThrottle();

    private HostJoinGate() {
    }

    /** @return the process-wide gate (one hosted world per game). */
    public static HostJoinGate get() {
        return INSTANCE;
    }

    /**
     * Arm the gate for a password-protected world. Costs one full KDF derivation, so call it off
     * the tick loop; a blank password disarms instead (a plaintext world gates nothing).
     *
     * @param worldId  the hosted world's id.
     * @param password the world password; not retained.
     * @Thread-context any thread.
     */
    public synchronized void arm(Bytes worldId, char[] password) {
        if (worldId == null || password == null || password.length == 0) {
            disarm();
            return;
        }
        this.worldId = worldId;
        this.material = JoinPasswordGate.newKeyMaterial(RNG);
        this.gateKey = JoinPasswordGate.gateKey(password, this.material);
        this.sealed = false;
        this.outstanding.clear();
        this.throttle = new JoinAttemptThrottle();
    }

    /** Disarm (the world stopped being hosted, or its password was removed). */
    public synchronized void disarm() {
        worldId = null;
        material = null;
        gateKey = null;
        sealed = false;
        outstanding.clear();
        throttle = new JoinAttemptThrottle();
    }

    /**
     * Seal the world shut: it is password-protected but has no usable gate (deriving the key
     * failed), so every join is refused. Failing <b>closed</b> is the only honest answer — the
     * alternative, disarming, would silently turn a password-protected world into an open one.
     *
     * @Thread-context any thread.
     */
    public synchronized void seal() {
        worldId = null;
        material = null;
        gateKey = null;
        sealed = true;
        outstanding.clear();
        throttle = new JoinAttemptThrottle();
    }

    /** @return whether the gate is sealed shut (no join can succeed until the host re-shares). */
    public synchronized boolean isSealed() {
        return sealed;
    }

    /**
     * @return whether this connection needs to pass the gate at all — true both for a working gate
     *         and for a sealed one, because a sealed world must refuse rather than skip the step.
     */
    public synchronized boolean isArmed() {
        return gateKey != null || sealed;
    }

    /**
     * Whether this joiner is inside a lockout window and must be refused without being challenged.
     *
     * <p>Asked before {@link #issue} so a grinding client does not even cost the host a nonce. It is
     * <b>not</b> the enforcement point — {@link #verify} refuses a throttled attempt on its own — so
     * a caller that skips this check loses the early exit and nothing else.
     *
     * @param joiner    the joiner's stable identity across reconnects (the remote address in
     *                  production); a null identity is never throttled, because refusing every
     *                  unidentifiable joiner would be a denial of service rather than a throttle.
     * @param nowMillis the current time.
     * @return whether the joiner is locked out right now.
     * @Thread-context any thread.
     */
    public synchronized boolean isThrottled(Bytes joiner, long nowMillis) {
        return gateKey != null
                && joiner != null
                && !throttle.mayAttempt(worldId, joiner, nowMillis);
    }

    /**
     * Issue this connection's challenge.
     *
     * @param connection an opaque per-connection key (the Minecraft {@code Connection}).
     * @param nowMillis  the current time.
     * @return the challenge to send, or empty when the gate is not armed.
     * @Thread-context any thread.
     */
    public synchronized Optional<Challenge> issue(Object connection, long nowMillis) {
        if (gateKey == null || connection == null) {
            return Optional.empty();
        }
        expire(nowMillis);
        Bytes nonce = JoinPasswordGate.newNonce(RNG);
        outstanding.put(connection, new Pending(nonce, nowMillis + TTL_MILLIS));
        while (outstanding.size() > MAX_OUTSTANDING) {
            Iterator<Object> oldest = outstanding.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return Optional.of(new Challenge(worldId.toHex(), material, nonce));
    }

    /**
     * Judge a joiner's answer. Single-use: the challenge is consumed whatever the verdict, so a
     * client gets exactly one attempt per connection and a captured answer cannot be replayed.
     *
     * @param connection the connection the answer arrived on.
     * @param joiner     the joiner's stable identity across reconnects, counted against the lockout
     *                   policy; null disables throttling for this attempt (see {@link #isThrottled}).
     * @param answer     the joiner's MAC (may be null/empty — that is a refusal, not an error).
     * @param nowMillis  the current time.
     * @return the verdict.
     * @Thread-context any thread.
     */
    public synchronized Verdict verify(
            Object connection, Bytes joiner, Bytes answer, long nowMillis) {
        if (sealed) {
            return Verdict.WRONG_PASSWORD;
        }
        if (gateKey == null) {
            return Verdict.NOT_GATED;
        }
        if (joiner != null && !throttle.mayAttempt(worldId, joiner, nowMillis)) {
            // Consume the challenge anyway: a locked-out joiner must not be able to park a valid
            // nonce through the lockout and spend it the moment the window closes.
            if (connection != null) {
                outstanding.remove(connection);
            }
            return Verdict.THROTTLED;
        }
        Pending pending = connection == null ? null : outstanding.remove(connection);
        if (pending == null || nowMillis > pending.expiresAtMillis()) {
            return Verdict.NO_CHALLENGE;
        }
        if (JoinPasswordGate.verify(gateKey, pending.nonce(), worldId, answer)) {
            if (joiner != null) {
                throttle.recordSuccess(worldId, joiner);
            }
            return Verdict.PASS;
        }
        if (joiner != null) {
            throttle.recordFailure(worldId, joiner, nowMillis);
        }
        return Verdict.WRONG_PASSWORD;
    }

    /** @return the number of outstanding challenges (diagnostics/tests). */
    public synchronized int outstanding() {
        return outstanding.size();
    }

    private void expire(long nowMillis) {
        outstanding.values().removeIf(pending -> nowMillis > pending.expiresAtMillis());
    }
}
