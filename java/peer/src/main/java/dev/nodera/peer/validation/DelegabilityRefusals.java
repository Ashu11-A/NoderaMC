package dev.nodera.peer.validation;

import dev.nodera.coordinator.DelegabilityPolicy;
import dev.nodera.protocol.simulationmsg.RegionRefusal;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which {@link DelegabilityPolicy} verdicts may be announced to other nodes, and as what.
 *
 * <p>The two enums are deliberately not the same set. {@code DelegabilityPolicy.Reason} is the full
 * rule set an <b>owner</b> evaluates against a region it holds; {@link RegionRefusal.Reason} is the
 * subset a node that owns <b>none</b> of the region can observe and announce. A refusal is advisory
 * — the recipient re-checks the condition against its own config before revoking — so announcing a
 * reason the recipient could never re-check would be sending a claim that can only be believed or
 * ignored, which is precisely the shape of message this protocol avoids.
 *
 * <p><b>Why this class lives in {@code peer}.</b> It is the only layer that can see both: the
 * policy is engine knowledge and the refusal is wire knowledge, and {@code transport} must not
 * depend on the engine (Task 0 §7 layering). Putting the mapping on {@code RegionRefusal} would
 * invert that dependency; putting it in {@code engine} would require the engine to know the wire.
 *
 * @Thread-context stateless; safe from any thread.
 */
public final class DelegabilityRefusals {

    private static final Map<DelegabilityPolicy.Reason, RegionRefusal.Reason> SHAREABLE =
            new EnumMap<>(DelegabilityPolicy.Reason.class);

    static {
        SHAREABLE.put(DelegabilityPolicy.Reason.UNSUPPORTED_PALETTE,
                RegionRefusal.Reason.UNSUPPORTED_PALETTE);
        SHAREABLE.put(DelegabilityPolicy.Reason.CHUNKS_NOT_LOADED,
                RegionRefusal.Reason.CHUNKS_NOT_LOADED);
        SHAREABLE.put(DelegabilityPolicy.Reason.FAKE_PLAYER_ACTIVE,
                RegionRefusal.Reason.FAKE_PLAYER_ACTIVE);
        SHAREABLE.put(DelegabilityPolicy.Reason.INTERFERENCE_RATE_HIGH,
                RegionRefusal.Reason.INTERFERENCE_RATE_HIGH);
        SHAREABLE.put(DelegabilityPolicy.Reason.NO_PLAYER_PRESENT,
                RegionRefusal.Reason.NO_PLAYER_PRESENT);
        SHAREABLE.put(DelegabilityPolicy.Reason.ENTITY_PRESENT,
                RegionRefusal.Reason.NON_DELEGABLE_ENTITY);
    }

    private DelegabilityRefusals() {
    }

    /**
     * @param reason a policy verdict.
     * @return the refusal to announce, or empty when this verdict is <b>not</b> announceable.
     *         Empty is the answer for every rule only an owner can evaluate: {@code
     *         NO_ELIGIBLE_NODES} is about the committee rather than the region,
     *         {@code CROSS_REGION_PENDING} and {@code GUARD_REQUIRED} are about this node's own
     *         in-flight state, {@code NEIGHBOR_UNSUPPORTED} needs the neighbour's replica, and
     *         {@code CONTRAPTION_CROSSES_VANILLA} is the migration lane's decision to make.
     */
    public static Optional<RegionRefusal.Reason> announceable(DelegabilityPolicy.Reason reason) {
        return reason == null ? Optional.empty() : Optional.ofNullable(SHAREABLE.get(reason));
    }

    /** @return whether a policy verdict may be announced to other nodes at all. */
    public static boolean isAnnounceable(DelegabilityPolicy.Reason reason) {
        return announceable(reason).isPresent();
    }
}
