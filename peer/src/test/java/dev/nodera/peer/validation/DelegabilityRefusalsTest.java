package dev.nodera.peer.validation;

import dev.nodera.coordinator.DelegabilityPolicy;
import dev.nodera.protocol.simulationmsg.RegionRefusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boundary between what an owner can evaluate and what an observer can announce.
 *
 * <p>Until now the live lane refused a region for exactly one reason out of the rule set's many, so
 * a region with an unsupported palette or unloaded chunks was never refused at all. Widening that
 * is not "announce every verdict": a refusal is advisory and the recipient re-checks the condition
 * itself, so a verdict it could never re-check must not be announced.
 */
final class DelegabilityRefusalsTest {

    @Test
    @DisplayName("the observable rules map to a refusal a recipient can re-check")
    void observableVerdictsAreAnnounceable() {
        assertThat(DelegabilityRefusals.announceable(DelegabilityPolicy.Reason.UNSUPPORTED_PALETTE))
                .contains(RegionRefusal.Reason.UNSUPPORTED_PALETTE);
        assertThat(DelegabilityRefusals.announceable(DelegabilityPolicy.Reason.CHUNKS_NOT_LOADED))
                .contains(RegionRefusal.Reason.CHUNKS_NOT_LOADED);
        assertThat(DelegabilityRefusals.announceable(DelegabilityPolicy.Reason.FAKE_PLAYER_ACTIVE))
                .contains(RegionRefusal.Reason.FAKE_PLAYER_ACTIVE);
        assertThat(DelegabilityRefusals.announceable(
                DelegabilityPolicy.Reason.INTERFERENCE_RATE_HIGH))
                .contains(RegionRefusal.Reason.INTERFERENCE_RATE_HIGH);
        assertThat(DelegabilityRefusals.announceable(DelegabilityPolicy.Reason.NO_PLAYER_PRESENT))
                .contains(RegionRefusal.Reason.NO_PLAYER_PRESENT);
        // The one reason the live lane already announced keeps its existing wire meaning.
        assertThat(DelegabilityRefusals.announceable(DelegabilityPolicy.Reason.ENTITY_PRESENT))
                .contains(RegionRefusal.Reason.NON_DELEGABLE_ENTITY);
    }

    @Test
    @DisplayName("a verdict only an owner can evaluate is never announced")
    void ownerOnlyVerdictsAreNotAnnounceable() {
        // Each of these would be a claim the recipient cannot verify for itself: about the
        // committee, about this node's in-flight state, about a neighbour's replica, or about a
        // decision that belongs to the migration lane.
        assertThat(DelegabilityRefusals.isAnnounceable(
                DelegabilityPolicy.Reason.NO_ELIGIBLE_NODES)).isFalse();
        assertThat(DelegabilityRefusals.isAnnounceable(
                DelegabilityPolicy.Reason.CROSS_REGION_PENDING)).isFalse();
        assertThat(DelegabilityRefusals.isAnnounceable(
                DelegabilityPolicy.Reason.GUARD_REQUIRED)).isFalse();
        assertThat(DelegabilityRefusals.isAnnounceable(
                DelegabilityPolicy.Reason.NEIGHBOR_UNSUPPORTED)).isFalse();
        assertThat(DelegabilityRefusals.isAnnounceable(
                DelegabilityPolicy.Reason.CONTRAPTION_CROSSES_VANILLA)).isFalse();
        assertThat(DelegabilityRefusals.announceable(null)).isEmpty();
    }

    @Test
    @DisplayName("every announceable reason survives a wire round trip")
    void announceableReasonsRoundTrip() {
        for (DelegabilityPolicy.Reason verdict : DelegabilityPolicy.Reason.values()) {
            DelegabilityRefusals.announceable(verdict).ifPresent(reason -> {
                int code = new RegionRefusal(
                        new dev.nodera.core.region.RegionId(
                                dev.nodera.core.region.DimensionKey.overworld(), 0, 0),
                        reason).reasonCode();
                assertThat(RegionRefusal.reasonOf(code)).isEqualTo(reason);
            });
        }
    }

    @Test
    @DisplayName("a reason newer than this build decodes to UNKNOWN and is never re-encoded")
    void anUnknownReasonIsExplicitAndUnsendable() {
        // Forward compatibility: decoding used to throw, which turned a newer peer's ordinary
        // refusal into an exception inside an older peer's decode path.
        assertThat(RegionRefusal.reasonOf(9_999)).isEqualTo(RegionRefusal.Reason.UNKNOWN);
        assertThat(RegionRefusal.reasonOf(-1)).isEqualTo(RegionRefusal.Reason.UNKNOWN);
        assertThat(RegionRefusal.reasonOf(RegionRefusal.Reason.UNKNOWN.ordinal()))
                .isEqualTo(RegionRefusal.Reason.UNKNOWN);

        // And it is unequal to every real reason, so no handler can confuse the two — which is the
        // invariant the old throw existed to protect, kept rather than weakened.
        for (RegionRefusal.Reason r : RegionRefusal.Reason.values()) {
            if (r != RegionRefusal.Reason.UNKNOWN) {
                assertThat(r).isNotEqualTo(RegionRefusal.Reason.UNKNOWN);
            }
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RegionRefusal(
                new dev.nodera.core.region.RegionId(
                        dev.nodera.core.region.DimensionKey.overworld(), 0, 0),
                RegionRefusal.Reason.UNKNOWN).reasonCode())
                .isInstanceOf(IllegalStateException.class);
    }
}
