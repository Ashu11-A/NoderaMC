package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.HashService;
import dev.nodera.distribution.WorldArchive;
import dev.nodera.peer.control.ControlProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * L-41's exit, proven with REAL OS processes: the companion daemon is a separate always-on
 * process, so a Minecraft {@code kill -9} cannot take the node down. A real
 * {@code nodera-headless} dist process seeds a world archive; a stand-in "game" process (the
 * co-located process whose death rule 5 quarantines against) is killed with SIGKILL — no
 * shutdown hook, exactly like a crashed game — and the daemon keeps answering control verbs
 * and still maintains every seeded piece.
 *
 * <p>Skipped (not failed) when the dist has not been installed:
 * {@code ./gradlew :peer:installDist}.
 */
final class CompanionCrashSurvivalIT {

    @TempDir
    Path tmp;

    private Process daemon;
    private Process game;

    @AfterEach
    void tearDown() {
        if (game != null && game.isAlive()) {
            game.destroyForcibly();
        }
        if (daemon != null && daemon.isAlive()) {
            daemon.destroy();
            try {
                if (!daemon.waitFor(10, TimeUnit.SECONDS)) {
                    daemon.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                daemon.destroyForcibly();
            }
        }
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repo root not found");
        }
        return dir;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String control(int port, String requestLine) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write((requestLine + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return in.readLine();
        }
    }

    private String probeUntilUp(int port, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                String reply = control(port, ControlProtocol.PROBE + " 2");
                if (reply != null && reply.startsWith("NODERA-OK")) {
                    return reply;
                }
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("daemon control never came up on " + port, last);
    }

    @Test
    void daemonKeepsSeedingAfterTheGameProcessIsKilledDashNine() throws Exception {
        Path dist = repoRoot().resolve(
                "java/peer/build/install/nodera-headless/bin/nodera-headless");
        assumeTrue(Files.isExecutable(dist),
                "worker dist missing — run ./gradlew :peer:installDist");

        int controlPort = freePort();
        int p2pPort = freePort();
        ProcessBuilder builder = new ProcessBuilder(dist.toString());
        builder.environment().put("NODERA_CONTROL_PORT", String.valueOf(controlPort));
        builder.environment().put("NODERA_P2P_PORT", String.valueOf(p2pPort));
        builder.environment().put("NODERA_P2P_BIND", "127.0.0.1");
        builder.environment().put("NODERA_P2P_ADVERTISE", "127.0.0.1");
        builder.environment().put("NODERA_IDENTITY_FILE",
                tmp.resolve("identity.bin").toString());
        builder.environment().put("NODERA_ARCHIVE_DIR", tmp.resolve("archive").toString());
        // No live discovery infra in this test: point at closed ports; announces are best-effort.
        builder.environment().put("NODERA_TRACKER_ENDPOINTS", "127.0.0.1:1");
        builder.environment().put("NODERA_RENDEZVOUS_ENDPOINTS", "127.0.0.1:1");
        builder.redirectOutput(tmp.resolve("daemon.log").toFile());
        builder.redirectErrorStream(true);
        daemon = builder.start();
        probeUntilUp(controlPort, 30_000);

        // The daemon seeds a world archive — the continuous seed duty that must survive.
        byte[] blob = WorldArchive.pack(Map.of(
                "level.dat", "level".getBytes(StandardCharsets.UTF_8),
                "region/r.0.0.mca", "regiondata".repeat(100).getBytes(StandardCharsets.UTF_8)));
        Bytes worldId = new HashService().sha256(blob);
        Path archiveFile = tmp.resolve("spool.nar");
        Files.write(archiveFile, blob);
        String seedReply = control(controlPort, ControlProtocol.SEED + " 2 " + worldId.toHex()
                + " " + Base64.getEncoder().encodeToString(
                        archiveFile.toString().getBytes(StandardCharsets.UTF_8)));
        assertThat(seedReply).startsWith(ControlProtocol.OK);
        int pieces = WorldArchive.manifestFor(1, blob).pieceCount();
        assertThat(control(controlPort, ControlProtocol.STATE + " 2"))
                .contains("\"maintained_pieces\":" + pieces);

        // The co-located "game" process — a separate OS process exactly like Minecraft.
        game = new ProcessBuilder("sleep", "300").start();
        assertThat(game.isAlive()).isTrue();

        // The game crashes hard: SIGKILL, no shutdown hooks, nothing graceful.
        game.destroyForcibly();
        assertThat(game.waitFor(10, TimeUnit.SECONDS)).isTrue();

        // The node did NOT go down with it: the daemon still answers and still seeds every piece.
        assertThat(control(controlPort, ControlProtocol.PROBE + " 2"))
                .startsWith("NODERA-OK");
        assertThat(control(controlPort, ControlProtocol.STATE + " 2"))
                .as("the daemon's seed duty survived the game's kill -9")
                .contains("\"maintained_pieces\":" + pieces);
        assertThat(daemon.isAlive()).isTrue();
    }
}
