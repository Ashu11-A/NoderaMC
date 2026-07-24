package dev.nodera.peer.control;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 32: the headless worker's control endpoint answers the presence probe. */
final class ControlServerTest {

    @Test
    void answersProbeWithOkAndVersion() throws Exception {
        try (ControlServer server = new ControlServer("127.0.0.1", 0, "9.9.9")) {
            server.start();
            int port = server.boundPort();
            assertTrue(port > 0);

            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                s.setSoTimeout(1500);
                OutputStream out = s.getOutputStream();
                out.write((ControlProtocol.probeLine() + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String reply = in.readLine();
                assertNotNull(reply);
                String[] parts = reply.trim().split("\\s+");
                assertTrue(parts.length >= 3);
                assertTrue(ControlProtocol.OK.equals(parts[0]));
                assertTrue(Integer.parseInt(parts[1]) == ControlProtocol.PROTOCOL_VERSION);
                assertTrue("9.9.9".equals(parts[2]));
            }
        }
    }

    @Test
    void dispatchesStateIdentityAndHostVerbs() throws Exception {
        ControlHandler handler = new ControlHandler() {
            @Override
            public String workerVersion() {
                return "1.2.3";
            }

            @Override
            public String stateJson() {
                return "{\"maintained_pieces\":7}";
            }

            @Override
            public String identityLine() {
                return "abc pubkey==";
            }

            @Override
            public String host(String worldId, String worldName, String optionsJson) {
                return worldId.equals("w1") ? null : "bad world";
            }
        };
        try (ControlServer server = new ControlServer("127.0.0.1", 0, handler)) {
            server.start();
            int port = server.boundPort();
            assertEquals("{\"maintained_pieces\":7}", request(port, "NODERA-STATE 1"));
            assertEquals("NODERA-OK abc pubkey==", request(port, "NODERA-IDENTITY 1"));
            assertEquals("NODERA-OK", request(port, "NODERA-HOST 2 w1 bmFtZQ== {}"));
            assertTrue(request(port, "NODERA-HOST 2 w2 x {}").startsWith("NODERA-ERR"));
            assertTrue(request(port, "NODERA-BOGUS").startsWith("NODERA-ERR"));
        }
    }

    @Test
    void dispatchesGrantVerbAndSignsAKeyBoundGrant() throws Exception {
        // The handler signs as the world author exactly like WorkerControlHandler.grantRole.
        dev.nodera.core.identity.NodeIdentity author = dev.nodera.core.identity.NodeIdentity.generate();
        dev.nodera.core.identity.NodeIdentity subject = dev.nodera.core.identity.NodeIdentity.generate();
        dev.nodera.core.Bytes worldId =
                new dev.nodera.core.crypto.HashService().sha256("w".getBytes());
        ControlHandler handler = new ControlHandler() {
            @Override
            public String workerVersion() {
                return "1.0";
            }

            @Override
            public String grantRole(String worldIdHex, String subjectNodeId, String subjectPubKeyB64,
                                    int roleOrdinal, long grantVersion) {
                dev.nodera.storage.WorldPermissionGrant g =
                        dev.nodera.storage.WorldPermissionGrant.create(author,
                                dev.nodera.core.Bytes.fromHex(worldIdHex),
                                new dev.nodera.core.identity.NodeId(
                                        java.util.UUID.fromString(subjectNodeId)),
                                dev.nodera.core.Bytes.unsafeWrap(
                                        java.util.Base64.getDecoder().decode(subjectPubKeyB64)),
                                dev.nodera.core.identity.WorldRole.fromOrdinal(roleOrdinal),
                                grantVersion);
                dev.nodera.core.crypto.CanonicalWriter w = new dev.nodera.core.crypto.CanonicalWriter();
                g.encode(w);
                return java.util.Base64.getEncoder().encodeToString(w.toBytes().toArray());
            }
        };
        try (ControlServer server = new ControlServer("127.0.0.1", 0, handler)) {
            server.start();
            int port = server.boundPort();
            String pubKeyB64 = java.util.Base64.getEncoder()
                    .encodeToString(subject.publicKeyBytes().toArray());
            String reply = request(port, "NODERA-GRANT 2 " + worldId.toHex() + " "
                    + subject.nodeId().value() + " " + pubKeyB64 + " "
                    + dev.nodera.core.identity.WorldRole.OPERATOR.ordinal() + " 1");
            assertTrue(reply.startsWith(ControlProtocol.OK + " "), reply);
            byte[] grantBytes = java.util.Base64.getDecoder()
                    .decode(reply.substring(ControlProtocol.OK.length() + 1).trim());
            dev.nodera.storage.WorldPermissionGrant back =
                    dev.nodera.storage.WorldPermissionGrant.decode(
                            new dev.nodera.core.crypto.CanonicalReader(
                                    dev.nodera.core.Bytes.unsafeWrap(grantBytes)));
            assertTrue(back.verifySignature());
            assertEquals(subject.publicKeyBytes(), back.subjectPublicKey());
            assertEquals(dev.nodera.core.identity.WorldRole.OPERATOR, back.role());
            assertEquals(author.nodeId(), back.granter());
        }
    }

    @Test
    void passwordVerbSurfacesHonestErrorNeverSilentSuccess() throws Exception {
        // F6: a worker that declines (the re-key pipeline is absent) must reply NODERA-ERR — never OK.
        ControlHandler handler = new ControlHandler() {
            @Override
            public String workerVersion() {
                return "1.0";
            }

            @Override
            public String password(String worldId, String newPasswordB64) {
                if (worldId.isBlank()) {
                    return "missing worldId";
                }
                return "password re-key pipeline not yet implemented";
            }
        };
        try (ControlServer server = new ControlServer("127.0.0.1", 0, handler)) {
            server.start();
            int port = server.boundPort();
            assertEquals("NODERA-ERR missing worldId", request(port, "NODERA-PASSWORD 2"));
            assertTrue(request(port, "NODERA-PASSWORD 2 deadbeef cGFzcw==").startsWith("NODERA-ERR"));
        }
    }

    /** Send one request line, return the single reply line. */
    private static String request(int port, String line) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            s.setSoTimeout(1500);
            s.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            return new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    @Test
    void unknownVerbDoesNotCrashServer() throws Exception {
        try (ControlServer server = new ControlServer("127.0.0.1", 0, "1.0")) {
            server.start();
            int port = server.boundPort();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                s.getOutputStream().write("GARBAGE\n".getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
            }
            // A second, valid probe still works → the server survived the bad connection.
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 1500);
                s.setSoTimeout(1500);
                s.getOutputStream().write((ControlProtocol.probeLine() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                assertTrue(in.readLine().startsWith(ControlProtocol.OK));
            }
        }
    }
}
