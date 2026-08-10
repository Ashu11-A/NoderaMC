package dev.nodera.testkit.harness;

import dev.nodera.headless.PeerNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The staged dedicated server must be pointed at the worker THIS harness started.
 *
 * <h2>The defect this exists to prevent</h2>
 *
 * <p>{@code companion.controlEndpoint} defaults to {@code 127.0.0.1:25610}
 * ({@link PeerNode#DEFAULT_CONTROL_PORT}), and for a long time the harness put its own first worker
 * on exactly that port — so {@code ServerBootstrap.linkServerWorker} found the harness's worker
 * without any file saying so. When the harness moved to its own port block (#266), so that a
 * developer running the real companion app could run a suite at all, the coincidence broke and
 * nothing replaced it: {@code stageDedicatedServer} wrote {@code [host]}, {@code [archive]},
 * {@code [entity]}, {@code [tracker]} and {@code [rendezvous]}, and no {@code [companion]} block at
 * all.
 *
 * <p>The failure that produced is worth recording, because it did not look like this. {@code
 * companion.required} defaults to true, so the gate throws {@code CompanionUnavailableException}
 * out of {@code ServerStartedEvent}; NeoForge's EventBus does not isolate listener exceptions, so
 * the server crashed during startup. The first full-matrix dispatch (run 31396175753) reported
 * churn, crash and mesh-soak as <em>"the server never answered RCON ... the game port was open, so
 * the server bound and then did not answer"</em> — a message about a port, for a server that was
 * already dying behind it.
 *
 * <p>The second assertion is the one that matters on a developer machine. There, 25610 is the
 * user's own installed companion app: the fallback does not fail, it silently links the world under
 * test to a daemon the harness does not control and calls the run green. Asserting "not the product
 * default" is therefore not the same test as "some endpoint is present", and both are here.
 *
 * <p>Thread-context: ordinary JUnit; nothing here binds a socket or starts a process.
 */
class DedicatedServerCompanionPointerTest {

    @Test
    void theStagedServerIsPointedAtThisHarnessesOwnWorker(@TempDir Path root) throws IOException {
        String toml = stageAndReadServerToml(root);

        assertThat(toml)
                .as("the staged server config must carry a companion block at all")
                .contains("[companion]");

        Topology topology = Topology.standard().withPortBase(Topology.DEFAULT_PORT_BASE);
        assertThat(toml)
                .as("it must name the control port of the worker this harness started")
                .contains("controlEndpoint = \"127.0.0.1:" + topology.workerControlPort(0) + "\"");
    }

    @Test
    void theStagedServerNeverFallsBackToTheProductsOwnControlPort(@TempDir Path root)
            throws IOException {
        String toml = stageAndReadServerToml(root);

        assertThat(toml)
                .as("25610 is the INSTALLED companion app's port — a suite that reaches it is "
                        + "measuring the developer's daemon rather than its own worker")
                .doesNotContain(":" + PeerNode.DEFAULT_CONTROL_PORT + "\"");
    }

    /** Stage a dedicated server into a scratch root and return the config it wrote. */
    private static String stageAndReadServerToml(Path root) throws IOException {
        Topology topology = Topology.standard().withPortBase(Topology.DEFAULT_PORT_BASE);
        LiveStack stack = new LiveStack(TestPaths.of(root), topology, root.resolve("results"), false);

        stack.stageDedicatedServer(Map.of());

        Path config = TestPaths.of(root).gameDir("run")
                .resolve("world/serverconfig/nodera-server.toml");
        assertThat(config).as("the staged server config").exists();
        return Files.readString(config);
    }
}
