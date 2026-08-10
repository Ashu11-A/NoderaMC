package dev.nodera.core.region;

/**
 * How much of a world a node claims to hold (server task 3, L-62).
 *
 * <p>{@code FULL} means: <i>this node can answer a request for any region's current certified head
 * at any time, without loading a chunk into the game.</i> It is backed by the peer's world store,
 * never by the server's chunk cache — a chunk unloaded in Minecraft is still held by the node.
 *
 * <p>Custody is a <b>claim</b>, and nothing in the network takes it on trust: every piece a node
 * serves is hash-verified by the receiver against the manifest, so a node claiming {@code FULL} it
 * cannot back simply fails to answer and is passed over. A spot-check auditor that would have
 * downgraded such a node to {@link #VIEW} was written for Task 21 and never reached from a
 * shipping entry point; it was deleted on 2026-08-06 (Plan 11 round 2, issue #210) rather than
 * left here as a check nobody runs.
 *
 * <p><b>Custody never buys authority.</b> It breaks ties in the ownership plan only on an exact
 * geometric distance tie, and never outranks distance (see {@code ViewOwnershipPlanner} and
 * {@code docs/server/LIMITATIONS.md} §C, "Capacity never buys authority").
 *
 * <p>Ordering is meaningful and is the ordinal order: {@code VIEW} &lt; {@code FULL}.
 *
 * <p>Thread-context: immutable, any thread.
 */
public enum CustodyClass {

    /** Holds whatever its play has caused it to hold; claims nothing. */
    VIEW,

    /** Claims every region of the world at its current certified head. */
    FULL;

    /** @return true when this class claims at least as much as {@code other}. */
    public boolean atLeast(CustodyClass other) {
        return ordinal() >= other.ordinal();
    }

    /**
     * Parse a configured custody value. Anything that is not exactly {@code full}
     * (case-insensitive, trimmed) is {@link #VIEW}: an unreadable value must never be read as the
     * stronger claim.
     *
     * @param raw the configured text; may be null.
     * @return the parsed class, {@link #VIEW} when absent or unrecognised.
     */
    public static CustodyClass parse(String raw) {
        return raw != null && "full".equalsIgnoreCase(raw.trim()) ? FULL : VIEW;
    }
}
