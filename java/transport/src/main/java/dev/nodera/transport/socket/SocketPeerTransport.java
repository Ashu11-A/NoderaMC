package dev.nodera.transport.socket;

import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.codec.ChunkedStreams;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.wire.WireCodec;
import dev.nodera.protocol.simulationmsg.StreamChunk;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A real TCP {@link PeerTransport}: peers connect to one another over ordinary sockets, keyed by
 * a dialable {@code "host:port"} route (Plan §3.10, Phase 6 direct P2P data plane).
 *
 * <p>This is the transport that makes session continuity observable end-to-end: two player peers
 * hold a direct socket to each other that is independent of — and outlives — their sockets to the
 * bootstrap peer, so when the bootstrap goes offline the peer↔peer link keeps carrying traffic.
 *
 * <h2>Wire framing</h2>
 * Every frame is {@code u32 length (big-endian) + length bytes}. The <b>first</b> frame on any
 * connection (both dialer and acceptor send it immediately) is a transport-internal
 * <i>hello</i>: {@code 16-byte NodeId UUID (msb,lsb) + u16 routeLen + route UTF-8}. It lets the
 * receiver attribute the connection to a stable {@link NodeId} and the peer's advertised listen
 * route (which may differ from the socket's ephemeral local port). All later frames are opaque
 * application frames handed to the {@link MessageHandler}.
 *
 * <p><b>Authenticated mode</b> (issue #41 / L-53): constructed with a {@link NodeIdentity}, the
 * transport replaces the claimed hello with a challenge-response proof — each side opens with a
 * fresh 32-byte challenge and answers the remote's challenge with an Ed25519-signed hello
 * ({@link TransportAuth}), so the {@link NodeId} attribution is key-proven. Unverifiable peers
 * (legacy hellos included) are torn down before any frame reaches the handler.
 *
 * <h2>Threading</h2>
 * {@link #start()}/{@link #stop()}/{@link #send}/{@link #sendStream}/{@link #setHandler} are safe
 * from any thread. Inbound frames and {@link MessageHandler#onPeerDown} are delivered on the
 * connection's own reader thread (a virtual thread) — the "network thread" of the
 * {@link MessageHandler} contract; handlers must offload heavy or re-entrant work.
 *
 * <h2>Connection de-duplication</h2>
 * Connections are keyed by the remote's advertised route; on a collision the connection whose
 * hello arrived LAST wins and the displaced one is closed — a fresh hello proves that socket is
 * alive right now, while the prior entry may be a half-dead race artifact (first-wins black-holed
 * a rejoining peer on CI). Higher layers still avoid most collisions by a single-dialer
 * policy (see {@code peer-runtime}: the numerically-smaller {@link NodeId} initiates peer↔peer
 * links; peers dial the bootstrap, never the reverse).
 */
public final class SocketPeerTransport implements PeerTransport {

    /** Absolute cap on a single socket frame (guards against a hostile length prefix). */
    private static final int MAX_FRAME_BYTES = dev.nodera.transport.Frames.MAX_FRAME_BYTES;

    /**
     * How long a send waits for this connection's auth handshake to reach the point where an
     * application frame may follow.
     *
     * <p>This bounds a wait on OUR OWN read loop being scheduled — it sees the remote's challenge
     * and writes our signed hello — not a wait on the network. A dead or refusing peer no longer
     * spends it at all: the reader's teardown releases the latch immediately. So the value only has
     * to be generous enough for a badly starved machine, and 10 s was not — the full {@code check}
     * gate at maximum parallelism starved this handshake and reported a transport timeout that was
     * really a measurement of the scheduler.
     */
    static final int AUTH_HANDSHAKE_TIMEOUT_SECONDS = 30;

    private final NodeId self;
    private final String bindHost;
    /** Inclusive bind range; a single-port transport is simply a 1-wide range. */
    private final int bindPortStart;
    private final int bindPortEnd;
    private final String advertiseHost;
    /** Non-null ⇒ authenticated handshake (issue #41 / L-53); null ⇒ legacy unauthenticated hello. */
    private final NodeIdentity identity;

    private final Object lifecycleLock = new Object();
    private final Object dialLock = new Object();
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();

    private volatile MessageHandler handler;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile String listenRoute;

    /**
     * Ceiling on concurrently-held peer connections; {@link Integer#MAX_VALUE} (the default) means
     * unbounded, so wiring this class changes nothing until an operator sets a cap.
     *
     * <p><b>Honest caveat:</b> the cap counts <i>route-keyed established</i> connections — entries
     * in {@link #connections}, which a socket only joins once its hello has been received and
     * attributed. A burst of sockets that have connected but not yet said hello is therefore not
     * counted, so the true number of open file descriptors can transiently exceed the cap by the
     * size of that burst. Counting raw accepted sockets instead would be a different (and, for a
     * peer that legitimately cross-dials, worse) bound: it would refuse peers we are ourselves in
     * the middle of meeting. This is a fairness/resource limit, not a security boundary.
     */
    private volatile int maxConnections = Integer.MAX_VALUE;

    /** How many inbound sockets were refused by {@link #maxConnections} (surfaced in STATE). */
    private final java.util.concurrent.atomic.AtomicLong refusedConnections =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Create a transport that will bind {@code bindHost:bindPort} and advertise
     * {@code advertiseHost:<actual-port>} as its dialable route.
     *
     * @param self          this peer's stable id (sent in the hello).
     * @param bindHost      local bind address (e.g. {@code "0.0.0.0"} or {@code "127.0.0.1"}).
     * @param bindPort      local bind port; {@code 0} picks an ephemeral port.
     * @param advertiseHost host other peers use to dial this peer (e.g. a LAN IP or hostname).
     * @throws IllegalArgumentException if any argument is null or the port is out of range.
     */
    public SocketPeerTransport(NodeId self, String bindHost, int bindPort, String advertiseHost) {
        this(self, null, bindHost, bindPort, bindPort, advertiseHost);
    }

    /**
     * Create an <b>authenticated</b> transport (issue #41 / L-53): every connection performs a
     * challenge-response handshake — each side sends a fresh 32-byte challenge and the peer
     * answers with an Ed25519-signed hello over {@code challenge ‖ nodeId ‖ route ‖ publicKey},
     * so a connection's {@link NodeId} attribution is key-proven, not claimed. A peer that
     * presents a legacy hello, a malformed frame, or a signature that does not verify is torn
     * down before any application frame reaches the {@link MessageHandler}. Authenticated and
     * legacy transports cannot interoperate by design.
     *
     * @param identity this peer's signing identity; its {@code nodeId()} is the transport's id.
     */
    public SocketPeerTransport(NodeIdentity identity, String bindHost, int bindPort, String advertiseHost) {
        this(Objects.requireNonNull(identity, "identity").nodeId(), identity,
                bindHost, bindPort, bindPort, advertiseHost);
    }

    /**
     * Create an authenticated transport that binds the <b>first free port</b> in the inclusive
     * range {@code [rangeStart, rangeEnd]}.
     *
     * <p>Why a range and not just port {@code 0}: a peer behind a router needs a <i>predictable</i>
     * external port to forward, which an ephemeral port cannot give — but a fixed single port makes
     * a second node on the same machine (a dev worker beside a game, two accounts, a stale JVM)
     * fail to start outright. A small range is the honest middle: still forwardable, still
     * deterministic enough to document, and self-healing against one busy neighbour.
     *
     * <p>Every port in the range is tried in ascending order; the first successful bind wins and
     * {@link #listenRoute()} carries it, so the advertised route is always the port actually bound.
     * If the whole range is busy the transport throws {@link TransportException} naming the range —
     * silently falling back to an ephemeral port would advertise a route nobody has forwarded.
     *
     * @param identity    this peer's signing identity.
     * @param bindHost    local bind address.
     * @param rangeStart  first port to try; {@code 0} means "ephemeral" (and then the range is
     *                    meaningless, so it degenerates to a single OS-chosen port).
     * @param rangeEnd    last port to try, inclusive; must not be below {@code rangeStart}.
     * @param advertiseHost host other peers use to dial this peer.
     * @throws IllegalArgumentException if a port is out of range or the range runs backwards.
     */
    public SocketPeerTransport(NodeIdentity identity, String bindHost, int rangeStart, int rangeEnd,
                               String advertiseHost) {
        this(Objects.requireNonNull(identity, "identity").nodeId(), identity,
                bindHost, rangeStart, rangeEnd, advertiseHost);
    }

    private SocketPeerTransport(NodeId self, NodeIdentity identity, String bindHost,
                                int rangeStart, int rangeEnd, String advertiseHost) {
        this.self = Objects.requireNonNull(self, "self");
        this.identity = identity;
        this.bindHost = Objects.requireNonNull(bindHost, "bindHost");
        this.advertiseHost = Objects.requireNonNull(advertiseHost, "advertiseHost");
        if (rangeStart < 0 || rangeStart > 65535) {
            throw new IllegalArgumentException("bindPort out of range: " + rangeStart);
        }
        if (rangeEnd < 0 || rangeEnd > 65535) {
            throw new IllegalArgumentException("bindPort out of range: " + rangeEnd);
        }
        if (rangeEnd < rangeStart) {
            throw new IllegalArgumentException(
                    "bind port range runs backwards: " + rangeStart + "-" + rangeEnd);
        }
        this.bindPortStart = rangeStart;
        // Port 0 already means "let the OS choose"; scanning upward from it would bind an arbitrary
        // low port instead, so the range collapses to the single ephemeral attempt.
        this.bindPortEnd = rangeStart == 0 ? 0 : rangeEnd;
    }

    /** @return this peer's stable id. */
    public NodeId nodeId() {
        return self;
    }

    /**
     * Cap how many peer connections this transport will hold at once.
     *
     * @param limit the ceiling; {@code 0} or negative is read as "1" (a transport that can hold no
     *              connection at all cannot participate in a session, so refusing to express that
     *              is more honest than pretending to honour it).
     * @Thread-context any thread; takes effect on the next inbound connection.
     */
    public void setMaxConnections(int limit) {
        this.maxConnections = limit <= 0 ? 1 : limit;
    }

    /** @return the current connection ceiling; {@link Integer#MAX_VALUE} means unbounded. */
    public int maxConnections() {
        return maxConnections;
    }

    /**
     * @return how many inbound sockets have been refused because the connection cap was reached.
     *         Surfaced in {@code NODERA-STATE}: a non-zero, climbing value is the only way an
     *         operator can tell "my cap is too low" from "nobody is connecting".
     */
    public long refusedConnections() {
        return refusedConnections.get();
    }

    /** @return how many peer connections are currently established and route-keyed. */
    public int connectionCount() {
        return connections.size();
    }

    /**
     * The dialable route other peers use to reach this transport, valid only while started:
     * {@code advertiseHost:<actual-bound-port>}.
     *
     * @return the advertised listen route, or {@code null} if not started.
     */
    @Override
    public String listenRoute() {
        return listenRoute;
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            // Try every port in the range, ascending, and keep the first that binds. A single-port
            // transport is a 1-wide range, so its behaviour — including the exact "failed to bind
            // host:port" message the elastic-retry path in the mod matches on — is unchanged.
            IOException lastFailure = null;
            for (int port = bindPortStart; port <= bindPortEnd; port++) {
                try {
                    ServerSocket ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(bindHost, port));
                    this.serverSocket = ss;
                    this.listenRoute = advertiseHost + ":" + ss.getLocalPort();
                    this.running = true;
                    this.acceptThread = dev.nodera.core.concurrent.Threads.start(
                            "nodera-socket-accept-" + ss.getLocalPort(), this::acceptLoop);
                    return;
                } catch (IOException busy) {
                    lastFailure = busy;
                }
            }
            throw new TransportException("failed to bind " + bindHost + ":" + describeRange(),
                    lastFailure);
        }
    }

    /** The bind target as it appears in the failure message: a port, or {@code start-end}. */
    private String describeRange() {
        return bindPortStart == bindPortEnd
                ? Integer.toString(bindPortStart)
                : bindPortStart + "-" + bindPortEnd;
    }

    @Override
    public void stop() {
        final ServerSocket ss;
        final List<Connection> toClose;
        synchronized (lifecycleLock) {
            if (!running) {
                return;
            }
            running = false;
            ss = serverSocket;
            serverSocket = null;
            listenRoute = null;
            toClose = new ArrayList<>(connections.values());
        }
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // closing the listener only interrupts accept(); nothing else to do.
            }
        }
        for (Connection c : toClose) {
            c.closeQuietly();
        }
        connections.clear();
    }

    @Override
    public void send(PeerAddress to, byte[] frame) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(frame, "frame");
        if (!running) {
            throw new TransportException("transport not started: " + self);
        }
        Connection c = connectionFor(to.route());
        c.writeFrame(frame);
    }

    @Override
    public void sendStream(PeerAddress to, long streamId, byte[] payload) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(payload, "payload");
        // Chunk identically to LoopbackTransport / the NeoForge relay so a caller can swap
        // transports without changing its reassembly path.
        List<StreamChunk> chunks = ChunkedStreams.split(streamId, payload);
        for (StreamChunk chunk : chunks) {
            send(to, WireCodec.encode(chunk));
        }
    }

    @Override
    public void setHandler(MessageHandler handler) {
        this.handler = handler;
    }

    // ---- internals -------------------------------------------------------------------------

    private Connection connectionFor(String route) {
        if (route == null || route.equals("server") || route.isBlank()) {
            throw new TransportException("socket transport requires a host:port route, got: " + route);
        }
        Connection existing = connections.get(route);
        if (existing != null) {
            return existing;
        }
        synchronized (dialLock) {
            existing = connections.get(route);
            if (existing != null) {
                return existing;
            }
            return dial(route);
        }
    }

    private Connection dial(String route) {
        HostPort hp = HostPort.parse(route);
        Socket socket = new Socket();
        try {
            // Bounded connect: an unresponsive route must fail fast, not hang the CALLER's
            // thread (sends run on the runtime state thread — a filtered port's multi-minute
            // SYN retry there would stall every message the runtime processes).
            socket.connect(new InetSocketAddress(hp.host(), hp.port()), 5_000);
            socket.setTcpNoDelay(true);
        } catch (IOException e) {
            closeQuietly(socket);
            throw new TransportException("failed to dial " + route, e);
        }
        // Outbound connection: the remote's advertised route is exactly the dial target.
        Connection c = new Connection(socket, route);
        connections.put(route, c);
        // Our challenge goes out BEFORE the reader can run. The authenticated handshake is
        // ordered — challenge, then the hello answering the remote's challenge — and the reader
        // writes that hello the moment the remote's challenge arrives. Starting the reader first
        // therefore races it against this write: if the remote's challenge is already buffered,
        // the reader can emit our hello as the FIRST frame and the remote refuses the connection
        // with "peer did not open with an auth challenge". Writing here, on this thread, makes
        // the order a property of the code rather than of scheduling.
        c.sendHello();
        c.startReader();
        return c;
    }

    private void acceptLoop() {
        while (running) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
            } catch (IOException e) {
                if (running) {
                    // transient accept error; keep serving unless we are shutting down.
                    continue;
                }
                return;
            }
            // Connection cap (issue: bounded resource use on a shared machine). Refuse by closing
            // the socket immediately rather than accepting-then-idling: a peer that gets a clean
            // TCP close re-selects another holder at once, whereas a socket held open with nobody
            // reading it looks like a live-but-silent peer and costs both sides a timeout.
            //
            // See the maxConnections field for the honest caveat: the count is of route-keyed
            // ESTABLISHED connections, so a burst of not-yet-hello'd sockets can transiently push
            // the real descriptor count above the cap.
            if (connections.size() >= maxConnections) {
                refusedConnections.incrementAndGet();
                closeQuietly(socket);
                continue;
            }
            // Inbound connection: route is unknown until the peer's hello arrives. A failure
            // handling ONE inbound socket (e.g. the peer closed before our hello write — seen
            // as "write failed … Socket closed" on CI) must never kill the accept loop: an
            // uncaught throw here leaves the transport running but deaf, and the whole session
            // silently stops forming.
            try {
                Connection c = new Connection(socket, null);
                // Same ordering rule as dial(): our challenge is the first frame this side ever
                // writes, so it is written here rather than raced against the read loop.
                c.sendHello();
                c.startReader();
            } catch (RuntimeException e) {
                closeQuietly(socket);
            }
        }
    }

    private void onConnectionClosed(Connection c) {
        String route = c.remoteRoute;
        if (route != null) {
            connections.remove(route, c);
        }
        if (c.downFired.compareAndSet(false, true)) {
            MessageHandler h = handler;
            if (h != null && running) {
                h.onPeerDown(PeerAddress.of(c.remoteNodeId, route != null ? route : "unknown"));
            }
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // best-effort close.
        }
    }

    /** One live TCP connection to a peer. Reader runs on its own virtual thread. */
    private final class Connection {
        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;
        private final Object writeLock = new Object();
        private final AtomicBoolean downFired = new AtomicBoolean(false);
        private final AtomicBoolean helloSent = new AtomicBoolean(false);
        private volatile NodeId remoteNodeId;
        private volatile String remoteRoute; // remote's advertised listen route (map key)
        private volatile boolean helloReceived;
        /** The challenge WE sent on this connection (authenticated mode only). */
        private volatile byte[] localChallenge;
        /**
         * Released once OUR signed hello has been written (authenticated mode). Application
         * frames must not interleave between our challenge and our hello — the remote expects
         * the hello as our second frame — so {@link #writeFrame} awaits this latch.
         */
        private final java.util.concurrent.CountDownLatch authHelloWritten =
                new java.util.concurrent.CountDownLatch(1);
        /**
         * Whether the latch above was released because the hello went out, rather than because the
         * connection was torn down. Both paths release it — a waiter must never be left blocked on
         * a handshake that can no longer happen — so this flag is what tells the two apart.
         */
        private volatile boolean authHelloSent;

        Connection(Socket socket, String remoteRoute) {
            this.socket = socket;
            this.remoteRoute = remoteRoute;
            try {
                this.out = socket.getOutputStream();
                this.in = socket.getInputStream();
            } catch (IOException e) {
                throw new TransportException("failed to open socket streams", e);
            }
        }

        void startReader() {
            dev.nodera.core.concurrent.Threads.start("nodera-socket-reader", this::readLoop);
        }

        void sendHello() {
            if (!helloSent.compareAndSet(false, true)) {
                return;
            }
            if (identity != null) {
                // Authenticated mode: open with a fresh challenge; the hello answering the
                // REMOTE's challenge is sent from the read loop once that challenge arrives.
                byte[] frame = TransportAuth.newChallengeFrame();
                byte[] challenge = new byte[TransportAuth.CHALLENGE_BYTES];
                System.arraycopy(frame, 1, challenge, 0, TransportAuth.CHALLENGE_BYTES);
                this.localChallenge = challenge;
                writeRaw(frame);
                return;
            }
            byte[] routeBytes = listenRouteOrEmpty().getBytes(StandardCharsets.UTF_8);
            byte[] hello = new byte[16 + 2 + routeBytes.length];
            UUID id = self.value();
            putLong(hello, 0, id.getMostSignificantBits());
            putLong(hello, 8, id.getLeastSignificantBits());
            hello[16] = (byte) ((routeBytes.length >>> 8) & 0xFF);
            hello[17] = (byte) (routeBytes.length & 0xFF);
            System.arraycopy(routeBytes, 0, hello, 18, routeBytes.length);
            writeRaw(hello);
        }

        void writeFrame(byte[] frame) {
            if (frame.length > MAX_FRAME_BYTES) {
                throw new TransportException("frame too large: " + frame.length);
            }
            if (identity != null) {
                // Application frames wait for the handshake's write half: the remote reads our
                // hello as our second frame, so nothing may interleave before it. (The read half
                // — verifying the REMOTE — gates delivery in readLoop, not sending.)
                try {
                    if (!authHelloWritten.await(
                            AUTH_HANDSHAKE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                        closeQuietly();
                        throw new TransportException("auth handshake timed out to " + remoteRoute);
                    }
                    if (!authHelloSent) {
                        // Released by the reader's teardown, not by the hello going out. Naming that
                        // distinctly matters: a caller reading "timed out" goes looking for a slow
                        // peer, and the peer is not slow — it is gone.
                        throw new TransportException(
                                "connection closed during the auth handshake to " + remoteRoute);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TransportException("interrupted awaiting auth handshake", e);
                }
            }
            writeRaw(frame);
        }

        private void writeRaw(byte[] frame) {
            byte[] header = new byte[4];
            header[0] = (byte) ((frame.length >>> 24) & 0xFF);
            header[1] = (byte) ((frame.length >>> 16) & 0xFF);
            header[2] = (byte) ((frame.length >>> 8) & 0xFF);
            header[3] = (byte) (frame.length & 0xFF);
            synchronized (writeLock) {
                try {
                    out.write(header);
                    out.write(frame);
                    out.flush();
                } catch (IOException e) {
                    closeQuietly();
                    throw new TransportException("write failed to " + remoteRoute, e);
                }
            }
        }

        private void readLoop() {
            try {
                if (identity != null) {
                    // Authenticated handshake (issue #41 / L-53), symmetric on both ends:
                    // 1. read the remote's challenge; 2. answer with our signed hello;
                    // 3. read the remote's signed hello and verify it against OUR challenge.
                    // Any malformed frame / legacy hello / bad signature throws → the finally
                    // block tears the connection down before the handler sees a single frame.
                    byte[] remoteChallenge = TransportAuth.parseChallenge(readFrame());
                    writeRaw(TransportAuth.encodeHello(identity, listenRouteOrEmpty(), remoteChallenge));
                    authHelloSent = true;
                    authHelloWritten.countDown();
                    TransportAuth.VerifiedHello verified =
                            TransportAuth.verifyHello(readFrame(), localChallenge);
                    registerVerified(verified.nodeId(), verified.route());
                } else {
                    // Legacy mode: first frame is the unauthenticated hello.
                    byte[] helloFrame = readFrame();
                    parseHello(helloFrame);
                }
                MessageHandler h = handler;
                while (running) {
                    byte[] frame = readFrame();
                    h = handler; // pick up handler replacements
                    if (h != null) {
                        h.onMessage(PeerAddress.of(remoteNodeId, remoteRoute), frame);
                    }
                }
            } catch (EOFException | TransportException e) {
                // peer closed or malformed frame — treat as down.
            } catch (IOException e) {
                // socket error — treat as down.
            } catch (Throwable t) {
                // A reader that dies silently leaves a mapped connection whose writes black-hole into
                // the kernel buffer forever — ANY failure must tear the connection down.
            } finally {
                closeQuietly();
                // Release anyone blocked in writeFrame. The handshake cannot complete on a torn-down
                // connection, and leaving them to wait the timeout out turns "the peer refused us"
                // into "this caller stalls for half a minute".
                authHelloWritten.countDown();
            }
        }

        private void parseHello(byte[] hello) {
            if (hello.length < 18) {
                throw new TransportException("malformed hello: " + hello.length + " bytes");
            }
            long msb = getLong(hello, 0);
            long lsb = getLong(hello, 8);
            int routeLen = ((hello[16] & 0xFF) << 8) | (hello[17] & 0xFF);
            if (hello.length != 18 + routeLen) {
                throw new TransportException("malformed hello length");
            }
            String advertised = new String(hello, 18, routeLen, StandardCharsets.UTF_8);
            registerVerified(new NodeId(new UUID(msb, lsb)), advertised);
        }

        /** Register the connection under its (now attributed) identity + advertised route. */
        private void registerVerified(NodeId nodeId, String advertised) {
            this.remoteNodeId = nodeId;
            String key = advertised.isEmpty() ? ("anon:" + socket.getRemoteSocketAddress()) : advertised;
            // Register / re-key under the remote's advertised route. LAST-WINS on collision:
            // a fresh hello proves THIS socket is alive right now, while the prior entry may be
            // a half-dead outbound that raced it — first-wins closed the fresh inbound BEFORE
            // its queued frames (e.g. a PeerJoin) were read, black-holing the sender for good
            // (observed on CI: a player re-announcing for 30 s into a socket the bootstrap had
            // silently discarded). The displaced prior is closed; its reader fires the ordinary
            // down-path, and cross-dials converge to one live socket per side.
            Connection prior = connections.put(key, this);
            if (prior != null && prior != this) {
                prior.closeQuietly();
            }
            if (this.remoteRoute != null && !this.remoteRoute.equals(key)) {
                connections.remove(this.remoteRoute, this);
            }
            this.remoteRoute = key;
            this.helloReceived = true;
        }

        private byte[] readFrame() throws IOException {
            byte[] header = in.readNBytes(4);
            if (header.length < 4) {
                throw new EOFException("stream closed");
            }
            int len = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                    | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            if (len < 0 || len > MAX_FRAME_BYTES) {
                throw new TransportException("bad frame length: " + len);
            }
            byte[] body = in.readNBytes(len);
            if (body.length < len) {
                throw new EOFException("stream closed mid-frame");
            }
            return body;
        }

        void closeQuietly() {
            SocketPeerTransport.closeQuietly(socket);
            onConnectionClosed(this);
        }

        private String listenRouteOrEmpty() {
            String r = listenRoute;
            return r == null ? "" : r;
        }
    }

    private static void putLong(byte[] a, int off, long v) {
        for (int i = 0; i < 8; i++) {
            a[off + i] = (byte) ((v >>> (56 - 8 * i)) & 0xFF);
        }
    }

    private static long getLong(byte[] a, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (a[off + i] & 0xFF);
        }
        return v;
    }

    /** Parsed {@code host:port} route. */
    private record HostPort(String host, int port) {
        static HostPort parse(String route) {
            int idx = route.lastIndexOf(':');
            if (idx <= 0 || idx == route.length() - 1) {
                throw new TransportException("invalid route (need host:port): " + route);
            }
            try {
                int port = Integer.parseInt(route.substring(idx + 1));
                return new HostPort(route.substring(0, idx), port);
            } catch (NumberFormatException e) {
                throw new TransportException("invalid port in route: " + route, e);
            }
        }
    }
}
