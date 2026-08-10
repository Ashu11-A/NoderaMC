package dev.nodera.mod.common;

import dev.nodera.testkit.harness.LayoutManifest;
import dev.nodera.transport.PeerTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC-JOIN-2: the joiner's peer bring-up must not run on the client's render thread.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code ModNetworking.handleSessionOnClient} hopped the <b>whole</b> bring-up onto the client
 * main thread, and the bring-up synchronously did a companion delegation exchange, an ephemeral
 * socket bind, a relay reservation that iterates <i>every</i> configured rendezvous endpoint at five
 * seconds to connect and ten to read, and then the bootstrap dial — while holding the singleton
 * monitor that shutdown also wants. Three black-holed relays is about ninety seconds of a client
 * that has stopped drawing frames, and the player's only signal is that the game has hung.
 *
 * <h2>How this is asserted without a clock</h2>
 *
 * <p>The register's exit test is a wall-clock claim ("the client never blocks for more than one
 * frame"), and a timing assertion is the wrong tool for it on a loaded machine: it would flake, and
 * a flaky guard on a lifecycle path is worse than none. So the claim is decomposed into two facts a
 * latch can settle:
 *
 * <ol>
 *   <li>the slow step runs on {@code nodera-client-bringup} and not on the calling thread; and</li>
 *   <li>{@code onServerSessionInfo} has already returned while that step is <b>still</b> inside its
 *       block — proven by the caller reaching an assertion over a latch it has not yet released.</li>
 * </ol>
 *
 * <p>Fails before the fix on the first fact: the pre-fix method ran the composition inline, so the
 * recorded thread is the test's own. It does not hang before the fix either, because the injected
 * step waits with a bound rather than forever — the bound is a safety net, never the measurement.
 *
 * <p>The third test is the structural half: the slow calls have to <i>be</i> in the off-thread body.
 * A test that only watches the injected hook would still pass if somebody moved the real relay
 * reservation back above it.
 *
 * <p>Thread-context: JUnit's test thread plus one bring-up thread per case.
 *
 * @see <a href="https://github.com/Ashu11-A/NoderaMC/issues/167">issue #167</a>
 */
final class ClientBringUpIsOffTheRenderThreadTest {

    /**
     * How long the injected step waits before giving up.
     *
     * <p>Not a measurement and not part of any assertion: it exists so that a regression makes the
     * test <b>fail</b> rather than hang, which is the difference between a red build somebody reads
     * and a timed-out job somebody reruns.
     */
    private static final long SAFETY_BOUND_SECONDS = 20L;

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        release.countDown();
        NoderaPeerService.get().clientTransportHook = null;
        NoderaPeerService.get().stopClient();
    }

    /**
     * Hold the bring-up open at the point the relay reservation sits, recording where it runs.
     *
     * @param enteredThread receives the name of the thread that reached the slow step.
     * @param entered       counted down as soon as the step is reached.
     */
    private void blockTheBringUp(AtomicReference<String> enteredThread, CountDownLatch entered) {
        NoderaPeerService.get().clientTransportHook = (PeerTransport composed) -> {
            enteredThread.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                release.await(SAFETY_BOUND_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return composed;
        };
    }

    @Test
    @DisplayName("onServerSessionInfo returns while the bring-up is still inside its slow step")
    void theSessionHandOffReturnsWhileTheBringUpIsStillBlocked() throws InterruptedException {
        AtomicReference<String> enteredThread = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        blockTheBringUp(enteredThread, entered);
        String caller = Thread.currentThread().getName();

        // A blank world id keeps the production composition a no-op (socket-only join), so what is
        // being timed here is the hand-off itself and not the relay list a test machine happens to
        // have configured.
        NoderaPeerService.get().onServerSessionInfo("127.0.0.1:1", "127.0.0.1", "", () -> { });

        assertThat(entered.await(30, TimeUnit.SECONDS))
                .as("the bring-up must actually reach its slow step")
                .isTrue();
        assertThat(release.getCount())
                .as("the caller returned and got here while the slow step is still blocked")
                .isEqualTo(1L);
        assertThat(enteredThread.get())
                .as("the slow half of the join must not run on the thread that received the payload"
                        + " — on a real client that thread is the one drawing frames")
                .isNotEqualTo(caller)
                .isEqualTo("nodera-client-bringup");
        assertThat(NoderaPeerService.get().clientRuntime())
                .as("nothing is published until the bring-up finishes")
                .isNull();
    }

    @Test
    @DisplayName("the session identity is available the moment the hand-off returns")
    void theSessionIdentityIsSynchronous() throws InterruptedException {
        AtomicReference<String> enteredThread = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        blockTheBringUp(enteredThread, entered);

        NoderaPeerService.get().onServerSessionInfo("127.0.0.1:1", "127.0.0.1", "", () -> { });

        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        // The announce proof, the gateway listener and `/noderac session` all read this. Generating
        // it off-thread would have traded a ninety-second freeze for a race nobody could reproduce.
        assertThat(NoderaPeerService.get().clientIdentity())
                .as("the session key is generated in the synchronous prologue")
                .isNotNull();
        assertThat(NoderaPeerService.get().clientBootstrapRoute())
                .as("the route the joiner's own worker is told to dial is recorded synchronously")
                .isEqualTo("127.0.0.1:1");
    }

    @Test
    @DisplayName("the blocking calls live in the off-thread body, not in the hand-off")
    void theBlockingCallsAreInTheOffThreadBody() throws IOException {
        String source = Files.readString(LayoutManifest.load().module("neoforge-mod")
                        .resolve("src/main/java/dev/nodera/mod/common/NoderaPeerService.java"),
                StandardCharsets.UTF_8);
        String handOff = between(source, "public void onServerSessionInfo(",
                "private void bringUpClient(");

        assertThat(handOff)
                .as("the hand-off's whole job is to record what is cheap and start the thread")
                .contains("Thread.ofPlatform().name(\"nodera-client-bringup\")")
                .doesNotContain("mintSessionDelegation(")
                .doesNotContain("composeClientTransport(")
                .doesNotContain("PeerRuntime.peer(")
                .doesNotContain("new SocketPeerTransport(");
        assertThat(source)
                .as("and the off-thread body is where every one of them ended up, in order")
                .containsSubsequence(
                        "private void bringUpClient(",
                        "mintSessionDelegation(",
                        "new SocketPeerTransport(",
                        "composeClientTransport(",
                        "PeerRuntime.peer(");

        String networking = Files.readString(LayoutManifest.load().module("neoforge-mod")
                        .resolve("src/main/java/dev/nodera/mod/common/ModNetworking.java"),
                StandardCharsets.UTF_8);
        assertThat(networking)
                .as("the announce needs a live runtime, so it is posted from the bring-up thread —"
                        + " and `reply` is not valid there, nor after the handler has returned")
                .contains("PacketDistributor.sendToServer(")
                .doesNotContain("context.reply(new NoderaNodeAnnouncePayload(");
    }

    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        int end = source.indexOf(to);
        if (start < 0 || end < 0 || end < start) {
            throw new AssertionError("cannot locate '" + from + "' before '" + to + "'");
        }
        return source.substring(start, end);
    }
}
