package dev.nodera.peer.control;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

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
