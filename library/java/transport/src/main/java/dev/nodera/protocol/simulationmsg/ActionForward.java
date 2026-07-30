package dev.nodera.protocol.simulationmsg;

import dev.nodera.core.Bytes;
import dev.nodera.core.region.RegionId;
import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * "This signed action belongs to <i>your</i> region — propose it" (the no-host submission path).
 *
 * <p>Under decentralized region ownership every region has exactly one primary, and it is a
 * <b>player's node</b>, not a privileged server: an action captured on any member (today, the
 * session server that still runs the vanilla protocol) is forwarded to the region's primary, who
 * executes and proposes it to the committee like any other batch. The envelope rides opaquely as
 * its canonical bytes — the primary re-decodes and re-verifies the actor signature and admission
 * before proposing, so the forwarder is a courier, never an authority.
 *
 * <p>Thread-context: immutable record, safe for any thread.
 *
 * @param region          the region the action targets (the primary routes on it).
 * @param encodedEnvelope the canonically-encoded, actor-signed {@code ActionEnvelope}.
 */
public record ActionForward(RegionId region, Bytes encodedEnvelope) implements NoderaMessage {

    public ActionForward {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(encodedEnvelope, "encodedEnvelope");
    }

    @Override
    public String toString() {
        return "ActionForward[" + region + ", " + encodedEnvelope.length() + " B]";
    }
}
