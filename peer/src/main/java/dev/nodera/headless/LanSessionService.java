package dev.nodera.headless;

import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeId;
import dev.nodera.peer.control.WorkerEvent;
import dev.nodera.peer.control.WorkerEventBus;
import dev.nodera.peer.tunnel.LanBeacon;
import dev.nodera.peer.tunnel.LanWatcher;
import dev.nodera.peer.tunnel.TunnelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turning "Open to LAN" into "open to the Nodera network" — with the player deciding.
 *
 * <h2>The lane in one paragraph</h2>
 *
 * <p>A player opens a world to LAN in an ordinary Minecraft. The worker hears the multicast beacon
 * and records an <b>offer</b>: a world it <i>could</i> share, and has not. The companion app raises
 * that as a modal. If the player accepts, the world's LAN port is published as a tunnel session and
 * announced to the trackers, so other people can find it and connect straight through to that
 * socket. When the world closes, the beacon stops, and everything is withdrawn.
 *
 * <h2>Consent is a state, not a prompt</h2>
 *
 * <p>Nothing is announced until somebody says so. Declining is remembered for that world, so the app
 * can stop asking without the offer disappearing — a declined world stays listed so it can be shared
 * later from the UI, which is exactly what "ask me again later" has to mean if it is to be useful.
 * The three states are therefore {@code offered}, {@code shared} and {@code declined}, and only the
 * middle one is on the network.
 *
 * <h2>Why a port change is a new world</h2>
 *
 * <p>Minecraft picks a fresh port every time a world is opened to LAN, and announces nothing at all
 * when it closes. So the port <i>is</i> the session's identity: it is what a joiner is ultimately
 * connected to, and a new one means the previous session cannot be reached whatever it was called.
 * Sessions are keyed by port for that reason, and their ids are derived from it.
 *
 * @Thread-context safe from any thread; the watcher's callbacks land on its receive thread and do
 *                 only bookkeeping here.
 */
