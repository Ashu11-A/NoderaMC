package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.rendezvous.PeerCandidate;
import dev.nodera.protocol.rendezvous.PunchSync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The peer half of hole-punch coordination (Task 29; rendezvous.md §4.6, DCUtR-style).
 *
 * <p>Over an established relay circuit (a reliable signaling channel), the peers exchange observed
 * addresses via {@link PunchSync} and agree on a shared go-signal the relay stamps; each then
 * attempts a TCP simultaneous-open at that instant. A success upgrades the session to a direct
 * socket; a failure is not an error — the circuit remains a legal steady state (§7). This class is
 * the pure, testable coordination logic: the timing wait and the candidate to dial.
 *
 * <p>Thread-context: stateless; safe for any thread.
 */
public final class HolePunchCoordinator {

    private HolePunchCoordinator() {}

    /**
     * Build this side's {@code PunchSync} carrying its observed candidates. A {@code goSignal} of
     * {@code 0} asks the relay to mint the shared T-minus.
     *
     * @param networkId          the network namespace.
     * @param genesisHash        the world namespace.
     * @param self               this peer.
     * @param target             the peer to punch to.
     * @param observedCandidates this side's observed reachability candidates.
     * @param goSignalEpochMillis the agreed go-signal, or {@code 0} to request one.
     * @return the punch-sync message.
     * @Thread-context any thread.
     */
    public static PunchSync buildSync(
            UUID networkId,
            Bytes genesisHash,
            NodeId self,
            NodeId target,
            List<PeerCandidate> observedCandidates,
            long goSignalEpochMillis) {
        return new PunchSync(networkId, genesisHash, self, target, observedCandidates,
                goSignalEpochMillis);
    }

    /**
     * How long to wait before the coordinated simultaneous-open, given the shared go-signal.
     *
     * @param goSignalEpochMillis the shared go-signal (epoch millis).
     * @param nowEpochMillis      the current wall clock (epoch millis).
     * @return milliseconds to wait; {@code 0} if the go-signal has already passed.
     * @Thread-context any thread.
     */
    public static long waitMillis(long goSignalEpochMillis, long nowEpochMillis) {
        return Math.max(0L, goSignalEpochMillis - nowEpochMillis);
    }

    /**
     * The best direct candidate to dial during the punch, from the peer's observed candidates.
     *
     * @param peerObservedCandidates the peer's observed candidates (from its {@link PunchSync}).
     * @return the highest-priority direct candidate, or empty if none is direct.
     * @Thread-context any thread.
     */
    public static Optional<PeerCandidate> targetCandidate(List<PeerCandidate> peerObservedCandidates) {
        return CandidateDialer.directCandidates(peerObservedCandidates).stream().findFirst();
    }
}
