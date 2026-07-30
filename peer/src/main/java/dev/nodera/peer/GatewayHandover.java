package dev.nodera.peer;

import dev.nodera.core.action.ActionEnvelope;
import dev.nodera.core.identity.NodeId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Freeze → reconnect → exactly-once resubmit, the session-gateway migration core
 * (network task 2 deliverable 7 / engine L-17).
 *
 * <p><b>What L-17 describes from a player's chair.</b> The gateway a player's actions flow through
 * goes away — it crashed, or the election moved it — and play stops for as long as the reconnect
 * takes. The reconnect itself is not the interesting part; what makes it a limitation rather than a
 * hiccup is what happens to the actions that were <i>in flight</i> when it happened. Drop them and
 * the player's last second of play silently never occurred. Resend them blindly and a block gets
 * placed twice, or an item duplicates — the exact failure the validated lane exists to make
 * impossible.
 *
 * <p><b>The three states, and why freezing is the correct middle one.</b>
 *
 * <ul>
 *   <li><b>Open.</b> Actions submit as usual and are held here until acknowledged.</li>
 *   <li><b>Frozen.</b> The gateway is gone. New actions are <i>accepted and queued</i> rather than
 *       rejected: rejecting would push the failure into the capture path, which would have to
 *       decide what a refused block placement means to a player mid-swing. Queuing bounds the
 *       damage to latency, which is what a migration should cost.</li>
 *   <li><b>Open again.</b> Everything unacknowledged replays, in submission order, exactly once.</li>
 * </ul>
 *
 * <p><b>Exactly-once is by identity, not by counting.</b> Every envelope is keyed by
 * {@code (actor, playerSeq)} — the actor's own monotonic sequence, which is what makes a resubmit
 * recognisable as the same action rather than a new one. An acknowledgement removes the entry, so a
 * replay carries only what the new gateway has genuinely never seen. Re-offering an action already
 * held is idempotent: it replaces nothing and queues nothing twice, because a client that retries a
 * submit during a wobble must not thereby double it.
 *
 * <p><b>What this deliberately does not do.</b> It does not decide who the new gateway is
 * ({@link GatewayElection} does), it does not dial anything, and it never re-signs an envelope: a
 * replayed action is byte-identical to the one first submitted, so the signature that authorised it
 * still authorises it and a migration cannot become a way to mint authority.
 *
 * @Thread-context synchronised; the submit path and the connection lifecycle run on different
 *                 threads, and the whole point is what happens when they interleave.
 */
public final class GatewayHandover {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("NoderaGateway");

    /** One in-flight action's identity: the actor and its own sequence number. */
    private record Key(NodeId actor, long playerSeq) {
    }

    /** How many unacknowledged actions may be held before the oldest is dropped. */
    public static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    /** Insertion-ordered so a replay preserves submission order. */
    private final Map<Key, ActionEnvelope> inFlight = new LinkedHashMap<>();
    private boolean frozen;
    private long droppedByCapacity;
    private long replayed;

    public GatewayHandover() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * @param capacity how many unacknowledged actions to hold. A frozen session with no gateway
     *                 cannot buffer forever; past the cap the OLDEST is dropped, because the newest
     *                 actions are the ones a player is still waiting to see.
     */
    public GatewayHandover(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /** @return whether the session is frozen — the gateway is gone and submissions are queuing. */
    public synchronized boolean isFrozen() {
        return frozen;
    }

    /** @return how many actions are held unacknowledged. */
    public synchronized int inFlightCount() {
        return inFlight.size();
    }

    /** @return how many actions were dropped because the buffer was full while frozen. */
    public synchronized long droppedByCapacity() {
        return droppedByCapacity;
    }

    /** @return how many actions have been replayed across every handover so far. */
    public synchronized long replayedCount() {
        return replayed;
    }

    /**
     * Offer one action.
     *
     * @param action the signed envelope.
     * @return whether the caller should send it now. {@code false} while frozen — the action is
     *         held and will be sent by {@link #resume}, so a {@code false} here is "later", never
     *         "lost".
     */
    public synchronized boolean submit(ActionEnvelope action) {
        if (action == null) {
            return false;
        }
        Key key = new Key(action.actor(), action.playerSeq());
        // Re-offering an action already held is not a second action. A client retrying a submit
        // during a wobble must not double it.
        boolean known = inFlight.containsKey(key);
        inFlight.put(key, action);
        if (!known) {
            trimToCapacity();
        }
        return !frozen;
    }

    /** Drop oldest-first; the newest actions are the ones a player is still waiting to see. */
    private void trimToCapacity() {
        while (inFlight.size() > capacity) {
            var oldest = inFlight.entrySet().iterator();
            if (!oldest.hasNext()) {
                return;
            }
            oldest.next();
            oldest.remove();
            droppedByCapacity++;
        }
    }

    /**
     * The gateway is gone: stop sending and start queuing. Idempotent — a second loss report while
     * already frozen changes nothing, because a flapping link must not restart the freeze.
     *
     * @return whether this call was the transition into frozen.
     */
    public synchronized boolean freeze() {
        if (frozen) {
            return false;
        }
        frozen = true;
        LOG.info("session frozen — the gateway is gone; {} action(s) held, new ones will queue",
                inFlight.size());
        return true;
    }

    /**
     * A gateway is available again: unfreeze and hand back everything unacknowledged, in
     * submission order, to be sent exactly once.
     *
     * @return the actions to resend; empty when nothing was in flight.
     */
    public synchronized List<ActionEnvelope> resume() {
        frozen = false;
        List<ActionEnvelope> replay = new ArrayList<>(inFlight.values());
        replayed += replay.size();
        if (!replay.isEmpty()) {
            LOG.info("session resumed — replaying {} unacknowledged action(s) in order",
                    replay.size());
        }
        return List.copyOf(replay);
    }

    /**
     * Acknowledge one action: the gateway has it, so a later handover must not replay it.
     *
     * @return whether an entry was actually removed.
     */
    public synchronized boolean acknowledge(NodeId actor, long playerSeq) {
        return actor != null && inFlight.remove(new Key(actor, playerSeq)) != null;
    }

    /**
     * Acknowledge every held action matching a predicate — the shape a committed batch has, where
     * the gateway confirms a run of sequences at once rather than one at a time.
     *
     * @return how many were removed.
     */
    public synchronized int acknowledgeIf(Predicate<ActionEnvelope> committed) {
        Objects.requireNonNull(committed, "committed");
        int before = inFlight.size();
        inFlight.values().removeIf(committed);
        return before - inFlight.size();
    }

    /** Forget everything unacknowledged — a session ending, not a gateway changing. */
    public synchronized void clear() {
        inFlight.clear();
        frozen = false;
    }
}
