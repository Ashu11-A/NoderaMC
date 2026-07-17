package dev.nodera.peer;

import dev.nodera.core.crypto.StableHash;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.membership.PeerEntry;

import java.util.Collection;
import java.util.UUID;

/**
 * Deterministic election of a session gateway (Phase 6 P2P continuity).
 *
 * <p>The gateway is the session's coordinator — initially the dedicated server acting as a
 * bootstrap peer. When the gateway goes offline, every surviving peer must independently pick the
 * <b>same</b> successor without any coordination, or the session would fork. This election is
 * therefore a pure, order-independent function of {@code (alive-member-set, epoch)}:
 *
 * <ol>
 *   <li><b>Bootstrap preference.</b> While a bootstrap-capable peer is alive it is the gateway
 *       (the dedicated server is the natural, best-provisioned coordinator). Once no bootstrap
 *       peer remains, the election falls through to the player peers.</li>
 *   <li><b>Rendezvous score.</b> Among the preferred class, the member with the highest
 *       {@link StableHash} score over {@code (uuidMsb, uuidLsb, epoch)} wins. Mixing in the epoch
 *       means a re-election reshuffles the winner rather than always re-picking the same node,
 *       which spreads gateway duty over successive failures.</li>
 *   <li><b>Tie-break.</b> On the astronomically-unlikely score tie, the larger {@link UUID}
 *       wins — a total, deterministic order.</li>
 * </ol>
 *
 * <p>Because {@link StableHash} is a frozen cross-JVM contract, two peers with the same alive-set
 * and epoch always elect the same gateway. (Capability-weighted rendezvous — Plan §3.5 — is a
 * refinement that slots in here later without changing the determinism guarantee.)
 *
 * <p>Thread-context: pure static function, safe for any thread.
 */
public final class GatewayElection {

    private GatewayElection() {}

    /**
     * Elect the gateway among {@code alive} for {@code epoch}.
     *
     * @param alive the current alive-member set (must contain at least one entry).
     * @param epoch the epoch being elected for (mixed into the score).
     * @return the elected gateway's {@link NodeId}.
     * @throws IllegalArgumentException if {@code alive} is null or empty.
     * @Thread-context any thread.
     */
    public static NodeId elect(Collection<PeerEntry> alive, long epoch) {
        if (alive == null || alive.isEmpty()) {
            throw new IllegalArgumentException("cannot elect a gateway from an empty member set");
        }
        PeerEntry best = null;
        long bestScore = 0L;
        for (PeerEntry e : alive) {
            UUID id = e.nodeId().value();
            long score = StableHash.of(new long[]{
                    id.getMostSignificantBits(), id.getLeastSignificantBits(), epoch});
            if (best == null || isBetter(e, score, best, bestScore)) {
                best = e;
                bestScore = score;
            }
        }
        return best.nodeId();
    }

    private static boolean isBetter(PeerEntry cand, long candScore, PeerEntry cur, long curScore) {
        // 1. bootstrap-capable peers outrank player peers.
        if (cand.bootstrap() != cur.bootstrap()) {
            return cand.bootstrap();
        }
        // 2. higher rendezvous score wins.
        if (candScore != curScore) {
            return Long.compareUnsigned(candScore, curScore) > 0;
        }
        // 3. deterministic tie-break: larger UUID wins.
        return cand.nodeId().value().compareTo(cur.nodeId().value()) > 0;
    }
}
