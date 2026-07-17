package dev.nodera.transport.socket;

import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.codec.ChunkedStreams;
import dev.nodera.protocol.codec.MessageCodec;
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
 * <h2>Threading</h2>
 * {@link #start()}/{@link #stop()}/{@link #send}/{@link #sendStream}/{@link #setHandler} are safe
 * from any thread. Inbound frames and {@link MessageHandler#onPeerDown} are delivered on the
 * connection's own reader thread (a virtual thread) — the "network thread" of the
 * {@link MessageHandler} contract; handlers must offload heavy or re-entrant work.
 *
 * <h2>Connection de-duplication</h2>
 * Connections are keyed by the remote's advertised route; on a collision the first-registered
 * connection wins and the later one is closed. Higher layers avoid collisions by a single-dialer
 * policy (see {@code peer-runtime}: the numerically-smaller {@link NodeId} initiates peer↔peer
 * links; peers dial the bootstrap, never the reverse).
 */
public final class SocketPeerTransport implements PeerTransport {

    /** Absolute cap on a single socket frame (guards against a hostile length prefix). */
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    private final NodeId self;
    private final String bindHost;
    private final int bindPort;
    private final String advertiseHost;

    private final Object lifecycleLock = new Object();
    private final Object dialLock = new Object();
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();

    private volatile MessageHandler handler;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile String listenRoute;

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
        this.self = Objects.requireNonNull(self, "self");
        this.bindHost = Objects.requireNonNull(bindHost, "bindHost");
        this.advertiseHost = Objects.requireNonNull(advertiseHost, "advertiseHost");
        if (bindPort < 0 || bindPort > 65535) {
            throw new IllegalArgumentException("bindPort out of range: " + bindPort);
        }
        this.bindPort = bindPort;
    }

    /** @return this peer's stable id. */
    public NodeId nodeId() {
        return self;
    }

    /**
     * The dialable route other peers use to reach this transport, valid only while started:
     * {@code advertiseHost:<actual-bound-port>}.
     *
     * @return the advertised listen route, or {@code null} if not started.
     */
    public String listenRoute() {
        return listenRoute;
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(bindHost, bindPort));
                this.serverSocket = ss;
                this.listenRoute = advertiseHost + ":" + ss.getLocalPort();
                this.running = true;
                this.acceptThread = Thread.ofVirtual()
                        .name("nodera-socket-accept-" + ss.getLocalPort())
                        .start(this::acceptLoop);
            } catch (IOException e) {
                throw new TransportException("failed to bind " + bindHost + ":" + bindPort, e);
            }
        }
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
            send(to, MessageCodec.encode(chunk));
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
            socket.connect(new InetSocketAddress(hp.host(), hp.port()));
            socket.setTcpNoDelay(true);
        } catch (IOException e) {
            closeQuietly(socket);
            throw new TransportException("failed to dial " + route, e);
        }
        // Outbound connection: the remote's advertised route is exactly the dial target.
        Connection c = new Connection(socket, route);
        connections.put(route, c);
        c.startReader();
        c.sendHello();
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
            // Inbound connection: route is unknown until the peer's hello arrives.
            Connection c = new Connection(socket, null);
            c.startReader();
            c.sendHello();
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
            Thread.ofVirtual().name("nodera-socket-reader").start(this::readLoop);
        }

        void sendHello() {
            if (!helloSent.compareAndSet(false, true)) {
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
                // First frame is always the hello.
                byte[] helloFrame = readFrame();
                parseHello(helloFrame);
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
            } finally {
                closeQuietly();
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
            this.remoteNodeId = new NodeId(new UUID(msb, lsb));
            String key = advertised.isEmpty() ? ("anon:" + socket.getRemoteSocketAddress()) : advertised;
            // Register / re-key under the remote's advertised route; first-wins on collision.
            Connection prior = connections.putIfAbsent(key, this);
            if (prior != null && prior != this) {
                // Another connection to this route already won; drop this one.
                if (this.remoteRoute == null) {
                    throw new TransportException("duplicate connection to " + key);
                }
            }
            if (this.remoteRoute != null && !this.remoteRoute.equals(key)) {
                connections.remove(this.remoteRoute, this);
                connections.put(key, this);
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
