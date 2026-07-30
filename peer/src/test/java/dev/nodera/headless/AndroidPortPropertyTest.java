package dev.nodera.headless;

import dev.nodera.peer.control.ControlProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves Android's system-property port channel reaches the worker's bound and reported route. */
final class AndroidPortPropertyTest {

    @TempDir
    Path tmp;

    private Process worker;

    @AfterEach
    void stopWorker() throws InterruptedException {
        if (worker == null || !worker.isAlive()) {
            return;
        }
        worker.destroy();
        if (!worker.waitFor(10, TimeUnit.SECONDS)) {
            worker.destroyForcibly();
            worker.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void p2pSystemPropertyAppearsInWorkerStateSelfRoute() throws Exception {
        int controlPort = freePort();
        int p2pPort = freePort();
        while (p2pPort == controlPort) {
            p2pPort = freePort();
        }
        Path log = tmp.resolve("worker.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(
                java,
                "-DNODERA_P2P_PORT=" + p2pPort,
                "-cp", System.getProperty("java.class.path"),
                HeadlessPeerMain.class.getName());
        // Android cannot add process environment variables. Removing inherited P2P values makes
        // this test exercise only the property fallback while control keeps its desktop env path.
        builder.environment().remove("NODERA_P2P_PORT");
        builder.environment().remove("NODERA_P2P_PORT_RANGE");
        builder.environment().put("NODERA_CONTROL_PORT", Integer.toString(controlPort));
        builder.environment().put("NODERA_P2P_BIND", "127.0.0.1");
        builder.environment().put("NODERA_P2P_ADVERTISE", "127.0.0.1");
        builder.environment().put("NODERA_IDENTITY_FILE", tmp.resolve("identity.bin").toString());
        builder.environment().put("NODERA_STATE_DIR", tmp.resolve("state").toString());
        builder.environment().put("NODERA_ARCHIVE_DIR", tmp.resolve("archive").toString());
        builder.environment().put("NODERA_TRACKER_ENDPOINTS", "127.0.0.1:1");
        builder.environment().put("NODERA_RENDEZVOUS_ENDPOINTS", "127.0.0.1:1");
        builder.environment().put("NODERA_REPLICATION_BUDGET", "0");
        builder.environment().put("NODERA_LAN_WATCH", "false");
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        worker = builder.start();

        String state = stateWhenReady(controlPort, log);
        assertThat(state).contains("\"self_route\":\"127.0.0.1:" + p2pPort + "\"");
        assertThat(worker.isAlive()).isTrue();
    }

    private String stateWhenReady(int port, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        IOException last = null;
        while (System.nanoTime() < deadline && worker.isAlive()) {
            try {
                String state = control(port, ControlProtocol.STATE + " 2");
                if (state != null && state.startsWith("{")) {
                    return state;
                }
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(100);
        }
        String output = Files.exists(log) ? Files.readString(log) : "<no worker output>";
        throw new AssertionError("worker state did not become available; output:\n" + output, last);
    }

    private static String control(int port, String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write((request + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
