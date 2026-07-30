package dev.nodera.peer.archival;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What actually replaces a departed holder's replicas.
 *
 * <p>{@code PeerShutdownHook} and {@code EmergencyFlush} were written as the answer — a departing
 * node pushes its pieces to replacements before it exits — and neither has a production caller.
 * `docs/network/REFACTORING.md` records why they cannot simply be wired: `EmergencyFlush.PieceTransfer`
 * is a <i>push</i> ("take this piece and acknowledge you stored it") and production moves content by
 * pull only, which is also why tags 30/31 are dead.
 *
 * <p>That leaves a durability question that a verdict in a register cannot answer: <b>if nothing
 * evacuates a departing node, what restores its replication factor?</b> The answer is the mechanism
 * that ships — placement is a pure function of the live peer set, so a departure re-ranks every
 * world the leaver held and hands each one to somebody still present, and `WorldReplicationService`'s
 * sweep adopts on exactly that signal. These tests pin the load-bearing half of that argument, so
 * the decision to leave the flush lane unwired rests on evidence rather than on reasoning.
 *
 * <p>Deliberately a pure-function test: no tracker, no transport, no timing. The claim is about the
 * placement policy's response to a smaller peer set, and that is all that is exercised.
 */
final class DepartureIsRepairedByPlacementTest {

    private static final Bytes ROOT = Bytes.unsafeWrap("a world's manifest root".getBytes());

    private final ArchivePlacementPolicy policy = new RendezvousArchivePolicy(
            ReplicationFactors.forWorldArchives(ReplicationTarget.standard()));

    /** Deterministic ids, so a failure is reproducible rather than a seed away from it. */
    private static List<NodeId> peers(int count) {
        List<NodeId> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(new NodeId(UUID.nameUUIDFromBytes(("peer-" + i).getBytes())));
        }
        return out;
    }

    private List<NodeId> expected(List<NodeId> eligible) {
        return policy.expectedHolders(ROOT, ArchiveObjectClass.SNAPSHOT,
                new ArrayList<>(eligible), Set.of());
    }

    /**
     * Peers needed before a world is <i>not</i> on every one of them.
     *
     * <p>{@code forWorldArchives(standard())} derives R = 22 from a 35% peer-availability assumption,
     * and {@code factor} caps R at the network size — so below this a full copy of every world sits
     * on every peer, no promotion is possible because nobody is a non-holder, and (usefully) the
     * replication sweep's release rule can never fire, because every peer is always placed. The
     * repair question only becomes non-trivial above the threshold, so that is where it is asked.
     */
    private static final int ABOVE_THE_FULL_COPY_THRESHOLD = 30;

    @Test
    @DisplayName("a holder's departure promotes somebody who was not holding it before")
    void aDepartureIsAnsweredByAPromotion() {
        List<NodeId> before = peers(ABOVE_THE_FULL_COPY_THRESHOLD);
        List<NodeId> holdersBefore = expected(before);
        assertThat(holdersBefore)
                .as("the swarm must be large enough for non-holders to exist at all")
                .hasSizeLessThan(before.size());

        // The world loses one of its expected holders — a player closing their laptop.
        NodeId departing = holdersBefore.get(0);
        List<NodeId> after = new ArrayList<>(before);
        after.remove(departing);

        List<NodeId> holdersAfter = expected(after);

        assertThat(holdersAfter)
                .as("the leaver is gone from the expected set, so nobody waits on it")
                .doesNotContain(departing);
        assertThat(holdersAfter)
                .as("and the factor is restored from the peers that remain")
                .hasSize(Math.min(holdersBefore.size(), after.size()));

        Set<NodeId> promoted = new LinkedHashSet<>(holdersAfter);
        promoted.removeAll(holdersBefore);
        assertThat(promoted)
                .as("somebody who was NOT a holder becomes one — this is the repair, and it is the "
                        + "signal WorldReplicationService.placedFor reads on its next sweep")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the peers that did not leave keep their assignments, so a departure is not a reshuffle")
    void survivorsAreNotDisturbed() {
        List<NodeId> before = peers(ABOVE_THE_FULL_COPY_THRESHOLD);
        List<NodeId> holdersBefore = expected(before);
        NodeId departing = holdersBefore.get(0);
        List<NodeId> after = new ArrayList<>(before);
        after.remove(departing);

        List<NodeId> holdersAfter = expected(after);

        // Rendezvous hashing exists for this: had placement been "hash modulo N", losing one peer
        // would have re-assigned nearly every world on the network at once, and the repair would
        // have cost more traffic than the outage.
        List<NodeId> stillExpected = new ArrayList<>(holdersBefore);
        stillExpected.remove(departing);
        assertThat(holdersAfter).containsAll(stillExpected);
    }

    @Test
    @DisplayName("a swarm too small to restore the factor keeps every peer it has, rather than none")
    void aSmallSwarmDegradesRatherThanEmptying() {
        // The failure to avoid is a policy that answers "nobody is expected to hold this" when the
        // population drops below the replication factor — which would make every remaining peer
        // eligible to RELEASE the world at the exact moment it is closest to being lost.
        for (int size = 1; size <= 4; size++) {
            List<NodeId> small = peers(size);
            assertThat(expected(small))
                    .as("with %s peer(s), every peer is an expected holder", size)
                    .containsExactlyInAnyOrderElementsOf(small);
        }
    }
}
