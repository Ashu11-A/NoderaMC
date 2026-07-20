package dev.nodera.mod.common;

import dev.nodera.mod.common.CompanionGate.Status;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 32: the mod-side companion presence gate — no NeoForge runtime. */
final class CompanionGateTest {

    @Test
    void absentDaemonReportsInstallUrlAndThrowsWhenRequired() {
        CompanionProbe absent = Optional::empty;
        CompanionGate.GateResult result = CompanionGate.evaluate(absent);
        assertEquals(Status.ABSENT, result.status());
        assertFalse(result.ok());
        assertTrue(result.message().contains(CompanionGate.INSTALL_URL));

        CompanionUnavailableException ex = assertThrows(CompanionUnavailableException.class,
                () -> CompanionGate.requireRunning(absent));
        assertTrue(ex.getMessage().contains(CompanionGate.INSTALL_URL));
    }

    @Test
    void probeThatThrowsIsTreatedAsAbsent() {
        CompanionProbe boom = () -> {
            throw new RuntimeException("connection refused");
        };
        assertEquals(Status.ABSENT, CompanionGate.evaluate(boom).status());
    }

    @Test
    void matchingProtocolIsRunning() {
        CompanionProbe present = () -> Optional.of(
                new CompanionInfo(CompanionProtocol.PROTOCOL_VERSION, "0.1.0"));
        CompanionGate.GateResult result = CompanionGate.requireRunning(present); // must not throw
        assertEquals(Status.RUNNING, result.status());
        assertTrue(result.ok());
    }

    @Test
    void olderDaemonSaysUpdateTheApp() {
        CompanionProbe old = () -> Optional.of(
                new CompanionInfo(CompanionProtocol.PROTOCOL_VERSION - 1, "0.0.1"));
        CompanionGate.GateResult result = CompanionGate.evaluate(old);
        assertEquals(Status.DAEMON_OUTDATED, result.status());
        assertTrue(result.message().toLowerCase().contains("companion app"));
    }

    @Test
    void newerDaemonSaysUpdateTheMod() {
        CompanionProbe newer = () -> Optional.of(
                new CompanionInfo(CompanionProtocol.PROTOCOL_VERSION + 1, "9.9.9"));
        assertEquals(Status.MOD_OUTDATED, CompanionGate.evaluate(newer).status());
    }

    @Test
    void clientParsesOkReply() {
        assertEquals(Optional.of(new CompanionInfo(1, "0.1.0")),
                CompanionClient.parseOk("NODERA-OK 1 0.1.0"));
        assertTrue(CompanionClient.parseOk("garbage").isEmpty());
        assertTrue(CompanionClient.parseOk(null).isEmpty());
    }

    @Test
    void endpointParsingRejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> CompanionClient.parse("nohost"));
        assertThrows(IllegalArgumentException.class, () -> CompanionClient.parse("h:0"));
        assertThrows(IllegalArgumentException.class, () -> CompanionClient.parse("h:99999"));
        CompanionClient ok = CompanionClient.parse("127.0.0.1:25610");
        assertFalse(ok.probe().isPresent()); // nothing listening → absent, not an exception
    }

    @Test
    void realLoopbackDaemonAnswersProbe() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread daemon = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String probe = in.readLine();
                    assertTrue(probe.startsWith(CompanionProtocol.PROBE));
                    OutputStream out = s.getOutputStream();
                    out.write((CompanionProtocol.okLine(CompanionProtocol.PROTOCOL_VERSION, "0.1.0")
                            + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                    // test daemon best-effort
                }
            });
            daemon.setDaemon(true);
            daemon.start();

            CompanionClient client = new CompanionClient("127.0.0.1", server.getLocalPort());
            CompanionGate.GateResult result = CompanionGate.requireRunning(client);
            assertEquals(Status.RUNNING, result.status());
        }
    }
}
