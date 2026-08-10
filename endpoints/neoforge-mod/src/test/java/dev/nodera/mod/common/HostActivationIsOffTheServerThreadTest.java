package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.endpoint.share.ShareOptions;
import dev.nodera.testkit.harness.LayoutManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC-JOIN-3: putting a world on the network must not run on the server main thread.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code NoderaHost.activate} ran the whole share inline on its caller, and the caller is the
 * server thread: a companion mint with its own timeouts, a P2P bind, a relay reservation that
 * iterates <i>every</i> configured rendezvous endpoint at five seconds to connect and ten to read,
 * a tracker announce, a memory-hard KDF for the join gate, the worker's HOST verb and a
 * {@code Files.walk} of the entire save. On an integrated server that thread is the one behind the
 * singleplayer loading screen, so a relay set pointed at a black hole was a world that took a
 * minute and a half to open with nothing on screen saying why.
 *
 * <h2>How this is asserted without a clock</h2>
 *
 * <p>The register's exit is a wall-clock claim ("world load time is unaffected by an unreachable
 * relay set"), and a timing assertion is the wrong tool for it on a shared machine: it would flake,
 * and a flaky guard on a lifecycle path is worse than none. So the claim is decomposed into facts a
 * latch settles:
 *
 * <ol>
 *   <li>the slow half runs on {@code nodera-host-activate} and not on the calling thread; and</li>
 *   <li>{@code begin} has already returned while that half is <b>still</b> inside a block
 *       the test itself has not released.</li>
 * </ol>
 *
 * <p>Fails before the fix on the first fact: the pre-fix method did all of it inline, so the
 * recorded thread is the test's own. The third test is the structural half — the blocking calls
 * have to <i>be</i> in the off-thread body, and the thread-affine ones have to still be where only
 * they can run. A behavioural test alone would still pass if somebody moved the relay reservation
 * back above the hand-off.
 *
 * <p>Thread-context: JUnit's test thread plus one bring-up thread per case.
 *
 * @see <a href="https://github.com/Ashu11-A/NoderaMC/issues/164">issue #164</a>
 */
final class HostActivationIsOffTheServerThreadTest {

    /**
     * How long the injected step waits before giving up.
     *
     * <p>Not a measurement and not part of any assertion: it exists so that a regression makes the
     * test <b>fail</b> rather than hang, which is the difference between a red build somebody reads
     * and a timed-out job somebody reruns.
     */
    private static final long SAFETY_BOUND_SECONDS = 20L;

    private final CountDownLatch release = new CountDownLatch(1);

    @TempDir
    Path saveRoot;

    @AfterEach
    void tearDown() {
        // Abandon first, release second: the generation bump is what makes the bring-up return at
        // its next checkpoint instead of going on to mint an identity and bind a socket against
        // whatever worker this machine happens to be running.
        HostActivation.abandon();
        HostActivation.activationStall = null;
        release.countDown();
    }

