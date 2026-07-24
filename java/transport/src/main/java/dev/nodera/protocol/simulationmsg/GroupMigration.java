package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.region.RegionEpoch;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;

import java.util.List;
import java.util.Objects;

/**
 * Contraption-group migration order (Task 13 border lane), coordinator → committee members: a
 * redstone contraption whose {@code BorderSignal}s connect several delegated regions is moved
 * under ONE shared primary so its cross-border signals become internal. Every region in the
 * group gets a new epoch (the committee change is certified through the Task 9 chain); the
 * replayed border signal becomes the first item of the merged schedule.
 *
 * @param newPrimary the single primary that will own every region in the group; not null.
 * @param bumps      one entry per group region: the region and its NEW epoch; not null,
 *                   non-empty. The group is exactly the key set of this list.
 */
public record GroupMigration(
        NodeId newPrimary,
        List<RegionEpochBump> bumps) implements NoderaMessage {

    /** One group member's epoch bump. */
    public record RegionEpochBump(RegionId region, RegionEpoch newEpoch) {
        public RegionEpochBump {
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(newEpoch, "newEpoch");
        }
    }

    public GroupMigration {
        Objects.requireNonNull(newPrimary, "newPrimary");
        Objects.requireNonNull(bumps, "bumps");
        if (bumps.isEmpty()) {
            throw new IllegalArgumentException("a migration must cover at least one region");
        }
        bumps = List.copyOf(bumps);
    }
}