public final class LanSessionService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    /** What the player has decided about one detected world. */
    public enum State {
        /** Detected, and nobody has been asked yet — or the answer was "later". */
        OFFERED,
        /** Published to the network: announced, and accepting tunnelled connections. */
        SHARED,
        /** The player said no. Kept, so it can still be shared later from the UI. */
        DECLINED
    }

    /** One LAN world this machine can see, and what has been decided about it. */
    public record Session(String sessionIdHex, String name, int port, State state,
                          long detectedAtEpochMillis) {
    }

    private final NodeId self;
    private final TunnelService tunnel;
    private final WorldHostingService hosting;
    private final HashService hashes = new HashService();

    /** port → session. Ports are the identity; see the class docs. */
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    /**
     * The multicast listener, or {@code null} in a test that drives {@link #onOpened} directly.
     *
     * <p>Nullable rather than mocked because the decision machine here — offered → shared →
     * declined, and what a world closing does to each — is the part with rules in it, and it should
     * be testable without a machine that can receive multicast. The listener itself is tested
     * separately, against real datagram payloads.
     */
    private final LanWatcher watcher;

    /**
     * Where this service announces what it saw, or {@code null} when nobody is listening.
     *
     * <p>The companion app's prompt hangs off this. Detection is a <b>moment</b> — a player pressed
     * a button — and a client that had to notice it by diffing successive state snapshots would need
     * its own previous copy, a definition of "changed", and to have been connected at the time. The
     * worker saying so once, into a buffer a late client can replay, is the difference between a
     * prompt that appears and one that appears when the timing happens to work out.
     */
    private final WorkerEventBus events;

    /**
     * As above, announcing to {@code events} so the companion app is told rather than left to
     * notice.
     *
     * @param events the node's event bus, or {@code null} when this embedding announces nothing.
     */
    public LanSessionService(NodeId self, TunnelService tunnel, WorldHostingService hosting,
                             WorkerEventBus events) throws IOException {
        this(self, tunnel, hosting, new LanWatcher(), events);
    }

    /** As above, with the listener supplied (or {@code null} for a test that drives it by hand). */
    LanSessionService(NodeId self, TunnelService tunnel, WorldHostingService hosting,
                      LanWatcher watcher, WorkerEventBus events) {
        this.events = events;
        this.self = Objects.requireNonNull(self, "self");
        this.tunnel = Objects.requireNonNull(tunnel, "tunnel");
        this.hosting = Objects.requireNonNull(hosting, "hosting");
        this.watcher = watcher;
        if (watcher != null) {
            watcher.onChange(this::onOpened, this::onClosed);
        }
    }

    /** Begin watching. */
    public void start() {
        if (watcher != null) {
            watcher.start();
        }
    }

    /** @return every LAN world this machine can currently see, with what was decided about it. */
    public List<Session> sessions() {
        return List.copyOf(sessions.values());
    }

    /** @return the session on a port, if one is open there. */
    public Optional<Session> session(int port) {
        return Optional.ofNullable(sessions.get(port));
    }

    /** @return the session with this id, if it is open. */
    public Optional<Session> byId(String sessionIdHex) {
        if (sessionIdHex == null) {
            return Optional.empty();
        }
        String id = sessionIdHex.trim().toLowerCase(Locale.ROOT);
        return sessions.values().stream().filter(s -> s.sessionIdHex().equals(id)).findFirst();
    }

    /**
     * Share a detected world with the network — the "yes" the modal asks for.
     *
     * @param port the LAN port of the world to share.
     * @return {@code null} on success, or the reason it could not be shared.
     * @Thread-context any thread.
     */
    public String share(int port) {
        Session session = sessions.get(port);
        if (session == null) {
            // Not a hypothetical: a player can press Share just as they close the world.
            return "no world is open to LAN on port " + port;
        }
        if (session.state() == State.SHARED) {
            return null; // idempotent — a second yes is not an error
        }
        tunnel.publish(session.sessionIdHex(), port);
        // Announced through the ordinary hosting lane, so a LAN session appears in the same
        // directory as everything else and needs no second discovery path. It carries no content:
        // "lan":true is what tells a browser this world is a live connection, not a download.
        String error = hosting.host(session.sessionIdHex(), session.name(),
                "{\"mc\":\"127.0.0.1:" + port + "\",\"players\":0,\"lan\":true}");
        if (error != null) {
            tunnel.unpublish(session.sessionIdHex());
            return error;
        }
        sessions.put(port, new Session(session.sessionIdHex(), session.name(), port, State.SHARED,
                session.detectedAtEpochMillis()));
        LOG.info("'{}' is now open to the Nodera network — other players can join it", session.name());
        announce(WorkerEvent.LAN_SHARED, session);
        return null;
    }

    /**
     * Decline to share a world — the "no" the modal asks for.
     *
     * <p>The world is kept in the list. "No" answers the prompt, not the question: the player may
     * still open it to the network later, and a declined world that vanished from the UI would make
     * that impossible without closing and reopening the world.
     *
     * @param port the LAN port.
     * @return {@code null} on success, or the reason.
     */
    public String decline(int port) {
        Session session = sessions.get(port);
        if (session == null) {
            return "no world is open to LAN on port " + port;
        }
        if (session.state() == State.SHARED) {
            String error = stop(port);
            if (error != null) {
                return error;
            }
        }
        sessions.put(port, new Session(session.sessionIdHex(), session.name(), port, State.DECLINED,
                session.detectedAtEpochMillis()));
        announce(WorkerEvent.LAN_DECLINED, session);
        return null;
    }

    /**
     * Stop sharing a world that is currently on the network, returning it to merely offered.
     *
     * @param port the LAN port.
     * @return {@code null} on success, or the reason.
     */
    public String stop(int port) {
        Session session = sessions.get(port);
        if (session == null) {
            return "no world is open to LAN on port " + port;
        }
        tunnel.unpublish(session.sessionIdHex());
        hosting.stop(session.sessionIdHex());
        sessions.put(port, new Session(session.sessionIdHex(), session.name(), port, State.OFFERED,
                session.detectedAtEpochMillis()));
        LOG.info("'{}' is no longer shared with the Nodera network", session.name());
        announce(WorkerEvent.LAN_STOPPED, session);
        return null;
    }

    void onOpened(LanBeacon beacon) {
        String id = sessionId(beacon).toHex();
        Session created = new Session(id, beacon.motd(), beacon.port(), State.OFFERED,
                System.currentTimeMillis());
        // Announce only when this is genuinely new. Vanilla re-broadcasts every ~1.5 s, and an event
        // per beacon would be a modal that reopens itself forever.
        if (sessions.putIfAbsent(beacon.port(), created) == null) {
            announce(WorkerEvent.LAN_OPENED, created);
        }
    }

    void onClosed(LanBeacon beacon) {
        Session session = sessions.remove(beacon.port());
        if (session == null) {
            return;
        }
        // Withdraw unconditionally. A session whose game has gone is not joinable however it was
        // announced, and leaving it listed would send every browser at a socket that is not there.
        //
        // Unless the same world is open again on another port: the session id no longer carries the
        // port, so re-opening a world produces the *same* id on a new port, and the old port's close
        // beacon arriving afterwards would withdraw the live session. Identity is the id, not the
        // map key.
        Session moved = openElsewhere(session);
        if (moved != null) {
            // The world moved, so the route has to move with it — the id is unchanged, and an
            // announce pointing at the abandoned port would send every joiner at a closed socket.
            if (session.state() == State.SHARED) {
                sessions.put(moved.port(), new Session(moved.sessionIdHex(), moved.name(),
                        moved.port(), State.OFFERED, moved.detectedAtEpochMillis()));
                share(moved.port());
            }
            announce(WorkerEvent.LAN_CLOSED, session);
            return;
        }
        tunnel.unpublish(session.sessionIdHex());
        if (session.state() == State.SHARED) {
            hosting.stop(session.sessionIdHex());
            LOG.info("'{}' closed, so it has been withdrawn from the network", session.name());
        }
        // Announced whatever was decided: a client showing a prompt for this world has to take it
        // down, and one showing it as shared has to stop.
        announce(WorkerEvent.LAN_CLOSED, session);
    }

    /** The live session carrying the same id — i.e. the world moved port rather than closing. */
    private Session openElsewhere(Session closed) {
        for (Session other : sessions.values()) {
            if (other.sessionIdHex().equals(closed.sessionIdHex())) {
                return other;
            }
        }
        return null;
    }

    /**
     * Tell whoever is listening.
     *
     * <p>Carries everything a client needs to act without a follow-up query — the port to answer
     * with, the name to show, the session id to join by — because the alternative is a prompt that
     * has to make a round trip before it can render.
     */
    private void announce(String name, Session session) {
        WorkerEventBus bus = events;
        if (bus == null) {
            return;
        }
        bus.publish(WorkerEvent.named(name)
                .with("session", session.sessionIdHex())
                .with("world", session.name())
                .with("port", session.port())
                .with("state", session.state().name().toLowerCase(Locale.ROOT))
                .build());
    }

    /**
     * Derive a session id from the node and the world's advertised name.
     *
     * <p>Deterministic so the same open world keeps one identity across a worker restart, and
     * node-scoped so two players sharing "New World" are two different sessions.
     *
     * <p><strong>The port is deliberately not part of this.</strong> It used to be, and the comment
     * here claimed the result was stable across a restart — it was not: vanilla's Open-to-LAN picks
     * a fresh ephemeral port every time it is opened, so every re-open minted a different session
     * id, announced it, and left the previous one in the registry. One world, one player, five
     * evenings = five worlds on the network, none of which could be told apart by name. The MOTD
     * carries the player and the level name, which is what actually identifies the session.
     */
    private Bytes sessionId(LanBeacon beacon) {
        CanonicalWriter w = new CanonicalWriter();
        w.writeU64(self.value().getMostSignificantBits());
        w.writeU64(self.value().getLeastSignificantBits());
        w.writeString(beacon.motd());
        return hashes.sha256(w.toBytes());
    }

    @Override
    public void close() {
        for (Session session : List.copyOf(sessions.values())) {
            if (session.state() == State.SHARED) {
                tunnel.unpublish(session.sessionIdHex());
                hosting.stop(session.sessionIdHex());
            }
        }
        sessions.clear();
        if (watcher != null) {
            watcher.close();
        }
    }
}
