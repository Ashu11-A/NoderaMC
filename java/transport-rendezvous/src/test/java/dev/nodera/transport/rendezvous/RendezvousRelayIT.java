package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeCapabilities;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.protocol.NoderaMessage;
import dev.nodera.protocol.codec.MessageCodec;
import dev.nodera.protocol.membership.PeerJoin;
import dev.nodera.protocol.membership.SessionKeepAlive;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.rendezvous.TransportSelector.MessageClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Task 29 acceptance #3 (L-27 exit): the <b>real</b> {@code nodera-rendezvous} binary bridges two
 * Java peers whose direct listeners are disabled, over an end-to-end-encrypted relay circuit.
 *
 * <p>Two {@link RendezvousPeerTransport}s (relay-only) register with a freshly spawned Rust process,
 * discover each other, and exchange real {@code PeerTransport} traffic — a {@code PeerJoin} and a
 * {@code SessionKeepAlive}, byte-exact — over the E2E circuit the relay can neither read nor forge.
 * Exhausting the reservation's byte ceiling tears the circuit down. Finally the {@link
 * TransportSelector} is shown to report the direct path when one is available (the punch-upgrade
 * policy), so a loopback pair with direct dialing allowed would prefer it.
 *
 * <p>Skipped (not failed) when the binary is absent: {@code cargo} is a separate toolchain and a
 * Java-only checkout stays green. Build it with
 * {@code cd rust && cargo build --release --bin nodera-rendezvous}.
 *
 * <p>Thread-context: single test thread; the service runs in a child process; transports use their
 * own daemon threads.
 */
final class RendezvousRelayIT {

    private static final UUID NETWORK = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Bytes GENESIS = Bytes.fromHex("11".repeat(32));

    private Process service;
    private Path configFile;
    private RendezvousPeerTransport a;
    private RendezvousPeerTransport b;

    @AfterEach
    void tearDown() throws Exception {
        if (a != null) {
            a.stop();
        }
        if (b != null) {
            b.stop();
        }
        if (service != null) {
            service.destroy();
            if (!service.waitFor(10, TimeUnit.SECONDS)) {
                service.destroyForcibly();
            }
        }
        if (configFile != null) {
            Files.deleteIfExists(configFile);
        }
    }

    @Test
    void twoRelayedPeersExchangeEndToEndTrafficAndTheByteCapTearsDown() throws Exception {
        Optional<Path> binary = serviceBinary();
        assumeThat(binary).as("nodera-rendezvous binary built").isPresent();
        RendezvousEndpoint endpoint = startService(binary.get(), 65_536);

        NodeIdentity idA = NodeIdentity.generate();
        NodeIdentity idB = NodeIdentity.generate();
        TestHandler handlerA = new TestHandler();
        TestHandler handlerB = new TestHandler();

        a = relayOnlyTransport(idA, endpoint);
        b = relayOnlyTransport(idB, endpoint);
        a.setHandler(handlerA);
        b.setHandler(handlerB);

        b.start();
        a.start();

        // Each side discovers the other, learning its identity key so the circuit can authenticate.
        assertThat(a.discover()).contains(idB.nodeId());
        assertThat(b.discover()).contains(idA.nodeId());

        // A committee-shaped exchange over the relay: A → B a PeerJoin, B → A a keep-alive.
        NoderaMessage join = new PeerJoin(
                idA.nodeId(), "relay", NodeCapabilities.initial(), false);
        a.send(PeerAddress.of(idB.nodeId(), "relay"), MessageCodec.encode(join));
        NoderaMessage delivered = handlerB.take();
        assertThat(delivered).isEqualTo(join);

        NoderaMessage keepAlive = new SessionKeepAlive(idB.nodeId(), 7L);
        b.send(PeerAddress.of(idA.nodeId(), "relay"), MessageCodec.encode(keepAlive));
        assertThat(handlerA.take()).isEqualTo(keepAlive);

        // The selector reports the direct path when one is available — the punch-upgrade policy that
        // a loopback pair with direct dialing allowed would follow to leave the relay.
        assertThat(a.selector().select(idB.nodeId(), MessageClass.CONTROL,
                EnumSet.of(TransportSelector.Path.DIRECT, TransportSelector.Path.RELAYED)))
                .isEqualTo(TransportSelector.Path.DIRECT);

        // Exhausting the 64 KiB reservation ceiling tears the circuit down: a peer-down is observed.
        byte[] oversized = new byte[100_000];
        java.util.Arrays.fill(oversized, (byte) 0x5A);
        try {
            a.sendStream(PeerAddress.of(idB.nodeId(), "relay"), 1L, oversized);
        } catch (RuntimeException tornDownMidSend) {
            // acceptable: the write may fail as the relay closes the circuit
        }
        assertThat(handlerA.peerDown.await(10, TimeUnit.SECONDS)
                || handlerB.peerDown.await(10, TimeUnit.SECONDS))
                .as("the byte ceiling tore the circuit down")
                .isTrue();
    }

    private RendezvousPeerTransport relayOnlyTransport(NodeIdentity id, RendezvousEndpoint endpoint) {
        return new RendezvousPeerTransport(
                id, List.of(endpoint), NETWORK, GENESIS, NodeCapabilities.initial(), null);
    }

    /** Start the service on an ephemeral port with the given circuit byte ceiling. */
    private RendezvousEndpoint startService(Path binary, long maxBytes) throws IOException {
        configFile = Files.createTempFile("nodera-rendezvous", ".toml");
        Files.writeString(configFile, """
                bind_addr = "127.0.0.1:0"
                registration_ttl_seconds = 60
                refresh_interval_seconds = 30
                reservation_max_bytes = %d
                per_ip_request_quota = 0
                reservation_hmac_key_hex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
                """.formatted(maxBytes), StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder(
                binary.toString(), "--config", configFile.toString());
        builder.redirectErrorStream(true);
        service = builder.start();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(service.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        String line;
        while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
            int idx = line.indexOf("listening on ");
            if (idx >= 0) {
                String addr = line.substring(idx + "listening on ".length()).trim();
                // Drain further output on a daemon thread so the child never blocks on a full pipe.
                Thread drain = new Thread(() -> {
                    try {
                        String l;
                        while ((l = reader.readLine()) != null) {
                            // ignore; the service logs go nowhere in the test
                        }
                    } catch (IOException ignored) {
                        // stream closed on shutdown
                    }
                }, "rendezvous-log-drain");
                drain.setDaemon(true);
                drain.start();
                return RendezvousEndpoint.parse(addr);
            }
        }
        throw new IOException("service did not report its bound address");
    }

    private static Optional<Path> serviceBinary() {
        Path root = repoRoot().resolve("rust").resolve("target");
        for (String profile : new String[] {"release", "debug"}) {
            Path candidate = root.resolve(profile).resolve("nodera-rendezvous");
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repo root not found");
        }
        return dir;
    }

    /** Collects delivered frames (decoded) and signals peer-down. */
    private static final class TestHandler implements MessageHandler {
        final BlockingQueue<NoderaMessage> received = new LinkedBlockingQueue<>();
        final CountDownLatch peerDown = new CountDownLatch(1);

        @Override
        public void onMessage(PeerAddress from, byte[] frame) {
            received.add(MessageCodec.decode(frame));
        }

        @Override
        public void onPeerDown(PeerAddress peer) {
            peerDown.countDown();
        }

        NoderaMessage take() throws InterruptedException {
            NoderaMessage message = received.poll(10, TimeUnit.SECONDS);
            assertThat(message).as("a frame was delivered within the timeout").isNotNull();
            return message;
        }
    }
}
