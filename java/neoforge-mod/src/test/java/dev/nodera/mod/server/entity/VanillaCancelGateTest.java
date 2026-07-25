package dev.nodera.mod.server.entity;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.DimensionKey;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.core.region.RegionLease;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #44 — the drop/pickup contract, red-first: the validated lane must never cancel a vanilla
 * outcome against an <b>unconfirmed</b> commit.
 *
 * <p>The live repro was a player who could not drop items: the toss was cancelled the moment
 * {@code submitDrop} <i>accepted</i> the action, but on the forward path acceptance is
 * fire-and-forget to a remote primary. With that primary stalled (the laggy peer of issue #46) the
 * cancelled vanilla drop never materialised and the stack snapped back into the inventory. The
 * pickup lane had learned exactly this in issue #33. Both now ask the same question, and it is
 * about ownership rather than acceptance.
 */
final class VanillaCancelGateTest {

    private static final RegionId REGION = new RegionId(DimensionKey.overworld(), 0, 0);
    private static final NodeId SELF = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final NodeId REMOTE = new NodeId(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    private static Optional<RegionLease> lease(NodeId primary, NodeId... validators) {
        return Optional.of(new RegionLease(REGION, RegionEpoch.INITIAL, primary,
                List.of(validators), 0, 200));
    }

    @Test
    void theRegionsPrimaryMayCancelVanillaBecauseItsCommitIsSynchronous() {
        assertThat(VanillaCancelGate.mayCancelVanilla(lease(SELF, REMOTE), SELF)).isTrue();
    }

    @Test
    void aValidatorMustNotCancelVanillaAgainstARemotePrimarysUnconfirmedCommit() {
        // The #44 failure: this node forwards to REMOTE and gets "accepted" back immediately.
        // If REMOTE is stalled, the item exists nowhere — so vanilla must be left alone.
        assertThat(VanillaCancelGate.mayCancelVanilla(lease(REMOTE, SELF), SELF)).isFalse();
    }

    @Test
    void anUndelegatedRegionIsPlainVanillaAndIsNeverCancelled() {
        assertThat(VanillaCancelGate.mayCancelVanilla(Optional.empty(), SELF)).isFalse();
    }

    @Test
    void aMissingLeaseOrIdentityFailsClosedRatherThanCancelling() {
        assertThat(VanillaCancelGate.mayCancelVanilla(null, SELF)).isFalse();
        assertThat(VanillaCancelGate.mayCancelVanilla(lease(SELF), null)).isFalse();
    }
}
