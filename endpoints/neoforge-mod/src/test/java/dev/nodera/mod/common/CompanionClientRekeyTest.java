package dev.nodera.mod.common;

import dev.nodera.endpoint.control.CompanionClient;
import dev.nodera.endpoint.control.CompanionProtocol;
import dev.nodera.core.Bytes;
import dev.nodera.core.crypto.CanonicalReader;
import dev.nodera.core.crypto.CanonicalWriter;
import dev.nodera.core.crypto.HashService;
import dev.nodera.core.identity.NodeIdentity;
import dev.nodera.storage.WorldIdentity;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #37 / L-51: {@link CompanionClient#rekey} issues the {@code NODERA-REKEY} verb and decodes
 * the re-signed {@link WorldIdentity}. Socket idiom, no Minecraft types.
 */
final class CompanionClientRekeyTest {

    private static String b64(Bytes bytes) {
        return Base64.getEncoder().encodeToString(bytes.toArray());
    }

    private static Bytes encodeIdentity(WorldIdentity id) {
        CanonicalWriter w = new CanonicalWriter();
        id.encode(w);
        return w.toBytes();
    }

    /** A one-shot loopback server that replies with a fixed line to the first request it reads. */
    private static int serve(String replyLine) throws Exception {
        ServerSocket server = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                s.setSoTimeout(5_000);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                in.readLine(); // consume the request
                OutputStream out = s.getOutputStream();
                out.write((replyLine + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {
                // test-only
            }
        });
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    @Test
    void rekeyWritesVerbAndDecodesReSignedIdentity() throws Exception {
        NodeIdentity author = NodeIdentity.generate();
        Bytes genesis = new HashService().sha256("g".getBytes());
        // The re-signed identity the worker would return (encrypted=true, manifestRef set).
        WorldIdentity reSigned = WorldIdentity.create(author, genesis, 1L, true, true, true,
                new HashService().sha256("manifest".getBytes()));
        Bytes reSignedBytes = encodeIdentity(reSigned);

        int port = serve(CompanionProtocol.OK + " " + b64(reSignedBytes) + " 7");
        CompanionClient client = new CompanionClient("127.0.0.1", port);

        Bytes currentId = encodeIdentity(WorldIdentity.create(author, genesis, 1L,
                true, true, false, Bytes.empty()));
        CompanionClient.Rekeyed got = client.rekey(reSigned.worldId().toHex(),
                Path.of("/tmp/packed.nar"), b64(Bytes.unsafeWrap("pw".getBytes())), currentId)
                .orElseThrow();

        assertEquals(reSignedBytes, got.identity());
        assertEquals(reSigned, WorldIdentity.decode(new CanonicalReader(got.identity())));
        assertEquals(7L, got.version(),
                "the seeded version rides the reply — an encrypted refresh has no other source");
    }

    @Test
    void aReplyWithoutTheVersionTokenStillDecodesTheIdentity() throws Exception {
        // The version token is additive: a worker that predates it answers with the identity alone,
        // and the only consequence must be an unknown version — never a failed re-key.
        NodeIdentity author = NodeIdentity.generate();
        Bytes genesis = new HashService().sha256("g2".getBytes());
        WorldIdentity reSigned = WorldIdentity.create(author, genesis, 1L, true, true, true,
                new HashService().sha256("manifest2".getBytes()));

        int port = serve(CompanionProtocol.OK + " " + b64(encodeIdentity(reSigned)));
        CompanionClient client = new CompanionClient("127.0.0.1", port);

        CompanionClient.Rekeyed got = client.rekey(reSigned.worldId().toHex(),
                Path.of("/tmp/packed.nar"), b64(Bytes.unsafeWrap("pw".getBytes())),
                encodeIdentity(reSigned)).orElseThrow();

        assertEquals(reSigned, WorldIdentity.decode(new CanonicalReader(got.identity())));
        assertEquals(-1L, got.version());
    }

    @Test
    void rekeyReturnsEmptyOnErrReply() throws Exception {
        int port = serve(CompanionProtocol.ERR + " not the author of this world");
        CompanionClient client = new CompanionClient("127.0.0.1", port);
        assertTrue(client.rekey("deadbeef", Path.of("/tmp/x.nar"), "pw==",
                Bytes.empty()).isEmpty());
    }

    @Test
    void rekeyReturnsEmptyWhenWorkerOffline() {
        CompanionClient client = new CompanionClient("127.0.0.1", 1); // nothing listening
        assertTrue(client.rekey("deadbeef", Path.of("/tmp/x.nar"), "pw==",
                Bytes.empty()).isEmpty());
    }
}
