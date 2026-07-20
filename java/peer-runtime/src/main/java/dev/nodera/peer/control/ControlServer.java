package dev.nodera.peer.control;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task 32: the loopback control endpoint the headless Nodera peer worker exposes. It answers the
 * mod's presence probe ({@link ControlProtocol}) so the Minecraft client can verify the always-on
 * node is running before it starts — and refuse to start if it is not. Later control verbs
 * (host/join/state) ride the same listener.
 *
 * <p>Deliberately dependency-light: a single accept thread + a per-connection worker thread over
 * plain {@link ServerSocket} sockets, bound to a loopback address only. No async framework, so it is
 * trivially unit-testable (bind port 0, connect, assert the reply).
 *
 * @Thread-context {@link #start} spawns a daemon accept thread; {@link #close} stops it. The version
 *                 string is read on each connection.
 */
public final class ControlServer implements AutoCloseable {

    private final String host;
    private final int requestedPort;
    private final String workerVersion;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    /**
     * @param host          loopback bind address (e.g. {@code 127.0.0.1}); never a routable one.
     * @param port          port to bind ({@code 0} picks a free port — read it back with
     *                      {@link #boundPort}).
     * @param workerVersion this worker's build version, reported on the probe reply.
     */
    public ControlServer(String host, int port, String workerVersion) {
        this.host = host == null ? "127.0.0.1" : host;
        this.requestedPort = port;
        this.workerVersion = workerVersion == null ? "unknown" : workerVersion;
    }

    /** Bind + begin accepting. Idempotent. */
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(host), requestedPort));
        this.serverSocket = socket;
        running.set(true);
        Thread t = new Thread(this::acceptLoop, "nodera-control-accept");
        t.setDaemon(true);
        this.acceptThread = t;
        t.start();
    }

    /** @return the actually-bound port (useful when constructed with port 0), or -1 before start. */
    public int boundPort() {
        ServerSocket s = serverSocket;
        return s == null ? -1 : s.getLocalPort();
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                if (running.get()) {
                    continue; // transient; keep serving
                }
                return; // closed
            }
            Thread worker = new Thread(() -> handle(client), "nodera-control-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(3000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) {
                return;
            }
            String verb = line.trim().split("\\s+", 2)[0];
            if (ControlProtocol.PROBE.equals(verb)) {
                OutputStream out = c.getOutputStream();
                out.write((ControlProtocol.okLine(ControlProtocol.PROTOCOL_VERSION, workerVersion)
                        + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            // Unknown verbs close quietly; future HOST/JOIN/STATE verbs branch here.
        } catch (IOException ignored) {
            // best-effort per-connection; a dropped client must never take the server down
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        ServerSocket s = serverSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        Thread t = acceptThread;
        if (t != null) {
            t.interrupt();
        }
    }
}
