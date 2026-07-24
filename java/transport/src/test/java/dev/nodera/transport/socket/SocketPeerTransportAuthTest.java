package dev.nodera.transport.socket;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeId;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.transport.MessageHandler;
import dev.nodera.transport.PeerAddress;
import dev.nodera.transport.TransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #41 / L-53: the authenticated socket handshake proves key possession at the transport
 * accept path. These tests pin the exit conditions over real TCP: (1) two authenticated
 * transports interoperate and the receiver's frame attribution carries the key-proven
 * {@link NodeId}; (2) a peer presenting the legacy unauthenticated hello is refused (no frame
 * ever reaches the handler); (3) a peer whose hello signature is forged (signed by a DIFFERENT
 * key than the one it carries — the NodeId-spoof attack the row describes) is refused; and
 * (4) {@link TransportAuth} refuses malformed and mis-signed hellos in isolation.
 */
final class SocketPeerTransportAuthTest {

    private final List<SocketPeerTransport> started = new ArrayList<>();
    private final List<Socket> rawSockets = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (SocketPeerTransport tx : started) {
            tx.stop();
        }
        for (Socket s : rawSockets) {
            try {
                s.close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private SocketPeerTransport startAuthenticated(NodeIdentity identity) {
        SocketPeerTransport tx = new SocketPeerTransport(identity, "127.0.0.1", 0, "127.0.0.1");
        tx.start();
        started.add(tx);
        return tx;
    }

    @Test
    void authenticatedTransportsExchangeFramesWithKeyProvenAttribution() throws Exception {
        NodeIdentity aliceId = NodeIdentity.generate();
        NodeIdentity bobId = NodeIdentity.generate();
        SocketPeerTransport alice = startAuthenticated(aliceId);
        SocketPeerTransport bob = startAuthenticated(bobId);

        BlockingQueue<PeerAddress> bobSaw = new LinkedBlockingQueue<>();
        BlockingQueue<byte[]> bobFrames = new LinkedBlockingQueue<>();
        bob.setHandler(new MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                bobSaw.add(from);
                bobFrames.add(frame);
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        });

        byte[] payload = "hello-over-authenticated-lane".getBytes(StandardCharsets.UTF_8);
        alice.send(PeerAddress.of(bobId.nodeId(), bob.listenRoute()), payload);

        PeerAddress from = bobSaw.poll(5, TimeUnit.SECONDS);
        assertThat(from).isNotNull();
        // The attribution is the KEY-PROVEN NodeId — alice proved possession of aliceId's key.
        assertThat(from.nodeId()).isEqualTo(aliceId.nodeId());
        assertThat(bobFrames.poll()).isEqualTo(payload);
    }

    @Test
    void aLegacyUnauthenticatedHelloIsRefusedBeforeAnyFrameReachesTheHandler() throws Exception {
        NodeIdentity serverId = NodeIdentity.generate();
        SocketPeerTransport server = startAuthenticated(serverId);

        CountDownLatch anyFrame = new CountDownLatch(1);
        server.setHandler(new MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                anyFrame.countDown();
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        });

        // A legacy client: writes the old [nodeId][routeLen][route] hello then an app frame.
        String route = server.listenRoute();
        int port = Integer.parseInt(route.substring(route.lastIndexOf(':') + 1));
        Socket raw = new Socket("127.0.0.1", port);
        rawSockets.add(raw);
        OutputStream out = raw.getOutputStream();
        byte[] hello = legacyHello(NodeId.random(), "127.0.0.1:1");
        writeFrame(out, hello);
        writeFrame(out, "smuggled".getBytes(StandardCharsets.UTF_8));

        // The server must tear the connection down (its refusal is observable as EOF) and the
        // handler must never fire.
        InputStream in = raw.getInputStream();
        raw.setSoTimeout(5_000);
        // First the server's challenge frame arrives (it always opens with one)...
        byte[] header = in.readNBytes(4);
        assertThat(header).hasSize(4);
        int len = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        assertThat(in.readNBytes(len)).hasSize(len);
        // ...then EOF: our legacy hello failed parseChallenge and the server closed the socket.
        assertThat(in.read()).isEqualTo(-1);
        assertThat(anyFrame.await(500, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void aForgedSignatureIsRefused() throws Exception {
        NodeIdentity serverId = NodeIdentity.generate();
        SocketPeerTransport server = startAuthenticated(serverId);

        CountDownLatch anyFrame = new CountDownLatch(1);
        server.setHandler(new MessageHandler() {
            @Override
            public void onMessage(PeerAddress from, byte[] frame) {
                anyFrame.countDown();
            }

            @Override
            public void onPeerDown(PeerAddress peer) {
            }
        });

        String route = server.listenRoute();
        int port = Integer.parseInt(route.substring(route.lastIndexOf(':') + 1));
        Socket raw = new Socket("127.0.0.1", port);
        rawSockets.add(raw);
        raw.setSoTimeout(5_000);
        OutputStream out = raw.getOutputStream();
        InputStream in = raw.getInputStream();

        // Speak the authenticated protocol: send our challenge, read the server's challenge.
        byte[] ourChallengeFrame = TransportAuth.newChallengeFrame();
        byte[] ourChallenge = TransportAuth.parseChallenge(ourChallengeFrame);
        writeFrame(out, ourChallengeFrame);
        byte[] first = readFrame(in);
        byte[] serverChallenge = TransportAuth.parseChallenge(first);

        // The NodeId-spoof attack: claim victim's NodeId, carry the ATTACKER's public key is
        // detectable by higher layers — the transport-level forgery is signing with a key that
        // does NOT match the carried public key. Build a valid-shaped hello signed by attackerKey
        // but carrying victimKey's public key: the signature must not verify.
        NodeIdentity attacker = NodeIdentity.generate();
        NodeIdentity victim = NodeIdentity.generate();
        byte[] honest = TransportAuth.encodeHello(attacker, "127.0.0.1:1", serverChallenge);
        // Swap the embedded public key for the victim's (same X.509 Ed25519 length).
        byte[] victimPk = victim.publicKeyBytes().toArray();
        byte[] attackerPk = attacker.publicKeyBytes().toArray();
        byte[] forged = replaceOnce(honest, attackerPk, victimPk);
        writeFrame(out, forged);

        // The server answers OUR challenge with its own signed hello before verifying ours —
        // read it, then the refusal is observable as EOF: connection torn down, handler silent.
        byte[] serverHello = readFrame(in);
        assertThat(TransportAuth.verifyHello(serverHello, ourChallenge).nodeId())
                .isEqualTo(serverId.nodeId());
        assertThat(in.read()).isEqualTo(-1);
        assertThat(anyFrame.await(500, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void transportAuthRefusesMalformedAndMisSignedHellos() {
        NodeIdentity identity = NodeIdentity.generate();
        byte[] challenge = new byte[TransportAuth.CHALLENGE_BYTES];

        // Malformed: wrong magic / truncated.
        assertThatThrownBy(() -> TransportAuth.parseChallenge(new byte[]{1, 2, 3}))
                .isInstanceOf(TransportException.class);
        assertThatThrownBy(() -> TransportAuth.verifyHello(new byte[]{0x00}, challenge))
                .isInstanceOf(TransportException.class);

        // A hello answering a DIFFERENT challenge must not verify (replay defense).
        byte[] otherChallenge = new byte[TransportAuth.CHALLENGE_BYTES];
        otherChallenge[0] = 1;
        byte[] hello = TransportAuth.encodeHello(identity, "127.0.0.1:9", otherChallenge);
        assertThatThrownBy(() -> TransportAuth.verifyHello(hello, challenge))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("key possession");

        // The honest round trip verifies and attributes the right identity.
        byte[] honest = TransportAuth.encodeHello(identity, "127.0.0.1:9", challenge);
        TransportAuth.VerifiedHello v = TransportAuth.verifyHello(honest, challenge);
        assertThat(v.nodeId()).isEqualTo(identity.nodeId());
        assertThat(v.route()).isEqualTo("127.0.0.1:9");
        assertThat(v.publicKey()).isEqualTo(new Bytes(identity.publicKeyBytes().toArray()));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static byte[] legacyHello(NodeId nodeId, String route) {
        byte[] routeBytes = route.getBytes(StandardCharsets.UTF_8);
        byte[] hello = new byte[16 + 2 + routeBytes.length];
        UUID id = nodeId.value();
        for (int i = 0; i < 8; i++) {
            hello[i] = (byte) ((id.getMostSignificantBits() >>> (56 - 8 * i)) & 0xFF);
            hello[8 + i] = (byte) ((id.getLeastSignificantBits() >>> (56 - 8 * i)) & 0xFF);
        }
        hello[16] = (byte) ((routeBytes.length >>> 8) & 0xFF);
        hello[17] = (byte) (routeBytes.length & 0xFF);
        System.arraycopy(routeBytes, 0, hello, 18, routeBytes.length);
        return hello;
    }

    private static void writeFrame(OutputStream out, byte[] frame) throws Exception {
        byte[] header = new byte[4];
        header[0] = (byte) ((frame.length >>> 24) & 0xFF);
        header[1] = (byte) ((frame.length >>> 16) & 0xFF);
        header[2] = (byte) ((frame.length >>> 8) & 0xFF);
        header[3] = (byte) (frame.length & 0xFF);
        out.write(header);
        out.write(frame);
        out.flush();
    }

    private static byte[] readFrame(InputStream in) throws Exception {
        byte[] header = in.readNBytes(4);
        assertThat(header).hasSize(4);
        int len = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] body = in.readNBytes(len);
        assertThat(body).hasSize(len);
        return body;
    }

    /** Replace the first occurrence of {@code find} inside {@code src} with {@code replace}. */
    private static byte[] replaceOnce(byte[] src, byte[] find, byte[] replace) {
        assertThat(find.length).isEqualTo(replace.length);
        outer:
        for (int i = 0; i + find.length <= src.length; i++) {
            for (int j = 0; j < find.length; j++) {
                if (src[i + j] != find[j]) {
                    continue outer;
                }
            }
            byte[] copy = src.clone();
            System.arraycopy(replace, 0, copy, i, replace.length);
            return copy;
        }
        throw new AssertionError("pattern not found");
    }
}
