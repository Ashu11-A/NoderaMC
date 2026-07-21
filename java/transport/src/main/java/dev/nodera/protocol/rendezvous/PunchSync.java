package dev.nodera.protocol.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A relayed observed-address exchange plus a synchronized go-signal (Task 29, wire tag 42;
 * rendezvous.md §4.6, DCUtR-style). Over an established relay circuit the peers swap observed
 * addresses and agree a shared T-minus; each then attempts a TCP simultaneous-open. Failure is not
 * an error — {@code RELAYED} is a legal steady state (rendezvous.md §7).
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param networkId           the network the punch is scoped to.
 * @param genesisHash         the world the punch is scoped to.
 * @param source              the peer sending its observed addresses.
 * @param target              the peer the addresses are for.
 * @param observedCandidates  the source's observed reachability candidates.
 * @param goSignalEpochMillis a shared T-minus for the simultaneous dial, or {@code 0} to request one.
 */
public record PunchSync(
        UUID networkId,
        Bytes genesisHash,
        NodeId source,
        NodeId target,
        List<PeerCandidate> observedCandidates,
        long goSignalEpochMillis) implements NoderaMessage {

    /**
     * @throws IllegalArgumentException if a reference argument is null or {@code genesisHash} is
     *                                  empty.
     */
    public PunchSync {
        Objects.requireNonNull(networkId, "networkId");
        Objects.requireNonNull(genesisHash, "genesisHash");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(observedCandidates, "observedCandidates");
        if (genesisHash.isEmpty()) {
            throw new IllegalArgumentException("genesisHash must not be empty");
        }
        observedCandidates = List.copyOf(observedCandidates);
    }
}
