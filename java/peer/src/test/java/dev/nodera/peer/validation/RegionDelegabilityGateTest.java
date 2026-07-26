package dev.nodera.peer.validation;

import dev.nodera.coordinator.DelegabilityMonitor;
import dev.nodera.coordinator.DelegabilityPolicy;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.simulationmsg.RegionRefusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The full delegability rule set reaching the mesh (engine task 7 / #11, step 4).
 *
 * <p>Before this, the live lane refused a region for one reason out of many: a region with an
 * unsupported palette or unloaded chunks stayed unvalidated and silent, which is indistinguishable
 * from working. These tests pin the three decisions that make widening it safe — revoke on the
 * edge and not on every tick, announce only what a recipient can re-check, and pick the announced
 * reason deterministically when a verdict has several.
 */
final class RegionDelegabilityGateTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);

    /** Records every announced refusal. */
    private static final class Announced {
        private final List<RegionRefusal.Reason> reasons = new ArrayList<>();

        boolean refuse(RegionId region, RegionRefusal.Reason reason) {
            reasons.add(reason);
            return true;
        }
    }

    private static DelegabilityPolicy.Inputs clean() {
        return DelegabilityPolicy.Inputs.delegableFlatMvp(3);
    }

    private static DelegabilityPolicy.Inputs withUnsupportedPalette() {
        return new DelegabilityPolicy.Inputs(
                false, true, 3, false, true, false, false, false, false, true, 0);
    }

    private static DelegabilityPolicy.Inputs withChunksNotLoaded() {
        return new DelegabilityPolicy.Inputs(
                true, false, 3, false, true, false, false, false, false, true, 0);
    }

    /** Only an owner can evaluate the committee's size, so it must never be announced. */
    private static DelegabilityPolicy.Inputs withNoEligibleNodes() {
        return new DelegabilityPolicy.Inputs(
                true, true, 0, false, true, false, false, false, false, true, 0);
    }

    private RegionDelegabilityGate gate(Announced announced, long cooldown) {
        return new RegionDelegabilityGate(
                new DelegabilityMonitor(new DelegabilityPolicy(3, true), cooldown),
                announced::refuse);
    }

    @Test
    @DisplayName("an unsupported palette now refuses the region — it used to say nothing at all")
    void anUnsupportedPaletteIsRefused() {
        Announced announced = new Announced();
        RegionDelegabilityGate gate = gate(announced, 0);

        RegionDelegabilityGate.Outcome outcome = gate.evaluate(REGION, withUnsupportedPalette(), 1);

        assertThat(outcome.revoked()).isTrue();
        assertThat(outcome.announced()).contains(RegionRefusal.Reason.UNSUPPORTED_PALETTE);
        assertThat(announced.reasons).containsExactly(RegionRefusal.Reason.UNSUPPORTED_PALETTE);
    }

    @Test
    @DisplayName("unloaded chunks are refused too, with their own reason")
    void unloadedChunksAreRefused() {
        Announced announced = new Announced();
        gate(announced, 0).evaluate(REGION, withChunksNotLoaded(), 1);
        assertThat(announced.reasons).containsExactly(RegionRefusal.Reason.CHUNKS_NOT_LOADED);
    }

    @Test
    @DisplayName("only the REVOKE edge announces — a region already out stays quiet")
    void onlyTheEdgeAnnounces() {
        Announced announced = new Announced();
        RegionDelegabilityGate gate = gate(announced, 100);

        assertThat(gate.evaluate(REGION, withUnsupportedPalette(), 1).revoked()).isTrue();
        for (long tick = 2; tick < 40; tick++) {
            assertThat(gate.evaluate(REGION, withUnsupportedPalette(), tick).revoked()).isFalse();
        }

        // Re-announcing every tick is exactly the log spam the refusal path exists to avoid.
        assertThat(announced.reasons).hasSize(1);
    }

    @Test
    @DisplayName("a verdict only an owner can evaluate revokes locally and announces nothing")
    void ownerOnlyVerdictsRevokeButDoNotAnnounce() {
        Announced announced = new Announced();
        RegionDelegabilityGate gate = gate(announced, 0);

        RegionDelegabilityGate.Outcome outcome = gate.evaluate(REGION, withNoEligibleNodes(), 1);

        // The region does leave the validated lane — that is a local decision this node is entitled
        // to make — but telling a peer "you have no eligible nodes" would be a claim about the
        // committee that the peer could never re-check.
        assertThat(outcome.revoked()).isTrue();
        assertThat(outcome.announced()).isEmpty();
        assertThat(announced.reasons).isEmpty();
    }

    @Test
    @DisplayName("restore is damped: a flapping region does not thrash between lanes")
    void restoreIsDamped() {
        Announced announced = new Announced();
        RegionDelegabilityGate gate = gate(announced, 20);

        gate.evaluate(REGION, withUnsupportedPalette(), 1);
        assertThat(gate.isDelegated(REGION)).isFalse();

        // Clean, but inside the cooldown: still out.
        assertThat(gate.evaluate(REGION, clean(), 5).transition())
                .isEqualTo(DelegabilityMonitor.Transition.REVOKED);
        // One dirty evaluation resets the streak — the whole point of the damping.
        gate.evaluate(REGION, withUnsupportedPalette(), 10);
        assertThat(gate.evaluate(REGION, clean(), 25).transition())
                .isEqualTo(DelegabilityMonitor.Transition.REVOKED);
        // A full clean cooldown restores it.
        assertThat(gate.evaluate(REGION, clean(), 50).transition())
                .isEqualTo(DelegabilityMonitor.Transition.RESTORE);
        assertThat(gate.isDelegated(REGION)).isTrue();

        // The revocations announced once each, not once per evaluation.
        assertThat(announced.reasons).hasSize(1);
    }

    @Test
    @DisplayName("a clean region is never refused")
    void aCleanRegionIsNeverRefused() {
        Announced announced = new Announced();
        RegionDelegabilityGate gate = gate(announced, 0);
        assertThat(gate.evaluate(REGION, clean(), 1).transition())
                .isEqualTo(DelegabilityMonitor.Transition.DELEGATED);
        assertThat(announced.reasons).isEmpty();
    }

    @Test
    @DisplayName("with several blocking reasons the announced one is deterministic")
    void theAnnouncedReasonIsDeterministic() {
        // Palette AND chunks both dirty: two nodes seeing the same region must announce the same
        // reason, so the choice follows the rule set's declaration order rather than a hash set's
        // iteration order.
        DelegabilityPolicy.Inputs both = new DelegabilityPolicy.Inputs(
                false, false, 3, false, true, false, false, false, false, true, 0);

        for (int run = 0; run < 5; run++) {
            Announced announced = new Announced();
            gate(announced, 0).evaluate(REGION, both, 1);
            assertThat(announced.reasons).containsExactly(RegionRefusal.Reason.UNSUPPORTED_PALETTE);
        }
    }

    @Test
    @DisplayName("nulls are refused rather than evaluated")
    void argumentsAreChecked() {
        Announced announced = new Announced();
        RegionDelegabilityGate gate = gate(announced, 0);
        assertThatThrownBy(() -> gate.evaluate(null, clean(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gate.evaluate(REGION, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegionDelegabilityGate(null, announced::refuse))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegionDelegabilityGate(
                new DelegabilityMonitor(new DelegabilityPolicy(3, true)), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
