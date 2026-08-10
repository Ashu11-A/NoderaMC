package dev.nodera.testkit.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every process {@link LiveStack} starts must be PROVEN to be answering before a scenario runs.
 *
 * <h2>The defect these cover</h2>
 *
 * <p>{@code startInfrastructure}'s javadoc has always said it waits "until every one of them
 * answers". For the trackers and the rendezvous that became true when {@code awaitListening}
 * landed. For the dedicated server and the Minecraft clients it stayed false: both were started and
 * handed straight back, and readiness was left to whichever scenario stage happened to watch a log
 * next. So a Gradle build that failed to compile handed the scenario a dead wrapper, the first log
 * wait after it burned its entire timeout, and the run reported that the GAME had never said the
 * thing it was asked for — the failure named the product and the cause was a build.
 *
 * <h2>Why a stub {@code gradlew} rather than a real one</h2>
 *
 * <p>The subject is the harness's reaction to a launcher that dies, not Gradle. {@link TestPaths}
 * resolves the repository layout against any root, so an empty temporary directory with a two-line
 * {@code gradlew} in it is a complete, correctly-shaped tree in which the launcher is guaranteed to
 * fail in a chosen way and in under a second. A real build would take minutes and could only ever
 * be made to fail by breaking the repository.
 *
 * <p>Thread-context: ordinary JUnit; each test owns its own temporary root and stack.
 */
class LiveStackLivenessTest {

    @Test
    void aClientLauncherThatDiesIsReportedWithItsNameAndItsLogTail(@TempDir Path root)
            throws IOException {
        assumeExecutableScripts();
        stubGradlew(root, """
                #!/bin/sh
                echo "> Task :neoforge-mod:compileJava FAILED"
                echo "error: cannot find symbol"
                exit 1
                """);

        try (LiveStack stack = stackOn(root)) {
            assertThatThrownBy(() -> stack.startClient("runClientHost", "client-host.log"))
                    .isInstanceOf(HarnessException.class)
                    .as("the failure must name the process, its exit status, and quote its output "
                            + "— 'the client never started' on its own sends the reader to the game")
                    .hasMessageContaining("client-runClientHost")
                    .hasMessageContaining("exited 1")
                    .hasMessageContaining("cannot find symbol");
        }
    }

    @Test
    void aServerLauncherThatDiesIsReportedBeforeAnyStageWaitsOnIt(@TempDir Path root)
            throws IOException {
        assumeExecutableScripts();
        stubGradlew(root, """
                #!/bin/sh
                echo "FAILURE: Build failed with an exception."
                exit 1
                """);

        try (LiveStack stack = stackOn(root)) {
            assertThatThrownBy(() -> stack.startDedicatedServer("server.log"))
                    .isInstanceOf(HarnessException.class)
                    .hasMessageContaining("server")
                    .hasMessageContaining("exited 1")
                    .hasMessageContaining("Build failed with an exception");
        }
    }

    /**
     * The token a readiness wait addresses a client's game JVM by.
     *
     * <p>Derived from the task name rather than passed in beside it: a scenario that had to name
     * both could name the wrong one, and the wrong token is a kill that reaches the dedicated
     * server instead of the client it meant.
     */
    @Test
    void aRunTaskNamesItsOwnGameJvm() {
        assertThat(LiveStack.runToken("runClientHost")).isEqualTo("clientHostRunProgramArgs");
        assertThat(LiveStack.runToken("runClientJoinTwo")).isEqualTo("clientJoinTwoRunProgramArgs");
        assertThat(LiveStack.runToken("runServer")).isEqualTo("serverRunProgramArgs");
    }

    /**
     * A fresh game directory must never reach a client without {@code options.txt}.
     *
     * <p>Without it {@code onboardAccessibility} defaults true, the accessibility onboarding screen
     * sits in front of quick play, and the client ticks happily on that screen forever. It was a
     * thirty-minute "the client never joined" in CI, and it was staged by a scenario support class
     * — so every scenario that wrote its client config through the stack directly, as the password
     * and re-key lanes do, skipped it.
     */
    @Test
    void writingAClientConfigAlsoStagesItsFirstRunOptions(@TempDir Path root) throws IOException {
        try (LiveStack stack = stackOn(root)) {
            // No worker has been started on this stack, so resolving the companion's control port
            // fails — after the options have been staged. That ORDER is the assertion: staging is
            // the first thing writing a client config does, not something a caller may forget.
            assertThatThrownBy(() ->
                    stack.writeClientConfig("run-join", PlayerRole.PLAYER_ONE, null))
                    .isInstanceOf(HarnessException.class)
                    .hasMessageContaining("no worker was started");

            Path options = stack.paths().gameDir("run-join").resolve("options.txt");
            assertThat(Files.readString(options))
                    .contains("onboardAccessibility:false")
                    .contains("pauseOnLostFocus:false");
        }
    }

