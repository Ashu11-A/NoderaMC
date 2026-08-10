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
            String request = line.trim();
            if (request.startsWith(ControlProtocol.EVENTS)) {
                // The other verb the worker writes: discrete things that happened, rather than a
                // picture of what is true. See ControlProtocol.EVENTS for why both exist.
                streamEvents(c, request);
                return;
            }
            if (request.startsWith(ControlProtocol.WATCH)) {
                // The one verb the worker writes rather than answers: it keeps this connection and
                // pushes state as it changes. Everything below assumes one line out and a close.
                watch(c, request);
                return;
            }
            if (request.startsWith(ControlProtocol.ARCHIVE)) {
                // The other verb that writes before it answers. A fetch takes minutes, and a
                // caller that hears nothing for minutes cannot tell it from a dead worker — so it
                // reports what it has verified as it goes, and the terminal line still closes the
                // exchange exactly as every other verb does.
                archive(c, request);
                return;
            }
            if (request.startsWith(ControlProtocol.FETCH_REGION)) {
                // The third: a region fetch reports every column as it lands, which is what lets the
                // game draw arriving terrain instead of waiting for the last piece (L-33). Same
                // shape as ARCHIVE — interim lines, then the same terminal line as before.
                fetchRegion(c, request);
                return;
            }
            String reply = dispatch(request);
            if (reply != null) {
                OutputStream out = c.getOutputStream();
                out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignored) {
            // best-effort per-connection; a dropped client must never take the server down
        }
    }

    /** Smallest interval a watcher may be pushed at, whatever it asks for. */
    static final long MIN_WATCH_INTERVAL_MILLIS = 50L;

    /** Default push interval when the client names none. */
    static final long DEFAULT_WATCH_INTERVAL_MILLIS = 250L;

    /**
     * How long the worker stays silent before re-sending the current state unchanged.
     *
     * <p>Not decoration. A reader that has heard nothing cannot tell a quiet node from a dead
     * socket, and TCP will not tell it either — a half-open connection reads as silence for
     * minutes. The keepalive turns "no news" into evidence, which is what lets a dashboard say
     * "live, nothing changed" instead of showing numbers it cannot vouch for.
     */
    static final long WATCH_KEEPALIVE_MILLIS = 10_000L;

    /**
     * Serve one {@link ControlProtocol#WATCH} client until it goes away.
     *
     * <p>Sampling rather than subscribing is deliberate: the state JSON is assembled from half a
     * dozen independent lanes (membership, meters, hosting, archive, validation, telemetry), none
     * of which has a change notification, and giving each one a listener would make every lane
     * responsible for a dashboard concern. Comparing the rendered line is one place, costs a string
     * compare at the client's own interval, and cannot miss a change that any lane makes.
     */
    /**
     * Serve one {@link ControlProtocol#ARCHIVE} fetch, reporting progress while it runs.
     *
     * <p>The read timeout is cleared for the same reason {@link #watch} clears it: this connection
     * is the worker's to write on now, and a fetch legitimately takes minutes. The client's own
     * budget is what bounds the wait, and every progress line resets it — so the number both ends
     * hold means "how long without progress", which is what the worker's stall budget has always
     * meant and what the client used to contradict.
     */
    private void archive(Socket c, String request) throws IOException {
        c.setSoTimeout(0);
        OutputStream out = c.getOutputStream();
        String[] parts = request.split("\\s+");
        String reply;
        try {
            String fetched = handler.fetchArchive(arg(parts, 2), arg(parts, 3),
                    parseLong(arg(parts, 4)), (verified, total) -> {
                        try {
                            out.write((ControlProtocol.PROGRESS + " " + verified + " " + total
                                    + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } catch (IOException clientLeft) {
                            // The caller gave up. Nothing to do about it here — the fetch is the
                            // worker's own work and finishing it leaves the archive on disk for
                            // whoever asks next, which is strictly better than abandoning it.
                        }
                    });
            reply = fetched == null ? err("archive lane unavailable")
                    : ControlProtocol.OK + " " + fetched;
        } catch (RuntimeException failed) {
            reply = err(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
        out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Serve one {@link ControlProtocol#FETCH_REGION}, naming each staged partial as it lands (L-33).
     *
     * <p>Same connection discipline as {@link #archive}: the read timeout is cleared, interim
     * {@link ControlProtocol#PROGRESS} lines carry liveness <b>and</b> the staged partial's path, and
     * the terminal {@code OK}/{@code ERR} line is byte-identical to what this verb has always
     * answered. The path rides as a third token on a line whose contract already says a client that
     * does not understand it must ignore it and keep reading — so a companion built before this
     * change parses the two numbers it knows and is otherwise unaffected.
     */
    private void fetchRegion(Socket c, String request) throws IOException {
        c.setSoTimeout(0);
        OutputStream out = c.getOutputStream();
        String[] parts = request.split("\\s+");
        String reply;
        try {
            String fetched = handler.fetchRegion(arg(parts, 2), arg(parts, 3), arg(parts, 4),
                    arg(parts, 5), arg(parts, 6), arg(parts, 7), arg(parts, 8),
                    (partialPath, verified, total) -> {
                        try {
                            out.write((ControlProtocol.PROGRESS + " " + verified + " " + total + " "
                                    + java.util.Base64.getEncoder().encodeToString(
                                            partialPath.getBytes(StandardCharsets.UTF_8))
                                    + "\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } catch (IOException clientLeft) {
                            // Same as the archive lane: the caller gave up, and finishing the fetch
                            // still leaves the region on disk for whoever asks next.
                        }
                    });
            reply = fetched == null ? err("region fetch unavailable")
                    : ControlProtocol.OK + " " + fetched;
        } catch (RuntimeException failed) {
            reply = err(failed.getMessage() == null ? failed.toString() : failed.getMessage());
        }
        out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void watch(Socket c, String request) throws IOException {
        long interval = watchInterval(request);
        // No read timeout: this connection is silent by design after the request line. The client
        // going away is detected by the write failing, not by a read.
        c.setSoTimeout(0);
        OutputStream out = c.getOutputStream();
        String previous = null;
        long lastWrite = 0L;
        while (running.get() && !c.isClosed()) {
            String state;
            try {
                state = handler.stateJson();
            } catch (RuntimeException e) {
                // A lane that throws while rendering must end this stream, not kill the node. The
                // client sees the error line and reconnects.
                out.write((err(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            long now = System.currentTimeMillis();
            boolean changed = !state.equals(previous);
            boolean keepalive = now - lastWrite >= WATCH_KEEPALIVE_MILLIS;
            if (changed || keepalive) {
                out.write((state + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush(); // an unflushed change is a change the reader does not have
                previous = state;
                lastWrite = now;
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * How long an event stream stays silent before sending a keepalive.
     *
     * <p>Events are sparse by nature — a player opens a world to LAN once, not continuously — so a
     * reader could sit for an hour with nothing to distinguish "nothing happened" from "this socket
     * died forty minutes ago". The keepalive carries the current sequence number, so it doubles as
     * the reader's proof that it has not missed anything.
     */
    static final long EVENT_KEEPALIVE_MILLIS = 15_000L;

    /**
     * Serve one {@link ControlProtocol#EVENTS} client until it goes away.
     *
     * <p>The optional {@code sinceSeq} argument is what makes this reliable rather than merely live:
     * a client that was not running when something happened passes the last sequence it saw (or
     * {@code 0}) and receives the backlog before the live stream. For the LAN prompt that is the
     * difference between "works if the app was open first" and "works".
     */
    private void streamEvents(Socket c, String request) throws IOException {
        WorkerEventBus bus = handler.events();
        OutputStream out = c.getOutputStream();
        if (bus == null) {
            out.write((err("this worker does not announce events") + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }
        long since = parseLong(arg(request.split("\\s+"), 2));
        c.setSoTimeout(0);
        try (WorkerEventBus.Subscription subscription = bus.subscribe(since)) {
            while (running.get() && !c.isClosed() && subscription.isOpen()) {
                WorkerEventBus.Published published;
                try {
                    published = subscription.next(EVENT_KEEPALIVE_MILLIS,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                String line = published != null ? published.toJson()
                        : "{\"seq\":" + bus.lastSequence() + ",\"event\":\"keepalive\","
                                + "\"at\":" + System.currentTimeMillis() + ",\"attributes\":{}}";
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush(); // an unflushed event is an event the reader does not have
            }
        }
    }

    /** Parse the optional interval argument of a watch request, clamped to the floor. */
    static long watchInterval(String request) {
        String[] parts = request.split("\\s+");
        long requested = parseLong(arg(parts, 2));
        return requested <= 0 ? DEFAULT_WATCH_INTERVAL_MILLIS
                : Math.max(MIN_WATCH_INTERVAL_MILLIS, requested);
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
            if (ControlProtocol.MESH.equals(verb)) {
                return ackOrErr(handler.mesh(arg(parts, 2), arg(parts, 3)));
            }
            if (ControlProtocol.JOIN.equals(verb)) {
                // NODERA-JOIN <ver> <worldId> [<nameB64>] [<leaseSeconds>] [<playersInWorld>] —
                // every trailing argument is additive, and `arg` yields "" for a caller that
                // predates them.
                return ackOrErr(handler.join(
                        arg(parts, 2), arg(parts, 3), arg(parts, 4), arg(parts, 5)));
            }
            if (ControlProtocol.STOP.equals(verb)) {
                return ackOrErr(handler.stop(arg(parts, 2)));
            }
            if (ControlProtocol.STATUS.equals(verb)) {
                return handler.statusJson(arg(parts, 2));
            }
            if (ControlProtocol.PIECES.equals(verb)) {
                // NODERA-PIECES <ver> <worldIdHex>
                String pieces = handler.piecesJson(arg(parts, 2));
                return pieces == null ? err("no piece data for world") : pieces;
            }
            if (ControlProtocol.SEED.equals(verb)) {
                // NODERA-SEED <ver> <worldId> <archivePathB64>
                String seeded = handler.seedArchive(arg(parts, 2), arg(parts, 3));
                return seeded == null ? err("archive lane unavailable")
                        : ControlProtocol.OK + " " + seeded;
            }
            if (ControlProtocol.SEED_REGION.equals(verb)) {
                // NODERA-SEED-REGION <ver> <worldId> <snapshotPathB64>
                String seeded = handler.seedRegion(arg(parts, 2), arg(parts, 3));
                return seeded == null ? err("validated-lane seeding unavailable")
                        : ControlProtocol.OK + " " + seeded;
            }
            // Neither NODERA-ARCHIVE nor NODERA-FETCH-REGION is dispatched here: both write progress
            // before they answer, so they are handled by #archive and #fetchRegion on the connection
            // itself. See the branches in #handle.
            if (ControlProtocol.WORLDID.equals(verb)) {
                // NODERA-WORLDID <ver> <genesisRootB64> <createdAt> <shared> <listed> <enc>
                //                <manifestRefB64> [pinnedWorldIdHex]
                // The last argument is optional: an older mod omits it and gets the original
                // derive-from-genesis behaviour, so the verb stays backward compatible.
                String minted = handler.mintWorldIdentity(arg(parts, 2), parseLong(arg(parts, 3)),
                        parseBool(arg(parts, 4)), parseBool(arg(parts, 5)), parseBool(arg(parts, 6)),
                        arg(parts, 7), arg(parts, 8));
                return minted == null ? err("cannot mint world identity")
                        : ControlProtocol.OK + " " + minted;
            }
            if (ControlProtocol.GRANT.equals(verb)) {
                // NODERA-GRANT <ver> <worldIdHex> <subjectNodeId> <subjectPubKeyB64> <role> <grantVer>
                String grant = handler.grantRole(arg(parts, 2), arg(parts, 3), arg(parts, 4),
                        parseInt(arg(parts, 5), 0, Integer.MAX_VALUE),
                        parseLong(arg(parts, 6)));
                return grant == null ? err("cannot mint grant") : ControlProtocol.OK + " " + grant;
            }
            if (ControlProtocol.DELEGATE.equals(verb)) {
                // NODERA-DELEGATE <ver> <worldIdHex> <sessionPubKeyB64> <ttlSeconds>
                String delegation = handler.delegateSession(arg(parts, 2), arg(parts, 3),
                        parseLong(arg(parts, 4)));
                return delegation == null ? err("cannot mint session delegation")
                        : ControlProtocol.OK + " " + delegation;
            }
            if (ControlProtocol.REKEY.equals(verb)) {
                // NODERA-REKEY <ver> <worldIdHex> <archivePathB64> <newPasswordB64> <currentIdentityB64>
                String reKeyed = handler.rekey(arg(parts, 2), arg(parts, 3), arg(parts, 4),
                        arg(parts, 5));
                return reKeyed == null ? err("archive lane unavailable")
                        : ControlProtocol.OK + " " + reKeyed;
            }
            if (ControlProtocol.LAN.equals(verb)) {
                // NODERA-LAN <ver> LIST | SHARE <port> | DECLINE <port> | STOP <port>
                String action = arg(parts, 2).toUpperCase(java.util.Locale.ROOT);
                if (action.isEmpty() || "LIST".equals(action)) {
                    String lan = handler.lanJson();
                    return lan == null ? err("this worker cannot watch for LAN worlds") : lan;
                }
                return ackOrErr(handler.lanAction(action, parseInt(arg(parts, 3), 0, 65535)));
            }
            if (ControlProtocol.DIRECTORY.equals(verb)) {
                // NODERA-DIRECTORY <ver> [limit]
                int limit = parseInt(arg(parts, 2), 0, 500);
                String directory = handler.directoryJson(limit <= 0 ? 50 : limit);
                return directory == null ? err("no discovery lane") : directory;
            }
            if (ControlProtocol.CONNECT.equals(verb)) {
                // NODERA-CONNECT <ver> <sessionIdHex>
                String address = handler.connectSession(arg(parts, 2));
                return address == null ? err("this worker cannot tunnel connections")
                        : ControlProtocol.OK + " " + address;
            }
            if (ControlProtocol.DISCONNECT.equals(verb)) {
                return ackOrErr(handler.disconnectSession(arg(parts, 2)));
            }
            if (ControlProtocol.SHARELINK.equals(verb)) {
                // NODERA-SHARELINK <ver> <worldIdHex>
                String link = handler.shareLink(arg(parts, 2));
                return link == null ? err("this node does not know that world")
                        : ControlProtocol.OK + " " + link;
            }
            if (ControlProtocol.DELETE.equals(verb)) {
                // NODERA-DELETE <ver> <worldIdHex> [reasonB64]
                String outcome = handler.deleteWorld(arg(parts, 2), arg(parts, 3));
                return outcome == null ? err("this worker cannot delete worlds")
                        : ControlProtocol.OK + " " + outcome;
            }
            if (ControlProtocol.WORLDS.equals(verb)) {
                // NODERA-WORLDS <ver> — the durable "what have I shared / what am I supporting".
                String worlds = handler.worldsJson();
                return worlds == null ? err("no world registry") : worlds;
            }
            if (ControlProtocol.PROVE.equals(verb)) {
                // NODERA-PROVE <ver> <worldIdHex> <challengeB64>
                String proof = handler.proveAdmin(arg(parts, 2), arg(parts, 3));
                return proof == null ? err("this node does not administer that world")
                        : ControlProtocol.OK + " " + proof;
            }
            if (ControlProtocol.CONFIG.equals(verb)) {
                // NODERA-CONFIG <ver> [<configJsonB64>] — with a payload it is a set, without one
                // it is a read of the effective config. Both answer in the JSON family (a raw JSON
                // line like STATE/PIECES), so the app parses one shape either way.
                String payload = arg(parts, 2);
                String json = payload.isEmpty() ? handler.readConfig() : handler.applyConfig(payload);
                // null means "this worker has no configuration plane". Declining loudly is the
                // point: a silent OK here would let the app badge settings as enforced that the
                // worker never even saw.
                return json == null ? err("unsupported") : json;
            }
            if (ControlProtocol.TELEMETRY.equals(verb)) {
                // NODERA-TELEMETRY <ver> GET | SET <granted|denied> | EVENT <eventJsonB64>
                String action = arg(parts, 2).toUpperCase(java.util.Locale.ROOT);
                switch (action) {
                    case "GET": {
                        String status = handler.telemetryStatus();
                        return status == null ? err("unsupported") : status;
                    }
                    case "SET":
                        return ackOrErr(handler.setTelemetryConsent(arg(parts, 3)));
                    case "EVENT":
                        return ackOrErr(handler.recordTelemetryEvent(arg(parts, 3)));
                    default:
                        return err("unknown telemetry action");
                }
            }
            if (ControlProtocol.TEST.equals(verb)) {
                // NODERA-TEST <ver> ROLE | READY | DRIVE <action…>
                // Everything after the action is handed over unsplit: a drive action carries
                // coordinates and item names, and re-joining what this dispatch just split is how a
                // harness ends up sending a subtly different command than the one it wrote.
                String action = arg(parts, 2).toUpperCase(java.util.Locale.ROOT);
                String rest = remainder(line, 3);
                String reply = handler.testMode(action, rest);
                return reply == null ? err("unsupported") : reply;
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

    /**
     * Everything from token {@code from} onwards, with the original spacing collapsed to one space.
     *
     * <p>Used by verbs whose tail is a free-form command rather than a fixed argument list. Rebuilding
     * it from the split parts (rather than re-splitting the raw line) keeps one definition of where
     * the arguments start, and the collapse is deliberate: a control line is one line by contract, so
     * runs of whitespace inside it carry no meaning worth preserving.
     */
    private static String remainder(String line, int from) {
        String[] parts = line.trim().split("\\s+");
        if (from >= parts.length) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }

    /**
     * A control argument as an int, clamped into {@code [lo, hi]}.
     *
     * <p>Narrowing a caller-supplied long with a cast is a truncation bug waiting to happen — a
     * limit of 4294967296 becomes 0, and a port of 65536 becomes 0 — so every int-valued verb
     * argument states the range it accepts instead.
     */
    private static int parseInt(String s, int lo, int hi) {
        long value = parseLong(s);
        return (int) Math.max(lo, Math.min(hi, value));
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
