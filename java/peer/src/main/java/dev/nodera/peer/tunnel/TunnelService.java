package dev.nodera.peer.tunnel;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.tunnel.TunnelClose;
import dev.nodera.protocol.tunnel.TunnelData;
import dev.nodera.protocol.tunnel.TunnelOpen;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.PeerTransport;
import dev.nodera.transport.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Carrying somebody else's TCP connection across the Nodera network.
 *
 * <h2>What this is for</h2>
 *
 * <p>A player presses <b>Open to LAN</b> in an ordinary, unmodified Minecraft. Their game starts
 * listening on a local port and shouts about it on the local network — and that is as far as it
 * goes, because a LAN is a LAN. This service extends the reach of that socket and nothing else: a
 * second player's game connects to a port on <i>their own</i> machine, and those bytes come out of
 * the first player's loopback as though the two were in the same room.
 *
 * <p>What is <b>not</b> happening is as important as what is. No world is transferred, no save is
 * copied, no chunk is replicated. The host's game remains the only authority over its world; this
 * moves the connection, not the content. That is why an unmodified client can use it at all — from
 * Minecraft's point of view nothing unusual has happened.
 *
 * <h2>Why a session id and not an address</h2>
 *
 * <p>The naive version of this — "connect me to {@code host:port}" — turns every peer into an open
 * TCP proxy into its own loopback and LAN, for anyone who can reach it. So a guest cannot express a
 * destination: {@link TunnelOpen} names a <b>session</b>, and only {@link #publish} decides what a
 * session resolves to. There is no message that can reach an unpublished port, which makes the
 * property structural rather than a check somebody has to remember.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #onMessage} runs on the runtime's state thread and must never block. Inbound payloads
 * are therefore handed to a per-stream writer with a bounded queue; if a socket cannot keep up, the
 * stream is closed rather than allowed to stall the whole node. Each stream also has one reader
 * thread pumping its socket outward. Streams are cheap and few: one per joined player.
 *
 * @Thread-context all methods safe from any thread; state is held in concurrent maps.
 */
public final class TunnelService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaTunnel");

    /**
     * Bytes read from a socket before they are handed to the transport.
     *
     * <p>16 KiB is chosen against the transport's inline frame budget rather than against Minecraft:
     * a bigger chunk would risk a frame the transport has to fragment for us, and a smaller one
     * multiplies per-frame overhead on a link that carries a game's whole traffic.
     */
    static final int CHUNK_BYTES = 16 * 1024;

    /**
     * How many inbound chunks may wait for one slow socket before the stream is dropped.
     *
     * <p>Dropping is deliberate and is the lesser evil: the alternative is applying backpressure to
     * the state thread, where the queue behind it is the entire node's message handling. One
     * player's connection failing is a bad minute; a stalled node is everyone's.
     */
    static final int MAX_QUEUED_CHUNKS = 256;

    private final PeerTransport transport;
    private final NodeId self;

    /** sessionIdHex → the local port this node publishes for it. Host side. */
    private final Map<String, Integer> published = new ConcurrentHashMap<>();

    /** (peer, streamId) → the socket serving it. Both sides. */
    private final Map<StreamKey, Stream> streams = new ConcurrentHashMap<>();

    /** Local listeners this node has opened for guests to point their game at. Guest side. */
    private final Map<String, LocalEndpoint> listeners = new ConcurrentHashMap<>();

    private final AtomicLong nextStreamId = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param self      this node's id, used only to key streams distinctly from peers'.
     * @param transport the peer transport the tunnelled bytes ride.
     */
    public TunnelService(NodeId self, PeerTransport transport) {
        this.self = Objects.requireNonNull(self, "self");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    // --- host side ---------------------------------------------------------------------------

    /**
     * Publish a session: from now on, a guest naming {@code sessionIdHex} is connected to
     * {@code localPort} on this machine and to nothing else.
     *
     * @param sessionIdHex the session identity, hex-encoded.
     * @param localPort    the loopback port the host's game is listening on.
     * @throws IllegalArgumentException if the port is not a usable one.
     * @Thread-context any thread.
     */
    public void publish(String sessionIdHex, int localPort) {
        Objects.requireNonNull(sessionIdHex, "sessionIdHex");
        if (localPort <= 0 || localPort > 65535) {
            throw new IllegalArgumentException("not a port: " + localPort);
        }
        published.put(key(sessionIdHex), localPort);
        LOG.info("Publishing session {} → 127.0.0.1:{} for the network", shortId(sessionIdHex),
                localPort);
    }

    /**
     * Stop publishing a session and drop every stream serving it.
     *
     * <p>The streams go too, on purpose: a session is withdrawn when the host's world closes or the
     * player declines, and leaving live connections attached to a session nobody can join any more
     * would mean the "stop sharing" button did not stop sharing.
     *
     * @param sessionIdHex the session.
     * @return whether it had been published.
     */
    public boolean unpublish(String sessionIdHex) {
        if (sessionIdHex == null || published.remove(key(sessionIdHex)) == null) {
            return false;
        }
        for (Stream stream : List.copyOf(streams.values())) {
            if (key(sessionIdHex).equals(stream.sessionIdHex)) {
                stream.close("the host stopped sharing this world");
            }
        }
        LOG.info("Session {} withdrawn", shortId(sessionIdHex));
        return true;
    }

    /** @return the local port a session resolves to, or empty when it is not published here. */
    public java.util.Optional<Integer> publishedPort(String sessionIdHex) {
        return sessionIdHex == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(published.get(key(sessionIdHex)));
    }

    // --- guest side --------------------------------------------------------------------------

    /**
     * Open a local door onto a remote session: bind a loopback port here, and connect every socket
     * that arrives on it to {@code host}'s published session.
     *
     * <p>The returned port is what the player types into Minecraft's <b>Direct Connect</b>. It is
     * bound to loopback only — this listener is a door into somebody else's game, and it has no
     * business being reachable from the network.
     *
     * <p>Idempotent per session: asking twice returns the same listener rather than opening a second
     * port, so a UI that re-issues a join does not leave ports behind.
     *
     * @param sessionIdHex the remote session to attach to.
     * @param host         the peer publishing it.
     * @return the local endpoint to point a game at.
     * @throws IOException if the loopback port cannot be bound.
     * @Thread-context any thread.
     */
    public LocalEndpoint open(String sessionIdHex, PeerAddress host) throws IOException {
        Objects.requireNonNull(sessionIdHex, "sessionIdHex");
        Objects.requireNonNull(host, "host");
        String session = key(sessionIdHex);
        LocalEndpoint existing = listeners.get(session);
        if (existing != null && existing.isOpen()) {
            return existing;
        }
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        LocalEndpoint endpoint = new LocalEndpoint(session, host, server);
        listeners.put(session, endpoint);
        Thread accept = new Thread(() -> acceptLoop(endpoint), "nodera-tunnel-accept");
        accept.setDaemon(true);
        accept.start();
        LOG.info("Listening on 127.0.0.1:{} — point Minecraft here to join session {}",
                server.getLocalPort(), shortId(session));
        return endpoint;
    }

    /** Close the local door onto a session (the guest leaving). */
    public boolean closeLocal(String sessionIdHex) {
        LocalEndpoint endpoint = sessionIdHex == null ? null : listeners.remove(key(sessionIdHex));
        if (endpoint == null) {
            return false;
        }
        endpoint.close();
        for (Stream stream : List.copyOf(streams.values())) {
            if (endpoint.sessionIdHex.equals(stream.sessionIdHex)) {
                stream.close("you left this world");
            }
        }
        return true;
    }

    /** @return the local endpoints currently open, keyed by session. */
    public Map<String, LocalEndpoint> localEndpoints() {
        return Map.copyOf(listeners);
    }

    private void acceptLoop(LocalEndpoint endpoint) {
        while (!closed.get() && endpoint.isOpen()) {
            Socket socket;
            try {
                socket = endpoint.server.accept();
            } catch (IOException e) {
                return; // the listener was closed, or the port died with it
            }
            long streamId = nextStreamId.getAndIncrement();
            Stream stream = new Stream(endpoint.sessionIdHex, endpoint.host, streamId, socket);
            streams.put(new StreamKey(endpoint.host.nodeId(), streamId), stream);
            try {
                send(endpoint.host, new TunnelOpen(Bytes.fromHex(endpoint.sessionIdHex), streamId));
            } catch (RuntimeException unreachable) {
                stream.close("could not reach the host: " + unreachable.getMessage());
                continue;
            }
            stream.startReader();
        }
    }

    // --- both sides --------------------------------------------------------------------------

    /**
     * Handle one tunnel message. Ignores everything that is not one.
     *
     * @param from    the sender.
     * @param message the message.
     * @Thread-context the runtime's state thread — must not block.
     */
    public void onMessage(PeerAddress from, NoderaMessage message) {
        if (from == null || from.nodeId() == null) {
            return;
        }
        if (message instanceof TunnelOpen open) {
            onOpen(from, open);
        } else if (message instanceof TunnelData data) {
            Stream stream = streams.get(new StreamKey(from.nodeId(), data.streamId()));
            if (stream != null) {
                stream.enqueue(data.payload());
            }
            // No stream: a chunk for something already closed. Silently dropped — answering would
            // be an error message per late packet on a connection that is over.
        } else if (message instanceof TunnelClose close) {
            Stream stream = streams.get(new StreamKey(from.nodeId(), close.streamId()));
            if (stream != null) {
                stream.closeLocally(close.reason());
            }
        }
    }

    /** The host end: a guest wants a socket. Resolve the session and dial our own game. */
    private void onOpen(PeerAddress from, TunnelOpen open) {
        String session = key(open.sessionId().toHex());
        Integer port = published.get(session);
        if (port == null) {
            // The refusal that keeps this from being an open proxy. It is also the honest answer to
            // a guest whose host has since closed the world.
            send(from, new TunnelClose(open.streamId(),
                    "this peer is not sharing that world"));
            return;
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3000);
        } catch (IOException e) {
            closeQuietly(socket);
            send(from, new TunnelClose(open.streamId(),
                    "the host's game is not answering on its LAN port: " + e.getMessage()));
            // The world is gone even though the session is still published — withdraw it so the
            // next guest is refused early rather than after a three-second dial.
            unpublish(session);
            return;
        }
        Stream stream = new Stream(session, from, open.streamId(), socket);
        streams.put(new StreamKey(from.nodeId(), open.streamId()), stream);
        stream.startReader();
        LOG.info("Guest {} attached to session {}", shortNode(from.nodeId()), shortId(session));
    }

    private void send(PeerAddress to, NoderaMessage message) {
        try {
            transport.send(to, MessageCodec.encode(message));
        } catch (TransportException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (LocalEndpoint endpoint : List.copyOf(listeners.values())) {
            endpoint.close();
        }
        listeners.clear();
        for (Stream stream : List.copyOf(streams.values())) {
            stream.close("this peer is shutting down");
        }
        streams.clear();
        published.clear();
    }

    /** @return how many tunnelled streams are live right now. */
    public int activeStreams() {
        return streams.size();
    }

    /** A loopback port a guest points their game at, and the session behind it. */
    public static final class LocalEndpoint {
        private final String sessionIdHex;
        private final PeerAddress host;
        private final ServerSocket server;

        private LocalEndpoint(String sessionIdHex, PeerAddress host, ServerSocket server) {
            this.sessionIdHex = sessionIdHex;
            this.host = host;
            this.server = server;
        }

        /** @return the port to type into Minecraft's Direct Connect. */
        public int port() {
            return server.getLocalPort();
        }

        /** @return {@code 127.0.0.1:<port>}. */
        public String address() {
            return "127.0.0.1:" + port();
        }

        /** @return the session this door leads to. */
        public String sessionIdHex() {
            return sessionIdHex;
        }

        boolean isOpen() {
            return !server.isClosed();
        }

        void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    /** Keyed by peer as well as id: two guests may both call their first stream 1. */
    private record StreamKey(NodeId peer, long streamId) {
    }

    /** One tunnelled TCP connection. */
    private final class Stream {
        private final String sessionIdHex;
        private final PeerAddress peer;
        private final long streamId;
        private final Socket socket;
        private final java.util.concurrent.BlockingQueue<Bytes> inbound =
                new java.util.concurrent.ArrayBlockingQueue<>(MAX_QUEUED_CHUNKS);
        private final AtomicBoolean done = new AtomicBoolean();

        private Stream(String sessionIdHex, PeerAddress peer, long streamId, Socket socket) {
            this.sessionIdHex = sessionIdHex;
            this.peer = peer;
            this.streamId = streamId;
            this.socket = socket;
        }

        /** Start the two pumps: socket → peer, and queue → socket. */
        void startReader() {
            Thread reader = new Thread(this::pumpOutward, "nodera-tunnel-out-" + streamId);
            reader.setDaemon(true);
            reader.start();
            Thread writer = new Thread(this::pumpInward, "nodera-tunnel-in-" + streamId);
            writer.setDaemon(true);
            writer.start();
        }

        private void pumpOutward() {
            byte[] buffer = new byte[CHUNK_BYTES];
            try (InputStream in = socket.getInputStream()) {
                int read;
                while (!done.get() && (read = in.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    send(peer, new TunnelData(streamId, Bytes.unsafeWrap(chunk)));
                }
                close(""); // clean EOF: the game closed the connection
            } catch (IOException | RuntimeException e) {
                close(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }

        private void pumpInward() {
            try (OutputStream out = socket.getOutputStream()) {
                while (!done.get()) {
                    Bytes chunk = inbound.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (chunk == null) {
                        continue;
                    }
                    out.write(chunk.toArray());
                    out.flush();
                }
            } catch (IOException | RuntimeException e) {
                close(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                close("interrupted");
            }
        }

        /** Called on the state thread: never blocks, drops the stream instead. */
        void enqueue(Bytes payload) {
            if (done.get()) {
                return;
            }
            if (!inbound.offer(payload)) {
                // The socket is not draining. Closing one player's connection beats stalling the
                // node's whole message handling behind it.
                LOG.warn("Tunnel stream {} fell behind ({} chunks queued) — dropping it",
                        streamId, MAX_QUEUED_CHUNKS);
                close("this connection could not keep up");
            }
        }

        /** End the stream and tell the other side why. */
        void close(String reason) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            streams.remove(new StreamKey(peer.nodeId(), streamId));
            closeQuietly(socket);
            try {
                send(peer, new TunnelClose(streamId, reason == null ? "" : reason));
            } catch (RuntimeException unreachable) {
                // The peer is why we are closing; failing to tell it is expected.
            }
        }

        /** End the stream because the other side said so — no reply, or the two would ping-pong. */
        void closeLocally(String reason) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            streams.remove(new StreamKey(peer.nodeId(), streamId));
            closeQuietly(socket);
            if (reason != null && !reason.isBlank()) {
                LOG.info("Tunnel stream {} ended by the other side: {}", streamId, reason);
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private static String key(String hex) {
        return hex.trim().toLowerCase(Locale.ROOT);
    }

    private static String shortId(String hex) {
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }

    private static String shortNode(NodeId node) {
        return node == null ? "?" : node.value().toString().substring(0, 8);
    }

    /** @return this node's id (kept so a future self-dial guard has something to compare against). */
    NodeId self() {
        return self;
    }
}
