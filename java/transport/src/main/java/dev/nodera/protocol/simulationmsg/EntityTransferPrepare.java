package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.state.EntityTransferDescriptor;
import dev.nodera.core.state.RegionDelta;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/** Primary request asking both region committees to approve one exact transfer plan. */
public record EntityTransferPrepare(
        EntityTransferDescriptor descriptor,
        RegionDelta sourceDelta,
        RegionDelta targetDelta
) implements NoderaMessage {

    public EntityTransferPrepare {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(sourceDelta, "sourceDelta");
        Objects.requireNonNull(targetDelta, "targetDelta");
        if (!descriptor.sourceRegion().equals(sourceDelta.region())
                || !descriptor.targetRegion().equals(targetDelta.region())) {
            throw new IllegalArgumentException("transfer deltas do not match descriptor regions");
        }
    }
}
