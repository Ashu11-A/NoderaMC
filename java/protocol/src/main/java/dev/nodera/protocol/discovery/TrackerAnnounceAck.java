package dev.nodera.protocol.discovery;

import dev.nodera.protocol.NoderaMessage;

import java.util.Objects;

/**
 * The tracker's reply to a {@link TrackerAnnounce} (Task 28, wire tag 34).
 *
 * <p><b>The tracker paces the traffic, not the peer</b> ({@code docs/torrent/trackers.md} §6):
 * {@link #nextAnnounceAfterSeconds()} tells the peer when to come back, so an operator can widen
 * the interval under load without shipping a new client. Peers jitter around it; they do not
 * shorten it.
 *
 * <p>A rejection is an ack too. Silently dropping a bad announce would leave the peer retrying
 * forever against a tracker that will never accept it, so {@link #accepted()} is {@code false}
 * with a machine-stable {@link #reason()} the peer can log and act on (fix the clock, slow down,
 * shrink the record).
 *
 * @param accepted                 whether the record was registered/refreshed/removed.
 * @param nextAnnounceAfterSeconds the interval before the next announce; positive even on
 *                                 rejection, so a rejected peer still backs off rather than
 *                                 hot-looping.
 * @param reason                   {@code ""} when accepted; otherwise a short stable code such as
 *                                 {@code "bad-signature"}, {@code "stale-announce"},
 *                                 {@code "quota"}, {@code "too-large"}, {@code "world-limit"}.
 * @Thread-context immutable record, safe for any thread.
 */
public record TrackerAnnounceAck(
        boolean accepted,
        int nextAnnounceAfterSeconds,
        String reason
) implements NoderaMessage {

    /**
     * Compact constructor.
     *
     * @throws IllegalArgumentException if {@code reason} is null or the interval is not positive.
     */
    public TrackerAnnounceAck {
        Objects.requireNonNull(reason, "reason");
        if (nextAnnounceAfterSeconds <= 0) {
            throw new IllegalArgumentException(
                    "nextAnnounceAfterSeconds must be positive: " + nextAnnounceAfterSeconds);
        }
    }

    /**
     * @param nextAnnounceAfterSeconds the interval the tracker wants.
     * @return an accepting ack.
     * @Thread-context any thread.
     */
    public static TrackerAnnounceAck accepted(int nextAnnounceAfterSeconds) {
        return new TrackerAnnounceAck(true, nextAnnounceAfterSeconds, "");
    }

    /**
     * @param nextAnnounceAfterSeconds the back-off the tracker wants before a retry.
     * @param reason                   the stable rejection code.
     * @return a rejecting ack.
     * @Thread-context any thread.
     */
    public static TrackerAnnounceAck rejected(int nextAnnounceAfterSeconds, String reason) {
        return new TrackerAnnounceAck(false, nextAnnounceAfterSeconds, reason);
    }
}
