package dev.nodera.consensus;

import dev.nodera.core.Bytes;
import dev.nodera.core.consensuscert.SignedVote;
import dev.nodera.core.consensuscert.VoteDecision;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class EquivocationDetectorTest {

    private static final ProposalKey KEY = new ProposalKey(
            new RegionId(DimensionKey.overworld(), 0, 0),
            new RegionEpoch(1),
            new SnapshotVersion(3));

    private static StateRoot root(int seed) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) seed);
        return new StateRoot(new Bytes(b));
    }

    private static SignedVote vote(NodeId voter, StateRoot root) {
        return new SignedVote(voter, root, VoteDecision.ACCEPT, Bytes.empty());
    }

    private static SignedVote vote(NodeId voter, StateRoot root, StateRoot transitionRoot) {
        return new SignedVote(
                voter, root, transitionRoot, VoteDecision.ACCEPT, Bytes.empty());
    }

    private static NodeId voter(long msb) {
        return new NodeId(new UUID(msb, 0L));
    }

    @Test
    void twoDifferentRootsForSameKeyFlagged() {
        EquivocationDetector detector = new EquivocationDetector();
        NodeId v = voter(1);
        StateRoot a = root(1);
        StateRoot b = root(2);

        detector.observe(KEY, vote(v, a));
        assertThat(detector.hasEquivoked(v)).isFalse();

        detector.observe(KEY, vote(v, b));
        assertThat(detector.hasEquivoked(v)).isTrue();

        var record = detector.record(v);
        assertThat(record).isPresent();
        assertThat(record.get().voter()).isEqualTo(v);
        assertThat(record.get().key()).isEqualTo(KEY);
        assertThat(record.get().firstRoot()).isEqualTo(a);
        assertThat(record.get().secondRoot()).isEqualTo(b);
    }

    @Test
    void sameRootTwiceNotEquivocation() {
        EquivocationDetector detector = new EquivocationDetector();
        NodeId v = voter(1);
        StateRoot a = root(1);

        detector.observe(KEY, vote(v, a));
        detector.observe(KEY, vote(v, a));

        assertThat(detector.hasEquivoked(v)).isFalse();
        assertThat(detector.record(v)).isEmpty();
    }

    @Test
    void sameStateRootWithDifferentTransitionRootsIsEquivocation() {
        EquivocationDetector detector = new EquivocationDetector();
        NodeId v = voter(1);
        StateRoot state = root(1);
        StateRoot firstTransition = root(2);
        StateRoot secondTransition = root(3);

        detector.observe(KEY, vote(v, state, firstTransition));
        detector.observe(KEY, vote(v, state, secondTransition));

        assertThat(detector.hasEquivoked(v)).isTrue();
        assertThat(detector.record(v)).hasValueSatisfying(record -> {
            assertThat(record.firstRoot()).isEqualTo(firstTransition);
            assertThat(record.secondRoot()).isEqualTo(secondTransition);
        });
    }

    @Test
    void differentKeysNotEquivocation() {
        EquivocationDetector detector = new EquivocationDetector();
        NodeId v = voter(1);
        ProposalKey k1 = new ProposalKey(
                new RegionId(DimensionKey.overworld(), 0, 0),
                new RegionEpoch(1),
                new SnapshotVersion(1));
        ProposalKey k2 = new ProposalKey(
                new RegionId(DimensionKey.overworld(), 0, 0),
                new RegionEpoch(1),
                new SnapshotVersion(2));

        detector.observe(k1, vote(v, root(1)));
        detector.observe(k2, vote(v, root(2)));

        assertThat(detector.hasEquivoked(v)).isFalse();
    }

    @Test
    void boundedCacheRetainsConfiguredMaxSize() {
        EquivocationDetector detector = new EquivocationDetector(128);
        assertThat(detector.maxSize()).isEqualTo(128L);
    }

    @Test
    void evictedVoterEquivocationStillFlagged() {
        // Byzantine evasion attempt: voter A votes root a, then floods the size-1 voter cache so
        // A's live history is evicted, then votes a conflicting root b for the SAME key. The
        // eviction-survivor pair cache must still convict A.
        EquivocationDetector detector = new EquivocationDetector(1);
        NodeId a = voter(1);

        detector.observe(KEY, vote(a, root(1)));
        for (long i = 2; i < 34; i++) {
            detector.observe(KEY, vote(voter(i), root(1)));
            detector.flushEvictions();
        }
        assertThat(detector.hasEquivoked(a)).isFalse();

        detector.observe(KEY, vote(a, root(2)));
        assertThat(detector.hasEquivoked(a)).isTrue();
        assertThat(detector.record(a)).hasValueSatisfying(record -> {
            assertThat(record.firstRoot()).isEqualTo(root(1));
            assertThat(record.secondRoot()).isEqualTo(root(2));
        });
    }

    @Test
    void detectedAtMillisComesFromInjectedClock() {
        EquivocationDetector detector = new EquivocationDetector(
                EquivocationDetector.DEFAULT_MAX_VOTERS, () -> 424242L);
        NodeId v = voter(1);

        detector.observe(KEY, vote(v, root(1)));
        detector.observe(KEY, vote(v, root(2)));

        assertThat(detector.record(v)).hasValueSatisfying(record ->
                assertThat(record.detectedAtMillis()).isEqualTo(424242L));
    }
}