    /**
     * Start a bring-up, waiting out any the previous case left running.
     *
     * <p>The in-flight state is process-wide and {@code begin} refuses while one is up, so a case
     * that simply called it could fail because of its predecessor rather than because of the rule
     * it is testing. {@code begin}'s own return value is the wait condition — no accessor exists
     * for the flag, and none should: a getter production never reads is dead code with a test
     * holding it up.
     */
    private static void beginOrWait(HostActivation.Request request,
                                    java.util.function.Consumer<Runnable> onServerThread,
                                    java.util.function.Consumer<HostActivation.Outcome> finish)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFETY_BOUND_SECONDS);
        while (!HostActivation.begin(request, onServerThread, finish)) {
            assertThat(System.nanoTime())
                    .as("a bring-up from an earlier case never finished")
                    .isLessThan(deadline);
            Thread.sleep(5L);
        }
    }

    private HostActivation.Request request() {
        return new HostActivation.Request(saveRoot, "Test World", ShareOptions.playerDefault(),
                NodeIdentity.generate(), Bytes.fromHex("00".repeat(32)),
                "127.0.0.1", 0, "127.0.0.1", false);
    }

    /**
     * Hold the bring-up open at its first step, recording where it runs.
     *
     * @param enteredThread receives the name of the thread that reached the slow half.
     * @param entered       counted down as soon as it is reached.
     */
    private void blockTheBringUp(AtomicReference<String> enteredThread, CountDownLatch entered) {
        HostActivation.activationStall = () -> {
            enteredThread.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                release.await(SAFETY_BOUND_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test
    @DisplayName("activate hands the share off while the bring-up is still inside its slow step")
    void theHandOffReturnsWhileTheBringUpIsStillBlocked() throws InterruptedException {
        AtomicReference<String> enteredThread = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        blockTheBringUp(enteredThread, entered);
        String caller = Thread.currentThread().getName();
        List<Runnable> postedToServerThread = new CopyOnWriteArrayList<>();

        beginOrWait(request(), postedToServerThread::add, outcome -> { });

        assertThat(entered.await(30, TimeUnit.SECONDS))
                .as("the bring-up must actually reach its slow step")
                .isTrue();
        assertThat(release.getCount())
                .as("the caller returned and got here while the slow step is still blocked")
                .isEqualTo(1L);
        assertThat(enteredThread.get())
                .as("the slow half of a share must not run on the thread that asked for it —"
                        + " on an integrated server that thread is behind the loading screen")
                .isNotEqualTo(caller)
                .isEqualTo("nodera-host-activate");
        assertThat(postedToServerThread)
                .as("nothing is handed back to the server thread until the bring-up finishes")
                .isEmpty();
    }

    @Test
    @DisplayName("a second share is refused while the first is still being brought up")
    void aSecondShareDoesNotStartASecondBringUp() throws InterruptedException {
        AtomicReference<String> enteredThread = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        blockTheBringUp(enteredThread, entered);

        beginOrWait(request(), r -> { }, outcome -> { });
        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();

        // `isHosting()` used to be the whole re-entrancy guard and it cannot be one any more: the
        // host runtime does not exist yet at this point, so a second Share click would have bound a
        // second P2P socket and registered a second relay reservation nothing ever stops.
        assertThat(HostActivation.begin(request(), r -> { }, outcome -> { }))
                .as("the second share must not start a second bring-up")
                .isFalse();
    }

    @Test
    @DisplayName("the blocking calls live in the off-thread body, and the thread-affine ones do not")
    void theBlockingCallsAreInTheOffThreadBody() throws IOException {
        String host = code("NoderaHost.java");
        String activation = code("HostActivation.java");
        String handOff = between(host, "public static void activate(MinecraftServer server,",
                "private static void finishActivation(");

        assertThat(handOff)
                .as("what stays on the server thread is what only it may do: read the level name and"
                        + " save path, digest live chunk sections, and touch the player list")
                .contains("WorldGenesisService.ensure(")
                .contains("grantHostOperator(server)")
                .contains("HostActivation.begin(");
        assertThat(handOff)
                .as("and every step this row names is gone from it")
                .doesNotContain("ensureIdentity(")
                .doesNotContain("startHost(")
                .doesNotContain("saveSizeBytes(")
                .doesNotContain("HostJoinGate");
        assertThat(activation)
                .as("the off-thread body is where every one of them ended up, in order")
                .containsSubsequence(
                        "private static void bringUp(",
                        "ensureIdentity(",
                        "startHost(",
                        "armGateNow(",
                        "NoderaHost.applyHostPermissions(",
                        "saveSizeBytes(");
        assertThat(activation)
                .as("the bring-up cannot name a MinecraftServer at all — that is what stops it"
                        + " reading the level name, the save path or the player list from the wrong"
                        + " thread — and it hands its result back through the caller's executor"
                        + " rather than by blocking")
                .doesNotContain("MinecraftServer")
                .contains("onServerThread.accept(");
        assertThat(between(host, "private static void finishActivation(",
                "static void applyHostPermissions("))
                .as("publishing the game port is server-thread-only (it calls publishServer), and so"
                        + " is the entity-lane bootstrap (it reads player positions) — while the"
                        + " worker's HOST verb can block, so it leaves again on its own thread")
                .contains("openGameServer(server)")
                .contains("activateEntityLaneFromWorld(server)")
                .contains("Thread.ofPlatform().name(\"nodera-host-notify\")");
    }

    /**
     * @param file a source file in {@code dev.nodera.mod.common}.
     * @return its source with comments removed — a `doesNotContain` over a file this heavily
     *         commented would otherwise be answered by the prose that explains the rule rather than
     *         by the code that keeps it.
     */
    private static String code(String file) throws IOException {
        String source = Files.readString(LayoutManifest.load().module("neoforge-mod")
                .resolve("src/main/java/dev/nodera/mod/common/" + file), StandardCharsets.UTF_8);
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
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
