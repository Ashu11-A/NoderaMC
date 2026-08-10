package dev.nodera.mod.common;

import dev.nodera.transport.PeerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving the joiner's bring-up off the client thread is only half of MC-JOIN-2. The other half is
 * that it must be possible to <b>abandon</b> one.
 *
 * <p>A player who leaves while the joiner is still reserving a relay circuit must not be followed
 * out of the world by a peer runtime that appears half a minute later, holding a bound socket, a
 * relay registration and a tracker client for a session nobody is in. And the abandonment must not
 * be arranged by taking the singleton monitor, because that is the monitor the bring-up would be
 * holding — guarding with it would move the ninety-second stall from the render thread to the
 * disconnect path rather than removing it. So the guard is a generation counter that
 * {@code stopClient} bumps and returns from immediately.
 *
 * <p>Both cases are settled with latches and a thread join, never with a sleep: the injected step
 * is released by the test itself, so "still blocked" and "finished" are facts rather than guesses.
 *
 * <p>Thread-context: JUnit's test thread plus one bring-up thread per case.
 *
 * @see <a href="https://github.com/Ashu11-A/NoderaMC/issues/167">issue #167</a>
 */
final class NoderaPeerServiceBringUpCancellationTest {

    /** A bound so a regression fails instead of hanging; never part of an assertion. */
    private static final long SAFETY_BOUND_SECONDS = 20L;

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        NoderaPeerService.get().clientTransportHook = null;
        NoderaPeerService.get().stopClient();
    }

    @Test
    @DisplayName("a stop during bring-up leaves no runtime, no identity and no transport behind")
    void aStopDuringBringUpLeavesNothingBehind() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> bringUp = new AtomicReference<>();
        NoderaPeerService.get().clientTransportHook = (PeerTransport composed) -> {
            bringUp.set(Thread.currentThread());
            entered.countDown();
            await(release);
            return composed;
        };

        NoderaPeerService.get().onServerSessionInfo("127.0.0.1:1", "127.0.0.1", "", () -> { });
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

        // The disconnect. It must not wait for the bring-up, and it must not be waited on by it.
        NoderaPeerService.get().stopClient();
        release.countDown();

        bringUp.get().join(TimeUnit.SECONDS.toMillis(30));
        assertThat(bringUp.get().isAlive())
                .as("the abandoned bring-up must finish rather than park forever")
                .isFalse();
        assertThat(NoderaPeerService.get().clientRuntime())
                .as("a peer runtime published after the player left is a peer nobody stops")
                .isNull();
        assertThat(NoderaPeerService.get().clientDataTransport())
                .as("nor may its transport be left behind")
                .isNull();
        assertThat(NoderaPeerService.get().clientIdentity())
                .as("the session key belongs to a session that no longer exists")
                .isNull();
        assertThat(NoderaPeerService.get().sessionDelegationB64())
                .as("a delegation naming a dead key must not survive into the next session")
                .isEmpty();
    }

    @Test
    @DisplayName("a cancelled bring-up does not wedge the next one")
    void aCancelledBringUpDoesNotWedgeTheNextSession() throws InterruptedException {
        AtomicInteger entries = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        NoderaPeerService.get().clientTransportHook = (PeerTransport composed) -> {
            if (entries.incrementAndGet() == 1) {
                firstEntered.countDown();
            } else {
                secondEntered.countDown();
            }
            await(release);
            return composed;
        };

        NoderaPeerService.get().onServerSessionInfo("127.0.0.1:1", "127.0.0.1", "", () -> { });
        assertThat(firstEntered.await(30, TimeUnit.SECONDS)).isTrue();
        NoderaPeerService.get().stopClient();

        // Reconnecting to the same world is the ordinary case, not the exotic one: the in-flight
        // flag has to be cleared by the stop, or the second join would silently never mesh.
        NoderaPeerService.get().onServerSessionInfo("127.0.0.1:2", "127.0.0.1", "", () -> { });
        assertThat(secondEntered.await(30, TimeUnit.SECONDS))
                .as("the second session must be allowed to start its own bring-up")
                .isTrue();
        assertThat(NoderaPeerService.get().clientBootstrapRoute())
                .as("and it is the second session's route that is recorded")
                .isEqualTo("127.0.0.1:2");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(SAFETY_BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
