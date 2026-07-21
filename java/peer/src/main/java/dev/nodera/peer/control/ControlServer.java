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
    private final ControlHandler handler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    /**
     * Full constructor: dispatch all control verbs to {@code handler}.
     *
     * @param host    loopback bind address (e.g. {@code 127.0.0.1}); never a routable one.
     * @param port    port to bind ({@code 0} picks a free port — read it back with {@link #boundPort}).
     * @param handler the worker behaviour behind the verbs.
     */
    public ControlServer(String host, int port, ControlHandler handler) {
        this.host = host == null ? "127.0.0.1" : host;
        this.requestedPort = port;
        this.handler = handler == null ? probeOnly("unknown") : handler;
    }

    /**
     * Probe-only convenience constructor (answers {@link ControlProtocol#PROBE} with the given
     * version; every other verb replies {@link ControlProtocol#ERR}).
     *
     * @param host          loopback bind address.
     * @param port          port to bind (0 = pick free).
     * @param workerVersion the version reported on the probe reply.
     */
    public ControlServer(String host, int port, String workerVersion) {
        this(host, port, probeOnly(workerVersion));
    }

    private static ControlHandler probeOnly(String version) {
        String v = version == null ? "unknown" : version;
        return () -> v;
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
            String reply = dispatch(line.trim());
            if (reply != null) {
                OutputStream out = c.getOutputStream();
                out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // best-effort per-connection; a dropped client must never take the server down
        }
    }

    /** Parse the request line and produce the reply line (or null to close silently). */
    private String dispatch(String line) {
        String[] parts = line.split("\\s+");
        String verb = parts[0];
        try {
            if (ControlProtocol.PROBE.equals(verb)) {
                return ControlProtocol.okLine(ControlProtocol.PROTOCOL_VERSION, handler.workerVersion());
            }
            if (ControlProtocol.STATE.equals(verb)) {
                return handler.stateJson();
            }
            if (ControlProtocol.IDENTITY.equals(verb)) {
                String id = handler.identityLine();
                return id == null ? err("no identity") : ControlProtocol.OK + " " + id;
            }
            // Verbs below carry the protocol version as the first token (index 1); the payload starts
            // at index 2. NODERA-HOST <version> <worldId> <nameB64> <optionsJson...>
            if (ControlProtocol.HOST.equals(verb)) {
                return ackOrErr(handler.host(arg(parts, 2), arg(parts, 3), rest(line, 4)));
            }
            if (ControlProtocol.JOIN.equals(verb)) {
                return ackOrErr(handler.join(arg(parts, 2)));
            }
            if (ControlProtocol.STOP.equals(verb)) {
                return ackOrErr(handler.stop(arg(parts, 2)));
            }
            if (ControlProtocol.PASSWORD.equals(verb)) {
                return ackOrErr(handler.password(arg(parts, 2), arg(parts, 3)));
            }
            if (ControlProtocol.STATUS.equals(verb)) {
                return handler.statusJson(arg(parts, 2));
            }
            if (ControlProtocol.WORLDID.equals(verb)) {
                // NODERA-WORLDID <ver> <genesisRootB64> <createdAt> <shared> <listed> <enc> <manifestRefB64>
                String minted = handler.mintWorldIdentity(arg(parts, 2), parseLong(arg(parts, 3)),
                        parseBool(arg(parts, 4)), parseBool(arg(parts, 5)), parseBool(arg(parts, 6)),
                        arg(parts, 7));
                return minted == null ? err("cannot mint world identity")
                        : ControlProtocol.OK + " " + minted;
            }
            return err("unknown verb");
        } catch (RuntimeException e) {
            return err(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String ackOrErr(String errorOrNull) {
        return errorOrNull == null ? ControlProtocol.OK : err(errorOrNull);
    }

    private static String err(String message) {
        return ControlProtocol.ERR + " " + (message == null ? "error" : message);
    }

    private static String arg(String[] parts, int i) {
        return i < parts.length ? parts[i] : "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean parseBool(String s) {
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /** The remainder of the request line starting at token index {@code from} (for JSON payloads). */
    private static String rest(String line, int from) {
        String[] parts = line.split("\\s+", from + 1);
        return from < parts.length ? parts[from] : "";
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
