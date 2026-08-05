package dev.nodera.endpoint.lane;

import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.membership.PeerEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exactly one peer takes over a world when its host leaves.
 *
 * <p>Before this existed there was no answer to that question anywhere: the recovery path consulted
 * only local state, which is true on every survivor at once, so all of them fetched the archive,
 * unpacked it into their own save directory, opened it and re-shared it. One world became <i>N</i>
 * divergent worlds all announcing the same id — seen live as "the world was cloned into everybody's
 * singleplayer list" and "two versions exist and the owner cannot enter the new one".
 */
final class HostSuccessionTest {

    private static NodeId node(long id) {
        return new NodeId(new UUID(0L, id));
    }

    private static PeerEntry peer(long id) {
        return new PeerEntry(node(id), "10.0.0." + id + ":25620", NodeCapabilities.initial(), false);
    }

    private static List<PeerEntry> peers(long... ids) {
        List<PeerEntry> out = new ArrayList<>();
        for (long id : ids) {
            out.add(peer(id));
        }
        return out;
    }

    @Test
    @DisplayName("every peer elects the same successor")
    void theElectionIsUnanimous() {
        List<PeerEntry> members = peers(1, 2, 3, 4);
        long epoch = HostSuccession.epochFor("00112233445566778899aabbccddeeff");

        Optional<NodeId> winner = HostSuccession.elect(members, node(1), epoch);
        assertThat(winner).isPresent();

        // Every survivor sees the membership in whatever order its own transport happened to
        // deliver it. If the answer depended on that, two peers would open the same world.
        for (int shuffle = 0; shuffle < 20; shuffle++) {
            List<PeerEntry> shuffled = new ArrayList<>(members);
            Collections.shuffle(shuffled);
            assertThat(HostSuccession.elect(shuffled, node(1), epoch)).isEqualTo(winner);
        }
    }

    @Test
    @DisplayName("exactly one member answers yes")
    void onlyOneNodeIsTheSuccessor() {
        List<PeerEntry> members = peers(1, 2, 3, 4);
        long epoch = HostSuccession.epochFor("world");

        long winners = members.stream()
                .filter(m -> HostSuccession.isSuccessor(m.nodeId(), members, node(1), epoch))
                .count();

        assertThat(winners)
                .as("two winners cost two worlds; that is the whole bug this replaces")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the departing host is never elected to succeed itself")
    void theDepartedHostIsExcluded() {
        List<PeerEntry> members = peers(1, 2, 3);

        for (int i = 0; i < 64; i++) {
            assertThat(HostSuccession.elect(members, node(1), i)).isNotEqualTo(Optional.of(node(1)));
        }
    }

    @Test
    @DisplayName("a peer nobody can dial cannot be the successor")
    void anUnreachablePeerIsIneligible() {
        List<PeerEntry> members = new ArrayList<>();
        members.add(new PeerEntry(node(1), "", NodeCapabilities.initial(), false));
        members.add(peer(2));

        // Electing a peer with no route succeeds and then produces a world with no endpoint —
        // a failure that surfaces minutes later as "nobody re-opened the world".
        assertThat(HostSuccession.elect(members, null, 0L)).contains(node(2));
    }

    @Test
    @DisplayName("no eligible member means nobody takes over, rather than everybody")
    void noEligibleMemberElectsNobody() {
        assertThat(HostSuccession.elect(List.of(), node(1), 0L)).isEmpty();
        assertThat(HostSuccession.elect(null, node(1), 0L)).isEmpty();
        assertThat(HostSuccession.elect(peers(1), node(1), 0L))
                .as("the only member was the host that just left")
                .isEmpty();

        // And the predicate must agree: a node that cannot tell whether it won must not act.
        assertThat(HostSuccession.isSuccessor(node(2), List.of(), node(1), 0L)).isFalse();
        assertThat(HostSuccession.isSuccessor(null, peers(1, 2), node(1), 0L)).isFalse();
    }

    @Test
    @DisplayName("the epoch is derived from the world, not from a clock")
    void theEpochIsSharedNotLocal() {
        // Two peers deriving it independently must get the same number, or they elect differently.
        assertThat(HostSuccession.epochFor("ABCDEF")).isEqualTo(HostSuccession.epochFor("abcdef"));
        assertThat(HostSuccession.epochFor("  abcdef  ")).isEqualTo(HostSuccession.epochFor("abcdef"));
        assertThat(HostSuccession.epochFor(null)).isZero();
        assertThat(HostSuccession.epochFor("a")).isNotEqualTo(HostSuccession.epochFor("b"));
    }
}
