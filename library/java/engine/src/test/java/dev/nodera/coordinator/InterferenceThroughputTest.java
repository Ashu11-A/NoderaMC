package dev.nodera.coordinator;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.consensuscert.ServerAuthorityCertificate;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.state.NBlockPos;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.SnapshotVersion;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.interference.InterferenceBuffer;
import dev.nodera.coordinator.interference.InterferenceCommitter;
import dev.nodera.coordinator.interference.InterferenceStats;
import dev.nodera.coordinator.interference.MutationGuard;
import dev.nodera.coordinator.interference.MutationSource;
import dev.nodera.testkit.engine.EngineFixtures;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headless twin of {@code e2e-folia} F5 — [L-67]'s cost, measured inside {@code ./gradlew check}
 * rather than only in a five-minute soak on a machine that may not exist.
 *
 * <h2>What L-67 actually is</h2>
 *
 * <p>The NeoForge mod cancels vanilla scheduled ticks at the source ({@code LevelTicksMixin}), so in
 * a delegated region the engine is the only scheduler and redstone contributes <b>zero</b> foreign
 * writes. Bukkit has no equivalent and a plugin may not add a mixin, so an endpoint runs vanilla
 * redstone in delegated regions and reconciles every edge through the interference guard. The row
 * claims that is correct but costly. This suite measures the cost in the two units the exit clause
 * is stated in, and it is where those two numbers are pinned:
 *
 * <ul>
 *   <li><b>The certificate rate</b> — bounded by
 *       {@link InterferenceCommitter#setCommitIntervalTicks} and <i>not</i> by how many writes
 *       arrive. That is the property that makes the approximation survivable at all, and it is
 *       asserted here over the whole space of load and cadence rather than at one point.</li>
 *   <li><b>The revocation bound</b> — {@link NoderaConstants#INTERFERENCE_REVOKE_RATE} foreign
 *       writes per {@link NoderaConstants#INTERFERENCE_RATE_WINDOW_TICKS}. The second test measures
 *       one ordinary redstone clock against it, and the answer is the finding: a single clock
 *       exceeds the bound by an order of magnitude, so the two halves of L-67's exit clause cannot
 *       both hold at the current bound. That is a decision — recorded in
 *       {@code docs/server/Task.5.md} §Design — and not something code should quietly paper over.</li>
 * </ul>
 *
 * <p>If someone moves either constant, one of these fails and points at the derivation, which is the
 * only way a threshold written in a document stays true.
 */
class InterferenceThroughputTest {

    private final RegionId region = EngineFixtures.region(0, 0);

    /**
     * A certified external delta costs a whole-region re-extract and a SHA-256, so what bounds an
     * endpoint's redstone cost is the CADENCE, never the write rate. Over {@code ticks} calls at
     * interval {@code commitInterval}, exactly {@code floor(ticks / commitInterval)} certificates
     * are emitted — whether one block changed per tick or sixty-four did.
     */
    @Property(tries = 50)
    void theCertificateRateFollowsTheCadenceAndIgnoresTheWriteVolume(
            @ForAll @IntRange(min = 1, max = 64) int writesPerTick,
            @ForAll @IntRange(min = 1, max = 40) int commitInterval,
            @ForAll @IntRange(min = 1, max = 200) int ticks) {

        Harness harness = new Harness(commitInterval);
        harness.drive(writesPerTick, ticks);

        assertThat(harness.certificates).hasSize(ticks / commitInterval);
        // Every certificate is an authority claim over one version step, so the chain must be
        // unbroken however aggressively the writes behind it were coalesced.
        for (int i = 0; i < harness.certificates.size(); i++) {
            assertThat(harness.certificates.get(i).baseVersion())
                    .isEqualTo(new SnapshotVersion(i));
        }
    }

    /**
     * The same claim stated the way an operator would ask it: sixty-four times the redstone does not
     * cost sixty-four times the certificates. This is the point of the cadence, and it is what makes
     * L-67's approximation a latency cost rather than an unbounded one.
     */
    @Test
    void sixtyFourTimesTheLoadCostsTheSameNumberOfCertificates() {
        Harness light = new Harness(10);
        Harness heavy = new Harness(10);

        light.drive(1, 200);
        heavy.drive(64, 200);

        assertThat(light.certificates).hasSize(20);
        assertThat(heavy.certificates).hasSize(20);
        assertThat(heavy.convertedWrites()).isEqualTo(64L * light.convertedWrites());
    }

    /**
     * The measurement the exit clause hides, and the reason it cannot be met as written.
     *
     * <p>A two-tick repeater clock is the ordinary case, not a stress test: it toggles a block every
     * two ticks, which is 0.5 edges a tick and 600 over the one-minute window the revocation bound
     * is measured across. The bound is 60. So on an endpoint — where every one of those edges is a
     * foreign write, because there is no mixin to cancel the scheduled tick — a single vanilla
     * redstone clock in a delegated region revokes that region ten times over, and F5's second
     * clause ("no region is revoked for interference rate") is unsatisfiable at the current bound
     * under exactly the load its first clause requires.
     */
    @Test
    void oneOrdinaryRedstoneClockExceedsTheRevocationBoundTenfold() {
        InterferenceStats stats = new InterferenceStats();
        int edgesPerWindow = 0;

        for (int tick = 0; tick < NoderaConstants.INTERFERENCE_RATE_WINDOW_TICKS; tick++) {
            if (tick % 2 == 0) {
                stats.record(region, MutationSource.SCHEDULED);
                edgesPerWindow++;
            }
            stats.advanceTick();
        }

        assertThat(edgesPerWindow).isEqualTo(600);
        assertThat(stats.ratePerWindow(region))
                .isGreaterThan(NoderaConstants.INTERFERENCE_REVOKE_RATE * 9L);

        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        DelegabilityPolicy.Inputs underRedstone = new DelegabilityPolicy.Inputs(
                true, true, 3, false, true, false, false, false, false, true,
                stats.ratePerWindow(region));
        assertThat(policy.evaluate(region, underRedstone).reasons())
                .containsExactly(DelegabilityPolicy.Reason.INTERFERENCE_RATE_HIGH);
    }

    /**
     * The same clock on the modded host, for contrast: {@code LevelTicksMixin} cancels the scheduled
     * tick before it ever enters the vanilla queue, so nothing is recorded and the region stays
     * delegated. This is what "the endpoint approximates rather than matches" costs, expressed as
     * the difference between two numbers instead of as a sentence.
     */
    @Test
    void theSameClockOnAModdedHostCostsNothingBecauseTheTickIsCancelledAtTheSource() {
        InterferenceStats stats = new InterferenceStats();
        for (int tick = 0; tick < NoderaConstants.INTERFERENCE_RATE_WINDOW_TICKS; tick++) {
            // The mixin cancels at LevelTicks.schedule, so the write never happens: the guard is
            // never consulted and the stats never see an edge.
            stats.advanceTick();
        }

        assertThat(stats.ratePerWindow(region)).isZero();
        DelegabilityPolicy policy = new DelegabilityPolicy(3, true);
        assertThat(policy.evaluate(region, new DelegabilityPolicy.Inputs(
                true, true, 3, false, true, false, false, false, false, true, 0)).isDelegable())
                .isTrue();
    }

    /** The committer, its buffer, its guard and its world, wired the way production wires them. */
    private final class Harness {

        private final InMemoryWorldView world = new InMemoryWorldView();
        private final InterferenceBuffer buffer = new InterferenceBuffer();
        private final InterferenceStats stats = new InterferenceStats();
        private final List<ServerAuthorityCertificate> certificates = new ArrayList<>();
        private final MutationGuard guard;
        private final InterferenceCommitter committer;
        private int written;

        Harness(int commitInterval) {
            world.load(EngineFixtures.fullUniformSnapshot(region, 0));
            guard = new MutationGuard(
                    r -> r.equals(region), MutationGuard.Mode.CONVERT, buffer, stats);
            committer = new InterferenceCommitter(
                    buffer,
                    this::root,
                    world::setSnapshotBodyVersion,
                    (RegionDelta delta, ServerAuthorityCertificate certificate)
                            -> certificates.add(certificate),
                    NodeIdentity.generate());
            committer.onCommittedVersion(region, SnapshotVersion.INITIAL);
            committer.setCommitIntervalTicks(commitInterval);
        }

        /** {@code writesPerTick} distinct foreign writes on each of {@code ticks} ticks. */
        void drive(int writesPerTick, int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                for (int write = 0; write < writesPerTick; write++) {
                    foreignWrite();
                }
                committer.onTickEnd(r -> PipelineState.ACTIVE);
            }
        }

        long convertedWrites() {
            return guard.convertedWrites();
        }

        /**
         * One redstone edge, at a position no earlier write has touched.
         *
         * <p>Distinct positions on purpose: {@link InterferenceBuffer} coalesces per block, and a
         * position toggled an even number of times inside one commit window drains to nothing. A
         * harness that let that happen would measure the buffer's coalescing rather than the
         * cadence, and would read as "the cadence bounds everything" for the wrong reason.
         */
        private void foreignWrite() {
            int n = written++;
            NBlockPos pos = new NBlockPos(n % 128, 64 + (n / 16_384) % 16, (n / 128) % 128);
            int previous = world.getBlock(region, pos);
            guard.withSource(MutationSource.SCHEDULED, () -> {
                assertThat(guard.verdict(region, pos, previous, 7))
                        .isEqualTo(MutationGuard.Verdict.CONVERT);
                world.setBlock(region, pos, 7);
            });
        }

        private StateRoot root(RegionId r, SnapshotVersion version, int bodyVersion) {
            RegionSnapshot extracted = world.reExtract(r, version, 0L);
            RegionSnapshot encoded = new RegionSnapshot(
                    r, version, extracted.tick(), extracted.chunks(), extracted.entities(),
                    bodyVersion);
            return StateRoot.of(EngineFixtures.hashes().hash(encoded));
        }
    }
}
