package dev.nodera.core.state;

import dev.nodera.core.NoderaConstants;
import dev.nodera.core.identity.NodeId;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One node's source of {@link Hlc} readings.
 *
 * <p>Two operations, and the second is the one that matters. {@link #now()} issues the next reading.
 * {@link #observe(Hlc)} is called with every remote reading this node learns about, and drags the
 * local clock up to it — which is what makes "newer" mean the same thing on two machines whose wall
 * clocks disagree. A node that never observes is a node that will eventually insist its own stale
 * edits are the freshest, and be told so by nobody.
 *
 * <p>Readings are monotone: {@link #now()} never returns a value less than or equal to one it has
 * already returned, nor one it has observed, even if the wall clock jumps backwards (NTP correction,
 * a suspended laptop, a container's clock being set at start-up). The wall component can stall; the
 * counter then carries the ordering until real time catches up.
 *
 * @Thread-context thread-safe; every reading is issued under one lock, which is also what makes
 *                 "strictly increasing" true rather than usually-true.
 */
public final class HybridClock {

    private final NodeId node;
    private final LongSupplier wallClock;

    private long lastWall;
    private long lastCounter;
    private long rejected;

    /**
     * @param node the issuing node — the tie-break every reading carries.
     */
    public HybridClock(NodeId node) {
        this(node, System::currentTimeMillis);
    }

    /**
     * @param node      the issuing node.
     * @param wallClock the wall-clock source; injectable so the ordering rules can be tested
     *                  against a clock that jumps, stalls and runs backwards on demand.
     */
    public HybridClock(NodeId node, LongSupplier wallClock) {
        this.node = Objects.requireNonNull(node, "node");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    }

    /**
     * @return the next reading, strictly after every reading this clock has issued or observed.
     * @Thread-context any thread.
     */
    public synchronized Hlc now() {
        long wall = wallClock.getAsLong();
        if (wall > lastWall) {
            lastWall = wall;
            lastCounter = 0;
        } else {
            // The wall clock did not move forward — same millisecond, or it went backwards. Either
            // way the counter is what keeps the sequence strictly increasing, and refusing to lower
            // `lastWall` is what stops a backwards jump from re-issuing readings that already exist.
            lastCounter++;
        }
        return new Hlc(lastWall, lastCounter, node.value());
    }

    /**
     * Learn about a remote reading.
     *
     * <h2>Why this refuses some readings</h2>
     *
     * <p>Dragging the local clock forward is the whole point of observing, and it is also the attack.
     * A merge resolves by "most recent wins", so a peer that announces a reading far in the future
     * both wins every merge it takes part in and — because every clock that hears it adopts it —
     * makes every honest peer agree that it should. One packet, and a region is owned forever.
     *
     * <p>So a reading more than {@link NoderaConstants#MAX_CLOCK_SKEW_MILLIS} ahead of this node's own
     * wall clock is <b>not adopted</b>. It is counted and it is not an error: the sender may simply
     * have a badly set clock, and refusing to move is the correct response either way. The reading
     * still travels in the stamp it arrived on, so nothing is silently rewritten — this only governs
     * what this node's own future readings are anchored to.
     *
     * @param remote a reading seen from another node; {@code null} is ignored.
     * @return whether the reading was adopted; {@code false} means it was beyond the skew bound.
     * @Thread-context any thread.
     */
    public synchronized boolean observe(Hlc remote) {
        if (remote == null) {
            return false;
        }
        long horizon = wallClock.getAsLong() + NoderaConstants.MAX_CLOCK_SKEW_MILLIS;
        if (remote.wallMillis() > horizon) {
            rejected++;
            return false;
        }
        if (remote.wallMillis() > lastWall) {
            lastWall = remote.wallMillis();
            lastCounter = remote.counter();
        } else if (remote.wallMillis() == lastWall && remote.counter() > lastCounter) {
            lastCounter = remote.counter();
        }
        return true;
    }

    /**
     * @return how many observed readings were refused for being beyond the skew bound — a
     *         non-zero count means either a peer's clock is wrong or somebody is trying it on.
     */
    public synchronized long rejectedReadings() {
        return rejected;
    }

    /** @return the node every reading from this clock is attributed to. */
    public NodeId node() {
        return node;
    }
}
