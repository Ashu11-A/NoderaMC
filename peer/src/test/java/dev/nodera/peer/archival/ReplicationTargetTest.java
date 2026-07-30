package dev.nodera.peer.archival;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The arithmetic behind "how many copies, and how much disk does that cost each peer".
 *
 * <p>These are not characterisation tests. Each one pins a number somebody could otherwise change
 * by feel: the copy count comes from {@code ln(1-D)/ln(1-p)}, and the per-peer share comes from
 * {@code R/N}. If a default moves, the expected values here have to be recomputed by hand — which
 * is the point, because the alternative is a durability constant nobody can defend.
 */
final class ReplicationTargetTest {

    private static final ReplicationTarget STANDARD = ReplicationTarget.standard();

    @Test
    @DisplayName("a network smaller than the target keeps a full copy on every peer")
    void aSmallNetworkStoresEverythingEverywhere() {
        // The rule this whole class exists to make true: with few peers there is no configuration
        // more durable than "everybody holds everything", so that is what gets placed. Holding back
        // copies a small network could have made is choosing to be less safe for no saving.
        for (int peers = 1; peers <= STANDARD.idealReplicas(); peers++) {
            assertThat(STANDARD.replicasFor(peers))
                    .as("%d peer(s)", peers)
                    .isEqualTo(peers);
            assertThat(STANDARD.shareOfNetworkPermille(peers))
                    .as("%d peer(s) each store all of it", peers)
                    .isEqualTo(1000);
        }
    }

    @Test
    @DisplayName("the copy count is ln(1-D)/ln(1-p), not a taste")
    void theIdealCountFollowsFromAvailabilityAndDurability() {
        // p = 0.35, D = 0.9999 → ln(0.0001)/ln(0.65) = 21.38 → 22.
        assertThat(STANDARD.idealReplicas()).isEqualTo(22);

        // Reliable peers need far fewer copies. p = 0.9 → ln(0.0001)/ln(0.1) = 4.
        assertThat(new ReplicationTarget(900, 9999).idealReplicas()).isEqualTo(4);

        // A modest target needs fewer still. p = 0.35, D = 0.99 → ln(0.01)/ln(0.65) = 10.7 → 11.
        assertThat(new ReplicationTarget(350, 9900).idealReplicas()).isEqualTo(11);

        // Very unreliable peers hit the ceiling rather than asking for an unbounded number.
        assertThat(new ReplicationTarget(10, 9999).idealReplicas())
                .isEqualTo(ReplicationTarget.MAX_REPLICAS);
    }

    @Test
    @DisplayName("a peer's share of the network's data falls as the network grows")
    void theShareEachPeerStoresFallsWithNetworkSize() {
        int ideal = STANDARD.idealReplicas(); // 22

        // At and below the target size: all of it.
        assertThat(STANDARD.shareOfNetworkPermille(ideal)).isEqualTo(1000);

        // Beyond it, R stops growing and the share is R/N.
        assertThat(STANDARD.replicasFor(50)).isEqualTo(ideal);
        assertThat(STANDARD.shareOfNetworkPermille(50)).isEqualTo(440); // 22/50
        assertThat(STANDARD.shareOfNetworkPermille(200)).isEqualTo(110); // 22/200
        assertThat(STANDARD.shareOfNetworkPermille(1000)).isEqualTo(22); // 22/1000

        // Durability did not fall with it — that is the whole argument for a target over a fraction.
        assertThat(STANDARD.lossRiskPermille(STANDARD.replicasFor(1000)))
                .isEqualTo(STANDARD.lossRiskPermille(STANDARD.replicasFor(ideal)));
    }

    @Test
    @DisplayName("the risk of a world being unreachable is stated, not assumed")
    void lossRiskIsComputableFromTheCopyCount() {
        // (1 - 0.35)^R. These are the numbers that make the copy count arguable on a screen.
        assertThat(STANDARD.lossRiskPermille(1)).isEqualTo(650);  // one copy: usually offline
        assertThat(STANDARD.lossRiskPermille(3)).isEqualTo(275);  // 27% — three copies is not safe
        assertThat(STANDARD.lossRiskPermille(5)).isEqualTo(116);  // the old fixed ×5
        assertThat(STANDARD.lossRiskPermille(10)).isEqualTo(13);
        assertThat(STANDARD.lossRiskPermille(22)).isZero();       // <0.05‰, the target

        // No copies is not "probably fine".
        assertThat(STANDARD.lossRiskPermille(0)).isEqualTo(1000);
    }

    @Test
    @DisplayName("a world with nowhere to go asks for nothing")
    void anEmptyNetworkPlacesNoCopies() {
        assertThat(STANDARD.replicasFor(0)).isZero();
        assertThat(STANDARD.shareOfNetworkPermille(0)).isZero();
        assertThat(STANDARD.replicasFor(-3)).isZero();
    }

    @Test
    @DisplayName("nonsense parameters are refused rather than silently clamped")
    void theParametersAreBounded() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReplicationTarget(0, 9999));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReplicationTarget(1000, 9999));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReplicationTarget(350, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReplicationTarget(350, 10_000));
    }

    @Test
    @DisplayName("world-archive placement asks every peer of a small network to hold the world")
    void theWorldArchiveFactorsAdoptTheTarget() {
        ReplicationFactors worlds = ReplicationFactors.forWorldArchives(STANDARD);

        // Under the spec's fixed ×5 a fifty-peer swarm placed five copies of a world — 12% of the
        // time nobody had it. Under the target, a network at or below 22 peers holds it everywhere.
        for (int peers = 1; peers <= STANDARD.idealReplicas(); peers++) {
            assertThat(worlds.factor(ArchiveObjectClass.SNAPSHOT, peers))
                    .as("%d peer(s) — everybody holds it", peers)
                    .isEqualTo(peers);
        }
        assertThat(worlds.factor(ArchiveObjectClass.SNAPSHOT, 100))
                .isEqualTo(STANDARD.idealReplicas());

        // The other classes are untouched. They are spec constants about replicated simulation
        // state, and this reasoning — home machines, whole copies, one survivor is enough — does
        // not describe them. Re-tuning them here would quietly change the repair lane.
        assertThat(worlds.recentLog()).isEqualTo(ReplicationFactors.SPEC.recentLog());
        assertThat(worlds.compacted()).isEqualTo(ReplicationFactors.SPEC.compacted());
        assertThat(ReplicationFactors.spec().factor(ArchiveObjectClass.SNAPSHOT, 100)).isEqualTo(5);
    }
}
