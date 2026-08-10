package dev.nodera.mod.common;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.endpoint.share.ShareOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MC-JOIN-3, the other half: what the bring-up produces gets back to the server thread by being
 * <b>posted</b> there, never by the bring-up doing it itself and never by anybody blocking.
 *
 * <p>Publishing the game port calls {@code MinecraftServer.publishServer}, the archive seed flushes
 * the save and the entity-lane bootstrap reads player positions — three things that are only legal
 * on the server thread. Moving the share off that thread is only safe if those three go back to it,
 * so this suite pins the direction of travel: {@code beginActivation} is handed an executor, and the
 * completion is reached through that executor and through nothing else.
 *
 * <p>The bring-up is made to fail on purpose here, because that is the case with a rule attached:
 * "keep the server, drop the feature" is this file's oldest contract (issue #39). A world whose mesh
 * refused to start is still a world, so its game port must still be published — which means the
 * hand-back has to happen on the failure path too, not only on the happy one.
 *
 * <p>Thread-context: JUnit's test thread plus one bring-up thread.
 *
 * @see <a href="https://github.com/Ashu11-A/NoderaMC/issues/164">issue #164</a>
 */
final class HostActivationCompletionRunsOnTheServerThreadTest {

    private static final long SAFETY_BOUND_SECONDS = 20L;

    @TempDir
    Path saveRoot;

    @AfterEach
    void tearDown() throws InterruptedException {
        HostActivation.abandon();
        HostActivation.activationStall = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFETY_BOUND_SECONDS);
        while (HostActivation.inFlight() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
    }

    @Test
    @DisplayName("the completion is posted to the server thread, and posted even when the share failed")
    void theCompletionIsPostedAndNeverRunInline() throws InterruptedException {
        HostActivation.Request request = new HostActivation.Request(
                saveRoot, "Test World", ShareOptions.playerDefault(), NodeIdentity.generate(),
                Bytes.fromHex("11".repeat(32)), "127.0.0.1", 0, "127.0.0.1", false);
        // Fail the bring-up at its first step. Nothing below it then touches this machine's worker,
        // its network or its save, so what the case observes is the hand-back and only the hand-back.
        HostActivation.activationStall = () -> {
            throw new IllegalStateException("injected: the mesh refused to start");
        };

        List<Runnable> posted = new CopyOnWriteArrayList<>();
        AtomicReference<String> postedFrom = new AtomicReference<>();
        CountDownLatch handedBack = new CountDownLatch(1);
        AtomicReference<HostActivation.Outcome> finished = new AtomicReference<>();
        AtomicReference<String> finishedOn = new AtomicReference<>();

        HostActivation.begin(request,
                runnable -> {
                    postedFrom.set(Thread.currentThread().getName());
                    posted.add(runnable);
                    handedBack.countDown();
                },
                outcome -> {
                    finishedOn.set(Thread.currentThread().getName());
                    finished.set(outcome);
                });

        assertThat(handedBack.await(30, TimeUnit.SECONDS))
                .as("a share whose bring-up failed still owes the server thread its half — the world"
                        + " stays playable even when the Nodera mesh does not come up")
                .isTrue();
        assertThat(postedFrom.get())
                .as("posted by the bring-up thread, which is what `server.execute` is for")
                .isEqualTo("nodera-host-activate");
        assertThat(finished.get())
                .as("and not run by it: publishServer, the save flush and the lane bootstrap are"
                        + " server-thread-only, so the bring-up must hand them over rather than do"
                        + " them")
                .isNull();
        assertThat(posted).hasSize(1);

        // Stand in for the server thread draining its task queue.
        String serverThread = Thread.currentThread().getName();
        posted.get(0).run();

        assertThat(finishedOn.get())
                .as("the completion runs wherever the executor runs it, and nowhere else")
                .isEqualTo(serverThread);
        assertThat(finished.get()).isNotNull();
        assertThat(finished.get().request()).isSameAs(request);
        assertThat(finished.get().route())
                .as("no route: the mesh did not come up, and the completion is told so rather than"
                        + " being skipped")
                .isNull();
        assertThat(finished.get().worldId())
                .as("a share that could not mint an identity still names the world by its certified"
                        + " genesis root, so the game port is opened for the right world")
                .isEqualTo(request.genesisSeed());
    }

    @Test
    @DisplayName("an abandoned share hands nothing back")
    void anAbandonedShareHandsNothingBack() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HostActivation.activationStall = () -> {
            entered.countDown();
            try {
                release.await(SAFETY_BOUND_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        List<Runnable> posted = new CopyOnWriteArrayList<>();

        HostActivation.begin(new HostActivation.Request(
                        saveRoot, "Test World", ShareOptions.playerDefault(), NodeIdentity.generate(),
                        Bytes.fromHex("22".repeat(32)), "127.0.0.1", 0, "127.0.0.1", false),
                posted::add, outcome -> { });

        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        // `deactivate` and `onServerStopping` do exactly this. A bring-up that is still dialling
        // relays for a world the player has just stopped sharing must not come back and publish a
        // game port for it.
        HostActivation.abandon();
        release.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SAFETY_BOUND_SECONDS);
        while (HostActivation.inFlight() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(HostActivation.inFlight()).isFalse();
        assertThat(posted)
                .as("an abandoned share hands the server thread nothing to do")
                .isEmpty();
    }
}