    /** A player's own {@code options.txt} is never overwritten by the harness. */
    @Test
    void anExistingOptionsFileIsLeftAlone(@TempDir Path root) throws IOException {
        try (LiveStack stack = stackOn(root)) {
            Path options = stack.paths().gameDir("run-join").resolve("options.txt");
            Files.createDirectories(options.getParent());
            Files.writeString(options, "fov:110\n");

            stack.stageClientOptions("run-join");

            assertThat(Files.readString(options)).isEqualTo("fov:110\n");
        }
    }

    /**
     * A key with no section is inserted UNDER its header, never appended at the end of the file.
     *
     * <p>TOML section membership is positional. An appended key silently joins whichever section
     * happens to be last, which is how {@code mobCaptureDimensions} once ended up under
     * {@code [debug]}: the entity lane never captured, and every region revoked mid-drive with
     * nothing in any log to say why.
     */
    @Test
    void aNewKeyJoinsItsOwnSectionAndNotTheLastOne(@TempDir Path root) throws IOException {
        Path config = root.resolve("nodera-server.toml");
        Files.writeString(config, """
                [entity]
                \tlaneAutoActivate = true
                [debug]
                \tregionDrive = false
                """);

        LiveStack.setHostConfig(config, "entity", "mobCaptureDimensions",
                "[\"minecraft:overworld\"]");

        String[] lines = Files.readString(config).split("\n");
        int entity = indexOf(lines, "[entity]");
        int debug = indexOf(lines, "[debug]");
        int key = indexOf(lines, "mobCaptureDimensions");
        assertThat(key).isGreaterThan(entity).isLessThan(debug);
    }

    /** An existing key is rewritten in place, keeping its indentation. */
    @Test
    void anExistingKeyIsRewrittenWhereItAlreadyIs(@TempDir Path root) throws IOException {
        Path config = root.resolve("nodera-server.toml");
        Files.writeString(config, "[host]\n\tgamePort = 25565\n");

        LiveStack.setHostConfig(config, "host", "gamePort", "25599");

        assertThat(Files.readString(config)).isEqualTo("[host]\n\tgamePort = 25599\n");
    }

    // ---------------------------------------------------------------------------------------

    /**
     * A stack on a throwaway root, with the game and RCON ports moved to whatever this machine has
     * free.
     *
     * <p>Not the standard 25599/25575: a developer box in this repository frequently has a live
     * stack on those, and a readiness probe that succeeded against SOMEBODY ELSE'S server would turn
     * a deterministic assertion into a three-minute intermittent one.
     */
    private static LiveStack stackOn(Path root) {
        Topology standard = Topology.standard();
        Topology isolated = new Topology(standard.players(), standard.sparePeers(),
                standard.trackers(), standard.rendezvous(), freePort(), standard.trackerPort(),
                standard.rendezvousPort(), standard.workerControlBase(), standard.workerP2pBase(),
                freePort(), standard.rconPassword(), standard.joinTimeout(),
                standard.timeoutMultiplier());
        return new LiveStack(TestPaths.of(root), isolated, root.resolve("results"), false);
    }

    /** A port nothing is listening on right now — closed again, so the stack may claim it. */
    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException noPorts) {
            throw new IllegalStateException("cannot reserve a free port", noPorts);
        }
    }

    private static void stubGradlew(Path root, String script) throws IOException {
        Path gradlew = root.resolve("gradlew");
        Files.createDirectories(root);
        Files.writeString(gradlew, script);
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static int indexOf(String[] lines, String needle) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * These tests spawn a shell script, which is a POSIX behaviour.
     *
     * <p>A skip here is a skip, not a pass: if it ever starts skipping the liveness coverage is
     * gone, and the whole point of this file is that a run must not report coverage it does not
     * have.
     */
    private static void assumeExecutableScripts() {
        assumeTrue(Files.getFileAttributeView(Path.of("."),
                        java.nio.file.attribute.PosixFileAttributeView.class) != null,
                "no POSIX file permissions — a stub launcher cannot be made executable here");
    }
}
