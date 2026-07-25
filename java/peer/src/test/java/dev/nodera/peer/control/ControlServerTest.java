package dev.nodera.peer.control;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * A handler that mirrors {@code WorkerControlHandler}'s config contract closely enough to pin
     * the dispatch: it decodes the base64 payload (throwing on garbage, exactly as the real one
     * does), applies the keys it knows, and names every key it did NOT apply.
     */
    private static ControlHandler configHandler(java.util.Set<String> knownKeys,
                                                java.util.List<String> appliedOut) {
        return new ControlHandler() {
            @Override
            public String workerVersion() {
                return "1.0";
            }

            @Override
            public String applyConfig(String configJsonB64) {
                // Garbage in the payload is an error, never an empty config: applying "nothing"
                // would silently unset the user's settings and report success for it.
                String json = new String(java.util.Base64.getDecoder().decode(configJsonB64),
                        StandardCharsets.UTF_8);
                java.util.List<String> applied = new java.util.ArrayList<>();
                java.util.List<String> rejected = new java.util.ArrayList<>();
                var m = java.util.regex.Pattern.compile("\"([a-z_.]+)\"\\s*:").matcher(json);
                while (m.find()) {
                    String key = m.group(1);
                    if (knownKeys.contains(key)) {
                        applied.add("\"" + key + "\"");
                        appliedOut.add(key);
                    } else {
                        rejected.add("\"" + key + "\":\"unknown setting\"");
                    }
                }
                return "{\"applied\":[" + String.join(",", applied) + "],"
                        + "\"restart_required\":[],"
                        + "\"rejected\":{" + String.join(",", rejected) + "}}";
            }

            @Override
            public String readConfig() {
                return "{\"network.max_upload_bytes_per_sec\":1024}";
            }
        };
    }

    private static String b64(String s) {
        return java.util.Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void configWithNoPayloadReadsBackTheEffectiveConfigAsJson() throws Exception {
        ControlHandler handler = configHandler(java.util.Set.of(), new java.util.ArrayList<>());
        try (ControlServer server = new ControlServer("127.0.0.1", 0, handler)) {
            server.start();
            String reply = request(server.boundPort(), ControlProtocol.CONFIG + " 2");
            // A read is the JSON family (like STATE/PIECES), not an OK/ERR line.
            assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
            assertEquals("{\"network.max_upload_bytes_per_sec\":1024}", reply);
        }
    }

    @Test
    void configWithMalformedBase64IsAnErrorNeverAnEmptyConfig() throws Exception {
        ControlHandler handler = configHandler(java.util.Set.of("behavior.transfers_paused"),
                new java.util.ArrayList<>());
        try (ControlServer server = new ControlServer("127.0.0.1", 0, handler)) {
            server.start();
            // "!!!" is not base64; the handler throws and the dispatch must surface it.
            String reply = request(server.boundPort(), ControlProtocol.CONFIG + " 2 !!!not-b64!!!");
            assertTrue(reply.startsWith(ControlProtocol.ERR), reply);
        }
    }

    @Test
    void configDecliningHandlerRepliesErrNeverOk() throws Exception {
        // A handler that has the verb but refuses to configure anything must still be visibly a
        // refusal — "never silent success".
        ControlHandler handler = new ControlHandler() {
            @Override
            public String workerVersion() {
                return "1.0";
            }

            @Override
            public String applyConfig(String configJsonB64) {
                throw new IllegalStateException("configuration plane is read-only here");
            }
        };
        try (ControlServer server = new ControlServer("127.0.0.1", 0, handler)) {
            server.start();
            String reply = request(server.boundPort(),
                    ControlProtocol.CONFIG + " 2 " + b64("{\"behavior.transfers_paused\":true}"));
            assertTrue(reply.startsWith(ControlProtocol.ERR), reply);
            assertFalse(reply.startsWith(ControlProtocol.OK), reply);
            assertTrue(reply.contains("read-only"), reply);
        }
    }

    @Test
    void configDefaultHandlerDeclinesLoudlyAsUnsupported() throws Exception {
        // The ControlHandler default returns null for BOTH directions — a partially-upgraded worker
        // that knows the verb but has no configuration plane says so instead of pretending.
        ControlHandler barebones = () -> "1.0";
        try (ControlServer server = new ControlServer("127.0.0.1", 0, barebones)) {
            server.start();
            int port = server.boundPort();
            assertEquals("NODERA-ERR unsupported",
                    request(port, ControlProtocol.CONFIG + " 2 " + b64("{\"a.b\":1}")));
            assertEquals("NODERA-ERR unsupported", request(port, ControlProtocol.CONFIG + " 2"));
        }
    }

    @Test
    void configReplyNamesAppliedAndRestartRequiredKeys() throws Exception {
        // The test the app's "enforced" badge rests on: a key appears under "applied" if and only
        // if the handler actually accepted it. Everything else is named under "rejected" with a
        // reason — a key silently dropped would be indistinguishable from one honoured.
        java.util.List<String> accepted = new java.util.ArrayList<>();
        ControlHandler handler = configHandler(
                java.util.Set.of("behavior.transfers_paused", "network.max_upload_bytes_per_sec"),
                accepted);
        try (ControlServer server = new ControlServer("127.0.0.1", 0, handler)) {
            server.start();
            String reply = request(server.boundPort(), ControlProtocol.CONFIG + " 2 "
                    + b64("{\"behavior.transfers_paused\":true,"
                            + "\"network.max_upload_bytes_per_sec\":4096,"
                            + "\"network.made_up_setting\":7}"));
            assertFalse(reply.startsWith(ControlProtocol.ERR), reply);
            assertEquals(java.util.List.of("behavior.transfers_paused",
                    "network.max_upload_bytes_per_sec"), accepted);
            assertTrue(reply.contains("\"applied\":[\"behavior.transfers_paused\","
                    + "\"network.max_upload_bytes_per_sec\"]"), reply);
            assertTrue(reply.contains("\"restart_required\":[]"), reply);
            // The unknown key is named with a reason, not dropped.
            assertTrue(reply.contains("\"network.made_up_setting\":\"unknown setting\""), reply);
            // …and never claimed as applied.
            assertFalse(reply.substring(reply.indexOf("\"applied\""), reply.indexOf("\"rejected\""))
                    .contains("made_up_setting"), reply);
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
