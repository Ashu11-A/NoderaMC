package dev.nodera.endpoint;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * One exchange with a Nodera worker's control socket (server task 2).
 *
 * <p>The wire is the worker's own: <b>one line out, one line back</b>, UTF-8, which is what
 * {@code dev.nodera.peer.control.ControlServer} reads and what every existing probe in the live
 * harness writes. Speaking the worker's protocol rather than inventing an endpoint-specific channel
 * is the whole point of the worker serving a loopback socket — the suites' existing helpers work
 * against a Paper server unchanged.
 *
 * <p>(Written first with a 4-byte length prefix, which is what the companion app uses elsewhere.
 * The worker answered nothing, and a live run said so immediately: the protocol is the server's,
 * not the client's preference.)
 *
 * <p><b>Loopback and bounded.</b> The control socket carries no authentication because it is not
 * reachable off the machine, which only stays true if nothing here dials a remote host. The read is
 * timed out, because a worker that accepts a connection and then says nothing must cost a timeout
 * rather than a stuck thread.
 *
 * @Thread-context blocking I/O; call from a background thread, never from a tick.
 */
public final class ControlClient {

    private final String host;
    private final int port;
    private final int timeoutMillis;

    public ControlClient(String host, int port, int timeoutMillis) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.host = host;
        this.port = port;
        this.timeoutMillis = Math.max(100, timeoutMillis);
    }

    /** The loopback default: a worker on this machine. */
    public static ControlClient loopback(int port) {
        return new ControlClient("127.0.0.1", port, 3_000);
    }

    /** @return {@code host:port}, for log lines that have to say which socket was meant. */
    public String address() {
        return host + ":" + port;
    }

    /**
     * Send one verb and read one reply line.
     *
     * @param verb the control verb, e.g. {@code NODERA-PROBE 2}.
     * @return the reply line.
     * @throws IOException when the worker is unreachable or silent.
     */
    public String send(String verb) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            out.write(verb);
            out.write('\n');
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String reply = in.readLine();
            if (reply == null) {
                throw new IOException("the worker closed before answering");
            }
            return reply;
        }
    }

    /**
     * @return whether a worker answered {@code NODERA-PROBE} affirmatively. A probe that fails is
     *         an ordinary state — the worker may not be up yet — so this returns false rather than
     *         throwing, and the caller decides whether that is worth a log line.
     */
    public boolean probe() {
        try {
            return send("NODERA-PROBE 2").startsWith("NODERA-OK");
        } catch (IOException unreachable) {
            return false;
        }
    }
}
