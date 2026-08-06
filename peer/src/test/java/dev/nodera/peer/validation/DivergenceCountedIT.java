package dev.nodera.peer.validation;

import dev.nodera.core.Bytes;
import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import dev.nodera.core.state.RegionSnapshot;
import dev.nodera.core.state.StateRoot;
import dev.nodera.coordinator.LeaseManager;
import dev.nodera.simulation.RegionEngine;
import dev.nodera.simulation.RegionExecutionResult;
import dev.nodera.testkit.peer.Await;
import dev.nodera.testkit.peer.PeerTestHarness;
import dev.nodera.testkit.peer.RegionFixtures;
import dev.nodera.testkit.peer.ValidationNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #5 — the Phase-1 exit gate is "hours of multi-client play, zero unexplained divergences",
 * which is a NUMBER, and until now nothing produced it.
 *
 * <p>Two nodes re-executing the same batch on the same base and disagreeing is the one outcome the
 * whole architecture is a bet against. The validator handled it correctly — it declines to vote —
 * but did so with a bare {@code return}, so a live soak could run for hours over a divergence and
 * report nothing but a quieter-than-usual mesh. These tests pin that a disagreement is counted from
 * both chairs, and that agreement leaves the counter alone (a divergence counter that drifts upward
 * during healthy play would be worse than none).
 */
final class DivergenceCountedIT {

    private final PeerTestHarness harness = PeerTestHarness.create();
    private final RegionId region = new RegionId(DimensionKey.overworld(), 0, 0);

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void aValidatorThatComputesADifferentWorldIsCounted() {
        NodeIdentity actor = NodeIdentity.generate();
        ValidationNode primary = harness.validationNode().build();
        // This validator's engine reports a corrupted root for the same inputs — a stand-in for the
        // thing the gate hunts: different hardware, different answer.
        ValidationNode validator = harness.validationNode()
                .engine(corrupting(harness.honestEngine()))
                .build();
        ValidationNode.mesh(List.of(primary, validator), actor);

        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, primary.nodeId(), List.of(validator.nodeId()), 0);
        primary.service().activateRegion(base, lease);
        validator.service().activateRegion(base, lease);

        assertThat(validator.service().divergences()).isZero();
        primary.service().proposeBatch(region, 1, 1, List.of(signedPlace(actor, 1)));

        Await.quietly(10_000, () -> validator.service().divergences() > 0);
        assertThat(validator.service().divergences())
                .as("the disagreement is a measurement, not a silent no-vote")
                .isGreaterThan(0);
        assertThat(validator.service().snapshot().divergences())
                .as("and it reaches the STATE reply a soak reads")
                .isEqualTo(validator.service().divergences());
    }

    @Test
    void agreementNeverIncrementsTheCounter() {
        NodeIdentity actor = NodeIdentity.generate();
        ValidationNode primary = harness.validationNode().build();
        ValidationNode validator = harness.validationNode().build();
        ValidationNode.mesh(List.of(primary, validator), actor);

        RegionSnapshot base = RegionFixtures.fullUniformSnapshot(region, 0);
        RegionLease lease = new LeaseManager(200).issue(
                region, primary.nodeId(), List.of(validator.nodeId()), 0);
        primary.service().activateRegion(base, lease);
        validator.service().activateRegion(base, lease);

        for (int i = 1; i <= 5; i++) {
            primary.service().proposeBatch(region, i, i, List.of(signedPlace(actor, i)));
        }

        assertThat(primary.service().snapshot().committeeCommits())
                .as("the batches really did go through the committee")
                .isGreaterThan(0);
        assertThat(primary.service().divergences()).isZero();
        assertThat(validator.service().divergences())
                .as("healthy play must leave the gate's number at zero")
                .isZero();
    }

    // --- fixture -----------------------------------------------------------------------------

    private ActionEnvelope signedPlace(NodeIdentity actor, long tick) {
        return RegionFixtures.place(actor, region, tick, tick, 5, 70, 5, 1);
    }

    private static RegionEngine corrupting(RegionEngine real) {
        return request -> {
            RegionExecutionResult r = real.execute(request);
            byte[] bytes = r.resultingRoot().hash().toArray();
            bytes[0] ^= (byte) 0xFF; // the delta stays honest; only the reported root diverges
            return new RegionExecutionResult(
                    r.delta(), StateRoot.of(Bytes.unsafeWrap(bytes)), r.stats());
        };
    }
}
