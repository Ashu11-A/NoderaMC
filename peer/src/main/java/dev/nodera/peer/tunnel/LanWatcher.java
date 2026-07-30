package dev.nodera.peer.tunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Watches the local network for a Minecraft world being opened to LAN, and says when one closes.
 *
 * <h2>Why the worker does this and the mod does not</h2>
 *
 * <p>The player this is for has no mod. They pressed <b>Open to LAN</b> in an ordinary Minecraft,
 * and the only thing that leaves their game is a multicast beacon on the local network. The
 * always-on worker is already running, already outside the game, and can simply listen — so the
 * feature works for vanilla, modded, any launcher, with nothing installed into Minecraft at all.
 *
 * <h2>Open is a stream of beacons; closed is their absence</h2>
 *
 * <p>Minecraft announces roughly every 1.5 seconds and says nothing at all when the world closes.
 * So "still open" is a beacon seen recently, and "closed" is a silence long enough not to be a
 * dropped packet. {@link #EXPIRY_MILLIS} is that judgement: several missed beacons, not one.
 *
 * <p>The same world re-announced from a different port is a <b>different</b> session, because the
 * port is what a joiner is ultimately connected to. Minecraft picks a fresh port each time a world
 * is opened, so this is also what makes "closed and reopened" distinguishable from "still open".
 *
 * @Thread-context {@link #start} spawns a daemon receive thread; every accessor is safe from any
 *                 thread. Listener callbacks run on the receive thread and must not block.
 */
public final class LanWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("NoderaWorker");

    /**
     * How long a world may go unannounced before it is treated as closed.
     *
     * <p>Vanilla announces every ~1.5 s, so this is several missed beacons. Shorter and a dropped
     * multicast packet would flap a world in and out of the directory; much longer and a player who
     * closed their world would keep being advertised as joinable.
     */
    public static final long EXPIRY_MILLIS = 8_000L;

    /** How often the sweep checks for worlds that have gone quiet. */
    private static final long SWEEP_MILLIS = 1_000L;

    private final MulticastSocket socket;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<Integer, Seen> open = new ConcurrentHashMap<>();
    private volatile Consumer<LanBeacon> onOpened = beacon -> { };
    private volatile Consumer<LanBeacon> onClosed = beacon -> { };
    private volatile Thread receiver;
    private volatile Thread sweeper;

    /** A beacon plus when it was last heard. */
    private record Seen(LanBeacon beacon, long lastHeardMillis) {
    }

    /**
     * Bind the multicast listener.
     *
     * @throws IOException if the group cannot be joined — which is normal on a machine with no
     *                     usable multicast interface, and is the caller's cue to run without the
     *                     LAN lane rather than to fail.
     */
    public LanWatcher() throws IOException {
        this.socket = new MulticastSocket(LanBeacon.PORT);
        socket.setReuseAddress(true);
        // A short read timeout so the receive loop can notice close() rather than blocking forever.
        socket.setSoTimeout((int) SWEEP_MILLIS);
        InetSocketAddress group = new InetSocketAddress(InetAddress.getByName(LanBeacon.GROUP),
                LanBeacon.PORT);
        int joined = 0;
        // Join on every interface that could carry it: a machine with a VPN, a container bridge and
        // a real NIC will otherwise join the wrong one and hear nothing, which presents as "the LAN
        // feature does not work" with no error anywhere.
        for (NetworkInterface nic : usableInterfaces()) {
            try {
                socket.joinGroup(group, nic);
                joined++;
            } catch (IOException notThisOne) {
                LOG.debug("Not joining the LAN group on {}: {}", nic.getName(),
                        notThisOne.getMessage());
            }
        }
        if (joined == 0) {
            socket.close();
            throw new IOException("no network interface could join " + LanBeacon.GROUP
                    + " — LAN worlds cannot be detected on this machine");
        }
    }

    private static List<NetworkInterface> usableInterfaces() throws IOException {
        java.util.List<NetworkInterface> out = new java.util.ArrayList<>();
        Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
        while (nics != null && nics.hasMoreElements()) {
            NetworkInterface nic = nics.nextElement();
            if (nic.isUp() && nic.supportsMulticast()) {
                out.add(nic);
            }
        }
        return out;
    }

    /**
     * Register the callbacks.
     *
     * @param opened called once when a world starts being announced.
     * @param closed called once when it stops.
     */
    public void onChange(Consumer<LanBeacon> opened, Consumer<LanBeacon> closed) {
        this.onOpened = Objects.requireNonNull(opened, "opened");
        this.onClosed = Objects.requireNonNull(closed, "closed");
    }

    /** Begin listening. Idempotent. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread r = new Thread(this::receiveLoop, "nodera-lan-watch");
        r.setDaemon(true);
        r.start();
        receiver = r;
        Thread s = new Thread(this::sweepLoop, "nodera-lan-sweep");
        s.setDaemon(true);
        s.start();
        sweeper = s;
        LOG.info("Watching {}:{} for worlds opened to LAN", LanBeacon.GROUP, LanBeacon.PORT);
    }

    /** @return the LAN worlds currently being announced on this machine's network. */
    public List<LanBeacon> openWorlds() {
        return open.values().stream().map(Seen::beacon).toList();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[512];
        while (running.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException quiet) {
                continue;
            } catch (IOException e) {
                if (running.get()) {
                    LOG.debug("LAN receive failed: {}", e.getMessage());
                }
                continue;
            }
            String payload = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                    StandardCharsets.UTF_8);
            LanBeacon.parse(payload).ifPresent(this::heard);
        }
    }

    private void heard(LanBeacon beacon) {
        long now = System.currentTimeMillis();
        Seen previous = open.put(beacon.port(), new Seen(beacon, now));
        if (previous == null) {
            LOG.info("A world was opened to LAN: '{}' on port {}", beacon.motd(), beacon.port());
            fire(onOpened, beacon);
        }
    }

    private void sweepLoop() {
        while (running.get()) {
            try {
                Thread.sleep(SWEEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            long deadline = System.currentTimeMillis() - EXPIRY_MILLIS;
            for (Map.Entry<Integer, Seen> entry : Map.copyOf(open).entrySet()) {
                if (entry.getValue().lastHeardMillis() < deadline
                        && open.remove(entry.getKey(), entry.getValue())) {
                    LOG.info("The LAN world '{}' on port {} has closed",
                            entry.getValue().beacon().motd(), entry.getKey());
                    fire(onClosed, entry.getValue().beacon());
                }
            }
        }
    }

    /** A listener that throws must not take the watcher down with it. */
    private static void fire(Consumer<LanBeacon> listener, LanBeacon beacon) {
        try {
            listener.accept(beacon);
        } catch (RuntimeException e) {
            LOG.warn("A LAN listener failed on '{}': {}", beacon.motd(), e.getMessage());
        }
    }

    @Override
    public void close() {
        running.set(false);
        socket.close();
        Thread r = receiver;
        if (r != null) {
            r.interrupt();
        }
        Thread s = sweeper;
        if (s != null) {
            s.interrupt();
        }
        open.clear();
    }
}
