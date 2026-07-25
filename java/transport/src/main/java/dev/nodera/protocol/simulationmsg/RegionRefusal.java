package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * "This region cannot be validated by anyone — stop trying" (L-60).
 *
 * <p>Some facts that disqualify a region from the validated lane are visible on a node that owns
 * <b>none</b> of it. The one this exists for: an entity kind the world never opted into
 * ({@code entity.mobCaptureDimensions}) is alive in the region. Under field-of-view ownership the
 * seats sit on the players' nodes while the session server — which is where entities actually
 * spawn and tick — usually owns nothing, so the node that <i>sees</i> the disqualifying entity is
 * routinely not the node that could act on it. Without this message the region simply stayed
 * unvalidated and silent, which is indistinguishable from working.
 *
 * <p>A refusal is not a vote and carries no authority: it says "I observed a condition your region
 * cannot be validated under". The recipient re-checks the same condition against its own config
 * before revoking, so a lying peer can at worst make a node re-examine a region it already owns.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region the region that cannot be validated.
 * @param reason why, as a stable code — see {@link Reason}.
 */
public record RegionRefusal(RegionId region, Reason reason) implements NoderaMessage {

    /** Why a region is being refused. Ordinals are wire values; append only. */
    public enum Reason {
        /** A non-delegable entity in a dimension that never opted into mob capture. */
        NON_DELEGABLE_ENTITY
    }

    public RegionRefusal {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(reason, "reason");
    }

    /** @return the reason's wire ordinal. */
    public int reasonCode() {
        return reason.ordinal();
    }

    /**
     * @param code the wire ordinal.
     * @return the reason it names.
     * @throws IllegalArgumentException if the code names no known reason — an unknown refusal is
     *                                  never silently treated as a known one.
     */
    public static Reason reasonOf(int code) {
        Reason[] values = Reason.values();
        if (code < 0 || code >= values.length) {
            throw new IllegalArgumentException("unknown RegionRefusal reason code: " + code);
        }
        return values[code];
    }

    @Override
    public String toString() {
        return "RegionRefusal[" + region + ", " + reason + "]";
    }
}
