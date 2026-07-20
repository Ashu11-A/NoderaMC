package dev.nodera.transport.rendezvous;

import dev.nodera.core.Bytes;
import dev.nodera.core.identity.NodeIdentity;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The end-to-end handshake + AEAD over a real loopback socket pair (Task 29): a session established
 * across the two ends encrypts round-trip, binds identities, and rejects tamper.
 */
final class EndToEndCipherTest {

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    /** Run initiator + responder concurrently over a connected socket pair; return their sessions. */
    private record Pair(EndToEndCipher.Session a, EndToEndCipher.Session b, Socket sa, Socket sb) {}

    private Pair handshake(NodeIdentity a, NodeIdentity b, Bytes bExpectsA, Bytes aExpectsB)
            throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Future<Socket> accepted = POOL.submit((Callable<Socket>) server::accept);
            Socket sa = new Socket(server.getInetAddress(), server.getLocalPort());
            Socket sb = accepted.get(5, TimeUnit.SECONDS);

            Future<EndToEndCipher.Session> fa = POOL.submit(() -> EndToEndCipher.handshake(
                    sa.getInputStream(), sa.getOutputStream(), a, aExpectsB, true));
            Future<EndToEndCipher.Session> fb = POOL.submit(() -> EndToEndCipher.handshake(
                    sb.getInputStream(), sb.getOutputStream(), b, bExpectsA, false));
            return new Pair(fa.get(5, TimeUnit.SECONDS), fb.get(5, TimeUnit.SECONDS), sa, sb);
        }
    }

    @Test
    void aSessionEncryptsRoundTripBetweenTheTwoEnds() throws Exception {
        NodeIdentity a = NodeIdentity.generate();
        NodeIdentity b = NodeIdentity.generate();
        Pair pair = handshake(a, b, a.publicKeyBytes(), b.publicKeyBytes());

        byte[] plaintext = "committee-vote-over-the-relay".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = pair.a().seal(plaintext);
        assertThat(sealed).isNotEqualTo(plaintext); // actually encrypted, not passthrough
        assertThat(pair.b().open(sealed)).contains(plaintext);

        // The reverse direction works under the same shared key.
        byte[] reply = "ack".getBytes(StandardCharsets.UTF_8);
        assertThat(pair.a().open(pair.b().seal(reply))).contains(reply);

        pair.sa().close();
        pair.sb().close();
    }

    @Test
    void aTamperedCiphertextDoesNotOpen() throws Exception {
        NodeIdentity a = NodeIdentity.generate();
        NodeIdentity b = NodeIdentity.generate();
        Pair pair = handshake(a, b, a.publicKeyBytes(), b.publicKeyBytes());

        byte[] sealed = pair.a().seal("secret".getBytes(StandardCharsets.UTF_8));
        sealed[sealed.length - 1] ^= 0xFF; // flip a tag byte
        assertThat(pair.b().open(sealed)).isEmpty();

        pair.sa().close();
        pair.sb().close();
    }

    @Test
    void anImpostorIdentityIsRejectedBeforeAnyByteCrosses() {
        NodeIdentity a = NodeIdentity.generate();
        NodeIdentity b = NodeIdentity.generate();
        NodeIdentity impostor = NodeIdentity.generate();
        // A expects the impostor's key but B actually answers, so A's handshake must fail.
        assertThatThrownBy(() -> handshake(a, b, a.publicKeyBytes(), impostor.publicKeyBytes()))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(SecurityException.class);
    }
}
