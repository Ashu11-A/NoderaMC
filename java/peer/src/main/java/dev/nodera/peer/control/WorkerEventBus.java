package dev.nodera.peer.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The node's event stream: what happened, in order, with enough memory that a client which was not
 * watching at the time still finds out.
 *
 * <h2>The replay buffer is the whole point</h2>
 *
 * <p>A companion app is started by a person, and the thing it most needs to react to — a world being
 * opened to LAN — is done by the same person, seconds apart, in either order. An event bus without
 * memory works only when the app happened to be connected first, which is exactly half the time and
 * is unrecoverable when it goes the wrong way: nothing on screen, nothing wrong, no way to ask.
 *
 * <p>So a subscriber names the last sequence number it saw, and receives everything after it that is
 * still in the ring. A fresh client passes {@code 0} and gets the recent history — which for the LAN
 * lane means the prompt appears whether the app or the world came first.
 *
 * <h2>A slow subscriber is dropped, not waited for</h2>
 *
 * <p>Publishing happens on whatever thread observed the thing — a multicast receive loop, a control
 * connection, the runtime's state thread. None of those may block on a socket that is not draining,
 * so each subscriber has a bounded queue and loses its subscription if it overflows. A dashboard
 * that missed events reconnects and replays; a node that stalled behind a dashboard is a worse
 * outcome for everyone on it.
 *
 * @Thread-context every method is safe from any thread. {@link #publish} never blocks.
 */
public final class WorkerEventBus {

    /**
     * How many past events a late subscriber can still receive.
     *
     * <p>Sized for "the app was started a minute after the world" rather than for an audit log: the
     * events this carries are prompts and notifications, and one that is minutes old is no longer
     * something to raise a modal about.
     */
    public static final int HISTORY = 256;

    /** How many events may queue for one subscriber before it is dropped. */
    static final int SUBSCRIBER_QUEUE = 128;

    private final AtomicLong sequence = new AtomicLong();
    private final Deque<Published> history = new ArrayDeque<>(HISTORY);
    private final List<Subscription> subscribers = new CopyOnWriteArrayList<>();

    /** An event with the sequence number it was published under. */
    public record Published(long seq, WorkerEvent event) {
        /** @return the JSON line a control client receives. */
        public String toJson() {
            return event.toJson(seq);
        }
    }

    /**
     * Publish one event: number it, remember it, and hand it to every live subscriber.
     *
     * @param event the event.
     * @return its sequence number.
     * @Thread-context any thread; never blocks.
     */
    public long publish(WorkerEvent event) {
        Objects.requireNonNull(event, "event");
        Published published;
        synchronized (history) {
            published = new Published(sequence.incrementAndGet(), event);
            if (history.size() == HISTORY) {
                history.removeFirst();
            }
            history.addLast(published);
        }
        for (Subscription subscriber : subscribers) {
            subscriber.offer(published);
        }
        return published.seq();
    }

    /** @return the sequence number of the most recent event, or {@code 0} when none has occurred. */
    public long lastSequence() {
        return sequence.get();
    }

    /**
     * Start receiving events, beginning with everything after {@code sinceSeq} that is still held.
     *
     * @param sinceSeq the last sequence the caller has already seen; {@code 0} for "whatever you
     *                 still have", which is what a freshly-started client wants.
     * @return the subscription; close it when done.
     * @Thread-context any thread.
     */
    public Subscription subscribe(long sinceSeq) {
        Subscription subscription = new Subscription();
        // Backlog first, then registration, then a de-duplicating drain: registering first would
        // let a concurrent publish arrive before the backlog and be delivered out of order, and
        // registering last would drop anything published while the backlog was being copied.
        List<Published> backlog = new ArrayList<>();
        synchronized (history) {
            for (Published published : history) {
                if (published.seq() > sinceSeq) {
                    backlog.add(published);
                }
            }
            subscribers.add(subscription);
        }
        for (Published published : backlog) {
            subscription.offer(published);
        }
        return subscription;
    }

    /** @return how many clients are currently receiving events. */
    public int subscriberCount() {
        return subscribers.size();
    }

    /** One client's view of the stream. */
    public final class Subscription implements AutoCloseable {

        private final BlockingQueue<Published> queue = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE);
        private volatile boolean open = true;
        private volatile long lastDelivered;

        private void offer(Published published) {
            if (!open) {
                return;
            }
            // Ordering guard for the subscribe race: the backlog copy and a concurrent publish can
            // both offer the same event, and a client must not see it twice.
            if (published.seq() <= lastDelivered) {
                return;
            }
            if (!queue.offer(published)) {
                // Dropped rather than blocked. See the class docs: the publisher's thread belongs to
                // the node, not to whoever is watching it.
                open = false;
                subscribers.remove(this);
                return;
            }
            lastDelivered = published.seq();
        }

        /**
         * Wait for the next event.
         *
         * @param timeout how long to wait before giving up.
         * @param unit    the unit.
         * @return the next event, or {@code null} on timeout — which the caller uses as its cue to
         *         send a keepalive, so silence on the wire is never ambiguous.
         * @throws InterruptedException if the wait is interrupted.
         */
        public Published next(long timeout, TimeUnit unit) throws InterruptedException {
            return queue.poll(timeout, unit);
        }

        /** @return whether this subscription is still receiving (a dropped one is not). */
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
            subscribers.remove(this);
            queue.clear();
        }
    }
}
