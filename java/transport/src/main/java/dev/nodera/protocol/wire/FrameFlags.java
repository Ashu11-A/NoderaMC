package dev.nodera.protocol.wire;

/**
 * The frame header's flag bits (Task 14 phase 3, {@code Plan.7} §4.1).
 *
 * <p>They say what a frame <em>is for</em>, which the kind alone cannot: the same kind can be a
 * request in one direction and an unsolicited event in the other. Together with the correlation id
 * this is what lets a response be matched to the request that asked for it — and lets a response
 * nobody asked for be dropped before a handler sees it, which is the shape of several accepted
 * findings at once (a tracker answer for the wrong world merged into a pending fetch, an unsolicited
 * manifest answer filed against a request that never went out).
 *
 * <p>Thread-context: constants; any thread.
 */
public final class FrameFlags {

    private FrameFlags() {}

    /** No flags — used only by tests and by frames whose role is carried entirely by the kind. */
    public static final int NONE = 0x0000;

    /** This frame asks a question; the sender expects a {@link #RESPONSE} echoing its correlation. */
    public static final int REQUEST = 0x0001;

    /** This frame answers a {@link #REQUEST}; its correlation id must match a pending one. */
    public static final int RESPONSE = 0x0002;

    /** Unsolicited. Its correlation id is 0 and no pending entry is consulted. */
    public static final int EVENT = 0x0004;

    /** The sender will not read an answer, so a receiver should not spend one. */
    public static final int NO_REPLY_EXPECTED = 0x0008;

    /** Every bit this build assigns; anything outside is reserved and must be zero. */
    public static final int ASSIGNED = REQUEST | RESPONSE | EVENT | NO_REPLY_EXPECTED;

    /** @return {@code true} if {@code flags} has {@code bit} set. */
    public static boolean has(int flags, int bit) {
        return (flags & bit) == bit;
    }

    /** A short human-readable rendering, for logs and assertion messages. */
    public static String describe(int flags) {
        if (flags == NONE) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        if (has(flags, REQUEST)) {
            out.append("REQUEST|");
        }
        if (has(flags, RESPONSE)) {
            out.append("RESPONSE|");
        }
        if (has(flags, EVENT)) {
            out.append("EVENT|");
        }
        if (has(flags, NO_REPLY_EXPECTED)) {
            out.append("NO_REPLY_EXPECTED|");
        }
        int unassigned = flags & ~ASSIGNED;
        if (unassigned != 0) {
            out.append("reserved:0x").append(Integer.toHexString(unassigned)).append('|');
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }
}
