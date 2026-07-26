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

    /**
     * Why a region is being refused. Ordinals are wire values; <b>append only</b>, and always
     * before {@link #UNKNOWN}.
     */
    public enum Reason {
        /** A non-delegable entity in a dimension that never opted into mob capture. */
        NON_DELEGABLE_ENTITY,

        // The delegability rule set, as far as it is OBSERVABLE by a node that owns none of the
        // region — which is this message's whole premise. `DelegabilityPolicy` enumerates more
        // reasons than these; the ones left off are the ones only an owner can evaluate, and a
        // refusal about them would be a claim the recipient could never re-check.

        /** The region, or its halo footprint, contains a block outside the validated palette. */
        UNSUPPORTED_PALETTE,
        /** Not all of the region's chunks are loaded. */
        CHUNKS_NOT_LOADED,
        /** A fake player is mutating the region. */
        FAKE_PLAYER_ACTIVE,
        /** The measured foreign-mutation rate is above the revoke threshold. */
        INTERFERENCE_RATE_HIGH,
        /** The region is held open only by a foreign ticket, with no player's view owning it. */
        NO_PLAYER_PRESENT,

        /**
         * A reason this build does not know — a peer newer than this one refused a region for a
         * cause that had not been defined when this build shipped.
         *
         * <p><b>This is never sent.</b> {@link #reasonCode()} refuses to encode it, so it cannot
         * travel; it exists only as what {@link #reasonOf(int)} produces for a code outside this
         * enum. It carries no ordinal commitment for the same reason — appending a real reason in
         * front of it changes its position and nothing else, because its position is never
         * written.
         *
         * <p><b>Why this is stricter than throwing, not laxer.</b> The rule was, and remains, that
         * an unknown refusal is never silently treated as a known one — and an unknown reason is
         * unequal to every known one, so no handler can confuse the two. What changes is the blast
         * radius: decoding used to throw, which turns a newer peer's ordinary refusal into an
         * exception in an older peer's decode path, and a message that cannot be parsed is a worse
         * outcome than one that is understood and declined. A refusal is advisory anyway — the
         * recipient re-checks the condition against its own config before revoking — so a
         * condition it cannot name is a condition it cannot re-check, and doing nothing is the
         * only correct response.
         */
        UNKNOWN
    }

    public RegionRefusal {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * @return the reason's wire ordinal.
     * @throws IllegalStateException if the reason is {@link Reason#UNKNOWN} — re-encoding a reason
     *                               this build could not understand would forward a claim it
     *                               cannot check, under an ordinal that means something else here.
     */
    public int reasonCode() {
        if (reason == Reason.UNKNOWN) {
            throw new IllegalStateException("an unknown refusal reason is never re-encoded");
        }
        return reason.ordinal();
    }

    /**
     * @param code the wire ordinal.
     * @return the reason it names, or {@link Reason#UNKNOWN} for a code this build does not define
     *         — an unknown refusal is never silently treated as a known one, and is no longer an
     *         exception in the decode path of a peer older than the sender.
     */
    public static Reason reasonOf(int code) {
        Reason[] values = Reason.values();
        if (code < 0 || code >= values.length || values[code] == Reason.UNKNOWN) {
            return Reason.UNKNOWN;
        }
        return values[code];
    }

    @Override
    public String toString() {
        return "RegionRefusal[" + region + ", " + reason + "]";
    }
}
